package org.example;

// libraries for JSON Parser (Serialisation + Deserialisation)
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

// data structures
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

// socket programming
import java.net.ServerSocket;
import java.net.Socket;

// Side packages
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class AggregationServer {
    // ----- config + identity -----
    private final int port;
    private volatile boolean running = true;
    private final AtomicLong arrival_seq = new AtomicLong(0);
    private final ExecutorService connection_pool = Executors.newCachedThreadPool(); // for new connection to server socket

    // ----- in-memory/persistent storage -----
    private final Map<String, WeatherRecord> memory_store = new ConcurrentHashMap();
    private final Map<String, Long> last_update = new ConcurrentHashMap();

    // ---- single writer with queue for simplifying concurrency ----
    private final BlockingQueue<PutRequest> put_queue = new LinkedBlockingQueue<>();
    private Thread writer;

    // ---- Serialisation + Deserialisation ----
    private final Gson gson = new Gson();

    // ---- TODO ----
    private final LamportClock lp_clock;
    private final PersistenceManager persis_manager;     // Persistent manager for WAL + snapshot

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
    public AggregationServer(int port, String node_id, String persistent_dir) throws IOException {
        this.port = port;
        this.lp_clock = new LamportClock(node_id);
        this.persis_manager = new PersistenceManager(persistent_dir);
        load_snapshot_WAL();

        // ---- TODO ----
        start_worker();
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
                        long lamport = o.has("_lamport") ? o.get("_lamport").getAsLong() : 0;
                        String source = o.has("_sourceId") ? o.get("_sourceId").getAsString() : "unknown";

                        memory_store.put(id, new WeatherRecord(id, o.deepCopy(), lamport, source));
                    } else {
                        continue;
                    }
                }

                // replay WAL for any update not in snapshot
                for (JsonObject new_o : persis_manager.replay_WAL()) {
                    if (new_o.has("id")) {
                        String id = new_o.get("id").getAsString();
                        long lamport = new_o.has("_lamport") ? new_o.get("_lamport").getAsLong() : 0;
                        String source = new_o.has("_sourceId") ? new_o.get("_sourceId").getAsString() : "unknown";

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
                write_response(out_stream, 400, "Bad Request");
            }

            // read header
            long content_length = 0;
            String content_type = "";
            long remote_lamport = -1;
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
                    }
                }
            }

            // Update Aggregation Server Lamport Clock when receive request
            if (remote_lamport >= 0) {
                lp_clock.on_receive(remote_lamport);
            }

            // ----- Handling PUT Request -----
            if ("PUT".equalsIgnoreCase(method) && "/weaher.json".equals(path)){
                if (content_length == 0) {
                    write_response(out_stream, 204, "No Content");
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
                    write_response(out_stream, 500, "Invalid JSON!");
                    return;
                }

                // 500 - Missing id
                String id = payload.has("id") ? payload.get("id").getAsString() : null;
                if (id == null || id.isEmpty()) {
                    write_response(out_stream, 500, "Missing id in payload!");
                    return;
                }

                // Prepare PUT request for writer to update
                long lamport_header = (remote_lamport >= 0) ? remote_lamport : lp_clock.tick();         // if lp_clock from content sv smaller -> use agg sv lp_clock
                PutRequest req = new PutRequest(lamport_header, arrival_seq.incrementAndGet(), payload.deepCopy());
                put_queue.put(req);

                int result = req.result_future().get();
                write_resposne(out_stream,result, result == 201? "Created" : "OK");
            } else if ("GET".equalsIgnoreCase(method) && "/weaher.json".equals(path)){
                // Sending multiple weather records back
                JsonArray arr = new JsonArray();
                for (Map.Entry<String, WeatherRecord> e : memory_store.entrySet()) {
                    arr.add(e.getValue().data);
                }
                write_repsonse(out_stream, 200, gson.toJson(arr));
            } else {
                write_response(out_stream, 400, "Only accept GET or PUT");
            }
        }
        catch (Exception e) {

        } finally {
            try {
                s.close();
            } catch (IOException ignored) {}
        }
    }
}