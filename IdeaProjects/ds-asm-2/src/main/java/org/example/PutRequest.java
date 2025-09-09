package org.example;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;      // writer notify Agg Sv when request being processed

/**
 * Function: order requests by Lamport Timestamp
 *           If the two requests have the same lamport
 *            -> order by arrival sequence
 */
public class PutRequest implements Comparable<PutRequest> {
    public final long lamport;
    public final long arrival_seq;
    public final JsonObject payload;
    public final CompletableFuture<Integer> result_future = new CompletableFuture<>();      // writer send back result asynchronously

    // ---- Constructor for first request ----
    public PutRequest(long lamport, long arrival_seq, JsonObject payload) {
        this.lamport = lamport;
        this.arrival_seq = arrival_seq;
        this.payload = payload;
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
