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

    /** set up test data directory and reset files for each test run*/
    private static void cleanDataDir() throws IOException {
        if (!Files.exists(DATA_DIR)) {
            Files.createDirectories(DATA_DIR);
        }

        // Delete old WAL file if exists
        Path walFile = DATA_DIR.resolve("updates.wal");
        if (Files.exists(walFile)) {
            Files.delete(walFile);
        }

        // Reset feed.json to []
        Path feedFile = DATA_DIR.resolve("feed.json");
        Files.writeString(feedFile, "[]", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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
        if (serverProcess != null) serverProcess.destroyForcibly();
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

    /** Send PUT request (with no source_id) */
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

    /** Send PUT request (with source_id) */
    private int sendPutWithSource(JsonObject record, long lamport, String source_id) throws Exception {
        URL url = new URL("http://localhost:4567/weather.json");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);

        // Building up the headers
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Content-Length", String.valueOf(record.toString().getBytes().length));
        connection.setRequestProperty("X-Lamport-Clock", String.valueOf(lamport));
        connection.setRequestProperty("X-Source-ID", source_id);  // send with source_id

        try (OutputStream os = connection.getOutputStream()) {
            os.write(record.toString().getBytes());
            os.flush();
        }

        int code = connection.getResponseCode();
        connection.disconnect();
        return code;
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

    /**
     * TEST 1
     *
     * Test if we get an empty array from an empty Aggregation Server
     *
     * Expected: 0 --------- Get: 0
     * */
    @Test
    @Order(1)
    public void testEmptyGet() throws Exception {
        System.out.println("TEST: GET from empty server");
        JsonArray arr = sendGet();
        assertEquals(0, arr.size(), "Expected empty array");
    }

    /**
     * TEST 2
     *
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
     * TEST 3
     *
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

    /**
     * TEST 4
     *
     * Test if the PUT request with older Lamport is not overwriting the current one
     * current: id - IDS0001, air - 25.0, lamport - 6
     * new: id - IDS0001, air - 15.0, lamport - 3
     *
     * Expected: 25.0 (current) --------- Get: 25
     * */
    @Test
    @Order(4)
    public void testPutOlderLamportIgnored() throws Exception {
        System.out.println("TEST: PUT with older Lamport ignored");
        JsonObject record = sampleRecord("IDS001", 15.0);
        int code = sendPut(record, 3);
        assertEquals(200, code, "Older Lamport still returns 200");

        JsonArray arr = sendGet();
        System.out.println("Air from server: " + arr.get(0).getAsJsonObject().get("air_temp").getAsDouble());
        assertEquals(25.0, arr.get(0).getAsJsonObject().get("air_temp").getAsDouble(),
                "Older Lamport should NOT overwrite newer value");
    }

    // ----------------------------
    // FAILURE MODES
    // ----------------------------

    /**
     * TEST 5
     *
     * Test if the PUT request with no content return 204
     * payload: PUT HTTP/1.1
     *
     * Expected: 204 --------- Get: 204
     * */
    @Test
    @Order(5)
    public void testPutNoContent() throws Exception {
        System.out.println("TEST: PUT no content returns 204");

        // Set up connection
        URL url = new URL(SERVER_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);

        // only PUT + content length nothing else
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Length", "0");

        // Check if get 204 code from Agg Sv
        int code = conn.getResponseCode();
        assertEquals(204, code);
    }

    /**
     * TEST 6
     *
     * Test if the PUT request has valid Json
     * payload: "{ this is an invalid json }"
     *
     * Expected: 500 --------- Get: 500
     * */
    @Test
    @Order(6)
    public void testPutInvalidJson() throws Exception {
        System.out.println("TEST: PUT invalid JSON returns 500");
        // Set up connection
        URL url = new URL(SERVER_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json");

        // parse in the invalid json
        byte[] body = "{ this is an invalid json }".getBytes();
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        try (OutputStream os = conn.getOutputStream()) { os.write(body); }

        // Check if server reponse with 500
        int code = conn.getResponseCode();
        assertEquals(500, code);
    }

    /**
     * TEST 7
     *
     * Test if sending a requests that are not PUT/GET
     * payload: HEAD
     *
     * Expected: 400 --------- Get: 400
     * */
    @Test
    @Order(7)
    public void testInvalidMethod() throws Exception {
        System.out.println("TEST: send request with invalid method returns 400");
        // Set up connection
        URL url = new URL(SERVER_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("HEAD");

        // Check if server reponse with 400
        int code = conn.getResponseCode();
        assertEquals(400, code);
    }

    // ----------------------------
    // CONCURRENCY TESTS
    // ----------------------------

    /**
     * TEST 8
     *
     * Test 2 PUTs with same record ID but different Lamport clock
     * These 2 arrive at the same time so the order is non-deterministic
     * Depending on the scheduling of the OS, any of them might hit the Agg Server first
     * So we need to use Lamport Clock to order these 2 request
     * (if 6 arrives first, it might over write 7 without Lamport clock)
     *
     * PUT-1 (Lamport 6)
     * PUT-2 (Lamport 7)
     *
     * Expected: PUT-2 (Lamport 7) --------- Get: PUT-2 (Lamport 7)
     * */
    @Test
    @Order(8)
    public void testConcurrentPuts() throws Exception {
        System.out.println("TEST: Concurrent PUTs maintain Lamport order");
        // Spawn 2 thread for sending at the same time
        ExecutorService ex = Executors.newFixedThreadPool(2);
        JsonObject rec1 = sampleRecord("IDS002", 10.0);
        JsonObject rec2 = sampleRecord("IDS002", 12.0);
        Future<Integer> future_1 = ex.submit(() -> sendPut(rec1, 7));
        Future<Integer> future_2 = ex.submit(() -> sendPut(rec2, 6));

        // Collect future result
        int res_1 = future_1.get();
        int res_2 = future_2.get();
        assertTrue((res_1 == 200 || res_1 == 201));
        assertTrue((res_2 == 200 || res_2 == 201));

        // Check which one is stored on the Agg Server
        JsonArray arr = sendGet();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            // look fore record of ID = IDS002 that either 6 or 7
            if ("IDS002".equals(obj.get("id").getAsString())) {
                // if 10, which means 7 was kept -> correct
                // else 12, which means 6 overwrites 7 -> incorrect
                assertEquals(10.0, obj.get("air_temp").getAsDouble(),
                        "Lamport=7 should overwrite Lamport=6");
            }
        }
        ex.shutdown();
    }

    /**
     * TEST 9
     *
     * Test if three clients get the same response from Agg Server
     * Spawn a pool of 3 threads to send 3 GET requests.
     * Check if the repsonse is consistent accross 3 threads
     * (correct if they have same length)
     *
     * Expected: True --------- Get: True
     * */
    @Test
    @Order(9)
    public void testConcurrentGets() throws Exception {
        System.out.println("TEST: Concurrent GETs return consistent feed");

        // Create a thread pool of 3 worker
        ExecutorService ex = Executors.newFixedThreadPool(3);

        // Each task call sendGet()
        Callable<JsonArray> task = this::sendGet;
        Future<JsonArray> future_1 = ex.submit(task);
        Future<JsonArray> future_2 = ex.submit(task);
        Future<JsonArray> future_3 = ex.submit(task);

        // Get result JSON array result from Agg Server
        JsonArray arr1 = future_1.get();
        JsonArray arr2 = future_2.get();
        JsonArray arr3 = future_3.get();

        // Just making sure that they didn't all get empty response
        // Got: [{"id":"IDS002","air_temp":10.0}]
        System.out.println("Test 9 - res from agg sv: " + arr1);

        // Check if they all get the same response
        assertEquals(arr1.size(), arr2.size());
        assertEquals(arr2.size(), arr3.size());
        ex.shutdown();
    }

    // ----------------------------
    // EDGE CASES
    // ----------------------------

    /**
     * TEST 10
     *
     * Test if multiple records from different Content Servers coexist
     * PUT-1: id - IDS003, air - 30.0, lamport - 8, source_id - CS1
     * PUT-2: id - IDS004, air - 18.0, lamport - 9, source_id - CS2
     * PUT-3: id - IDS005, air - 12.0, lamport - 10, source_id - CS3
     *
     * Expected: >= 3 records --------- Get: >= 3 records
     */
    @Test
    @Order(10)
    public void testMultipleRecords() throws Exception {
        System.out.println("TEST: Multiple records coexist");

        // Sending from CS1, CS2 and CS3
        sendPutWithSource(sampleRecord("IDS003", 30.0), 8, "CS1");
        sendPutWithSource(sampleRecord("IDS004", 18.0), 9, "CS2");
        sendPutWithSource(sampleRecord("IDS005", 12.0), 10, "CS3");

        // Get Agg Sv response
        JsonArray arr = sendGet();

        // For debugging
        System.out.println("Test 10 - res from server: " + arr);

        // Return true if at least 3 records
        assertTrue(arr.size() >= 3, "Expect at least 3 records total");
    }

    // ----------------------------
    // EXPIRY MECHANISM
    // ----------------------------

    /**
     * TEST 11
     *
     * Test if server persists data after crash/restart
     * Step 1: destroy serverProcess to simulate a crash
     * Step 2: restart AggregationServer with same data dir
     * Step 3: GET should return all previously stored records
     *
     * Expected: >= 3 records recovered --------- Get: >= 3
     */
    @Test
    @Order(11)
    public void testPersistenceRecovery() throws Exception {
        System.out.println("TEST: Server crash/restart recovers feed");
        serverProcess.destroy();
        Thread.sleep(1500); // simulate downtime

        serverProcess = new ProcessBuilder("java",
                "-cp", "out;gson-2.11.0.jar",
                "org.example.AggregationServer",
                String.valueOf(PORT),
                DATA_DIR.toString())
                .redirectErrorStream(true)
                .start();
        Thread.sleep(1500); // wait for restart

        JsonArray arr = sendGet();
        assertTrue(arr.size() >= 3, "Recovered feed should have all previous records");
    }

    /**
     * TEST 12
     *
     * Test if records expire after 30 seconds
     * PUT: id - IDS_EXPIRE, air - 99.0, lamport - 20
     * wait 31 seconds (records of out-of-contact server expire after 30s)
     * GET should no longer contain IDS_EXPIRE
     * We need to start a new server to make sure Agg Sv is not replaying WAL
     *
     * Expected: IDS_EXPIRE not found --------- Get: not found
     */
    @Test
    @Order(12)
    public void testExpiry() throws Exception {
        System.out.println("TEST: Expiry removes old content after 30s");

        // Start the new server in different dir
        Path tempDir = Files.createTempDirectory("agg_test_expiry");
        serverProcess = new ProcessBuilder("java", "-cp", "out;gson-2.11.0.jar",
                "org.example.AggregationServer", String.valueOf(PORT),
                tempDir.toString())
                .redirectErrorStream(true)
                .start();

        Thread.sleep(1500);

        // Now put the expiry record
        JsonObject rec = sampleRecord("IDS_EXPIRE", 99.0);
        sendPutWithSource(rec, 20, "CS1");

        // Wait >30s for expiry
        Thread.sleep(35_000);

        // Verify record expired
        JsonArray arr = sendGet();
        System.out.println("Test 12 - res from server: " + arr);
        for (int i = 0; i < arr.size(); i++) {
            assertNotEquals("IDS_EXPIRE", arr.get(i).getAsJsonObject().get("id").getAsString(),
                    "Expired record should be removed");
        }

        // Cleanup tempDir
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }

    /**
     * TEST 13
     *
     * Sequence: PUT1 (Lamport=4) -> GET (Lamport=6) -> PUT2 (Lamport=8)
     *
     * Expected:
     *  - The GET between the two PUTs must see the state after PUT1.
     *  - PUT2 is applied after, so later GET should reflect the new value.
     */
    @Test
    @Order(13)
    public void testPutGetPutOrdering() throws Exception {
        System.out.println("TEST: PUT -> GET -> PUT ordering (Lamport sequence)");

        // PUT1 (Lamport=4)
        JsonObject rec1 = sampleRecord("IDS_SEQ", 11.0);
        int code1 = sendPut(rec1, 4);
        assertTrue(code1 == 200 || code1 == 201, "PUT1 should succeed");

        // Step 2: GET (Lamport=6) -> must see PUT1
        JsonArray arrMid = sendGet();
        boolean found11 = false;
        for (int i = 0; i < arrMid.size(); i++) {
            JsonObject obj = arrMid.get(i).getAsJsonObject();
            if ("IDS_SEQ".equals(obj.get("id").getAsString())) {
                double temp = obj.get("air_temp").getAsDouble();
                assertEquals(11.0, temp, "Mid-GET must see PUT1 result (11.0)");
                found11 = true;
            }
        }
        assertTrue(found11, "Expected to find IDS_SEQ after PUT1");

        // Step 3: PUT2 (Lamport=8) overwrites the value
        JsonObject rec2 = sampleRecord("IDS_SEQ", 22.0);
        int code2 = sendPut(rec2, 8);
        assertEquals(200, code2, "PUT2 should return 200 OK");

        // Step 4: GET after PUT2 -> must see updated value
        JsonArray arrFinal = sendGet();
        boolean found22 = false;
        for (int i = 0; i < arrFinal.size(); i++) {
            JsonObject obj = arrFinal.get(i).getAsJsonObject();
            if ("IDS_SEQ".equals(obj.get("id").getAsString())) {
                double temp = obj.get("air_temp").getAsDouble();
                assertEquals(22.0, temp, "Final GET must see PUT2 result (22.0)");
                found22 = true;
            }
        }
        assertTrue(found22, "Expected to find IDS_SEQ after PUT2");
    }

}