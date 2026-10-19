package com.bi.ingestionkappa.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metricas de Kappa. El procesamiento NO cambia: el stream sigue siendo uno solo.
 * Se agrega deteccion de FASES: una fase empieza con el primer evento despues de un periodo
 * de inactividad y termina cuando no llegan eventos durante {@code kappa.phase-idle-seconds}
 * (10 s por defecto). Asi se separa, sin reiniciar la app, la carga historica (replay del CSV)
 * del flujo en tiempo real (simulador), igual que las capas batch y speed de Lambda.
 */
@Component
public class KappaMetrics {
    private static final Logger log = LoggerFactory.getLogger(KappaMetrics.class);
    private static final double BULK_THRESHOLD_EPS = 1000;   // >= 1000 ev/s => "carga masiva"

    public final AtomicLong consumed = new AtomicLong();
    public final AtomicLong rejected = new AtomicLong();
    public final AtomicLong duplicates = new AtomicLong();

    private long latSum, latRows, latMax;   // intervalo (log cada 5 s)
    private long totSum, totRows, totMax;   // total
    private long lastConsumed;
    private long lastNs = System.nanoTime();

    // ---- fases
    private final long idleNs;
    private volatile long lastEventNs;
    private volatile boolean phaseOpen;
    private int phaseNo;
    private long phaseStartNs;
    private long baseC, baseR, baseD;           // totales al cerrar la fase anterior (sistema en reposo)
    private long phLatSum, phLatRows, phLatMax; // latencia de la fase actual
    private final List<String> phaseSummaries = new ArrayList<>();

    public KappaMetrics(@Value("${kappa.phase-idle-seconds:10}") long idleSeconds) {
        this.idleNs = idleSeconds * 1_000_000_000L;
    }

    /** Llamar en lugar de consumed.incrementAndGet() al recibir cada evento. */
    public void onConsumed() {
        lastEventNs = System.nanoTime();
        if (!phaseOpen) openPhase();
        consumed.incrementAndGet();
    }

    private synchronized void openPhase() {
        if (phaseOpen) return;
        phaseNo++;
        phaseStartNs = lastEventNs;
        phLatSum = phLatRows = phLatMax = 0;
        phaseOpen = true;
        log.info("[KAPPA] Fase {} iniciada", phaseNo);
    }

    private void closePhase(boolean force) {
        if (!phaseOpen) return;
        if (!force && System.nanoTime() - lastEventNs <= idleNs) return;   // llego un evento: sigue abierta
        long c = consumed.get(), r = rejected.get(), d = duplicates.get();
        long events = c - baseC;
        double secs = Math.max((lastEventNs - phaseStartNs) / 1e9, 0.001);
        double eps = events / secs;
        String s = """
                ----- KAPPA FASE %d (%s) -----
                Eventos consumidos : %d
                Duracion           : %.2f s (primer a ultimo evento)
                Throughput         : %d ev/s
                Rechazados         : %d
                Duplicados         : %d
                Latencia E2E       : media %d ms, max %d ms
                -----------------------------""".formatted(
                phaseNo, eps >= BULK_THRESHOLD_EPS ? "carga masiva / replay" : "flujo continuo",
                events, secs, Math.round(eps), r - baseR, d - baseD,
                phLatRows == 0 ? 0 : phLatSum / phLatRows, phLatMax);
        phaseSummaries.add(s);
        log.info("\n{}", s);
        baseC = c; baseR = r; baseD = d;
        phaseOpen = false;
    }

    public synchronized void latency(long sumMs, long maxMs, long rows) {
        latSum += sumMs; latRows += rows; latMax = Math.max(latMax, maxMs);
        totSum += sumMs; totRows += rows; totMax = Math.max(totMax, maxMs);
        if (phaseOpen) {
            phLatSum += sumMs; phLatRows += rows; phLatMax = Math.max(phLatMax, maxMs);
        }
    }

    public synchronized void maybeLog() {
        long now = System.nanoTime();
        closePhase(false);                       // se invoca cada 500 ms desde KappaSink.flush()
        if (now - lastNs < 5_000_000_000L) return;
        long c = consumed.get();
        if (c > lastConsumed) {
            double secs = (now - lastNs) / 1e9;
            log.info("[KAPPA f{}] {}/s | total={} rechazados={} duplicados={} | latencia media={} ms max={} ms",
                    phaseNo, Math.round((c - lastConsumed) / secs), c, rejected.get(), duplicates.get(),
                    latRows == 0 ? 0 : latSum / latRows, latMax);
        }
        lastConsumed = c;
        lastNs = now;
        latSum = latRows = latMax = 0;
    }

    public synchronized String summary() {
        closePhase(true);
        StringBuilder sb = new StringBuilder();
        for (String s : phaseSummaries) sb.append(s).append("\n\n");
        sb.append("""
                ===== RESULTADO KAPPA (TOTAL) =====
                Consumidos : %d
                Rechazados : %d
                Duplicados : %d
                Latencia   : media %d ms, max %d ms
                ===================================
                """.formatted(consumed.get(), rejected.get(), duplicates.get(),
                totRows == 0 ? 0 : totSum / totRows, totMax));
        return sb.toString();
    }
}