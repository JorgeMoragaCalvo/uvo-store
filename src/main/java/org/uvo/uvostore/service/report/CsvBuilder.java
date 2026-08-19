package org.uvo.uvostore.service.report;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

// Ports the fputcsv(..., ';')-with-UTF-8-BOM shape every Admin\ReportController export uses.
final class CsvBuilder {

    private final StringBuilder sb = new StringBuilder();

    CsvBuilder header(String... columns) {
        row((Object[]) columns);
        return this;
    }

    CsvBuilder row(Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(escape(String.valueOf(values[i])));
        }
        sb.append("\r\n");
        return this;
    }

    byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xEF);
        out.write(0xBB);
        out.write(0xBF);
        out.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // Ports number_format($amount, 0, ',', '.') — thousands separated with '.', no decimals.
    static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        long rounded = amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        String digits = Long.toString(Math.abs(rounded));
        StringBuilder grouped = new StringBuilder();
        int count = 0;
        for (int i = digits.length() - 1; i >= 0; i--) {
            grouped.append(digits.charAt(i));
            count++;
            if (count % 3 == 0 && i != 0) {
                grouped.append('.');
            }
        }
        return (rounded < 0 ? "-" : "") + grouped.reverse();
    }

    static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
