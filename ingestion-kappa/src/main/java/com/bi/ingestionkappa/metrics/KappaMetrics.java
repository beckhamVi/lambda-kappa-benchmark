package com.bi.ingestionkappa.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class KappaMetrics {
    private static final Logger log = LoggerFactory.getLogger(KappaMetrics.class);

    public final AtomicLong consumed = new AtomicLong();
    public final AtomicLong rejected = new AtomicLong();
    public final AtomicLong duplicates = new AtomicLong();

    private long latSum, latRows, latMax;   // intervalo
    private long totSum, totRows, totMax;   // total
    private long lastConsumed;
    private long lastNs = System.nanoTime();

    public synchronized void latency(long sumMs, long maxMs, long rows) {
        latSum += sumMs; latRows += rows; latMax = Math.max(latMax, maxMs);
        totSum += sumMs; totRows += rows; totMax = Math.max(totMax, maxMs);
    }

    public synchronized void maybeLog() {
        long now = System.nanoTime();
        if (now - lastNs < 5_000_000_000L) return;
        long c = consumed.get();
        if (c > lastConsumed) {
            double secs = (now - lastNs) / 1e9;
            log.info("[KAPPA] {}/s | total={} rechazados={} duplicados={} | latencia media={} ms max={} ms",
                    Math.round((c - lastConsumed) / secs), c, rejected.get(), duplicates.get(),
                    latRows == 0 ? 0 : latSum / latRows, latMax);
        }
        lastConsumed = c;
        lastNs = now;
        latSum = latRows = latMax = 0;
    }

    public synchronized String summary() {
        return """
                ===== RESULTADO KAPPA =====
                Consumidos : %d
                Rechazados : %d
                Duplicados : %d
                Latencia   : media %d ms, max %d ms
                ===========================
                """.formatted(consumed.get(), rejected.get(), duplicates.get(),
                totRows == 0 ? 0 : totSum / totRows, totMax);
    }
}