CREATE TABLE daily_sales (
                             day date PRIMARY KEY,
                             revenue numeric(14,2) NOT NULL DEFAULT 0,
                             units bigint NOT NULL DEFAULT 0,
                             views bigint NOT NULL DEFAULT 0,
                             carts bigint NOT NULL DEFAULT 0,
                             purchases bigint NOT NULL DEFAULT 0
);

CREATE TABLE daily_product_stats (
                                     day date NOT NULL,
                                     product_id bigint NOT NULL,
                                     views bigint NOT NULL DEFAULT 0,
                                     carts bigint NOT NULL DEFAULT 0,
                                     purchases bigint NOT NULL DEFAULT 0,
                                     revenue numeric(14,2) NOT NULL DEFAULT 0,
                                     PRIMARY KEY (day, product_id)
);

CREATE TABLE daily_category_sales (
                                      day date NOT NULL,
                                      category_code text NOT NULL,
                                      revenue numeric(14,2) NOT NULL DEFAULT 0,
                                      units bigint NOT NULL DEFAULT 0,
                                      views bigint NOT NULL DEFAULT 0,
                                      carts bigint NOT NULL DEFAULT 0,
                                      purchases bigint NOT NULL DEFAULT 0,
                                      PRIMARY KEY (day, category_code)
);

CREATE TABLE rejected_events (
                                 id bigserial PRIMARY KEY,
                                 line_number bigint NOT NULL,
                                 raw_line text,
                                 reason text NOT NULL
);