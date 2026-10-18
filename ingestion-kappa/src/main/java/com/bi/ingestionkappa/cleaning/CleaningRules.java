package com.bi.ingestionkappa.cleaning;

import com.bi.ingestionkappa.domain.CleanEvent;
import com.bi.ingestionkappa.domain.RawEvent;
import com.bi.ingestionkappa.domain.RejectReason;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

public final class CleaningRules {

    private static final Set<String> VALID_TYPES =
            Set.of("view", "cart", "remove_from_cart", "purchase");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CleaningRules() {}

    public static CleaningResult clean(RawEvent raw) {
        String[] f = raw.line().split(",", -1);
        if (f.length != 9) {
            return CleaningResult.rejected(RejectReason.INVALID_COLUMNS);
        }

        String type = f[1].trim().toLowerCase(Locale.ROOT);
        if (!VALID_TYPES.contains(type)) {
            return CleaningResult.rejected(RejectReason.INVALID_EVENT_TYPE);
        }

        Instant time;
        try {
            String t = f[0].trim();
            if (t.endsWith(" UTC")) {
                t = t.substring(0, t.length() - 4);
            }
            time = LocalDateTime.parse(t, TIME_FMT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return CleaningResult.rejected(RejectReason.INVALID_TIME);
        }

        long productId;
        long categoryId;
        long userId;
        try {
            productId = Long.parseLong(f[2].trim());
            categoryId = Long.parseLong(f[3].trim());
            userId = Long.parseLong(f[7].trim());
        } catch (NumberFormatException e) {
            return CleaningResult.rejected(RejectReason.INVALID_NUMBER);
        }

        BigDecimal price;
        try {
            price = new BigDecimal(f[6].trim());
        } catch (NumberFormatException e) {
            return CleaningResult.rejected(RejectReason.INVALID_PRICE);
        }
        if (price.signum() <= 0) {
            return CleaningResult.rejected(RejectReason.INVALID_PRICE);
        }
        price = price.setScale(2, RoundingMode.HALF_UP);

        String session = f[8].trim();
        if (session.isEmpty()) {
            return CleaningResult.rejected(RejectReason.EMPTY_SESSION);
        }

        return CleaningResult.ok(new CleanEvent(
                time, type, productId, categoryId,
                normalize(f[4]), normalize(f[5]),
                price, userId, session));
    }

    private static String normalize(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? "unknown" : v;
    }
}