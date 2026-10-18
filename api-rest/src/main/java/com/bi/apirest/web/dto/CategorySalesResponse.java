package com.bi.apirest.web.dto;

import com.bi.apirest.domain.CategorySales;

import java.time.LocalDate;
import java.util.List;

public record CategorySalesResponse(String from, String to, int limit, List<CategorySalesItem> items) {

    public static CategorySalesResponse of(LocalDate from, LocalDate to, int limit, List<CategorySales> categories) {
        return new CategorySalesResponse(from.toString(), to.toString(), limit,
                categories.stream().map(CategorySalesItem::from).toList());
    }
}
