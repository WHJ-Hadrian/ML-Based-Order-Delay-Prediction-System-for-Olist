package com.olist.jobs.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorkflowSampleFilterTest {
    @Test
    void comparesNumericValuesAsDecimalsAndIncludesRangeBounds() {
        assertTrue(WorkflowSampleFilter.matches("GREATER_THAN", "10", "9.5", null));
        assertTrue(WorkflowSampleFilter.matches("LESS_THAN_OR_EQUAL", "10", "10", null));
        assertTrue(WorkflowSampleFilter.matches("BETWEEN", "10.25", "10", "10.25"));
        assertFalse(WorkflowSampleFilter.matches("BETWEEN", "10.26", "10", "10.25"));
        assertFalse(WorkflowSampleFilter.matches("GREATER_THAN", "not-a-number", "1", null));
    }

    @Test
    void detectsOnlySupportedNumericLogicalTypes() {
        assertTrue(WorkflowSampleFilter.isNumericType("INTEGER"));
        assertTrue(WorkflowSampleFilter.isNumericType("DECIMAL(18,2)"));
        assertTrue(WorkflowSampleFilter.isNumericType("Float64"));
        assertFalse(WorkflowSampleFilter.isNumericType("STRING"));
        assertFalse(WorkflowSampleFilter.isNumericType("DATETIME"));
    }
}
