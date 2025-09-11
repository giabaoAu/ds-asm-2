package org.example;

import com.google.gson.JsonObject;

public class WeatherRecord {
    public final String id;
    public volatile JsonObject data;
    public volatile long lamport;
    public final String source_id;  // identifying source content server

    public WeatherRecord(String id, JsonObject data, long lamport, String source_id) {
        this.id = id;
        this.data = data;
        this.lamport = lamport;
        this.source_id = source_id;
    }
}
