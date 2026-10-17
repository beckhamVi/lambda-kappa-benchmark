package com.bi.ingestionlambda.runner;

import com.bi.ingestionlambda.aggregation.AggregationService;
import com.bi.ingestionlambda.batch.IngestionJob;
import com.bi.ingestionlambda.config.IngestionProperties;
import com.bi.ingestionlambda.metrics.RunMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "ingestion.batch.enabled", havingValue = "true", matchIfMissing = true)
public class BenchmarkRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final IngestionProperties props;
    private final IngestionJob job;
    private final AggregationService aggregation;
    private final JdbcTemplate jdbc;
    private final boolean speedEnabled;

    public BenchmarkRunner(IngestionProperties props, IngestionJob job,
                           AggregationService aggregation, JdbcTemplate jdbc,
                           @Value("${ingestion.speed.enabled:false}") boolean speedEnabled) {
        this.props = props;
        this.job = job;
        this.aggregation = aggregation;
        this.jdbc = jdbc;
        this.speedEnabled = speedEnabled;
    }

    @Override
    public void run(String... args) throws Exception {
        RunMetrics m = new RunMetrics();

        // Arranque limpio de la capa batch. Las tablas speed solo se vacian si el speed esta apagado.
        if (speedEnabled) {
            jdbc.execute("TRUNCATE events, rejected_events");
        } else {
            jdbc.execute("TRUNCATE events, rejected_events, "
                    + "speed_daily_sales, speed_product_stats, speed_category_sales");
        }

        long t0 = System.nanoTime();
        job.run(Path.of(props.getCsvPath()), props.getChunkSize(), m);
        m.loadMillis = (System.nanoTime() - t0) / 1_000_000;

        Long inserted = jdbc.queryForObject("SELECT count(*) FROM events", Long.class);
        m.inserted = inserted == null ? 0 : inserted;
        m.duplicates = m.accepted - m.inserted;

        long t1 = System.nanoTime();
        aggregation.rebuildAll();
        m.aggregationMillis = (System.nanoTime() - t1) / 1_000_000;

        jdbc.update("""
                INSERT INTO run_log (lines_read, rejected, duplicates, inserted,
                                     load_ms, aggregation_ms, events_per_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, m.linesRead, m.rejected, m.duplicates, m.inserted,
                m.loadMillis, m.aggregationMillis, m.eventsPerSecond());

        log.info("\n{}", m);
        if (speedEnabled) {
            log.info("Capa batch terminada. La capa speed sigue escuchando Kafka (topic '{}')...",
                    "events");
        }
    }
}