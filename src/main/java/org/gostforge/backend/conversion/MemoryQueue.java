package org.gostforge.backend.conversion;
import org.springframework.stereotype.Component;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Component
public class MemoryQueue {
    private final BlockingQueue<UUID> queue = new LinkedBlockingQueue<>();
    
    public void push(UUID jobId) { queue.offer(jobId); }
    public UUID pop(long timeoutSeconds) {
        try { return queue.poll(timeoutSeconds, TimeUnit.SECONDS); } 
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }
    public void clear() { queue.clear(); }
    public int indexOf(UUID jobId) {
        int index = 0;
        for (UUID id : queue) { if (id.equals(jobId)) return index; index++; }
        return -1;
    }
}
