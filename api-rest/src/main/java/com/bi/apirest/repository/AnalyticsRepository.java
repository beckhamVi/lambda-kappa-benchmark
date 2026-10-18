package com.bi.apirest.repository;

import com.bi.apirest.domain.CategorySales;
import com.bi.apirest.domain.Granularity;
import com.bi.apirest.domain.ProductMetric;
import com.bi.apirest.domain.SalesPoint;
import com.bi.apirest.domain.TopProduct;

import java.time.LocalDate;
import java.util.List;

/** Acceso de solo lectura a los agregados (vistas serving_*). Los rangos incluyen ambos extremos. */
public interface AnalyticsRepository {

    List<SalesPoint> salesByPeriod(LocalDate from, LocalDate to, Granularity granularity);

    List<TopProduct> topProducts(LocalDate from, LocalDate to, ProductMetric metric, int limit);

    List<CategorySales> categorySales(LocalDate from, LocalDate to, int limit);
}
