package org.example;

// libraries for JSON Parser (Serialisation + Deserialisation)
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class AggregationServer {
    // ----- config + identity -----
    private final int port;
    private final String node_id;
    private final String persistent_dir;        // directory where WAL/snapshots are stored

    // ----- in-memory/persistent storage -----
    private final Map<String, WeatherRecord> memory_store = new ConcurrentHashMap<>();
    private final Map<String, Long> last_update = new ConcurrentHashMap<>();

    // ---- single writer with queue for simplifying concurrency ----
    private final BlockingQueue<PutRequest> put_queue = new LinkedBlockingQueue<>();
    private Thread writer;

    // ---- Serialisation + Deserialisation ----
    private final Gson gson = new Gson();

    // ---- Persistent manager for WAL + snapshot ----
    private final PersistenceManager persis_manager;

    /**
     * Aggregation server (simple socket-based HTTP parsing).
     *
     * Key functionality:
     * - Accept PUT from content servers and GET from clients
     * - PUTs must include lamport clock and node id headers for tracking
     * - Enqueue PUTs into a PriorityBlockingQueue ordered by for handling multiple PUTs
     * - Single writer applies data updates: WAL + in-memory store + write snapshot
     * - Writer completes per-request CompletableFuture so the connection thread can send 201/200
     * - Expiry chcker removes content server out of contact after 30s
     */
    public AggregationServer(int port, String node_id, String persistent_dir) throws IOException {
        this.port = port;
        this.node_id = node_id;
        this.persistent_dir = persistent_dir;
        this.persis_manager = new PersistenceManager(persistent_dir);
    }
}