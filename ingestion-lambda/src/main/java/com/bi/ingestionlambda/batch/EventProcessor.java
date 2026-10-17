package com.bi.ingestionlambda.batch;

import com.bi.ingestionlambda.cleaning.CleaningResult;
import com.bi.ingestionlambda.cleaning.CleaningRules;
import com.bi.ingestionlambda.domain.RawEvent;
import org.springframework.stereotype.Component;

@Component
public class EventProcessor {
    public CleaningResult process(RawEvent raw) {
        return CleaningRules.clean(raw);
    }
}
