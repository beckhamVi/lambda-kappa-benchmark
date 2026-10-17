package com.bi.ingestionlambda.domain;

public record RejectedRow(long lineNumber, String rawLine, RejectReason reason) {
}
