package org.alxarafe.arp.dictionary;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;

final class ScalarValidator {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSS", Locale.ROOT);
    private static final DateTimeFormatter INSTANT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSS'Z'", Locale.ROOT);
    private ScalarValidator() {}

    static ValidationResult validate(Descriptor descriptor, Object value) {
        String id = descriptor.id();
        try {
            Object normalized = normalize(value, descriptor.type());
            var constraints = descriptor.constraints();
            var issues = new ArrayList<Issue>();
            if (descriptor.type().numeric()) {
                BigDecimal number = new BigDecimal((String) normalized);
                if (!Numbers.inRange(number, descriptor.type())) fail(issues, id, "typeRange");
                if (constraints.containsKey("minimum")) {
                    int comparison = number.compareTo(new BigDecimal((String) constraints.get("minimum")));
                    if (comparison < 0 || comparison == 0 && Boolean.TRUE.equals(constraints.get("exclusiveMinimum"))) fail(issues, id, "minimum");
                }
                if (constraints.containsKey("maximum")) {
                    int comparison = number.compareTo(new BigDecimal((String) constraints.get("maximum")));
                    if (comparison > 0 || comparison == 0 && Boolean.TRUE.equals(constraints.get("exclusiveMaximum"))) fail(issues, id, "maximum");
                }
                if (constraints.containsKey("multipleOf") && number.remainder(new BigDecimal((String) constraints.get("multipleOf"))).signum() != 0)
                    fail(issues, id, "multipleOf");
                if (constraints.containsKey("precision")) {
                    int precision = Numbers.metadata(constraints.get("precision")).intValueExact();
                    int scale = Numbers.metadata(constraints.get("scale")).intValueExact();
                    if (number.abs().compareTo(BigDecimal.ONE.scaleByPowerOfTen(precision - scale)) >= 0) fail(issues, id, "precision");
                    if (Math.max(0, number.stripTrailingZeros().scale()) > scale) fail(issues, id, "scale");
                }
            }
            if (descriptor.type().text() || descriptor.type() == ScalarType.BINARY) {
                String text = (String) value;
                int length = descriptor.type() == ScalarType.BINARY ? Base64.getDecoder().decode(text).length : text.codePointCount(0, text.length());
                if (constraints.containsKey("minimumLength") && BigDecimal.valueOf(length).compareTo(Numbers.metadata(constraints.get("minimumLength"))) < 0)
                    fail(issues, id, "minimumLength");
                if (constraints.containsKey("maximumLength") && BigDecimal.valueOf(length).compareTo(Numbers.metadata(constraints.get("maximumLength"))) > 0)
                    fail(issues, id, "maximumLength");
                if (descriptor.pattern() != null && !descriptor.pattern().matches(text)) fail(issues, id, "pattern");
                if (descriptor.type() == ScalarType.ENUM && !descriptor.enumValues().contains(text)) fail(issues, id, "enumValues");
            }
            return issues.isEmpty() ? ValidationResult.success(normalized) : ValidationResult.failure(issues);
        } catch (Rejected rejected) {
            return ValidationResult.failure(java.util.List.of(new Issue(rejected.code, id, null, null)));
        }
    }

    private static void fail(ArrayList<Issue> issues, String id, String constraint) {
        issues.add(new Issue(Code.DICTIONARY_VALUE_CONSTRAINT, id, constraint, null));
    }

    private static Object normalize(Object value, ScalarType type) {
        if (type == ScalarType.BOOLEAN) {
            if (!(value instanceof Boolean)) throw Rejected.type();
            return value;
        }
        if (!(value instanceof String text) || !Unicode.valid(text)) throw Rejected.type();
        if (type.numeric()) return Numbers.parse(text, type).stripTrailingZeros().toPlainString();
        if (type.text()) return text;
        switch (type) {
            case UUID -> {
                if (!text.matches("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")) throw Rejected.type();
                return text.toLowerCase(Locale.ROOT);
            }
            case BINARY -> {
                byte[] decoded;
                try { decoded = Base64.getDecoder().decode(text); } catch (IllegalArgumentException e) { throw Rejected.type(); }
                if (!Base64.getEncoder().encodeToString(decoded).equals(text)) throw Rejected.type();
                return text;
            }
            case DATE -> { date(text); return text; }
            case TIME -> { return time(text).format(TIME); }
            case DATETIME -> {
                if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,7})?(Z|[+-][0-9]{2}:[0-9]{2})")) throw Rejected.type();
                date(text.substring(0, 10));
                try {
                    var parsed = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    if (Math.abs(parsed.getOffset().getTotalSeconds()) > 14 * 3600) throw Rejected.type();
                    var utc = parsed.withOffsetSameInstant(ZoneOffset.UTC);
                    if (utc.getYear() < 1 || utc.getYear() > 9999) throw Rejected.type();
                    return utc.format(INSTANT);
                } catch (DateTimeException e) { throw Rejected.type(); }
            }
            default -> throw new IllegalStateException("Unimplemented scalar type " + type);
        }
    }

    private static void date(String text) {
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || text.startsWith("0000")) throw Rejected.type();
        try { LocalDate.parse(text); } catch (DateTimeException e) { throw Rejected.type(); }
    }

    private static LocalTime time(String text) {
        if (!text.matches("[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,7})?")) throw Rejected.type();
        try { return LocalTime.parse(text); } catch (DateTimeException e) { throw Rejected.type(); }
    }
}
