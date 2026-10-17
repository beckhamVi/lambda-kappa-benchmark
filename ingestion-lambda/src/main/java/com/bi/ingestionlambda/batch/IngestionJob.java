package com.bi.ingestionlambda.batch;

import com.bi.ingestionlambda.cleaning.CleaningResult;
import com.bi.ingestionlambda.domain.CleanEvent;
import com.bi.ingestionlambda.domain.RawEvent;
import com.bi.ingestionlambda.domain.RejectedRow;
import com.bi.ingestionlambda.metrics.RunMetrics;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class IngestionJob {

    private final EventProcessor processor;
    private final EventDbWriter eventWriter;
    private final RejectedWriter rejectedWriter;

    public IngestionJob(EventProcessor processor, EventDbWriter eventWriter,
                        RejectedWriter rejectedWriter) {
        this.processor = processor;
        this.eventWriter = eventWriter;
        this.rejectedWriter = rejectedWriter;
    }

    public void run(Path csv, int chunkSize, RunMetrics metrics) throws IOException {
        List<CleanEvent> good = new ArrayList<>(chunkSize);
        List<RejectedRow> bad = new ArrayList<>();

        try (EventCsvReader reader = new EventCsvReader(csv)) {
            RawEvent raw;
            while ((raw = reader.next()) != null) {
                metrics.linesRead++;
                CleaningResult result = processor.process(raw);
                if (result.isRejected()) {
                    metrics.countReject(result.reason());
                    bad.add(new RejectedRow(raw.lineNumber(), raw.line(), result.reason()));
                } else {
                    metrics.accepted++;
                    good.add(result.event());
                }
                if (good.size() + bad.size() >= chunkSize) {
                    flush(good, bad);
                }
            }
            flush(good, bad);
        }
    }

    private void flush(List<CleanEvent> good, List<RejectedRow> bad) {
        eventWriter.write(good);
        rejectedWriter.write(bad);
        good.clear();
        bad.clear();
    }
}