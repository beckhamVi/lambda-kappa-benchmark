package com.bi.apigrpc.repository;

import com.bi.apigrpc.domain.CategorySales;
import com.bi.apigrpc.domain.Granularity;
import com.bi.apigrpc.domain.ProductMetric;
import com.bi.apigrpc.domain.SalesPoint;
import com.bi.apigrpc.domain.TopProduct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Consultas SQL explicitas (sin ORM) sobre las vistas serving_*.
 * El schema (lambda o kappa) lo decide la URL de conexion (currentSchema), por eso las tablas van sin prefijo.
 * Lo unico que se concatena al SQL (unidad de fecha y columna de orden) sale de enums propios, nunca del cliente.
 */
@Repository
public class JdbcAnalyticsRepository implements AnalyticsRepository {

    private static final String SALES_SQL = """
            SELECT CAST(date_trunc('%s', CAST(day AS timestamp)) AS date) AS period,
                   SUM(revenue)                   AS revenue,
                   CAST(SUM(units)     AS bigint) AS units,
                   CAST(SUM(views)     AS bigint) AS views,
                   CAST(SUM(carts)     AS bigint) AS carts,
                   CAST(SUM(purchases) AS bigint) AS purchases
            FROM serving_daily_sales
            WHERE day BETWEEN :fromDay AND :toDay
            GROUP BY 1
            ORDER BY 1
            """;

    private static final String TOP_PRODUCTS_SQL = """
            SELECT product_id,
                   CAST(SUM(views)     AS bigint) AS views,
                   CAST(SUM(carts)     AS bigint) AS carts,
                   CAST(SUM(purchases) AS bigint) AS purchases,
                   SUM(revenue)                   AS revenue
            FROM serving_product_stats
            WHERE day BETWEEN :fromDay AND :toDay
            GROUP BY product_id
            ORDER BY %s DESC, product_id
            LIMIT :maxRows
            """;

    private static final String CATEGORY_SQL = """
            SELECT category_code,
                   SUM(revenue)                   AS revenue,
                   CAST(SUM(units)     AS bigint) AS units,
                   CAST(SUM(views)     AS bigint) AS views,
                   CAST(SUM(carts)     AS bigint) AS carts,
                   CAST(SUM(purchases) AS bigint) AS purchases
            FROM serving_category_sales
            WHERE day BETWEEN :fromDay AND :toDay
            GROUP BY category_code
            ORDER BY revenue DESC, category_code
            LIMIT :maxRows
            """;

    private final JdbcClient jdbc;

    public JdbcAnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
    }

    @Override
    public List<SalesPoint> salesByPeriod(LocalDate from, LocalDate to, Granularity granularity) {
        return jdbc.sql(SALES_SQL.formatted(granularity.sqlUnit()))
                .param("fromDay", from)
                .param("toDay", to)
                .query((rs, i) -> new SalesPoint(
                        rs.getObject("period", LocalDate.class),
                        rs.getBigDecimal("revenue"),
                        rs.getLong("units"),
                        rs.getLong("views"),
                        rs.getLong("carts"),
                        rs.getLong("purchases")))
                .list();
    }

    @Override
    public List<TopProduct> topProducts(LocalDate from, LocalDate to, ProductMetric metric, int limit) {
        return jdbc.sql(TOP_PRODUCTS_SQL.formatted(metric.column()))
                .param("fromDay", from)
                .param("toDay", to)
                .param("maxRows", limit)
                .query((rs, i) -> new TopProduct(
                        rs.getLong("product_id"),
                        rs.getLong("views"),
                        rs.getLong("carts"),
                        rs.getLong("purchases"),
                        rs.getBigDecimal("revenue")))
                .list();
    }

    @Override
    public List<CategorySales> categorySales(LocalDate from, LocalDate to, int limit) {
        return jdbc.sql(CATEGORY_SQL)
                .param("fromDay", from)
                .param("toDay", to)
                .param("maxRows", limit)
                .query((rs, i) -> new CategorySales(
                        rs.getString("category_code"),
                        rs.getBigDecimal("revenue"),
                        rs.getLong("units"),
                        rs.getLong("views"),
                        rs.getLong("carts"),
                        rs.getLong("purchases")))
                .list();
    }
}
