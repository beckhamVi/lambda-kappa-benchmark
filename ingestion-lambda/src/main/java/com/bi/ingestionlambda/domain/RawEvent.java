package com.bi.ingestionlambda.domain;

public record RawEvent(long lineNumber, String line) {
}
