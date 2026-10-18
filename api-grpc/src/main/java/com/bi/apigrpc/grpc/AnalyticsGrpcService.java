package com.bi.apigrpc.grpc;

import com.bi.apigrpc.domain.Granularity;
import com.bi.apigrpc.domain.InvalidRequestException;
import com.bi.apigrpc.domain.ProductMetric;
import com.bi.apigrpc.proto.AnalyticsGrpc;
import com.bi.apigrpc.proto.CategorySalesItem;
import com.bi.apigrpc.proto.CategorySalesRequest;
import com.bi.apigrpc.proto.CategorySalesResponse;
import com.bi.apigrpc.proto.SalesItem;
import com.bi.apigrpc.proto.SalesRequest;
import com.bi.apigrpc.proto.SalesResponse;
import com.bi.apigrpc.proto.TopProductItem;
import com.bi.apigrpc.proto.TopProductsRequest;
import com.bi.apigrpc.proto.TopProductsResponse;
import com.bi.apigrpc.service.AnalyticsService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

/** Capa de transporte gRPC: equivalente a AnalyticsController de api-rest. */
@Service
public class AnalyticsGrpcService extends AnalyticsGrpc.AnalyticsImplBase {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsGrpcService.class);
    private static final int DEFAULT_LIMIT = 10;

    private final AnalyticsService service;

    public AnalyticsGrpcService(AnalyticsService service) {
        this.service = service;
    }

    @Override
    public void getSales(SalesRequest req, StreamObserver<SalesResponse> out) {
        handle(out, () -> {
            LocalDate from = date(req.getFrom());
            LocalDate to = date(req.getTo());
            Granularity g = Granularity.parse(req.getGranularity().isBlank() ? "day" : req.getGranularity());
            SalesResponse.Builder b = SalesResponse.newBuilder()
                    .setFrom(from.toString()).setTo(to.toString()).setGranularity(g.apiName());
            service.sales(from, to, g).forEach(p -> b.addItems(SalesItem.newBuilder()
                    .setPeriod(p.period().toString())
                    .setRevenue(p.revenue().toPlainString())
                    .setUnits(p.units()).setViews(p.views())
                    .setCarts(p.carts()).setPurchases(p.purchases())));
            return b.build();
        });
    }

    @Override
    public void getTopProducts(TopProductsRequest req, StreamObserver<TopProductsResponse> out) {
        handle(out, () -> {
            LocalDate from = date(req.getFrom());
            LocalDate to = date(req.getTo());
            ProductMetric m = ProductMetric.parse(req.getMetric().isBlank() ? "revenue" : req.getMetric());
            int limit = req.hasLimit() ? req.getLimit() : DEFAULT_LIMIT;
            TopProductsResponse.Builder b = TopProductsResponse.newBuilder()
                    .setFrom(from.toString()).setTo(to.toString())
                    .setMetric(m.apiName()).setLimit(limit);
            service.topProducts(from, to, m, limit).forEach(p -> b.addItems(TopProductItem.newBuilder()
                    .setProductId(p.productId())
                    .setViews(p.views()).setCarts(p.carts()).setPurchases(p.purchases())
                    .setRevenue(p.revenue().toPlainString())));
            return b.build();
        });
    }

    @Override
    public void getCategorySales(CategorySalesRequest req, StreamObserver<CategorySalesResponse> out) {
        handle(out, () -> {
            LocalDate from = date(req.getFrom());
            LocalDate to = date(req.getTo());
            int limit = req.hasLimit() ? req.getLimit() : DEFAULT_LIMIT;
            CategorySalesResponse.Builder b = CategorySalesResponse.newBuilder()
                    .setFrom(from.toString()).setTo(to.toString()).setLimit(limit);
            service.categorySales(from, to, limit).forEach(c -> b.addItems(CategorySalesItem.newBuilder()
                    .setCategoryCode(c.categoryCode())
                    .setRevenue(c.revenue().toPlainString())
                    .setUnits(c.units()).setViews(c.views())
                    .setCarts(c.carts()).setPurchases(c.purchases())));
            return b.build();
        });
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("from and to are required (format yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("invalid date '" + raw + "' (format yyyy-MM-dd)");
        }
    }

    /** Traduce errores igual que GlobalExceptionHandler: 400 -> INVALID_ARGUMENT, 503 -> UNAVAILABLE, 500 -> INTERNAL. */
    private <T> void handle(StreamObserver<T> out, Supplier<T> action) {
        try {
            T result = action.get();
            out.onNext(result);
            out.onCompleted();
        } catch (InvalidRequestException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (DataAccessException e) {
            log.error("Database error", e);
            out.onError(Status.UNAVAILABLE.withDescription("The database is not available").asRuntimeException());
        } catch (Exception e) {
            log.error("Unexpected error", e);
            out.onError(Status.INTERNAL.withDescription("Unexpected error").asRuntimeException());
        }
    }
}
