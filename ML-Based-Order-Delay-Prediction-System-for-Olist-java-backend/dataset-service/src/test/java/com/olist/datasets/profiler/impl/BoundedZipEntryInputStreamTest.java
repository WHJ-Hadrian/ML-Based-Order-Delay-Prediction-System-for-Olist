package com.olist.datasets.profiler.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.olist.datasets.exception.InvalidDatasetException;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class BoundedZipEntryInputStreamTest {

    @Test
    void rejectsActualEntryBytesPastLimit() {
        var input = new DefaultDatasetProfiler.BoundedZipEntryInputStream(
                new ByteArrayInputStream(new byte[6]),
                new long[] {0},
                5,
                20,
                "archive too large",
                "entry too large");

        assertThatThrownBy(input::readAllBytes)
                .isInstanceOf(InvalidDatasetException.class)
                .hasMessage("entry too large");
    }

    @Test
    void countsBytesAcrossEntriesAgainstArchiveLimit() throws Exception {
        long[] total = {0};
        var first = new DefaultDatasetProfiler.BoundedZipEntryInputStream(
                new ByteArrayInputStream(new byte[3]), total, 10, 5, "archive too large", null);
        var second = new DefaultDatasetProfiler.BoundedZipEntryInputStream(
                new ByteArrayInputStream(new byte[3]), total, 10, 5, "archive too large", null);

        first.readAllBytes();

        assertThatThrownBy(second::readAllBytes)
                .isInstanceOf(InvalidDatasetException.class)
                .hasMessage("archive too large");
    }

    @Test
    void countsSkippedBytesAgainstEntryLimit() {
        var input = new DefaultDatasetProfiler.BoundedZipEntryInputStream(
                new ByteArrayInputStream(new byte[6]),
                new long[] {0},
                5,
                20,
                "archive too large",
                "entry too large");

        assertThatThrownBy(() -> input.skip(6))
                .isInstanceOf(InvalidDatasetException.class)
                .hasMessage("entry too large");
    }

    @Test
    void countsSkippedBytesAcrossEntriesAgainstArchiveLimit() throws Exception {
        long[] total = {0};
        var first = new DefaultDatasetProfiler.BoundedZipEntryInputStream(
                new ByteArrayInputStream(new byte[3]), total, 10, 5, "archive too large", null);
        var second = new DefaultDatasetProfiler.BoundedZipEntryInputStream(
                new ByteArrayInputStream(new byte[3]), total, 10, 5, "archive too large", null);

        first.skip(3);

        assertThatThrownBy(() -> second.skip(3))
                .isInstanceOf(InvalidDatasetException.class)
                .hasMessage("archive too large");
    }
}
