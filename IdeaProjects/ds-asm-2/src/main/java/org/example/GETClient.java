package org.example;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class GETClient {
    public static void main(String[] args) throws Exception {
        // Get serverName + portNumber if provided from command line
        String server;
        if (args.length > 0) {
            server = args[0];
        } else {
            server = "http://localhost:4567/weather.json";
        }

        // Set up connection
        URL url = new URL(server);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        // Receive server status code
        System.out.println("GET status: " + connection.getResponseCode());

        // Printing out the result
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
    }
}
