package com.bi.apirest.service;

import com.bi.apirest.config.ApiProperties;
import com.bi.apirest.domain.CategorySales;
import com.bi.apirest.domain.Granularity;
import com.bi.apirest.domain.InvalidRequestException;
import com.bi.apirest.domain.ProductMetric;
import com.bi.apirest.domain.SalesPoint;
import com.bi.apirest.domain.TopProduct;
import com.bi.apirest.repository.AnalyticsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Reglas de negocio de la API: valida los parametros y delega la consulta en el repositorio. */
@Service
public class AnalyticsService {

    private final AnalyticsRepository repository;
    private final ApiProperties props;

    public AnalyticsService(AnalyticsRepository repository, ApiProperties props) {
        this.repository = repository;
        this.props = props;
    }

    public List<SalesPoint> sales(LocalDate from, LocalDate to, Granularity granularity) {
        validateRange(from, to);
        return repository.salesByPeriod(from, to, granularity);
    }

    public List<TopProduct> topProducts(LocalDate from, LocalDate to, ProductMetric metric, int limit) {
        validateRange(from, to);
        validateLimit(limit);
        return repository.topProducts(from, to, metric, limit);
    }

    public List<CategorySales> categorySales(LocalDate from, LocalDate to, int limit) {
        validateRange(from, to);
        validateLimit(limit);
        return repository.categorySales(from, to, limit);
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidRequestException("from and to are required (format yyyy-MM-dd)");
        }
        if (from.isAfter(to)) {
            throw new InvalidRequestException("from must be on or before to");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > props.maxRangeDays()) {
            throw new InvalidRequestException("range too large: maximum is " + props.maxRangeDays() + " days");
        }
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > props.maxLimit()) {
            throw new InvalidRequestException("limit must be between 1 and " + props.maxLimit());
        }
    }
}
