ALTER TABLE events
    ADD COLUMN event_date date
        GENERATED ALWAYS AS ((event_time AT TIME ZONE 'UTC')::date) STORED;

CREATE UNIQUE INDEX uq_events_dedup
    ON events (event_time, event_type, product_id, user_id, user_session);

CREATE TABLE rejected_events (
                                 id          bigserial PRIMARY KEY,
                                 line_number bigint NOT NULL,
                                 raw_line    text,
                                 reason      text NOT NULL
);

CREATE TABLE run_log (
                         id               bigserial PRIMARY KEY,
                         run_at           timestamptz NOT NULL DEFAULT now(),
                         lines_read       bigint,
                         rejected         bigint,
                         duplicates       bigint,
                         inserted         bigint,
                         load_ms          bigint,
                         aggregation_ms   bigint,
                         events_per_sec   numeric(12,2)
);