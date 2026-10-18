package com.bi.ingestionkappa.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record CleanEvent(
        Instant eventTime,
        String eventType,
        long productId,
        long categoryId,
        String categoryCode,
        String brand,
        BigDecimal price,
        long userId,
        String userSession
) {
}