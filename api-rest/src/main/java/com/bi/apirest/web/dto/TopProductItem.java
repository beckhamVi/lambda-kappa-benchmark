package com.bi.apirest.web.dto;

import com.bi.apirest.domain.TopProduct;

import java.math.BigDecimal;

public record TopProductItem(
        long productId,
        long views,
        long carts,
        long purchases,
        BigDecimal revenue) {

    public static TopProductItem from(TopProduct p) {
        return new TopProductItem(p.productId(), p.views(), p.carts(), p.purchases(), p.revenue());
    }
}
