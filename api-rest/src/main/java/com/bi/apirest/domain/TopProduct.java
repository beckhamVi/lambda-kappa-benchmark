package com.bi.apirest.domain;

import java.math.BigDecimal;

/** Totales de un producto en el rango consultado. */
public record TopProduct(
        long productId,
        long views,
        long carts,
        long purchases,
        BigDecimal revenue) {
}
