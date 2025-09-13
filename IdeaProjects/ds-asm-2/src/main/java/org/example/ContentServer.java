package org.example;

// For JSON serialisation + deserialisation
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * Function: sends PUT requests with weather data to AggregationServer.
 *
 * Read 2 parameters from command line for: ServerName/PortNum + FileLocation
 * When a content server started, its initial Lamport Clock is 0, which is
 * then incremented when it tries to send a new PUT request
 */
public class ContentServer {
    private static int lamport = 0;                // initially start at 0
    public static void main (String[] args) throws Exception {
        String server = args[0];
        String file = args[1];
        String source_id = args[2];     // content server identification

        JsonObject payload = text_to_json(file, source_id);

        // Convert payload body to JSON format
        //JsonObject payload = new JsonParser().parse(body).getAsJsonObject();

        // ----- Interactive menu -----
        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        while (running) {
            System.out.println("------ Welcome to Content Server ------ \n");
            System.out.println("    (Please choose option 1 or 2) \n");
            System.out.println("1. Send PUT to Aggregation Server\n");
            System.out.println("2. Change weather data file \n");
            System.out.println("3. Exit \n");

            String option = scanner.nextLine().trim();

            switch(option){
                case "1":
                    lamport++;
                    // Call helper function for checking if we need to retry (convert payload from Json to String)
                    boolean success = send_with_retry(server, payload, source_id);
                    if (success) {
                        System.out.println("PUT sent successfully (Lamport: " + lamport + ", source_id: " + source_id + ")");
                    } else {
                        System.out.println("Put sent unsuccessfully after retries!");
                    }
                    break;

                case "2":
                    // Ask user for new file path
                    System.out.println("Enter the new file path: (eg. cs-data/sample2.txt");
                    file = scanner.nextLine().trim();
                    payload = text_to_json(file, source_id);
                    System.out.println("File switched to: " + file);
                    break;

                case "3":
                    running = false;
                    System.out.println("Exiting ...");
                    break;

                default:
                    System.out.println("Invalid choice! Please choose 1 and 2");
            }
        }
        scanner.close();
    }

    // helper function for converting from plaintext -> json
    private static JsonObject text_to_json(String file_path, String source_id) {
        try {
            // Read the whole BOM plaintext file
            String content = Files.readString(Paths.get(file_path), StandardCharsets.UTF_8);

            JsonObject obj = new JsonObject();

            // Split into lines and parse "key:value"
            String[] lines = content.split("\r?\n");
            for (String line : lines) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    obj.addProperty(parts[0].trim(), parts[1].trim());
                }
            }

            // Add lamport + source_id explicitly
            obj.addProperty("lamport", lamport);
            obj.addProperty("source_id", source_id);

            return obj;
        } catch (IOException e) {
            System.err.println("Failed to read file: " + e.getMessage());
            return new JsonObject();  // empty payload if error
        }
    }

    // helper function for sending + retry
    private static boolean send_with_retry(String server, JsonObject payload, String source_id){
        // Set up
        int max_retries = 5;
        int attempt = 0;
        int expo_backoff = 2000;    // 2 seconds

        // only retry within max number of times
        while (attempt < max_retries) {
            try {
                // Get a new URL for this content server using the provided address
                URL url = new URL(server);

                // Setup connection
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod("PUT");
                connection.setRequestProperty("Content-Type", "application/json");

                // Convert payload as string to bytes
                byte[] body_byte = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Length", String.valueOf(body_byte.length));
                connection.setRequestProperty("X-Lamport-Clock", Integer.toString(lamport));
                connection.setRequestProperty("X-Source-ID", source_id);

                // sending to the connection as writing to file
                try (OutputStream out_stream = connection.getOutputStream()){
                    out_stream.write(body_byte);
                }

                // Check the response from Agg Sv
                int status = connection.getResponseCode();

                // Update Content Server Lamport Clock
                String agg_lamport_header = connection.getHeaderField("X-Lamport-Clock");
                if (agg_lamport_header != null) {
                    int agg_lamport = Integer.parseInt(agg_lamport_header);
                    lamport = Math.max(lamport, agg_lamport) + 1;
                }

                // print out response
                System.out.println("Status: " + status);
                System.out.println("Lamport Updated: " + lamport);

                if (status >= 200 && status < 300) {
                    return true;
                }
            } catch (Exception e) {
                System.err.println("Content Server attempt: " + (attempt + 1 ) + e.getMessage());
            }

            // Implementing retry
            attempt++;
            try {
                // Backoff by putting this content server (thread) to sleep
                Thread.sleep(expo_backoff);
            } catch (InterruptedException ignored) {
                // ignore this
            }
            expo_backoff *= 2;
        }

        // Cannot send after the above retries
        return false;
    }
}
