package com.bi.apigrpc.domain;

/** Criterio para ordenar el top de productos. La columna SQL sale de este enum (lista blanca). */
public enum ProductMetric {
    REVENUE("revenue"),
    VIEWS("views"),
    PURCHASES("purchases");

    private final String column;

    ProductMetric(String column) {
        this.column = column;
    }

    public String column() {
        return column;
    }

    public String apiName() {
        return column;
    }

    public static ProductMetric parse(String raw) {
        if (raw != null) {
            for (ProductMetric m : values()) {
                if (m.column.equalsIgnoreCase(raw.trim())) {
                    return m;
                }
            }
        }
        throw new InvalidRequestException("metric must be one of: revenue, views, purchases");
    }
}
