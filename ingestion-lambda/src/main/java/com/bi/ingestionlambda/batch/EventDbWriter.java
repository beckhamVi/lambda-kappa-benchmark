package com.bi.ingestionlambda.batch;

import com.bi.ingestionlambda.domain.CleanEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;

@Component
public class EventDbWriter {

    private static final String SQL = """
            INSERT INTO events (event_time, event_type, product_id, category_id,
                                category_code, brand, price, user_id, user_session)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public EventDbWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(List<CleanEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(SQL, events, events.size(), (ps, e) -> {
            ps.setTimestamp(1, Timestamp.from(e.eventTime()));
            ps.setString(2, e.eventType());
            ps.setLong(3, e.productId());
            ps.setLong(4, e.categoryId());
            ps.setString(5, e.categoryCode());
            ps.setString(6, e.brand());
            ps.setBigDecimal(7, e.price());
            ps.setLong(8, e.userId());
            ps.setString(9, e.userSession());
        });
    }
}