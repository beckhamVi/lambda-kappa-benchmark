package com.bi.ingestionlambda.metrics;

import com.bi.ingestionlambda.domain.RejectReason;

import java.util.EnumMap;
import java.util.Map;

public class RunMetrics {
    public long linesRead;
    public long accepted;
    public long rejected;
    public long inserted;   // filas que quedaron en events
    public long duplicates; // accepted - inserted
    public long loadMillis;
    public long aggregationMillis;
    public final Map<RejectReason, Long> rejectedByReason = new EnumMap<>(RejectReason.class);

    public void countReject(RejectReason reason) {
        rejected++;
        rejectedByReason.merge(reason, 1L, Long::sum);
    }

    public double eventsPerSecond() {
        return loadMillis == 0 ? 0 : linesRead * 1000.0 / loadMillis;
    }

    @Override
    public String toString() {
        return """
                ===== RESULTADO LAMBDA (capa batch) =====
                Lineas leidas      : %d
                Aceptadas          : %d
                Rechazadas         : %d  %s
                Duplicados         : %d
                Insertadas (final) : %d
                Carga              : %d ms (%.0f eventos/s)
                Agregacion         : %d ms
                =========================================
                """.formatted(linesRead, accepted, rejected, rejectedByReason, duplicates,
                inserted, loadMillis, eventsPerSecond(), aggregationMillis);
    }
}