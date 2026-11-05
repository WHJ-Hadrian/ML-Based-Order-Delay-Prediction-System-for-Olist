package com.olist.jobs.workflow;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

final class WorkflowSampleFilter {
    private static final Set<String> NUMERIC_OPERATORS = Set.of(
            "GREATER_THAN", "GREATER_THAN_OR_EQUAL", "LESS_THAN", "LESS_THAN_OR_EQUAL", "BETWEEN");

    private WorkflowSampleFilter() { }

    static boolean usesNumericComparison(String operator) {
        return NUMERIC_OPERATORS.contains(operator);
    }

    static boolean isNumericType(String logicalType) {
        String type = logicalType == null ? "" : logicalType.toUpperCase(Locale.ROOT);
        return type.matches("(?:U?INT[0-9]+|FLOAT[0-9]+|DECIMAL(?:[0-9]+)?(?:\\(.*\\))?|"
                + "INTEGER|LONG|DECIMAL|DOUBLE|FLOAT|FLOAT64|INT64)");
    }

    static boolean matches(String operator, String actual, String value, String valueTo) {
        String text = actual == null ? "" : actual;
        String expected = value == null ? "" : value;
        return switch (operator) {
            case "EQUALS" -> text.equals(expected);
            case "NOT_EQUALS" -> !text.equals(expected);
            case "CONTAINS" -> text.contains(expected);
            case "IS_EMPTY" -> text.isBlank();
            case "IS_NOT_EMPTY" -> !text.isBlank();
            case "GREATER_THAN" -> compare(text, expected, comparison -> comparison > 0);
            case "GREATER_THAN_OR_EQUAL" -> compare(text, expected, comparison -> comparison >= 0);
            case "LESS_THAN" -> compare(text, expected, comparison -> comparison < 0);
            case "LESS_THAN_OR_EQUAL" -> compare(text, expected, comparison -> comparison <= 0);
            case "BETWEEN" -> between(text, expected, valueTo);
            default -> throw new IllegalArgumentException("筛选条件不受支持");
        };
    }

    private static boolean compare(String actual, String expected, java.util.function.IntPredicate predicate) {
        try {
            return predicate.test(new BigDecimal(actual.trim()).compareTo(new BigDecimal(expected.trim())));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean between(String actual, String lower, String upper) {
        try {
            BigDecimal value = new BigDecimal(actual.trim());
            return value.compareTo(new BigDecimal(lower.trim())) >= 0
                    && value.compareTo(new BigDecimal(upper.trim())) <= 0;
        } catch (NullPointerException | NumberFormatException exception) {
            return false;
        }
    }
}
