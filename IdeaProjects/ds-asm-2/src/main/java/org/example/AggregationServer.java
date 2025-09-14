package org.example;

// libraries for JSON Parser (Serialisation + Deserialisation)
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

// data structures
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

// socket programming
import java.net.ServerSocket;
import java.net.Socket;

// Side packages
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

// For thread safe
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class AggregationServer {
    // ----- config + identity -----
    private final int port;
    private volatile boolean running = true;
    private final AtomicLong arrival_seq = new AtomicLong(0);
    private final ExecutorService connection_pool = Executors.newCachedThreadPool(); // for new connection to server socket

    // ----- in-memory/persistent storage -----
    // Use ConcurrentMap for thread safety
    private final ConcurrentMap<String, WeatherRecord> memory_store = new ConcurrentHashMap();
    private final ConcurrentMap<String, Long> last_update = new ConcurrentHashMap();

    // ---- single writer with queue for simplifying concurrency ----
    private final BlockingQueue<PutRequest> put_queue = new LinkedBlockingQueue<>();
    private Thread writer;

    // ---- Serialisation + Deserialisation ----
    private final Gson gson = new Gson();

    // Persistent manager for WAL + snapshot
    private final LamportClock lp_clock;
    private final PersistenceManager persis_manager;

    // For respecting order such as PUT -> GET -> PUT
    private final ReentrantReadWriteLock reentrant_lock = new ReentrantReadWriteLock();

    /**
     * Aggregation server (simple socket-based HTTP parsing).
     *
     * Key functionality:
     * - Accept PUT from content servers and GET from clients
     * - PUTs must include lamport clock and node id headers for tracking
     * - Enqueue PUTs into a PriorityBlockingQueue ordered by for handling multiple PUTs
     * - Single writer applies data updates: WAL + in-memory store + write snapshot
     * - Writer completes per-request CompletableFuture so the connection thread can send 201/200
     * - Expiry checker removes content server out of contact after 30s
     */
    public AggregationServer(int port, String persistent_dir) throws IOException {
        this.port = port;
        this.lp_clock = new LamportClock();
        this.persis_manager = new PersistenceManager(persistent_dir);
        load_snapshot_WAL();

        // Starting writer thread waiting for upcoming PUT requests
        start_worker();

        // Check for any expired content server
        start_expiry_checker();
    }

    // Load snapshot + WAL on startup in case of recovery after crash
    private void load_snapshot_WAL() {
        try {
            String snapshot = persis_manager.read_snapshot();
            // We got a snapshot (its a JSON array of records)
            if (snapshot != null && !snapshot.trim().isEmpty()) {
                // ---- Warning: this is deceprecated for newer version of Gson -> fix later ----
                JsonArray arr = new JsonParser().parse(snapshot).getAsJsonArray();
                // For each Json object
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject o = arr.get(i).getAsJsonObject();
                    // if object has an id
                    if (o.has("id")) {
                        String id = o.get("id").getAsString();
                        long lamport = o.has("lamport") ? o.get("lamport").getAsLong() : 0;
                        String source = o.has("source_id") ? o.get("source_id").getAsString() : "unknown";   // for identifying source content server

                        memory_store.put(id, new WeatherRecord(id, o.deepCopy(), lamport, source));
                    } else {
                        continue;
                    }
                }

                // replay WAL for any update not in snapshot
                for (JsonObject new_o : persis_manager.replay_WAL()) {
                    if (new_o.has("id")) {
                        String id = new_o.get("id").getAsString();
                        long lamport = new_o.has("lamport") ? new_o.get("lamport").getAsLong() : 0;
                        String source = new_o.has("source_id") ? new_o.get("source_id").getAsString() : "unknown";       // for identifying source content server

                        // overwrite old record -> larger lamport means new
                        WeatherRecord existing = memory_store.get(id);
                        if (existing == null || lamport >= existing.lamport) {
                            memory_store.put(id, new WeatherRecord(id, new_o.deepCopy(), lamport, source));
                        }
                    } else {
                        continue;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load snapshot/WAL" + e.getMessage());
        }
    }

    // Starting the aggregation server and start accepting request
    private void start() throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("Aggregation Server listening on port " + port);
            // Accpeting requests while on
            while (running) {
                Socket s = server.accept();
                connection_pool.submit(() -> handle_connection(s));
            }
        } catch (java.lang.Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- HTTP Request Handling -----
    private void handle_connection(Socket s) {
        try(BufferedInputStream buffer_input = new BufferedInputStream(s.getInputStream());
            OutputStream out_stream = s.getOutputStream()){
            BufferedReader reader = new BufferedReader(new InputStreamReader(buffer_input, StandardCharsets.UTF_8));

            // read each line from the request
            String request_line = reader.readLine();
            if (request_line == null) return;

            String[] parts = request_line.split(" ");
            String method = parts[0];
            String path = parts[1];

            // 400 - Requests are not either GET or PUT
            if (!method.equals("GET") && !method.equals("PUT")) {
                write_response(out_stream, 400, "Bad Request", lp_clock.get());
            }

            // read header
            long content_length = 0;
            long remote_lamport = -1;
            String content_type = "";
            String source_id = "unknown";       // content server initial default
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int index = line.indexOf(":");

                if (index > 0) {
                    String key = line.substring(0, index).trim();
                    String value = line.substring(index + 1).trim();

                    if (key.equalsIgnoreCase("Content-Length")){
                        content_length = Long.parseLong(value);
                    } else if (key.equalsIgnoreCase("Content-Type")){
                        content_type = value;
                    } else if (key.equalsIgnoreCase("X-Lamport-Clock")){
                        remote_lamport = Long.parseLong(value);
                    } else if (key.equalsIgnoreCase("X-Source-ID")) {
                        source_id = value;
                    }
                }
            }

            // Update Aggregation Server Lamport Clock when receive request
            //if (remote_lamport >= 0){
            //    lp_clock.on_receive(remote_lamport);
            //}

            // ----- Handling PUT Request -----
            if ("PUT".equalsIgnoreCase(method) && "/weather.json".equals(path)){
                if (content_length == 0) {
                    write_response(out_stream, 204, "No Content", lp_clock.get());
                    return;
                }

                // reading the request body
                char[] body_buffer = new char[(int) content_length];
                int actual_read = 0;
                // parse character buffer into body
                while (actual_read < content_length) {
                    int read_len = reader.read(body_buffer, actual_read, (int) (content_length - actual_read));
                    if (read_len < 0) break;
                    actual_read += read_len;
                }
                // example: body = "{\"id\":\"123\",\"temp\":25,\"humidity\":80}"
                String body = new String(body_buffer, 0, Math.max(0, actual_read));

                // Convert body into JSON object
                JsonObject payload;
                try {
                    payload = new JsonParser().parse(body).getAsJsonObject();
                } catch (Exception e){
                    write_response(out_stream, 500, "Invalid JSON!", lp_clock.get());
                    return;
                }

                // 500 - Missing id
                String id = payload.has("id") ? payload.get("id").getAsString() : null;
                if (id == null || id.isEmpty()) {
                    write_response(out_stream, 500, "Missing id in payload!", lp_clock.get());
                    return;
                }

                // Prepare PUT request for writer to update
                long lamport_header = remote_lamport >= 0 ? remote_lamport : lp_clock.get();
                PutRequest req = new PutRequest(lamport_header, arrival_seq.incrementAndGet(), payload.deepCopy(), source_id);
                put_queue.put(req);

                int result = req.result_future.get();

                // update last_update table as we got a new PUT from a content server
                // ------ Reetrant Lock ------
                // This is needed because expiry_checker might be checking while we have a new PUT
                reentrant_lock.writeLock().lock();
                try{
                    last_update.put(req.source_id, System.currentTimeMillis());
                } finally {
                    reentrant_lock.writeLock().unlock();
                }

                // Send 201 or 200 to content server
                write_response(out_stream,result, result == 201? "Created" : "OK", lp_clock.get());
            } else if ("GET".equalsIgnoreCase(method) && "/weather.json".equals(path)){
                // Update agg server lamport to reflect we've seen the GET
                lp_clock.on_receive(remote_lamport);

                // GET is locked to prevent later PUT interleave
                reentrant_lock.readLock().lock();
                try{
                    // Sending multiple weather records back
                    JsonArray arr = new JsonArray();
                    for (Map.Entry<String, WeatherRecord> e : memory_store.entrySet()) {
                        arr.add(e.getValue().data);
                    }
                    write_response(out_stream, 200, gson.toJson(arr), lp_clock.get());
                } finally {
                    reentrant_lock.readLock().unlock();
                }
            } else {
                write_response(out_stream, 400, "Only accept GET or PUT", lp_clock.get());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---- Helper function for outputing ----
    private void write_response(OutputStream out_stream, int status, String body, long lamport) throws IOException {
        // Store char as byte and prepare header
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " OK\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Content-Type: application/json\r\n" +
                "X-Lamport-Clock: " + lp_clock.get() + "\r\n\r\n";           // everything we send back is JSON (Serialisation)
        out_stream.write(headers.getBytes(StandardCharsets.UTF_8));
        out_stream.write(bytes);
        out_stream.flush();         // immediate print out
    }

    // ---- writer for processing PUT with queue ----
    private void start_worker() {
        // writer takes requests from PUT queue and call apply_put()
        Thread writer = new Thread(() -> {
            while (true) {
                try {
                    PutRequest req = put_queue.take();
                    apply_put(req);
                } catch(Exception e) {
                    System.err.println("Writer: cannot process PUT" + e.getMessage());
                }
            }
        },"Put Worker");
        writer.start();
    }

    // ---- Function for processing the PUT request ----
    private void apply_put(PutRequest req) {

        // ------ Reetrant lock --------
        // Lock this to prevent GET to interleave
        reentrant_lock.writeLock().lock();
        try {
            // If an existing record exists and its lamport is greater than incoming, ignore.
            String id = req.source_id;
            WeatherRecord existing = memory_store.get(id);
            if (existing != null && req.lamport < existing.lamport) {
                // return 200 "OK" but do not overwrite.
                req.result_future.complete(200);
                // Update agg server lamport even if we dont use the new PUT
                lp_clock.on_receive(req.lamport);
                return;
            }

            // ---- prepare for write-ahead-log (wal) ----
            JsonObject wal_payload = req.payload.deepCopy();
            wal_payload.addProperty("lamport", req.lamport);
            persis_manager.append_wal(req.lamport, req.source_id, wal_payload);

            // ---- Write to in-memory -----
            boolean created = !memory_store.containsKey(id);
            memory_store.put(id, new WeatherRecord(id, req.payload.deepCopy(), req.lamport, req.source_id));

            // Update agg server lamport
            lp_clock.on_receive(req.lamport);

            // ---- Write snapshot ----
            persis_manager.write_snapshot(memory_store);

            // 201 - first time created
            // 200 - sucessful
            req.result_future.complete(created ? 201: 200);
        } catch (Exception e) {
            // 500 - internal server error
            req.result_future.complete(500);
        } finally {
            reentrant_lock.writeLock().unlock();
        }
    }

    // ---- Function for checking out of contact Content Server ----
    private void start_expiry_checker() {
        // Initialising thread for disconnecting content servers
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        // Run periodically every 5 seconds
        executor.scheduleAtFixedRate(()->{
            long current_time = System.currentTimeMillis();

            // ------ Reetrant lock --------
            reentrant_lock.writeLock().lock();
            try{
                for (Map.Entry<String, Long> it : last_update.entrySet()) {
                    // Check if server is inactive
                    if (current_time - it.getValue() > 30_000) {
                        String source = it.getKey();        // key (source_id of content server) : value (time when it last sent PUT)
                        // remove this record of this content server
                        // each weather record has a source_id because it was sent with the content server
                        memory_store.entrySet().removeIf(content_server_iter -> source.equals(content_server_iter.getValue().source_id));
                        last_update.remove(source);     // we can add the source back to last_seen if it send a PUT again

                        // update the snapshot by persis_manager
                        try { persis_manager.write_snapshot(memory_store); } catch (IOException e) { /* ignore this */ }
                    }
                }
            } finally {
                reentrant_lock.writeLock().unlock();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    // ---- main function ----
    public static void main(String[] args) throws Exception {
        int port = 4567;                    // default port -> can be changed
        String persis_dir = "./data";       // store in data for now

        // Parse in port num or persistent directory if provided
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            persis_dir = args[1];
        }

        // Starting the Aggregation Server
        new AggregationServer(port, persis_dir).start();
    }
}