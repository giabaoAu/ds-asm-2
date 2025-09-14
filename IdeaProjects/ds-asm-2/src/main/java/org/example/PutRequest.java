package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;      // writer notify Agg Sv when request being processed

/**
 * Function: order requests by Lamport Timestamp
 *           If the two requests have the same lamport
 *            -> order by arrival sequence
 */
public class PutRequest implements Comparable<PutRequest> {
    enum Type { PUT, GET }

    public final Type type;
    public final long lamport;
    public final long arrival_seq;

    // For PUT request only
    public final JsonObject payload;
    public final String source_id;  // identifying source content server
    public final CompletableFuture<Integer> result_future;   // writer send back result asynchronously

    // For GET request only
    public final CompletableFuture<JsonArray> get_future;

    // ---- Constructor for first PUT request ----
    public PutRequest(long lamport, long arrival_seq, JsonObject payload, String source_id) {
        this.type = Type.PUT;
        this.lamport = lamport;
        this.arrival_seq = arrival_seq;
        this.payload = payload;
        this.source_id = source_id;
        this.result_future = new CompletableFuture<>();
        this.get_future = null;
    }

    // ---- Constructor for first GET request ----
    public PutRequest(long lamport, long arrival_seq) {
        this.type = Type.GET;
        this.lamport = lamport;
        this.arrival_seq = arrival_seq;
        this.payload = null;
        this.source_id = null;
        this.result_future =null;
        this.get_future = new CompletableFuture<>();
    }

    // ---- This is the other requests we're comparing to ----
    /**
     * Compare this PutRequest with another one.
     * Orders first by Lamport timestamp, then by arrival sequence.
     * The arrival sequence was increased in AggregationServer.java before coming here
     */
    @Override
    public int compareTo(PutRequest o) {
        // Compare lamport timestamp
        int compare = Long.compare(this.lamport, o.lamport);

        // If lamport is equal -> compare arrival sequence
        if (compare == 0) {
            return Long.compare(this.arrival_seq, o.arrival_seq);
        }

        // else return result from lamport comparison
        return compare;
    }
}
