package com.bi.apirest.web.dto;

import com.bi.apirest.domain.SalesPoint;

import java.math.BigDecimal;

public record SalesItem(
        String period,
        BigDecimal revenue,
        long units,
        long views,
        long carts,
        long purchases) {

    public static SalesItem from(SalesPoint p) {
        return new SalesItem(p.period().toString(), p.revenue(), p.units(), p.views(), p.carts(), p.purchases());
    }
}
