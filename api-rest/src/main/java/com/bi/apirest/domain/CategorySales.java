package com.bi.apirest.domain;

import java.math.BigDecimal;

/** Totales de una categoria en el rango consultado. */
public record CategorySales(
        String categoryCode,
        BigDecimal revenue,
        long units,
        long views,
        long carts,
        long purchases) {
}
