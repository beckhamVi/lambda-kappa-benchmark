CREATE TABLE raw_events (
                            event_time    timestamptz,
                            event_type    text,
                            product_id    bigint,
                            category_id   bigint,
                            category_code text,
                            brand         text,
                            price         numeric(10,2),
                            user_id       bigint,
                            user_session  text
);