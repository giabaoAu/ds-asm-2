package org.example;

// libraries for JSON Parser (Serialisation + Deserialisation)
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class AggregationServer {
    // ----- config + identity -----
    private final int port;
    private final String node_id;
    private final String persistent_dir;        // directory where WAL/snapshots are stored
    private volatile boolean running = true;
    private final AtomicLong arrival_seq = new AtomicLong(0);
    private final ExecutorService connection_pool = Executor.newCachedThreadPool();

    // ----- in-memory/persistent storage -----
    private final Map<String, WeatherRecord> memory_store = new ConcurrentHashMap<>();
    private final Map<String, Long> last_update = new ConcurrentHashMap<>();

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
    }

    // Load snapshot + WAL on startup in case of recovery after crash
    private void load_snapshot_WAL() {
        try {
            String snapshot = persis_manager.read_snapshot();
            // We got a snapshot (its a JSON array of records)
            if (snapshot != null && !snapshot.trim().isEmpty()) {
                JsonArray arr = JsonParser.parseString(snapshot).getAsJsonArray();
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
                        tring id = new_o.get("id").getAsString();
                        long lamport = new_o.has("_lamport") ? new_o.get("_lamport").getAsLong() : 0;
                        String source = new_o.has("_sourceId") ? new_o.get("_sourceId").getAsString() : "unknown";

                        // overwrite old record -> larger lamport means new
                        WeatherRecord existing = memory_store.get(id);
                        if (existing == null || lamport >= existing.lamport) {
                            memory_store.put(id, new WeatherRecord(id, new_deepCopy(), lamport, source));
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
}