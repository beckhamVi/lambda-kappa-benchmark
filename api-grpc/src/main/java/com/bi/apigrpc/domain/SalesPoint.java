package com.bi.apigrpc.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Ventas de un periodo (dia, semana o mes). {@code period} es el primer dia del periodo. */
public record SalesPoint(
        LocalDate period,
        BigDecimal revenue,
        long units,
        long views,
        long carts,
        long purchases) {
}
