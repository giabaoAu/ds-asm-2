import org.example.AggregationServer;
import org.example.ContentServer;
import org.example.GETClient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for AggregationServer project
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AggregationServerTest {

    // ----------------------------
    // Ultility Setup
    // ----------------------------

    private static final int PORT = 4567;
    private static final String SERVER_URL = "http://localhost:" + PORT + "/weather.json";
    private static final Path DATA_DIR = Paths.get("./cs-data-test");
    private static Process serverProcess;

    /** set up test data directory */
    private static void cleanDataDir() throws IOException {
        if (!Files.exists(DATA_DIR)) {
            Files.createDirectories(DATA_DIR);
        }
    }

    /** Start AggregationServer */
    @BeforeAll
    public static void startServer() throws Exception {
        cleanDataDir();

        // java -cp "out;gson-2.11.0.jar" org.example.AggregationServer
        serverProcess = new ProcessBuilder("java",
                "-cp", "out;gson-2.11.0.jar",
                "org.example.AggregationServer",
                String.valueOf(PORT),
                DATA_DIR.toString())
                .redirectErrorStream(true)
                .start();

        // Give server time to start
        Thread.sleep(1500);
        System.out.println("AggregationServer started for testing...");
    }

    /** Stop server */
    @AfterAll
    public static void stopServer() {
        if (serverProcess != null) serverProcess.destroy();
    }

    /** send GET request (Agg Sv return Json Array) */
    private JsonArray sendGet() throws Exception {
        URL url = new URL(SERVER_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        /** get server response */
        int code = conn.getResponseCode();
        assertEquals(200, code, "GET should return 200 OK");

        /** read line by line */
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return JsonParser.parseString(sb.toString()).getAsJsonArray();
        }
    }

    /** Send PUT request */
    private int sendPut(JsonObject payload, int lamport) throws Exception {
        URL url = new URL(SERVER_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        /** prepare payload */
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] body = payload.toString().getBytes();
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        conn.setRequestProperty("X-Lamport-Clock", String.valueOf(lamport));

        /** send as output */
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        return conn.getResponseCode();
    }

    /** build simple sample weather JSON */
    private JsonObject sampleRecord(String id, double temp) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("air_temp", temp);
        return obj;
    }

    // ----------------------------
    // FUNCTIONALITY TESTS
    // ----------------------------

    @Test
    @Order(1)
    public void testEmptyGet() throws Exception {
        System.out.println("TEST: GET from empty server");
        JsonArray arr = sendGet();
        assertEquals(0, arr.size(), "Expected empty array");
    }

    /**
     * Test if the first PUT request returned with a 201
     * payload: id - IDS0001, air - 20.0, lamport - 1
     *
     * Expected: 201 --------- Get: 201
     * */
    @Test
    @Order(2)
    public void testPutFirstRecord() throws Exception {
        System.out.println("TEST: First PUT returns 201");
        JsonObject record = sampleRecord("IDS001", 20.0);
        int code = sendPut(record, 1);
        assertEquals(201, code, "First PUT should return 201 Created");

        JsonArray arr = sendGet();
        assertEquals(1, arr.size(), "GET should return 1 record");
        assertEquals(20.0, arr.get(0).getAsJsonObject().get("air_temp").getAsDouble());
    }

    /**
     * Test if the second PUT request overwrite the first one
     * Old: id - IDS0001, air - 20.0, lamport - 1
     * New:  id - IDS0001, air - 25.0, lamport - 5
     *
     * Expected: 25 ------- Get: 25
     * */
    @Test
    @Order(3)
    public void testPutUpdateHigherLamport() throws Exception {
        System.out.println("TEST: PUT update with higher Lamport returns 200 and updates record");
        JsonObject record = sampleRecord("IDS001", 25.0);
        int code = sendPut(record, 5);
        assertEquals(200, code, "Update PUT should return 200 OK");

        JsonArray arr = sendGet();
        assertEquals(25.0, arr.get(0).getAsJsonObject().get("air_temp").getAsDouble());
    }

    @Test
    @Order(4)
    public void testPutOlderLamportIgnored() throws Exception {
        System.out.println("TEST: PUT with older Lamport ignored");
        JsonObject record = sampleRecord("IDS001", 15.0);
        int code = sendPut(record, 3);
        assertEquals(200, code, "Older Lamport still returns 200");

        JsonArray arr = sendGet();
        assertEquals(25.0, arr.get(0).getAsJsonObject().get("air_temp").getAsDouble(),
                "Older Lamport should NOT overwrite newer value");
    }

    // ----------------------------
    // FAILURE MODES
    // ----------------------------

    @Test
    @Order(5)
    public void testPutNoContent() throws Exception {
        System.out.println("TEST: PUT no content returns 204");
        URL url = new URL(SERVER_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Length", "0");
        int code = conn.getResponseCode();
        assertEquals(204, code);
    }

    @Test
    @Order(6)
    public void testPutInvalidJson() throws Exception {
        System.out.println("TEST: PUT invalid JSON returns 500");
        URL url = new URL(SERVER_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] body = "{ invalid json }".getBytes();
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        try (OutputStream os = conn.getOutputStream()) { os.write(body); }
        int code = conn.getResponseCode();
        assertEquals(500, code);
    }

    // ----------------------------
    // CONCURRENCY TESTS
    // ----------------------------

    @Test
    @Order(7)
    public void testConcurrentPuts() throws Exception {
        System.out.println("TEST: Concurrent PUTs maintain Lamport order");
        ExecutorService ex = Executors.newFixedThreadPool(2);
        JsonObject rec1 = sampleRecord("IDS002", 10.0);
        JsonObject rec2 = sampleRecord("IDS002", 12.0);

        Future<Integer> f1 = ex.submit(() -> sendPut(rec1, 7));
        Future<Integer> f2 = ex.submit(() -> sendPut(rec2, 6));

        int code1 = f1.get();
        int code2 = f2.get();
        assertTrue((code1 == 200 || code1 == 201));
        assertTrue((code2 == 200 || code2 == 201));

        JsonArray arr = sendGet();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            if ("IDS002".equals(obj.get("id").getAsString())) {
                assertEquals(10.0, obj.get("air_temp").getAsDouble(),
                        "Lamport=7 should overwrite Lamport=6");
            }
        }
        ex.shutdown();
    }

    @Test
    @Order(8)
    public void testConcurrentGets() throws Exception {
        System.out.println("TEST: Concurrent GETs return consistent feed");
        ExecutorService ex = Executors.newFixedThreadPool(3);
        Callable<JsonArray> task = this::sendGet;
        Future<JsonArray> f1 = ex.submit(task);
        Future<JsonArray> f2 = ex.submit(task);
        Future<JsonArray> f3 = ex.submit(task);

        JsonArray arr1 = f1.get();
        JsonArray arr2 = f2.get();
        JsonArray arr3 = f3.get();

        assertEquals(arr1.size(), arr2.size());
        assertEquals(arr2.size(), arr3.size());
        ex.shutdown();
    }

    // ----------------------------
    // EDGE CASES
    // ----------------------------

    @Test
    @Order(9)
    public void testMultipleRecords() throws Exception {
        System.out.println("TEST: Multiple records coexist");
        sendPut(sampleRecord("IDS003", 30.0), 8);
        sendPut(sampleRecord("IDS004", 18.0), 9);
        JsonArray arr = sendGet();
        assertTrue(arr.size() >= 3, "Expect at least 3 records total");
    }

    @Test
    @Order(10)
    public void testPersistenceRecovery() throws Exception {
        System.out.println("TEST: Server crash/restart recovers feed");
        serverProcess.destroy();
        Thread.sleep(1500); // simulate downtime

        serverProcess = new ProcessBuilder("java",
                "-cp", "out:gson-2.10.1.jar",
                "agg.AggregationServer",
                String.valueOf(PORT),
                DATA_DIR.toString())
                .redirectErrorStream(true)
                .start();
        Thread.sleep(1500); // wait for restart

        JsonArray arr = sendGet();
        assertTrue(arr.size() >= 3, "Recovered feed should have all previous records");
    }

    // ----------------------------
    // EXPIRY MECHANISM
    // ----------------------------
    @Test
    @Order(11)
    public void testExpiry() throws Exception {
        System.out.println("TEST: Expiry removes old content after 30s");
        JsonObject rec = sampleRecord("IDS_EXPIRE", 99.0);
        sendPut(rec, 20);
        Thread.sleep(31_000); // wait for expiry
        JsonArray arr = sendGet();
        for (int i = 0; i < arr.size(); i++) {
            assertNotEquals("IDS_EXPIRE", arr.get(i).getAsJsonObject().get("id").getAsString(),
                    "Expired record should be removed");
        }
    }
}
