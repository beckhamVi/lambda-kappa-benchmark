package com.bi.ingestionkappa.stream;

import com.bi.ingestionkappa.metrics.KappaMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Recibe los totales de Kafka Streams y los escribe en Postgres por lotes cada 500 ms. */
@Component
public class KappaSink {
    private static final Logger log = LoggerFactory.getLogger(KappaSink.class);

    private static final String DAILY = """
            INSERT INTO daily_sales (day, revenue, units, views, carts, purchases) VALUES (?,?,?,?,?,?)
            ON CONFLICT (day) DO UPDATE SET revenue=EXCLUDED.revenue, units=EXCLUDED.units,
              views=EXCLUDED.views, carts=EXCLUDED.carts, purchases=EXCLUDED.purchases""";
    private static final String PRODUCT = """
            INSERT INTO daily_product_stats (day, product_id, views, carts, purchases, revenue) VALUES (?,?,?,?,?,?)
            ON CONFLICT (day, product_id) DO UPDATE SET views=EXCLUDED.views, carts=EXCLUDED.carts,
              purchases=EXCLUDED.purchases, revenue=EXCLUDED.revenue""";
    private static final String CATEGORY = """
            INSERT INTO daily_category_sales (day, category_code, revenue, units, views, carts, purchases)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT (day, category_code) DO UPDATE SET revenue=EXCLUDED.revenue, units=EXCLUDED.units,
              views=EXCLUDED.views, carts=EXCLUDED.carts, purchases=EXCLUDED.purchases""";

    // valor agregado = "revenue|views|carts|purchases|maxTimestamp"
    private final Map<String, String> daily = new ConcurrentHashMap<>();
    private final Map<String, String> products = new ConcurrentHashMap<>();
    private final Map<String, String> categories = new ConcurrentHashMap<>();
    private final Queue<Object[]> rejected = new ConcurrentLinkedQueue<>();

    private final JdbcTemplate jdbc;
    private final KappaMetrics metrics;
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();

    public KappaSink(JdbcTemplate jdbc, KappaMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public void daily(String k, String v) { daily.put(k, v); }
    public void product(String k, String v) { products.put(k, v); }
    public void category(String k, String v) { categories.put(k, v); }
    public void reject(long line, String raw, String reason) { rejected.add(new Object[]{line, raw, reason}); }

    @PostConstruct
    void start() {
        ex.scheduleWithFixedDelay(this::flush, 500, 500, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        ex.shutdown();
        flush();
    }

    private synchronized void flush() {
        try {
            long done = System.currentTimeMillis();
            List<Map.Entry<String, String>> all = new ArrayList<>();

            all.addAll(upsert(DAILY, daily, (ps, en) -> {
                String[] a = en.getValue().split("\\|");
                ps.setDate(1, Date.valueOf(LocalDate.parse(en.getKey())));
                ps.setBigDecimal(2, new BigDecimal(a[0]));
                ps.setLong(3, Long.parseLong(a[3]));   // units = purchases
                ps.setLong(4, Long.parseLong(a[1]));
                ps.setLong(5, Long.parseLong(a[2]));
                ps.setLong(6, Long.parseLong(a[3]));
            }));
            all.addAll(upsert(PRODUCT, products, (ps, en) -> {
                String[] k = en.getKey().split("\\|", 2);
                String[] a = en.getValue().split("\\|");
                ps.setDate(1, Date.valueOf(LocalDate.parse(k[0])));
                ps.setLong(2, Long.parseLong(k[1]));
                ps.setLong(3, Long.parseLong(a[1]));
                ps.setLong(4, Long.parseLong(a[2]));
                ps.setLong(5, Long.parseLong(a[3]));
                ps.setBigDecimal(6, new BigDecimal(a[0]));
            }));
            all.addAll(upsert(CATEGORY, categories, (ps, en) -> {
                String[] k = en.getKey().split("\\|", 2);
                String[] a = en.getValue().split("\\|");
                ps.setDate(1, Date.valueOf(LocalDate.parse(k[0])));
                ps.setString(2, k[1]);
                ps.setBigDecimal(3, new BigDecimal(a[0]));
                ps.setLong(4, Long.parseLong(a[3]));
                ps.setLong(5, Long.parseLong(a[1]));
                ps.setLong(6, Long.parseLong(a[2]));
                ps.setLong(7, Long.parseLong(a[3]));
            }));

            List<Object[]> rej = new ArrayList<>();
            for (Object[] r; (r = rejected.poll()) != null; ) rej.add(r);
            if (!rej.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO rejected_events (line_number, raw_line, reason) VALUES (?,?,?)", rej);
            }

            if (!all.isEmpty()) {
                long sum = 0, max = 0;
                for (var en : all) {
                    long lat = done - Long.parseLong(en.getValue().split("\\|")[4]);
                    sum += lat;
                    max = Math.max(max, lat);
                }
                metrics.latency(sum, max, all.size());
            }
            metrics.maybeLog();
        } catch (Exception e) {
            log.error("Error escribiendo en Postgres", e);
        }
    }

    private List<Map.Entry<String, String>> upsert(
            String sql, Map<String, String> pending,
            ParameterizedPreparedStatementSetter<Map.Entry<String, String>> setter) {
        List<Map.Entry<String, String>> rows = new ArrayList<>();
        for (String k : new ArrayList<>(pending.keySet())) {
            String v = pending.remove(k);
            if (v != null) rows.add(Map.entry(k, v));
        }
        if (rows.isEmpty()) return rows;
        try {
            jdbc.batchUpdate(sql, rows, rows.size(), setter);
        } catch (RuntimeException e) {
            rows.forEach(en -> pending.putIfAbsent(en.getKey(), en.getValue()));  // reintenta en el proximo flush
            throw e;
        }
        return rows;
    }
}