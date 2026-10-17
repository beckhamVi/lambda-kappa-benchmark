package com.bi.ingestionlambda.domain;

public enum RejectReason {
    INVALID_COLUMNS,
    INVALID_EVENT_TYPE,
    INVALID_TIME,
    INVALID_NUMBER,
    INVALID_PRICE,
    EMPTY_SESSION
}
