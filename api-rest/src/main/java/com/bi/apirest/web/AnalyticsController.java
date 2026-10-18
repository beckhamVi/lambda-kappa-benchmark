package com.bi.apirest.web;

import com.bi.apirest.domain.Granularity;
import com.bi.apirest.domain.ProductMetric;
import com.bi.apirest.service.AnalyticsService;
import com.bi.apirest.web.dto.CategorySalesResponse;
import com.bi.apirest.web.dto.SalesResponse;
import com.bi.apirest.web.dto.TopProductsResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Capa de transporte REST: convierte parametros HTTP a tipos del dominio y delega en el servicio. */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/sales")
    public SalesResponse sales(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "granularity", defaultValue = "day") String granularity) {
        Granularity g = Granularity.parse(granularity);
        return SalesResponse.of(from, to, g, service.sales(from, to, g));
    }

    @GetMapping("/products/top")
    public TopProductsResponse topProducts(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "metric", defaultValue = "revenue") String metric,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        ProductMetric m = ProductMetric.parse(metric);
        return TopProductsResponse.of(from, to, m, limit, service.topProducts(from, to, m, limit));
    }

    @GetMapping("/categories/sales")
    public CategorySalesResponse categorySales(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return CategorySalesResponse.of(from, to, limit, service.categorySales(from, to, limit));
    }
}
