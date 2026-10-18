package com.bi.apigrpc.domain;

/** Periodo en el que se agrupan las ventas. El valor SQL sale de este enum (lista blanca), nunca del cliente. */
public enum Granularity {
    DAY("day"),
    WEEK("week"),
    MONTH("month");

    private final String sqlUnit;

    Granularity(String sqlUnit) {
        this.sqlUnit = sqlUnit;
    }

    public String sqlUnit() {
        return sqlUnit;
    }

    public String apiName() {
        return sqlUnit;
    }

    public static Granularity parse(String raw) {
        if (raw != null) {
            for (Granularity g : values()) {
                if (g.sqlUnit.equalsIgnoreCase(raw.trim())) {
                    return g;
                }
            }
        }
        throw new InvalidRequestException("granularity must be one of: day, week, month");
    }
}
