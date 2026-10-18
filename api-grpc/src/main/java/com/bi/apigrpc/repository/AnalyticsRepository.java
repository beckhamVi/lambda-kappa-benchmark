package com.bi.apigrpc.repository;

import com.bi.apigrpc.domain.CategorySales;
import com.bi.apigrpc.domain.Granularity;
import com.bi.apigrpc.domain.ProductMetric;
import com.bi.apigrpc.domain.SalesPoint;
import com.bi.apigrpc.domain.TopProduct;

import java.time.LocalDate;
import java.util.List;

/** Acceso de solo lectura a los agregados (vistas serving_*). Los rangos incluyen ambos extremos. */
public interface AnalyticsRepository {

    List<SalesPoint> salesByPeriod(LocalDate from, LocalDate to, Granularity granularity);

    List<TopProduct> topProducts(LocalDate from, LocalDate to, ProductMetric metric, int limit);

    List<CategorySales> categorySales(LocalDate from, LocalDate to, int limit);
}
