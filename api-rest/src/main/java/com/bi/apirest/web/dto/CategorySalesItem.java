package com.bi.apirest.web.dto;

import com.bi.apirest.domain.CategorySales;

import java.math.BigDecimal;

public record CategorySalesItem(
        String categoryCode,
        BigDecimal revenue,
        long units,
        long views,
        long carts,
        long purchases) {

    public static CategorySalesItem from(CategorySales c) {
        return new CategorySalesItem(c.categoryCode(), c.revenue(), c.units(), c.views(), c.carts(), c.purchases());
    }
}
