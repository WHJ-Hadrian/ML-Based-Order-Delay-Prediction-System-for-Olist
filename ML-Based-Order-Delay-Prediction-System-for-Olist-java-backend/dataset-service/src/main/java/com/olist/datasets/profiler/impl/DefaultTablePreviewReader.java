package com.olist.datasets.profiler.impl;

import com.olist.datasets.enums.DatasetFormat;
import com.olist.datasets.exception.InvalidDatasetException;
import com.olist.datasets.profiler.TablePreview;
import com.olist.datasets.profiler.TablePreviewOptions;
import com.olist.datasets.profiler.TablePreviewReader;
import com.olist.datasets.profiler.TableRowSearch;
import com.olist.datasets.profiler.TableRows;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
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
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

@Component
public class DefaultTablePreviewReader implements TablePreviewReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTablePreviewReader.class);
    private static final int MAX_CELL_LENGTH = 320;

    @Override
    public TablePreview read(
            String datasetFilename,
            String sourceFile,
            String tableName,
            DatasetFormat tableFormat,
            InputStream input,
            TablePreviewOptions options) {
        DatasetFormat container = DatasetFormat.fromFilename(datasetFilename)
                .orElseThrow(() -> new InvalidDatasetException("无法识别数据文件格式"));
        DatasetFormat format = resolveFormat(sourceFile, tableFormat);
        try {
            if (container == DatasetFormat.ZIP) {
                return readFromZip(input, sourceFile, tableName, format, options);
            }
            if (format == DatasetFormat.XLSX) {
                return readXlsx(input, tableName, options);
            }
            return readDelimited(input, delimiterOf(format), options);
        } catch (InvalidDatasetException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("Failed to read preview for table {}", tableName, exception);
            throw new InvalidDatasetException("读取数据表预览失败，请确认文件内容完整", exception);
        }
    }

    @Override
    public TableRows readRows(
            String datasetFilename,
            String sourceFile,
            String tableName,
            DatasetFormat tableFormat,
            InputStream input,
            int offset,
            int limit) {
        DatasetFormat container = DatasetFormat.fromFilename(datasetFilename)
                .orElseThrow(() -> new InvalidDatasetException("无法识别数据文件格式"));
        DatasetFormat format = resolveFormat(sourceFile, tableFormat);
        try {
            if (container == DatasetFormat.ZIP) {
                return readRowsFromZip(input, sourceFile, tableName, format, offset, limit);
            }
            if (format == DatasetFormat.XLSX) {
                return readRowsXlsx(input, tableName, offset, limit);
            }
            return readRowsDelimited(input, delimiterOf(format), offset, limit);
        } catch (InvalidDatasetException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("Failed to read rows for table {}", tableName, exception);
            throw new InvalidDatasetException("读取数据表行失败，请确认文件内容完整", exception);
        }
    }

    @Override
    public TableRowSearch searchRows(
            String datasetFilename,
            String sourceFile,
            String tableName,
            DatasetFormat tableFormat,
            InputStream input,
            String keyword,
            int columnIndex,
            long offset,
            int limit,
            long maxScannedRows) {
        DatasetFormat container = DatasetFormat.fromFilename(datasetFilename)
                .orElseThrow(() -> new InvalidDatasetException("无法识别数据文件格式"));
        DatasetFormat format = resolveFormat(sourceFile, tableFormat);
        try {
            if (container == DatasetFormat.ZIP) {
                return searchRowsFromZip(
                        input, sourceFile, tableName, format, keyword, columnIndex, offset, limit, maxScannedRows);
            }
            if (format == DatasetFormat.XLSX) {
                return searchRowsXlsx(input, tableName, keyword, columnIndex, offset, limit, maxScannedRows);
            }
            return searchRowsDelimited(
                    input, delimiterOf(format), keyword, columnIndex, offset, limit, maxScannedRows);
        } catch (InvalidDatasetException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("Failed to search rows for table {}", tableName, exception);
            throw new InvalidDatasetException("搜索数据表失败，请确认文件内容完整", exception);
        }
    }

    private TablePreview readFromZip(
            InputStream input,
            String sourceFile,
            String tableName,
            DatasetFormat format,
            TablePreviewOptions options) throws Exception {
        String target = normalizeEntryName(sourceFile);
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = normalizeEntryName(entry.getName());
                if (!entryName.equals(target)) {
                    continue;
                }
                if (format == DatasetFormat.XLSX) {
                    return readXlsx(zip, sheetNameOf(tableName), options);
                }
                return readDelimited(zip, delimiterOf(format), options);
            }
        }
        throw new InvalidDatasetException("压缩包中找不到数据表来源文件 " + sourceFile);
    }

    private TableRows readRowsFromZip(
            InputStream input,
            String sourceFile,
            String tableName,
            DatasetFormat format,
            int offset,
            int limit) throws Exception {
        String target = normalizeEntryName(sourceFile);
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = normalizeEntryName(entry.getName());
                if (!entryName.equals(target)) {
                    continue;
                }
                if (format == DatasetFormat.XLSX) {
                    return readRowsXlsx(zip, sheetNameOf(tableName), offset, limit);
                }
                return readRowsDelimited(zip, delimiterOf(format), offset, limit);
            }
        }
        throw new InvalidDatasetException("压缩包中找不到数据表来源文件 " + sourceFile);
    }

    private TableRowSearch searchRowsFromZip(
            InputStream input,
            String sourceFile,
            String tableName,
            DatasetFormat format,
            String keyword,
            int columnIndex,
            long offset,
            int limit,
            long maxScannedRows) throws Exception {
        String target = normalizeEntryName(sourceFile);
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = normalizeEntryName(entry.getName());
                if (!entryName.equals(target)) {
                    continue;
                }
                if (format == DatasetFormat.XLSX) {
                    return searchRowsXlsx(
                            zip, sheetNameOf(tableName), keyword, columnIndex, offset, limit, maxScannedRows);
                }
                return searchRowsDelimited(
                        zip, delimiterOf(format), keyword, columnIndex, offset, limit, maxScannedRows);
            }
        }
        throw new InvalidDatasetException("压缩包中找不到数据表来源文件 " + sourceFile);
    }

    private TablePreview readDelimited(
            InputStream input, char delimiter, TablePreviewOptions options) throws IOException {
        try (PushbackReader reader = readerOf(input);
                CSVParser parser = new CSVParser(reader, csvFormatOf(delimiter))) {
            List<String> columns = columnsOf(parser);
            if (options.all()) {
                return readAllDelimited(parser, columns, options);
            }
            return readHeadAndTailDelimited(parser, columns, options);
        }
    }

    private TableRows readRowsDelimited(
            InputStream input, char delimiter, int offset, int limit) throws IOException {
        try (PushbackReader reader = readerOf(input);
                CSVParser parser = new CSVParser(reader, csvFormatOf(delimiter))) {
            List<String> columns = columnsOf(parser);
            List<List<String>> rows = new ArrayList<>();
            long index = 0;
            for (CSVRecord record : parser) {
                if (index >= offset && rows.size() < limit) {
                    rows.add(rowOf(record, columns.size()));
                    if (rows.size() >= limit) {
                        break;
                    }
                }
                index++;
            }
            return new TableRows(columns, rows);
        }
    }

    private TableRowSearch searchRowsDelimited(
            InputStream input,
            char delimiter,
            String keyword,
            int columnIndex,
            long offset,
            int limit,
            long maxScannedRows) throws IOException {
        try (PushbackReader reader = readerOf(input);
                CSVParser parser = new CSVParser(reader, csvFormatOf(delimiter))) {
            List<String> columns = columnsOf(parser);
            SearchAccumulator accumulator = new SearchAccumulator(
                    columns, keyword, columnIndex, offset, limit, maxScannedRows);
            for (CSVRecord record : parser) {
                List<String> values = new ArrayList<>(columns.size());
                for (int index = 0; index < columns.size(); index++) {
                    values.add(index < record.size() ? record.get(index) : "");
                }
                if (!accumulator.add(values)) {
                    break;
                }
            }
            return accumulator.toSearch();
        }
    }

    private TablePreview readAllDelimited(
            CSVParser parser, List<String> columns, TablePreviewOptions options) {
        int maxRows = Math.max(options.maxRows(), 1);
        List<List<String>> rows = new ArrayList<>();
        for (CSVRecord record : parser) {
            rows.add(rowOf(record, columns.size()));
            if (rows.size() >= maxRows) {
                break;
            }
        }
        long total = options.knownRowCount() > 0 ? options.knownRowCount() : rows.size();
        return new TablePreview(columns, rows, List.of(), total, total > rows.size());
    }

    private TablePreview readHeadAndTailDelimited(
            CSVParser parser, List<String> columns, TablePreviewOptions options) {
        int headLimit = Math.max(options.headLimit(), 1);
        int tailLimit = Math.max(options.tailLimit(), 0);
        List<List<String>> head = new ArrayList<>();
        Deque<List<String>> tail = new ArrayDeque<>();
        long total = 0;
        for (CSVRecord record : parser) {
            List<String> row = rowOf(record, columns.size());
            total++;
            if (head.size() < headLimit) {
                head.add(row);
            }
            if (tailLimit > 0) {
                tail.addLast(row);
                if (tail.size() > tailLimit) {
                    tail.removeFirst();
                }
            }
        }
        return buildPreview(columns, head, tail, total, headLimit, tailLimit);
    }

    private TablePreview readXlsx(
            InputStream input, String sheetName, TablePreviewOptions options) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(input)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheetStream = sheets.next()) {
                    if (!sheets.getSheetName().equals(sheetName)) {
                        continue;
                    }
                    PreviewSheetHandler handler = new PreviewSheetHandler(options);
                    parseSheet(sheetStream, styles, sharedStrings, handler);
                    return handler.toPreview();
                }
            }
        }
        throw new InvalidDatasetException("Excel 文件中找不到工作表 " + sheetName);
    }

    private TableRows readRowsXlsx(
            InputStream input, String sheetName, int offset, int limit) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(input)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheetStream = sheets.next()) {
                    if (!sheets.getSheetName().equals(sheetName)) {
                        continue;
                    }
                    RowsWindowHandler handler = new RowsWindowHandler(offset, limit);
                    parseSheet(sheetStream, styles, sharedStrings, handler);
                    return handler.toRows();
                }
            }
        }
        throw new InvalidDatasetException("Excel 文件中找不到工作表 " + sheetName);
    }

    private TableRowSearch searchRowsXlsx(
            InputStream input,
            String sheetName,
            String keyword,
            int columnIndex,
            long offset,
            int limit,
            long maxScannedRows) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(input)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheetStream = sheets.next()) {
                    if (!sheets.getSheetName().equals(sheetName)) {
                        continue;
                    }
                    SearchSheetHandler handler = new SearchSheetHandler(
                            keyword, columnIndex, offset, limit, maxScannedRows);
                    parseSheet(sheetStream, styles, sharedStrings, handler);
                    return handler.toSearch();
                }
            }
        }
        throw new InvalidDatasetException("Excel 文件中找不到工作表 " + sheetName);
    }

    private void parseSheet(
            InputStream sheetStream,
            StylesTable styles,
            ReadOnlySharedStringsTable sharedStrings,
            XSSFSheetXMLHandler.SheetContentsHandler handler) throws Exception {
        XMLReader parser = newXmlReader();
        parser.setContentHandler(new XSSFSheetXMLHandler(
                styles, sharedStrings, handler, new DataFormatter(), false));
        try {
            parser.parse(new InputSource(sheetStream));
        } catch (PreviewCompleteException ignored) {
            // The requested row window has been fully read.
        }
    }

    private XMLReader newXmlReader() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newSAXParser().getXMLReader();
    }

    private static TablePreview buildPreview(
            List<String> columns,
            List<List<String>> head,
            Deque<List<String>> tail,
            long total,
            int headLimit,
            int tailLimit) {
        if (total <= headLimit) {
            return new TablePreview(columns, head, List.of(), total, false);
        }
        if (total <= (long) headLimit + tailLimit) {
            List<List<String>> all = new ArrayList<>(head);
            List<List<String>> tailRows = new ArrayList<>(tail);
            int remaining = (int) (total - all.size());
            int start = Math.max(tailRows.size() - remaining, 0);
            all.addAll(tailRows.subList(start, tailRows.size()));
            return new TablePreview(columns, all, List.of(), total, false);
        }
        return new TablePreview(columns, head, new ArrayList<>(tail), total, true);
    }

    private PushbackReader readerOf(InputStream input) throws IOException {
        PushbackReader reader = new PushbackReader(
                new InputStreamReader(input, StandardCharsets.UTF_8), 1);
        int first = reader.read();
        if (first != -1 && first != '\uFEFF') {
            reader.unread(first);
        }
        return reader;
    }

    private CSVFormat csvFormatOf(char delimiter) {
        return CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
    }

    private List<String> columnsOf(CSVParser parser) {
        List<String> columns = parser.getHeaderNames().stream()
                .map(DefaultTablePreviewReader::sanitize)
                .toList();
        if (columns.isEmpty()) {
            throw new InvalidDatasetException("数据表缺少表头");
        }
        return columns;
    }

    private List<String> rowOf(CSVRecord record, int columns) {
        List<String> row = new ArrayList<>(columns);
        for (int index = 0; index < columns; index++) {
            row.add(index < record.size() ? sanitize(record.get(index)) : "");
        }
        return row;
    }

    private DatasetFormat resolveFormat(String sourceFile, DatasetFormat tableFormat) {
        return tableFormat == null
                ? DatasetFormat.fromFilename(sourceFile)
                        .orElseThrow(() -> new InvalidDatasetException("无法识别数据表格式"))
                : tableFormat;
    }

    private char delimiterOf(DatasetFormat format) {
        return format == DatasetFormat.TSV ? '\t' : ',';
    }

    private String sheetNameOf(String tableName) {
        int separator = tableName.lastIndexOf(" / ");
        return separator >= 0 ? tableName.substring(separator + 3) : tableName;
    }

    private String normalizeEntryName(String name) {
        return name == null ? "" : name.replace('\\', '/');
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() > MAX_CELL_LENGTH
                ? normalized.substring(0, MAX_CELL_LENGTH)
                : normalized;
    }

    private static List<String> normalizeRow(List<String> row, int columnCount) {
        List<String> normalized = new ArrayList<>(columnCount);
        for (int index = 0; index < columnCount; index++) {
            normalized.add(index < row.size() ? row.get(index) : "");
        }
        return normalized;
    }

    private static void trimTrailingBlanks(List<String> row) {
        for (int index = row.size() - 1; index >= 0; index--) {
            if (!row.get(index).isEmpty()) {
                return;
            }
            row.remove(index);
        }
    }

    private static int columnIndex(String reference) {
        if (reference == null) {
            return 0;
        }
        int index = 0;
        for (int position = 0; position < reference.length(); position++) {
            char character = reference.charAt(position);
            if (character >= 'A' && character <= 'Z') {
                index = index * 26 + (character - 'A' + 1);
            } else if (character >= 'a' && character <= 'z') {
                index = index * 26 + (character - 'a' + 1);
            } else {
                break;
            }
        }
        return Math.max(index - 1, 0);
    }

    private static final class PreviewSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final TablePreviewOptions options;
        private final RowAccumulator accumulator;
        private final List<String> columns = new ArrayList<>();
        private List<String> currentRow = new ArrayList<>();
        private boolean headerRead;

        private PreviewSheetHandler(TablePreviewOptions options) {
            this.options = options;
            this.accumulator = new RowAccumulator(options);
        }

        @Override
        public void startRow(int rowNum) {
            currentRow = new ArrayList<>();
        }

        @Override
        public void endRow(int rowNum) {
            trimTrailingBlanks(currentRow);
            if (currentRow.isEmpty()) {
                return;
            }
            if (!headerRead) {
                columns.addAll(currentRow);
                headerRead = true;
                return;
            }
            accumulator.add(normalizeRow(currentRow, columns.size()));
            if (accumulator.shouldStop()) {
                throw new PreviewCompleteException();
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int index = columnIndex(cellReference);
            while (currentRow.size() <= index) {
                currentRow.add("");
            }
            currentRow.set(index, sanitize(formattedValue));
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Header and footer rows are not part of the table.
        }

        private TablePreview toPreview() {
            if (!headerRead || columns.isEmpty()) {
                throw new InvalidDatasetException("工作表中没有表头");
            }
            return accumulator.toPreview(columns);
        }
    }

    private static final class RowsWindowHandler implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final int offset;
        private final int limit;
        private final List<String> columns = new ArrayList<>();
        private final List<List<String>> rows = new ArrayList<>();
        private List<String> currentRow = new ArrayList<>();
        private long dataIndex;
        private boolean headerRead;

        private RowsWindowHandler(int offset, int limit) {
            this.offset = offset;
            this.limit = limit;
        }

        @Override
        public void startRow(int rowNum) {
            currentRow = new ArrayList<>();
        }

        @Override
        public void endRow(int rowNum) {
            trimTrailingBlanks(currentRow);
            if (currentRow.isEmpty()) {
                return;
            }
            if (!headerRead) {
                columns.addAll(currentRow);
                headerRead = true;
                return;
            }
            if (dataIndex >= offset) {
                rows.add(normalizeRow(currentRow, columns.size()));
                if (rows.size() >= limit) {
                    throw new PreviewCompleteException();
                }
            }
            dataIndex++;
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int index = columnIndex(cellReference);
            while (currentRow.size() <= index) {
                currentRow.add("");
            }
            currentRow.set(index, sanitize(formattedValue));
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Header and footer rows are not part of the table.
        }

        private TableRows toRows() {
            if (!headerRead || columns.isEmpty()) {
                throw new InvalidDatasetException("工作表中没有表头");
            }
            return new TableRows(columns, rows);
        }
    }

    private static final class SearchSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final String keyword;
        private final int columnIndex;
        private final long offset;
        private final int limit;
        private final long maxScannedRows;
        private final List<String> columns = new ArrayList<>();
        private List<String> currentRow = new ArrayList<>();
        private SearchAccumulator accumulator;
        private boolean headerRead;

        private SearchSheetHandler(
                String keyword, int columnIndex, long offset, int limit, long maxScannedRows) {
            this.keyword = keyword;
            this.columnIndex = columnIndex;
            this.offset = offset;
            this.limit = limit;
            this.maxScannedRows = maxScannedRows;
        }

        @Override
        public void startRow(int rowNum) {
            currentRow = new ArrayList<>();
        }

        @Override
        public void endRow(int rowNum) {
            trimTrailingBlanks(currentRow);
            if (currentRow.isEmpty()) {
                return;
            }
            if (!headerRead) {
                columns.addAll(currentRow.stream().map(DefaultTablePreviewReader::sanitize).toList());
                headerRead = true;
                accumulator = new SearchAccumulator(
                        columns, keyword, columnIndex, offset, limit, maxScannedRows);
                return;
            }
            if (!accumulator.add(normalizeRow(currentRow, columns.size()))) {
                throw new PreviewCompleteException();
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int index = columnIndex(cellReference);
            while (currentRow.size() <= index) {
                currentRow.add("");
            }
            currentRow.set(index, formattedValue == null ? "" : formattedValue.trim());
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Header and footer rows are not part of the table.
        }

        private TableRowSearch toSearch() {
            if (!headerRead || columns.isEmpty()) {
                throw new InvalidDatasetException("工作表中没有表头");
            }
            return accumulator.toSearch();
        }
    }

    private static final class SearchAccumulator {

        private final List<String> columns;
        private final String keyword;
        private final int columnIndex;
        private final long offset;
        private final int limit;
        private final long maxScannedRows;
        private final List<TableRowSearch.Match> rows = new ArrayList<>();
        private long totalMatches;
        private long scannedRows;
        private boolean truncated;

        private SearchAccumulator(
                List<String> columns,
                String keyword,
                int columnIndex,
                long offset,
                int limit,
                long maxScannedRows) {
            this.columns = List.copyOf(columns);
            this.keyword = keyword.toLowerCase(Locale.ROOT);
            this.columnIndex = columnIndex;
            this.offset = Math.max(offset, 0);
            this.limit = Math.max(limit, 1);
            this.maxScannedRows = Math.max(maxScannedRows, 1);
            if (columnIndex < -1 || columnIndex >= columns.size()) {
                throw new InvalidDatasetException("指定搜索列不存在");
            }
        }

        private boolean add(List<String> values) {
            if (scannedRows >= maxScannedRows) {
                truncated = true;
                return false;
            }
            scannedRows++;
            if (!matches(values)) {
                return true;
            }

            long matchIndex = totalMatches++;
            if (matchIndex >= offset && rows.size() < limit) {
                rows.add(new TableRowSearch.Match(
                        scannedRows,
                        values.stream().map(DefaultTablePreviewReader::sanitize).toList()));
            }
            return true;
        }

        private boolean matches(List<String> values) {
            if (columnIndex >= 0) {
                return contains(values.get(columnIndex));
            }
            return values.stream().anyMatch(this::contains);
        }

        private boolean contains(String value) {
            return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
        }

        private TableRowSearch toSearch() {
            return new TableRowSearch(columns, rows, totalMatches, scannedRows, truncated);
        }
    }

    private static final class RowAccumulator {

        private final TablePreviewOptions options;
        private final List<List<String>> head = new ArrayList<>();
        private final Deque<List<String>> tail = new ArrayDeque<>();
        private long collected;

        private RowAccumulator(TablePreviewOptions options) {
            this.options = options;
        }

        private void add(List<String> row) {
            collected++;
            if (options.all()) {
                if (head.size() < Math.max(options.maxRows(), 1)) {
                    head.add(row);
                }
                return;
            }
            int headLimit = Math.max(options.headLimit(), 1);
            if (head.size() < headLimit) {
                head.add(row);
            }
            int tailLimit = Math.max(options.tailLimit(), 0);
            if (tailLimit > 0) {
                tail.addLast(row);
                if (tail.size() > tailLimit) {
                    tail.removeFirst();
                }
            }
        }

        private boolean shouldStop() {
            return options.all() && head.size() >= Math.max(options.maxRows(), 1);
        }

        private TablePreview toPreview(List<String> columns) {
            if (options.all()) {
                long total = options.knownRowCount() > 0 ? options.knownRowCount() : collected;
                return new TablePreview(columns, head, List.of(), total, total > head.size());
            }
            return buildPreview(
                    columns,
                    head,
                    tail,
                    collected,
                    Math.max(options.headLimit(), 1),
                    Math.max(options.tailLimit(), 0));
        }
    }

    private static final class PreviewCompleteException extends RuntimeException {

        private PreviewCompleteException() {
            super("preview complete");
        }
    }
}
