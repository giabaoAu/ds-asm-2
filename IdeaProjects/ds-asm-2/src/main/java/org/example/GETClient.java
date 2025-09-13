package org.example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class GETClient {
    private static int lamport = 0; // initially starts at 0

    public static void main(String[] args) throws Exception {
        // Get serverName + portNumber if provided from command line
        String server;
        if (args.length > 0) {
            server = args[0];
        } else {
            server = "http://localhost:4567/weather.json";
        }

        // Increment Lamport Clock before sending
        lamport++;

        // Set up connection
        URL url = new URL(server);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        // Attach the lamport header
        connection.setRequestProperty("X-Lamport-Clock", Integer.toString(lamport));

        // Receive server status code
        int status = connection.getResponseCode();
        System.out.println("GET status: " + status);

        // Update client clock based on Lamport from server
        String agg_lamport_header = connection.getHeaderField("X-Lamport-Clock");
        if(agg_lamport_header != null) {
            int agg_lamport = Integer.parseInt(agg_lamport_header);
            System.out.println("GET status: " + agg_lamport);
            lamport = Math.max(lamport, agg_lamport) + 1;
        }
        System.out.println("Client Updated Lamport: " + lamport);

        // Printing out the result
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
    }
}
