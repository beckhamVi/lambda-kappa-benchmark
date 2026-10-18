package com.bi.apirest.service;

import com.bi.apirest.config.ApiProperties;
import com.bi.apirest.domain.CategorySales;
import com.bi.apirest.domain.Granularity;
import com.bi.apirest.domain.InvalidRequestException;
import com.bi.apirest.domain.ProductMetric;
import com.bi.apirest.domain.SalesPoint;
import com.bi.apirest.domain.TopProduct;
import com.bi.apirest.repository.AnalyticsRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prueba de la logica de validacion, sin Spring ni base de datos. */
class AnalyticsServiceTest {

    private static final LocalDate D1 = LocalDate.of(2020, 4, 1);
    private static final LocalDate D7 = LocalDate.of(2020, 4, 7);

    private final AnalyticsService service =
            new AnalyticsService(new EmptyRepository(), new ApiProperties(366, 100));

    @Test
    void acceptsValidRange() {
        assertTrue(service.sales(D1, D7, Granularity.DAY).isEmpty());
        assertTrue(service.topProducts(D1, D7, ProductMetric.REVENUE, 10).isEmpty());
        assertTrue(service.categorySales(D1, D7, 10).isEmpty());
    }

    @Test
    void acceptsSingleDayRange() {
        assertTrue(service.sales(D1, D1, Granularity.DAY).isEmpty());
    }

    @Test
    void rejectsFromAfterTo() {
        assertThrows(InvalidRequestException.class, () -> service.sales(D7, D1, Granularity.DAY));
    }

    @Test
    void rejectsRangeLargerThanMaximum() {
        assertThrows(InvalidRequestException.class,
                () -> service.sales(D1, D1.plusDays(366), Granularity.MONTH));
        // 366 dias exactos si se aceptan
        assertTrue(service.sales(D1, D1.plusDays(365), Granularity.MONTH).isEmpty());
    }

    @Test
    void rejectsLimitOutOfBounds() {
        assertThrows(InvalidRequestException.class, () -> service.topProducts(D1, D7, ProductMetric.VIEWS, 0));
        assertThrows(InvalidRequestException.class, () -> service.topProducts(D1, D7, ProductMetric.VIEWS, 101));
        assertThrows(InvalidRequestException.class, () -> service.categorySales(D1, D7, -5));
    }

    @Test
    void parsesEnumsIgnoringCase() {
        assertEquals(Granularity.WEEK, Granularity.parse("WeEk"));
        assertEquals(ProductMetric.PURCHASES, ProductMetric.parse(" purchases "));
        assertThrows(InvalidRequestException.class, () -> Granularity.parse("year"));
        assertThrows(InvalidRequestException.class, () -> ProductMetric.parse("price"));
    }

    private static final class EmptyRepository implements AnalyticsRepository {
        @Override
        public List<SalesPoint> salesByPeriod(LocalDate from, LocalDate to, Granularity granularity) {
            return List.of();
        }

        @Override
        public List<TopProduct> topProducts(LocalDate from, LocalDate to, ProductMetric metric, int limit) {
            return List.of();
        }

        @Override
        public List<CategorySales> categorySales(LocalDate from, LocalDate to, int limit) {
            return List.of();
        }
    }
}
