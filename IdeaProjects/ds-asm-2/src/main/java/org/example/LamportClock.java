package org.example;
import java.util.concurrent.atomic.AtomicLong;

public class LamportClock {
    private final AtomicLong time = new AtomicLong(0);

    // Increment for local event or send
    public long tick(){
        return time.incrementAndGet();
    }

    // On receiving a message
    public long on_receive(long remote_lp){
        long updated = Math.max(time.get(), remote_lp) + 1;
        time.set(updated);
        return updated;
    }

    // getter for current logic time
    public long get(){
        return time.get();
    }
}
