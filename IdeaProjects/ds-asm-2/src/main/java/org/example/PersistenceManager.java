package org.example;

// For JSON Serialisation + Deserialisation
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

public class PersistenceManager {
    private final Path wal_path;        // where updates are logged
    private final Path snapshot_path;
    private final Gson gson = new Gson();

    // ---- Constructor ----
    public PersistenceManager(String persis_dir) throws IOException {
        // initiate dir path for persistent manager
        Files.createDirectories(Paths.get(persis_dir));
        this.wal_path = Paths.get(persis_dir, "updates.wal");
        this.snapshot_path = Paths.get(persis_dir, "feed.json");

        // Set up paths for WAL and snapshot if the dir exists
        if (!Files.exists(wal_path)) {
            Files.createFile(wal_path);
        }
    }

    // ---- Append to WAL ----
    public synchronized void append_wal(long lamport, String source_id, JsonObject payload) throws IOException {
        // Preparing wal_payload to write to file
        // example: {"lamport":15,"source_id":"CS1","payload":{"id":"A1","temperature":28}}
        JsonObject wal_payload = new JsonObject();
        wal_payload.addProperty("lamport", lamport);
        wal_payload.addProperty("source_id", source_id);
        wal_payload.add("payload", payload);

        // Write to file
        try (FileOutputStream file_out = new FileOutputStream(wal_path.toFile(), true);) {
            file_out.write((gson.toJson(wal_payload) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            file_out.getChannel().force(true);
        }
    }

    // ---- Write snapshot ----
    public synchronized void write_snapshot(ConcurrentMap<String, WeatherRecord> memory_store) throws IOException {
        // example: [{"id":"A1","temperature":28,"lamport":15,"source_id":"CS1"}]
        // create a temporary snapshot file
        Path temp = snapshot_path.resolveSibling("feed.json.temp");

        // Iterate over each weather record (used try block as recommended by IDE)
        try (BufferedWriter writer = Files.newBufferedWriter(temp)) {
            List<JsonObject> arr = new ArrayList<>();
            for (Map.Entry<String, WeatherRecord> it : memory_store.entrySet()){
                // add both lamport + payload to each array object
                JsonObject o = it.getValue().data.deepCopy();
                o.addProperty("lamport", it.getValue().lamport);
                o.addProperty("source_id", it.getValue().source_id);
                arr.add(o);
            }

            // convert arr to JSON
            writer.write(gson.toJson(arr));
        }
        // If no crash happen after writing -> replace temp as the newest snapshot
        Files.move(temp, snapshot_path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);       // replace temp with current snapshot if no crash atomically
    }

    // ---- Replay WAL ----
    public synchronized List<JsonObject> replay_WAL() throws IOException {
        // Check if WAL exits
        List<JsonObject> result = new ArrayList<>();        // returning an array of Json object
        if (!Files.exists(wal_path)) return result;

        // Read the file (updates.wal) in the wal_path dir
        try (BufferedReader reader = Files.newBufferedReader(wal_path)) {
            String line;
            // Add line from wal_path file to entry as JSON object
            // Then append all payload (Json obj) into result
            while((line = reader.readLine()) != null) {
                try {
                    // Prepare payload for result
                    JsonObject entry = new JsonParser().parse(line).getAsJsonObject();
                    JsonObject payload = entry.getAsJsonObject("payload");

                    // Embed lamport + source_id into payload for recovery
                    if (entry.has("lamport")) {
                        payload.addProperty("lamport", entry.get("lamport").getAsLong());
                    }
                    if (entry.has("source_id")) {
                        payload.addProperty("source_id", entry.get("source_id").getAsString());
                    }

                    // Append to result
                    result.add(entry.getAsJsonObject("payload"));
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    // ---- Reload the last saved snapshot when startup ----
    public synchronized String read_snapshot() throws IOException {
        // Check if no snapshot found
        if (!Files.exists(snapshot_path)) return null;
        // this result will be used for the actual reading in the Agg Sv code
        return new String(Files.readAllBytes(snapshot_path), StandardCharsets.UTF_8);
    }

    // ---- Truncate WAL only after replaying WAL in the Aggregation Server ----
    public synchronized void truncate_WAL() throws IOException {
        Files.newBufferedWriter(wal_path, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING).close();
    }
}
