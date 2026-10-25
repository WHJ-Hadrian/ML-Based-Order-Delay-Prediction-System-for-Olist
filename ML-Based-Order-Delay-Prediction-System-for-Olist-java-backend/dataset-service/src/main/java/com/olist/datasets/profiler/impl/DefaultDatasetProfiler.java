package com.olist.datasets.profiler.impl;

import com.olist.datasets.enums.DatasetFormat;
import com.olist.datasets.exception.InvalidDatasetException;
import com.olist.datasets.profiler.DatasetProfile;
import com.olist.datasets.profiler.DatasetProfiler;
import com.olist.datasets.profiler.TableProfile;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.SAXParserFactory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

@Component
public class DefaultDatasetProfiler implements DatasetProfiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDatasetProfiler.class);
    private static final int MAX_TABLES = 200;
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final long MAX_XLSX_BYTES = 100L * 1024L * 1024L;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_TABLE_NAME_LENGTH = 500;

    @Override
    public DatasetProfile profile(MultipartFile file, String filename) {
        DatasetFormat format = DatasetFormat.fromFilename(filename)
                .orElseThrow(() -> new InvalidDatasetException("仅支持 CSV、TSV、ZIP 或 XLSX 文件"));
        try {
            List<TableProfile> tables = switch (format) {
                case CSV -> List.of(profileDelimited(
                        file.getInputStream(), baseName(filename), filename, ',', format));
                case TSV -> List.of(profileDelimited(
                        file.getInputStream(), baseName(filename), filename, '\t', format));
                case ZIP -> profileZip(file.getInputStream());
                case XLSX -> profileXlsx(file.getInputStream(), null, filename, file.getSize());
            };
            if (tables.isEmpty()) {
                throw new InvalidDatasetException("文件中没有可识别的数据表");
            }
            return new DatasetProfile(tables);
        } catch (InvalidDatasetException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("Failed to profile dataset {}", filename, exception);
            throw new InvalidDatasetException("文件解析失败，请确认文件内容完整且格式正确", exception);
        }
    }

    private TableProfile profileDelimited(
            InputStream input,
            String tableName,
            String sourceFile,
            char delimiter,
            DatasetFormat format) throws IOException {
        try (PushbackReader reader = new PushbackReader(
                new InputStreamReader(input, StandardCharsets.UTF_8), 1)) {
            int first = reader.read();
            if (first != -1 && first != '\uFEFF') {
                reader.unread(first);
            }
            CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                    .setDelimiter(delimiter)
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .build();
            try (CSVParser parser = new CSVParser(reader, csvFormat)) {
                int columns = parser.getHeaderNames().size();
                if (columns == 0) {
                    throw new InvalidDatasetException("文件 " + tableName + " 缺少表头");
                }
                long rows = 0;
                for (CSVRecord ignored : parser) {
                    rows++;
                }
                return new TableProfile(tableName, sourceFile, format, rows, columns);
            }
        }
    }

    private List<TableProfile> profileZip(InputStream input) throws Exception {
        List<TableProfile> tables = new ArrayList<>();
        long[] totalUncompressed = {0};
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new InvalidDatasetException(
                            "压缩包内文件数量不能超过 " + MAX_ARCHIVE_ENTRIES + " 个");
                }
                String entryName = entry.getName().replace('\\', '/');
                DatasetFormat format = DatasetFormat.fromFilename(entryName).orElse(null);
                long entryLimit = format == DatasetFormat.XLSX
                        ? MAX_XLSX_BYTES
                        : Long.MAX_VALUE;
                InputStream boundedEntry = new BoundedZipEntryInputStream(
                        new NonClosingInputStream(zip),
                        totalUncompressed,
                        entryLimit,
                        MAX_TOTAL_UNCOMPRESSED_BYTES,
                        "压缩包解压后总大小不能超过 2GB",
                        format == DatasetFormat.XLSX
                                ? "压缩包内单个 XLSX 文件不能超过 100MB"
                                : null);
                if (entry.isDirectory() || shouldIgnore(entryName)) {
                    drain(boundedEntry);
                    zip.closeEntry();
                    continue;
                }
                if (format == null || format == DatasetFormat.ZIP) {
                    drain(boundedEntry);
                    zip.closeEntry();
                    continue;
                }
                if (tables.size() >= MAX_TABLES) {
                    throw new InvalidDatasetException("压缩包内数据表不能超过 " + MAX_TABLES + " 个");
                }
                long size = entry.getSize();
                if (format == DatasetFormat.XLSX) {
                    tables.addAll(profileXlsx(
                            boundedEntry, baseName(entryName), entryName, size));
                } else {
                    char delimiter = format == DatasetFormat.TSV ? '\t' : ',';
                    tables.add(profileDelimited(
                            boundedEntry,
                            baseName(entryName, true),
                            entryName,
                            delimiter,
                            format));
                }
                if (tables.size() > MAX_TABLES) {
                    throw new InvalidDatasetException("压缩包内数据表不能超过 " + MAX_TABLES + " 个");
                }
                drain(boundedEntry);
                zip.closeEntry();
            }
        }
        if (tables.isEmpty()) {
            throw new InvalidDatasetException("压缩包中没有 CSV、TSV 或 XLSX 数据表");
        }
        return tables;
    }

    private void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        while (input.read(buffer) != -1) {
            // Read the full entry so skipped data is included in the archive limit.
        }
    }

    private List<TableProfile> profileXlsx(
            InputStream input, String namePrefix, String sourceFile, long size) throws Exception {
        if (size > MAX_XLSX_BYTES) {
            throw new InvalidDatasetException("XLSX 文件不能超过 100MB");
        }
        List<TableProfile> tables = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(input)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheetStream = sheets.next()) {
                    String sheetName = sheets.getSheetName();
                    SheetStatistics statistics = new SheetStatistics();
                    XMLReader parser = newXmlReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(
                            styles, sharedStrings, statistics, new DataFormatter(), false));
                    parser.parse(new InputSource(sheetStream));
                    if (statistics.columnCount > 0) {
                        if (tables.size() >= MAX_TABLES) {
                            throw new InvalidDatasetException("单个 XLSX 文件中的数据表不能超过 " + MAX_TABLES + " 个");
                        }
                        String name = namePrefix == null || namePrefix.isBlank()
                                ? sheetName
                                : namePrefix + " / " + sheetName;
                        tables.add(new TableProfile(
                                truncate(name),
                                sourceFile,
                                DatasetFormat.XLSX,
                                statistics.dataRows(),
                                statistics.columnCount));
                    }
                }
            }
        }
        if (tables.isEmpty()) {
            throw new InvalidDatasetException("XLSX 文件中没有可识别的数据表");
        }
        return tables;
    }

    private XMLReader newXmlReader() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newSAXParser().getXMLReader();
    }

    private boolean shouldIgnore(String entryName) {
        return entryName.startsWith("__MACOSX/")
                || entryName.startsWith(".")
                || entryName.contains("/.")
                || entryName.isBlank();
    }

    private String baseName(String path) {
        return baseName(path, false);
    }

    private String baseName(String path, boolean keepParent) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String filename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String parent = slash > 0 ? normalized.substring(0, slash) : "";
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        if (base.isBlank()) {
            base = "table";
        }
        String name = keepParent && !parent.isBlank() ? parent + "/" + base : base;
        return truncate(name);
    }

    private String truncate(String value) {
        return value.length() > MAX_TABLE_NAME_LENGTH
                ? value.substring(0, MAX_TABLE_NAME_LENGTH)
                : value;
    }

    private static final class NonClosingInputStream extends FilterInputStream {

        private NonClosingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public void close() {
            // The enclosing ZIP stream is closed by profileZip.
        }
    }

    static final class BoundedZipEntryInputStream extends FilterInputStream {

        private final long[] totalUncompressed;
        private final long entryLimit;
        private final long totalLimit;
        private final String totalLimitMessage;
        private final String entryLimitMessage;
        private long entryBytes;

        BoundedZipEntryInputStream(
                InputStream input,
                long[] totalUncompressed,
                long entryLimit,
                long totalLimit,
                String totalLimitMessage,
                String entryLimitMessage) {
            super(input);
            this.totalUncompressed = totalUncompressed;
            this.entryLimit = entryLimit;
            this.totalLimit = totalLimit;
            this.totalLimitMessage = totalLimitMessage;
            this.entryLimitMessage = entryLimitMessage;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length);
            if (read > 0) {
                account(read);
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            byte[] buffer = new byte[(int) Math.min(count, 8192)];
            long skipped = 0;
            while (skipped < count) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    if (read() < 0) {
                        break;
                    }
                    skipped++;
                } else {
                    skipped += read;
                }
            }
            return skipped;
        }

        @Override
        public void close() {
            // The enclosing ZIP stream owns the entry lifecycle.
        }

        private void account(int bytesRead) {
            entryBytes += bytesRead;
            totalUncompressed[0] += bytesRead;
            if (totalUncompressed[0] > totalLimit) {
                throw new InvalidDatasetException(totalLimitMessage);
            }
            if (entryLimitMessage != null && entryBytes > entryLimit) {
                throw new InvalidDatasetException(entryLimitMessage);
            }
        }
    }

    private static final class SheetStatistics implements XSSFSheetXMLHandler.SheetContentsHandler {

        private long nonEmptyRows;
        private int columnCount;
        private boolean rowHasContent;

        @Override
        public void startRow(int rowNum) {
            rowHasContent = false;
        }

        @Override
        public void endRow(int rowNum) {
            if (rowHasContent) {
                nonEmptyRows++;
            }
            rowHasContent = false;
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (formattedValue != null && !formattedValue.isBlank()) {
                rowHasContent = true;
                columnCount = Math.max(columnCount, columnIndex(cellReference));
            }
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Header and footer rows are not part of the table.
        }

        private long dataRows() {
            return Math.max(nonEmptyRows - 1, 0);
        }

        private static int columnIndex(String reference) {
            if (reference == null) {
                return 0;
            }
            int index = 0;
            for (int i = 0; i < reference.length(); i++) {
                char character = reference.charAt(i);
                if (character >= 'A' && character <= 'Z') {
                    index = index * 26 + (character - 'A' + 1);
                } else if (character >= 'a' && character <= 'z') {
                    index = index * 26 + (character - 'a' + 1);
                } else {
                    break;
                }
            }
            return index;
        }
    }
}
