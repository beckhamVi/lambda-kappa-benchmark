CREATE TABLE daily_sales (
                             day          date PRIMARY KEY,
                             revenue      numeric(14,2) NOT NULL,
                             orders       bigint NOT NULL,
                             units        bigint NOT NULL,
                             views        bigint NOT NULL,
                             carts        bigint NOT NULL,
                             purchases    bigint NOT NULL,
                             avg_ticket   numeric(12,2) NOT NULL,
                             active_users bigint NOT NULL
);

CREATE TABLE daily_product_stats (
                                     day        date NOT NULL,
                                     product_id bigint NOT NULL,
                                     views      bigint NOT NULL,
                                     carts      bigint NOT NULL,
                                     purchases  bigint NOT NULL,
                                     revenue    numeric(14,2) NOT NULL,
                                     PRIMARY KEY (day, product_id)
);

CREATE TABLE daily_category_sales (
                                      day           date NOT NULL,
                                      category_code text NOT NULL,
                                      revenue       numeric(14,2) NOT NULL,
                                      units         bigint NOT NULL,
                                      views         bigint NOT NULL,
                                      carts         bigint NOT NULL,
                                      purchases     bigint NOT NULL,
                                      PRIMARY KEY (day, category_code)
);