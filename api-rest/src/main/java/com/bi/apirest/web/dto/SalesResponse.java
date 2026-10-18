package com.bi.apirest.web.dto;

import com.bi.apirest.domain.Granularity;
import com.bi.apirest.domain.SalesPoint;

import java.time.LocalDate;
import java.util.List;

public record SalesResponse(String from, String to, String granularity, List<SalesItem> items) {

    public static SalesResponse of(LocalDate from, LocalDate to, Granularity granularity, List<SalesPoint> points) {
        return new SalesResponse(from.toString(), to.toString(), granularity.apiName(),
                points.stream().map(SalesItem::from).toList());
    }
}
