package com.bi.ingestionlambda.cleaning;

import com.bi.ingestionlambda.domain.CleanEvent;
import com.bi.ingestionlambda.domain.RejectReason;

public record CleaningResult(CleanEvent event, RejectReason reason) {
    public static CleaningResult ok(CleanEvent e) { return new CleaningResult(e, null); }
    public static CleaningResult rejected(RejectReason r) { return new CleaningResult(null, r); }
    public boolean isRejected() { return reason != null; }
}
