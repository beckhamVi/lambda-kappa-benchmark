package com.bi.ingestionlambda.speed;

import com.bi.ingestionlambda.cleaning.CleaningResult;
import com.bi.ingestionlambda.cleaning.CleaningRules;
import com.bi.ingestionlambda.domain.CleanEvent;
import com.bi.ingestionlambda.domain.RawEvent;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Capa SPEED de Lambda: consume eventos de Kafka, los limpia con las mismas reglas del batch
 * y suma de forma incremental en las tablas speed_*.
 */
@Component
@ConditionalOnProperty(name = "ingestion.speed.enabled", havingValue = "true")
public class SpeedLayerListener {

    private static final Logger log = LoggerFactory.getLogger(SpeedLayerListener.class);

    private static final String UPSERT_DAILY = """
            INSERT INTO speed_daily_sales (day, revenue, units, views, carts, purchases)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (day) DO UPDATE SET
              revenue = speed_daily_sales.revenue + EXCLUDED.revenue,
              units = speed_daily_sales.units + EXCLUDED.units,
              views = speed_daily_sales.views + EXCLUDED.views,
              carts = speed_daily_sales.carts + EXCLUDED.carts,
              purchases = speed_daily_sales.purchases + EXCLUDED.purchases
            """;

    private static final String UPSERT_PRODUCT = """
            INSERT INTO speed_product_stats (day, product_id, views, carts, purchases, revenue)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (day, product_id) DO UPDATE SET
              views = speed_product_stats.views + EXCLUDED.views,
              carts = speed_product_stats.carts + EXCLUDED.carts,
              purchases = speed_product_stats.purchases + EXCLUDED.purchases,
              revenue = speed_product_stats.revenue + EXCLUDED.revenue
            """;

    private static final String UPSERT_CATEGORY = """
            INSERT INTO speed_category_sales (day, category_code, revenue, units, views, carts, purchases)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (day, category_code) DO UPDATE SET
              revenue = speed_category_sales.revenue + EXCLUDED.revenue,
              units = speed_category_sales.units + EXCLUDED.units,
              views = speed_category_sales.views + EXCLUDED.views,
              carts = speed_category_sales.carts + EXCLUDED.carts,
              purchases = speed_category_sales.purchases + EXCLUDED.purchases
            """;

    private final JdbcTemplate jdbc;
    private final SpeedMetrics metrics = new SpeedMetrics();

    public SpeedLayerListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = "${ingestion.topic:events}", groupId = "lambda-speed")
    @Transactional
    public void onBatch(List<ConsumerRecord<String, String>> records) {
        Map<LocalDate, Agg> daily = new HashMap<>();
        Map<DayProduct, Agg> products = new HashMap<>();
        Map<DayCategory, Agg> categories = new HashMap<>();

        long rejected = 0;
        long tsSum = 0;
        long tsMin = Long.MAX_VALUE;

        for (ConsumerRecord<String, String> r : records) {
            tsSum += r.timestamp();
            tsMin = Math.min(tsMin, r.timestamp());

            CleaningResult result = CleaningRules.clean(new RawEvent(r.offset(), r.value()));
            if (result.isRejected()) {
                rejected++;
                continue;
            }
            CleanEvent e = result.event();
            LocalDate day = e.eventTime().atZone(ZoneOffset.UTC).toLocalDate();
            daily.computeIfAbsent(day, k -> new Agg()).add(e);
            products.computeIfAbsent(new DayProduct(day, e.productId()), k -> new Agg()).add(e);
            categories.computeIfAbsent(new DayCategory(day, e.categoryCode()), k -> new Agg()).add(e);
        }

        writeDaily(daily);
        writeProducts(products);
        writeCategories(categories);

        // Latencia: desde que el simulador envio el mensaje hasta que ya esta en la BD
        long done = System.currentTimeMillis();
        long latencySum = done * records.size() - tsSum;
        long latencyMax = records.isEmpty() ? 0 : done - tsMin;
        metrics.record(records.size(), rejected, latencySum, latencyMax);
    }

    private void writeDaily(Map<LocalDate, Agg> map) {
        List<Map.Entry<LocalDate, Agg>> rows = new ArrayList<>(map.entrySet());
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(UPSERT_DAILY, rows, rows.size(), (ps, en) -> {
            Agg a = en.getValue();
            ps.setDate(1, Date.valueOf(en.getKey()));
            ps.setBigDecimal(2, a.revenue);
            ps.setLong(3, a.units);
            ps.setLong(4, a.views);
            ps.setLong(5, a.carts);
            ps.setLong(6, a.purchases);
        });
    }

    private void writeProducts(Map<DayProduct, Agg> map) {
        List<Map.Entry<DayProduct, Agg>> rows = new ArrayList<>(map.entrySet());
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(UPSERT_PRODUCT, rows, rows.size(), (ps, en) -> {
            Agg a = en.getValue();
            ps.setDate(1, Date.valueOf(en.getKey().day()));
            ps.setLong(2, en.getKey().productId());
            ps.setLong(3, a.views);
            ps.setLong(4, a.carts);
            ps.setLong(5, a.purchases);
            ps.setBigDecimal(6, a.revenue);
        });
    }

    private void writeCategories(Map<DayCategory, Agg> map) {
        List<Map.Entry<DayCategory, Agg>> rows = new ArrayList<>(map.entrySet());
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(UPSERT_CATEGORY, rows, rows.size(), (ps, en) -> {
            Agg a = en.getValue();
            ps.setDate(1, Date.valueOf(en.getKey().day()));
            ps.setString(2, en.getKey().categoryCode());
            ps.setBigDecimal(3, a.revenue);
            ps.setLong(4, a.units);
            ps.setLong(5, a.views);
            ps.setLong(6, a.carts);
            ps.setLong(7, a.purchases);
        });
    }

    @PreDestroy
    public void onShutdown() {
        log.info("\n{}", metrics.summary());
    }

    private record DayProduct(LocalDate day, long productId) {}

    private record DayCategory(LocalDate day, String categoryCode) {}

    /** Acumulador. Misma definicion que el batch: units = purchases, revenue = suma de precios de compras. */
    private static final class Agg {
        BigDecimal revenue = BigDecimal.ZERO;
        long units;
        long views;
        long carts;
        long purchases;

        void add(CleanEvent e) {
            switch (e.eventType()) {
                case "view" -> views++;
                case "cart" -> carts++;
                case "purchase" -> {
                    purchases++;
                    units++;
                    revenue = revenue.add(e.price());
                }
                default -> { } // remove_from_cart no se cuenta, igual que en el batch
            }
        }
    }
}