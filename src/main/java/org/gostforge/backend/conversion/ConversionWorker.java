package org.gostforge.backend.conversion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.conversion.MemoryQueue;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConversionWorker {

    private final MemoryQueue redis;
    private final ConversionService conversionService;

    private static final String QUEUE_KEY = "gostforge:conversion:queue";
    private static final int WORKER_COUNT = 4;

    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    void start() {
        running.set(true);
        executorService = Executors.newFixedThreadPool(WORKER_COUNT);
        for (int i = 0; i < WORKER_COUNT; i++) {
            final int workerId = i;
            executorService.submit(() -> workerLoop(workerId));
        }
        log.info("Started {} conversion workers", WORKER_COUNT);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdownNow();
        }
        log.info("Conversion workers stopped");
    }

    private void workerLoop(int workerId) {
        log.info("Worker-{} started", workerId);
        while (running.get()) {
            try {
                // BRPOP with 5-second timeout (returns null if nothing in queue)
                UUID jobId = redis.pop(5);
                if (jobId == null) continue;
                log.info("Worker-{} picked up job {}", workerId, jobId);
                conversionService.processJob(jobId);
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Worker-{} error: {}", workerId, e.getMessage(), e);
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.info("Worker-{} stopped", workerId);
    }
}
