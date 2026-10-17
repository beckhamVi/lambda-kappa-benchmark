package com.bi.ingestionlambda.aggregation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AggregationService {

    private static final String DAILY_SALES = """
            INSERT INTO daily_sales (day, revenue, orders, units, views, carts,
                                     purchases, avg_ticket, active_users)
            SELECT event_date,
                   COALESCE(SUM(price) FILTER (WHERE event_type = 'purchase'), 0),
                   COUNT(DISTINCT user_session) FILTER (WHERE event_type = 'purchase'),
                   COUNT(*) FILTER (WHERE event_type = 'purchase'),
                   COUNT(*) FILTER (WHERE event_type = 'view'),
                   COUNT(*) FILTER (WHERE event_type = 'cart'),
                   COUNT(*) FILTER (WHERE event_type = 'purchase'),
                   COALESCE(ROUND(SUM(price) FILTER (WHERE event_type = 'purchase')
                       / NULLIF(COUNT(DISTINCT user_session)
                           FILTER (WHERE event_type = 'purchase'), 0), 2), 0),
                   COUNT(DISTINCT user_id)
            FROM events
            GROUP BY event_date
            """;

    private static final String PRODUCT_STATS = """
            INSERT INTO daily_product_stats (day, product_id, views, carts, purchases, revenue)
            SELECT event_date, product_id,
                   COUNT(*) FILTER (WHERE event_type = 'view'),
                   COUNT(*) FILTER (WHERE event_type = 'cart'),
                   COUNT(*) FILTER (WHERE event_type = 'purchase'),
                   COALESCE(SUM(price) FILTER (WHERE event_type = 'purchase'), 0)
            FROM events
            GROUP BY event_date, product_id
            """;

    private static final String CATEGORY_SALES = """
            INSERT INTO daily_category_sales (day, category_code, revenue, units,
                                              views, carts, purchases)
            SELECT event_date, category_code,
                   COALESCE(SUM(price) FILTER (WHERE event_type = 'purchase'), 0),
                   COUNT(*) FILTER (WHERE event_type = 'purchase'),
                   COUNT(*) FILTER (WHERE event_type = 'view'),
                   COUNT(*) FILTER (WHERE event_type = 'cart'),
                   COUNT(*) FILTER (WHERE event_type = 'purchase')
            FROM events
            GROUP BY event_date, category_code
            """;

    private final JdbcTemplate jdbc;

    public AggregationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Recalcula todo desde cero (la capa batch siempre es exacta). */
    public void rebuildAll() {
        jdbc.execute("TRUNCATE daily_sales, daily_product_stats, daily_category_sales");
        jdbc.update(DAILY_SALES);
        jdbc.update(PRODUCT_STATS);
        jdbc.update(CATEGORY_SALES);
    }
}