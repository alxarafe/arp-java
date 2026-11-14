package org.alxarafe.arp.dictionary;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.regex.Pattern;

final class Numbers {
    private static final Pattern SIGNED = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern UNSIGNED = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?");
    private static final Map<ScalarType, String[]> RANGES = Map.of(
            ScalarType.INT8, new String[]{"-128", "127"}, ScalarType.UINT8, new String[]{"0", "255"},
            ScalarType.INT16, new String[]{"-32768", "32767"}, ScalarType.UINT16, new String[]{"0", "65535"},
            ScalarType.INT32, new String[]{"-2147483648", "2147483647"}, ScalarType.UINT32, new String[]{"0", "4294967295"},
            ScalarType.INT64, new String[]{"-9223372036854775808", "9223372036854775807"});

    private Numbers() {}
    static BigDecimal parse(Object value, ScalarType type) {
        Pattern grammar = type == ScalarType.DECIMAL ? DECIMAL : type.name().startsWith("UINT") ? UNSIGNED : SIGNED;
        if (!(value instanceof String text) || !grammar.matcher(text).matches()) throw Rejected.type();
        int digits = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= '0' && text.charAt(i) <= '9') digits++;
        if (digits > 4096) throw Rejected.limit();
        return new BigDecimal(text);
    }

    static boolean inRange(BigDecimal value, ScalarType type) {
        String[] range = RANGES.get(type);
        return range == null || value.compareTo(new BigDecimal(range[0])) >= 0 && value.compareTo(new BigDecimal(range[1])) <= 0;
    }

    static boolean integral(Object value) {
        return value instanceof BigInteger || value instanceof BigDecimal d && d.stripTrailingZeros().scale() <= 0;
    }

    static BigDecimal metadata(Object value) {
        return value instanceof BigInteger i ? new BigDecimal(i) : (BigDecimal) value;
    }
}
