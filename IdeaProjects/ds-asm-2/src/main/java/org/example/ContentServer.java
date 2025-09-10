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

/**
 * Function: sends PUT requests with weather data to AggregationServer.
 *
 * Read 2 parameters from command line for: ServerName/PortNum + FileLocation
 */
public class ContentServer {
    public static void main (String[] args) throws Exception {
        String server = args[0];
        String file = args[1];
        int lamport = 0;        // We assume that content server initially start at 0

        // Get the file from local path
        String body = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        // Convert payload body to JSON format
        JsonObject payload = new JsonParser().parse(body).getAsJsonObject();

        // Get a new URL for this content server using the provided address
        URL url = new URL(server);

        // Setup connection
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("content-Length", String.valueOf(body.getBytes(StandardCharsets.UTF_8).length));
        connection.setRequestProperty("X-Lamport-Clock", Integer.toString(lamport));

        // sending to the connection as writing to file
        try (OutputStream out_stream = connection.getOutputStream()){
            out_stream.write(body.getBytes(StandardCharsets.UTF_8));
        }

        // Check the response from Agg Sv
        System.out.println("Status: " + connection.getResponseCode());
    }
}
