package org.example;

import com.google.gson.JsonObject;

public class WeatherRecord {
    public final String id;
    public volatile JsonObject data;
    public volatile long lamport;

    public WeatherRecord(String id, JsonObject data, long lamport) {
        this.id = id;
        this.data = data;
        this.lamport = lamport;
    }
}
