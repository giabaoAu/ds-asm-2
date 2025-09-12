package org.example;

// For JSON serialisation + deserialisation
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

        // Get the file from local path
        String body = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);

        // Convert payload body to JSON format
        JsonObject payload = new JsonParser().parse(body).getAsJsonObject();

        // ----- Interactive menu -----
        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        while (running) {
            System.out.println("------ Welcome to Content Server ------ \n");
            System.out.println("    (Please choose option 1 or 2) \n");
            System.out.println("1. Send PUT to Aggregation Server\n");
            System.out.println("2. Exit \n");

            String option = scanner.nextLine().trim();

            switch(option){
                case "1":
                    lamport++;
                    // Call helper function for checking if we need to retry (convert payload from Json to String)
                    boolean success = send_with_retry(server, payload.toString(), source_id);
                    if (success) {
                        System.out.println("PUT sent successfully (Lamport: " + lamport + ", source_id: " + source_id + ")");
                    } else {
                        System.out.println("Put sent unsuccessfully after retries!");
                    }
                    break;

                case "2":
                    running = false;
                    System.out.println("Exiting ...");
                    break;

                default:
                    System.out.println("Invalid choice! Please choose 1 and 2");
            }
        }
        scanner.close();
    }

    // helper function for sending + retry
    private static boolean send_with_retry(String server, String body, String source_id){
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
                connection.setRequestProperty("Content-Length", String.valueOf(body.getBytes(StandardCharsets.UTF_8).length));
                connection.setRequestProperty("X-Lamport-Clock", Integer.toString(lamport));
                connection.setRequestProperty("X-Source-ID", source_id);

                // sending to the connection as writing to file
                try (OutputStream out_stream = connection.getOutputStream()){
                    out_stream.write(body.getBytes(StandardCharsets.UTF_8));
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
