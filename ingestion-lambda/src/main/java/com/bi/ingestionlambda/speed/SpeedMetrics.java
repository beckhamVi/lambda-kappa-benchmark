package com.bi.ingestionlambda.speed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Contadores de la capa speed: tasa, rechazos y latencia de extremo a extremo. */
public class SpeedMetrics {

    private static final Logger log = LoggerFactory.getLogger(SpeedMetrics.class);
    private static final long LOG_EVERY_NS = 5_000_000_000L;

    private long totalConsumed;
    private long totalRejected;
    private long totalLatencySum;
    private long totalLatencyMax;

    private long intervalConsumed;
    private long intervalLatencySum;
    private long intervalLatencyMax;
    private long lastLogNs = System.nanoTime();

    /** latencySumMs = suma de (momento visible en BD - momento en que el simulador envio) de cada evento. */
    public void record(long consumed, long rejected, long latencySumMs, long latencyMaxMs) {
        totalConsumed += consumed;
        totalRejected += rejected;
        totalLatencySum += latencySumMs;
        totalLatencyMax = Math.max(totalLatencyMax, latencyMaxMs);
        intervalConsumed += consumed;
        intervalLatencySum += latencySumMs;
        intervalLatencyMax = Math.max(intervalLatencyMax, latencyMaxMs);

        long now = System.nanoTime();
        if (now - lastLogNs >= LOG_EVERY_NS && intervalConsumed > 0) {
            double secs = (now - lastLogNs) / 1e9;
            log.info("[SPEED] {}/s | total={} rechazados={} | latencia media={} ms max={} ms",
                    Math.round(intervalConsumed / secs), totalConsumed, totalRejected,
                    intervalLatencySum / intervalConsumed, intervalLatencyMax);
            intervalConsumed = 0;
            intervalLatencySum = 0;
            intervalLatencyMax = 0;
            lastLogNs = now;
        }
    }

    public String summary() {
        long avg = totalConsumed == 0 ? 0 : totalLatencySum / totalConsumed;
        return """
                ===== RESULTADO LAMBDA (capa speed) =====
                Consumidos : %d
                Rechazados : %d
                Latencia   : media %d ms, max %d ms
                =========================================
                """.formatted(totalConsumed, totalRejected, avg, totalLatencyMax);
    }
}