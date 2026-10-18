package com.bi.apirest.web.dto;

import com.bi.apirest.domain.ProductMetric;
import com.bi.apirest.domain.TopProduct;

import java.time.LocalDate;
import java.util.List;

public record TopProductsResponse(String from, String to, String metric, int limit, List<TopProductItem> items) {

    public static TopProductsResponse of(LocalDate from, LocalDate to, ProductMetric metric, int limit,
                                         List<TopProduct> products) {
        return new TopProductsResponse(from.toString(), to.toString(), metric.apiName(), limit,
                products.stream().map(TopProductItem::from).toList());
    }
}
