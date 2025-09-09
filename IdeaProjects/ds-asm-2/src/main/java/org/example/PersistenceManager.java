package org.example;

// For JSON Serialisation + Deserialisation
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
    public synchronized void append_wal(long lamport, JsonObject payload) throws IOException {
        // Preparing payload
        JsonObject wal_payload = new JsonObject();
        wal_payload.addProperty("lamport", lamport);
        wal_payload.add("payload", payload);

        // Write to file
        try (FileOutputStream file_out = new FileOutputStream(wal_path.toFile(), true);) {
            file_out.write((gson.toJson(wal_payload) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            file_out.getChannel().force(true);
        }
    }

    // ---- Write snapshot ----
    public synchronized void write_snapshot(ConcurrentMap<String, WeatherRecord> memory_store) throws IOException {
        // create a temporary snapshot file
        Path temp = snapshot_path.resolveSibling("feed.json.temp");

        // Iterate over each weather record (used try block as recommended by IDE)
        try (BufferedWriter writer = Files.newBufferedWriter(temp)) {
            List<JsonObject> arr = new ArrayList<>();
            for (Map.Entry<String, WeatherRecord> it : memory_store.entrySet()){
                // add both lamport + payload to each array object
                JsonObject o = it.getValue().data.deepCopy();
                o.addProperty("_lamport", it.getValue().lamport);
                arr.add(o);
            }

            // convert arr to JSON
            writer.write(gson.toJson(arr));
        }

        Files.move(temp, snapshot_path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);       // replace temp with current snapshot if no crash atomically
    }


}
