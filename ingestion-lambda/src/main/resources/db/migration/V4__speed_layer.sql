CREATE TABLE speed_daily_sales (
                                   day       date PRIMARY KEY,
                                   revenue   numeric(14,2) NOT NULL DEFAULT 0,
                                   units     bigint NOT NULL DEFAULT 0,
                                   views     bigint NOT NULL DEFAULT 0,
                                   carts     bigint NOT NULL DEFAULT 0,
                                   purchases bigint NOT NULL DEFAULT 0
);

CREATE TABLE speed_product_stats (
                                     day        date NOT NULL,
                                     product_id bigint NOT NULL,
                                     views      bigint NOT NULL DEFAULT 0,
                                     carts      bigint NOT NULL DEFAULT 0,
                                     purchases  bigint NOT NULL DEFAULT 0,
                                     revenue    numeric(14,2) NOT NULL DEFAULT 0,
                                     PRIMARY KEY (day, product_id)
);

CREATE TABLE speed_category_sales (
                                      day           date NOT NULL,
                                      category_code text NOT NULL,
                                      revenue       numeric(14,2) NOT NULL DEFAULT 0,
                                      units         bigint NOT NULL DEFAULT 0,
                                      views         bigint NOT NULL DEFAULT 0,
                                      carts         bigint NOT NULL DEFAULT 0,
                                      purchases     bigint NOT NULL DEFAULT 0,
                                      PRIMARY KEY (day, category_code)
);

-- CAPA SERVING: une lo exacto (batch) con lo reciente (speed)
CREATE VIEW serving_daily_sales AS
SELECT day, SUM(revenue) AS revenue, SUM(units) AS units, SUM(views) AS views,
        SUM(carts) AS carts, SUM(purchases) AS purchases
        FROM (
        SELECT day, revenue, units, views, carts, purchases FROM daily_sales
        UNION ALL
        SELECT day, revenue, units, views, carts, purchases FROM speed_daily_sales
        ) t GROUP BY day;

CREATE VIEW serving_product_stats AS
SELECT day, product_id, SUM(views) AS views, SUM(carts) AS carts,
        SUM(purchases) AS purchases, SUM(revenue) AS revenue
        FROM (
        SELECT day, product_id, views, carts, purchases, revenue FROM daily_product_stats
        UNION ALL
        SELECT day, product_id, views, carts, purchases, revenue FROM speed_product_stats
        ) t GROUP BY day, product_id;

CREATE VIEW serving_category_sales AS
SELECT day, category_code, SUM(revenue) AS revenue, SUM(units) AS units,
        SUM(views) AS views, SUM(carts) AS carts, SUM(purchases) AS purchases
        FROM (
        SELECT day, category_code, revenue, units, views, carts, purchases FROM daily_category_sales
        UNION ALL
        SELECT day, category_code, revenue, units, views, carts, purchases FROM speed_category_sales
        ) t GROUP BY day, category_code;