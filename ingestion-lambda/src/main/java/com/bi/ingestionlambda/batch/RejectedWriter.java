package com.bi.ingestionlambda.batch;

import com.bi.ingestionlambda.domain.RejectedRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RejectedWriter {

    private static final String SQL =
            "INSERT INTO rejected_events (line_number, raw_line, reason) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbc;

    public RejectedWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(List<RejectedRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(SQL, rows, rows.size(), (ps, r) -> {
            ps.setLong(1, r.lineNumber());
            ps.setString(2, r.rawLine());
            ps.setString(3, r.reason().name());
        });
    }
}