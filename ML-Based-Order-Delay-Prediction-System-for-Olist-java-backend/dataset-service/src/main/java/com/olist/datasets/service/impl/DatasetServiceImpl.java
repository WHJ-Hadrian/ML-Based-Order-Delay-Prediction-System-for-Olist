package com.olist.datasets.service.impl;

import com.olist.datasets.convert.DatasetConverter;
import com.olist.datasets.dto.DatasetCreateRequest;
import com.olist.datasets.dto.DatasetQuery;
import com.olist.datasets.dto.DatasetTableUpdateRequest;
import com.olist.datasets.dto.DatasetUpdateRequest;
import com.olist.datasets.dto.DatasetUploadRequest;
import com.olist.datasets.entity.DatasetFile;
import com.olist.datasets.entity.DatasetTable;
import com.olist.datasets.entity.DatasetVersion;
import com.olist.datasets.enums.DatasetFormat;
import com.olist.datasets.enums.DatasetStatus;
import com.olist.datasets.exception.DatasetNotFoundException;
import com.olist.datasets.exception.DatasetTableNotFoundException;
import com.olist.datasets.exception.InvalidDatasetException;
import com.olist.datasets.exception.StorageException;
import com.olist.datasets.mapper.DatasetFileMapper;
import com.olist.datasets.mapper.DatasetTableMapper;
import com.olist.datasets.mapper.DatasetVersionMapper;
import com.olist.datasets.profiler.DatasetProfile;
import com.olist.datasets.profiler.DatasetProfiler;
import com.olist.datasets.profiler.TablePreview;
import com.olist.datasets.profiler.TablePreviewOptions;
import com.olist.datasets.profiler.TablePreviewReader;
import com.olist.datasets.profiler.TableRows;
import com.olist.datasets.profiler.TableProfile;
import com.olist.datasets.service.DatasetService;
import com.olist.datasets.storage.DatasetStorage;
import com.olist.datasets.vo.DatasetDetailVO;
import com.olist.datasets.vo.DatasetVO;
import com.olist.datasets.vo.PageVO;
import com.olist.datasets.vo.TablePreviewVO;
import com.olist.datasets.vo.TableRowsVO;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DatasetServiceImpl implements DatasetService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetServiceImpl.class);
    private static final long MAX_FILE_SIZE_BYTES = 1024L * 1024L * 1024L;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_FILENAME_LENGTH = 200;
    private static final int MAX_TABLE_NAME_LENGTH = 512;
    private static final int MAX_PREVIEW_ROWS = 50;
    private static final int MAX_EXPANDED_ROWS = 2000;
    private static final int MAX_ROW_PAGE_SIZE = 500;
    private static final String DEFAULT_NAME_PREFIX = "数据集";
    private static final Pattern DEFAULT_NAME_PATTERN = Pattern.compile("^数据集(\\d+)$");

    private final DatasetVersionMapper datasetVersionMapper;
    private final DatasetTableMapper datasetTableMapper;
    private final DatasetFileMapper datasetFileMapper;
    private final DatasetProfiler datasetProfiler;
    private final DatasetStorage datasetStorage;
    private final TablePreviewReader tablePreviewReader;

    public DatasetServiceImpl(
            DatasetVersionMapper datasetVersionMapper,
            DatasetTableMapper datasetTableMapper,
            DatasetFileMapper datasetFileMapper,
            DatasetProfiler datasetProfiler,
            DatasetStorage datasetStorage,
            TablePreviewReader tablePreviewReader) {
        this.datasetVersionMapper = datasetVersionMapper;
        this.datasetTableMapper = datasetTableMapper;
        this.datasetFileMapper = datasetFileMapper;
        this.datasetProfiler = datasetProfiler;
        this.datasetStorage = datasetStorage;
        this.tablePreviewReader = tablePreviewReader;
    }

    @Override
    @Transactional
    public DatasetVO create(DatasetCreateRequest request) {
        String name = request == null ? null : request.getName();
        String description = request == null ? null : request.getDescription();
        return DatasetConverter.toVO(createEmptyDataset(name, description));
    }

    @Override
    @Transactional
    public DatasetVO rename(UUID id, DatasetUpdateRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new InvalidDatasetException("数据集名称不能为空");
        }
        DatasetVersion entity = requireDatasetForUpdate(id);
        String name = normalizeName(request.getName());
        entity.setName(name);
        if (request.getDescription() != null) {
            entity.setDescription(normalizeDescription(request.getDescription()));
        }
        entity.setUpdatedAt(Instant.now());
        ensureUpdated(entity);
        return DatasetConverter.toVO(entity);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        DatasetVersion entity = requireDatasetForUpdate(id);
        Set<String> storageKeys = new LinkedHashSet<>();
        if (entity.getStorageKey() != null && !entity.getStorageKey().isBlank()) {
            storageKeys.add(entity.getStorageKey());
        }
        for (DatasetFile file : datasetFileMapper.findByDatasetId(id)) {
            if (file.getStorageKey() != null && !file.getStorageKey().isBlank()) {
                storageKeys.add(file.getStorageKey());
            }
        }
        if (datasetVersionMapper.deleteById(id) != 1) {
            throw new StorageException("数据集删除失败");
        }
        deleteAfterCommit(storageKeys);
    }

    @Override
    @Transactional
    public DatasetDetailVO renameTable(
            UUID datasetId, UUID tableId, DatasetTableUpdateRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new InvalidDatasetException("数据表名称不能为空");
        }
        String name = normalizeTableName(request.getName());
        DatasetVersion entity = requireDatasetForUpdate(datasetId);
        requireTable(datasetId, tableId);
        if (datasetTableMapper.updateName(tableId, datasetId, name) != 1) {
            throw new StorageException("数据表名称更新失败");
        }
        entity.setUpdatedAt(Instant.now());
        ensureUpdated(entity);
        return toDetail(entity);
    }

    @Override
    @Transactional
    public DatasetDetailVO deleteTable(UUID datasetId, UUID tableId) {
        DatasetVersion entity = requireDatasetForUpdate(datasetId);
        requireTable(datasetId, tableId);
        if (datasetTableMapper.deleteByIdAndDatasetId(tableId, datasetId) != 1) {
            throw new StorageException("数据表删除失败");
        }

        List<DatasetFile> remainingFiles = new ArrayList<>(datasetFileMapper.findByDatasetId(datasetId));
        for (DatasetFile file : new ArrayList<>(remainingFiles)) {
            if (datasetTableMapper.countByFileId(file.getId()) > 0) {
                continue;
            }
            if (datasetFileMapper.deleteByIdAndDatasetId(file.getId(), datasetId) == 1) {
                if (file.getStorageKey() != null && !file.getStorageKey().isBlank()) {
                    deleteAfterCommit(Set.of(file.getStorageKey()));
                }
                remainingFiles.remove(file);
            }
        }

        List<DatasetTable> remainingTables = datasetTableMapper.findByDatasetId(datasetId);
        long rowCount = remainingTables.stream().mapToLong(DatasetTable::getRowCount).sum();
        entity.setTableCount(remainingTables.size());
        entity.setRowCount(rowCount);
        entity.setFileCount(remainingFiles.size());
        entity.setStatus(remainingTables.isEmpty() ? DatasetStatus.EMPTY : DatasetStatus.UPLOADED);
        if (remainingFiles.isEmpty()) {
            entity.setOriginalFilename("");
            entity.setStorageKey("");
            entity.setSizeBytes(0L);
            entity.setSha256("");
            entity.setContentType(null);
        } else {
            DatasetFile primary = remainingFiles.get(0);
            entity.setOriginalFilename(primary.getOriginalFilename());
            entity.setStorageKey(primary.getStorageKey());
            entity.setSizeBytes(primary.getSizeBytes());
            entity.setSha256(primary.getSha256());
            entity.setContentType(primary.getContentType());
        }
        entity.setUpdatedAt(Instant.now());
        ensureUpdated(entity);
        return toDetail(entity);
    }

    @Override
    @Transactional
    public DatasetDetailVO addFile(UUID id, MultipartFile file) {
        validate(file);
        DatasetVersion entity = requireDatasetForUpdate(id);
        return appendFile(entity, file);
    }

    @Override
    @Transactional
    public DatasetVO upload(DatasetUploadRequest request) {
        if (request == null) {
            throw new InvalidDatasetException("上传请求不能为空");
        }
        MultipartFile file = request.getFile();
        validate(file);
        String name = request.getName();
        if (name == null || name.isBlank()) {
            name = baseName(normalizeFilename(file.getOriginalFilename()));
        }
        DatasetVersion entity = createEmptyDataset(name, request.getDescription());
        appendFile(entity, file);
        return DatasetConverter.toVO(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public DatasetDetailVO get(UUID id) {
        DatasetVersion entity = datasetVersionMapper.findById(id);
        if (entity == null) {
            throw new DatasetNotFoundException(id);
        }
        return toDetail(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public TablePreviewVO previewTable(UUID datasetId, UUID tableId, int limit, boolean all) {
        DatasetTable table = datasetTableMapper.findByIdAndDatasetId(tableId, datasetId);
        if (table == null) {
            throw new DatasetTableNotFoundException(datasetId, tableId);
        }
        DatasetFile file = datasetFileMapper.findById(table.getFileId());
        if (file == null) {
            throw new DatasetTableNotFoundException(datasetId, tableId);
        }
        int effectiveLimit;
        TablePreviewOptions options;
        if (all) {
            effectiveLimit = Math.min(Math.max(limit, 1), MAX_EXPANDED_ROWS);
            options = new TablePreviewOptions(
                    0, 0, true, effectiveLimit, table.getRowCount());
        } else {
            effectiveLimit = Math.min(Math.max(limit, 1), MAX_PREVIEW_ROWS);
            options = new TablePreviewOptions(
                    effectiveLimit, effectiveLimit, false, 0, table.getRowCount());
        }
        try (InputStream input = datasetStorage.open(file.getStorageKey())) {
            TablePreview preview = tablePreviewReader.read(
                    file.getOriginalFilename(),
                    table.getFileName(),
                    table.getTableName(),
                    table.getTableFormat(),
                    input,
                    options);
            return new TablePreviewVO(
                    table.getId(),
                    table.getTableName(),
                    effectiveLimit,
                    preview.columns(),
                    preview.rows(),
                    preview.tailRows(),
                    preview.totalRows(),
                    preview.truncated());
        } catch (IOException exception) {
            throw new StorageException("读取数据表预览失败", exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TableRowsVO rows(UUID datasetId, UUID tableId, int offset, int limit) {
        DatasetTable table = datasetTableMapper.findByIdAndDatasetId(tableId, datasetId);
        if (table == null) {
            throw new DatasetTableNotFoundException(datasetId, tableId);
        }
        DatasetFile file = datasetFileMapper.findById(table.getFileId());
        if (file == null) {
            throw new DatasetTableNotFoundException(datasetId, tableId);
        }
        int effectiveOffset = Math.max(offset, 0);
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_ROW_PAGE_SIZE);
        try (InputStream input = datasetStorage.open(file.getStorageKey())) {
            TableRows page = tablePreviewReader.readRows(
                    file.getOriginalFilename(),
                    table.getFileName(),
                    table.getTableName(),
                    table.getTableFormat(),
                    input,
                    effectiveOffset,
                    effectiveLimit);
            return new TableRowsVO(
                    table.getId(),
                    table.getTableName(),
                    effectiveOffset,
                    effectiveLimit,
                    table.getRowCount(),
                    page.columns(),
                    page.rows());
        } catch (IOException exception) {
            throw new StorageException("读取数据表行失败", exception);
        }
    }
    @Override
    @Transactional(readOnly = true)
    public PageVO<DatasetVO> list(DatasetQuery query) {
        DatasetQuery effectiveQuery = query == null ? new DatasetQuery() : query;
        int page = Math.max(effectiveQuery.getPage(), 0);
        int size = Math.min(Math.max(effectiveQuery.getSize(), MIN_PAGE_SIZE), MAX_PAGE_SIZE);
        long offset = (long) page * size;
        String keywordPattern = keywordPattern(effectiveQuery.getKeyword());
        String formatPattern = formatPattern(effectiveQuery.getFormat());
        List<DatasetVersion> entities = datasetVersionMapper.findPage(
                effectiveQuery.getStatus(),
                keywordPattern,
                formatPattern,
                effectiveQuery.getHasData(),
                offset,
                size);
        long total = datasetVersionMapper.count(
                effectiveQuery.getStatus(), keywordPattern, formatPattern, effectiveQuery.getHasData());
        List<DatasetVO> items = entities.stream().map(DatasetConverter::toVO).toList();
        return PageVO.of(items, page, size, total);
    }

    private DatasetVersion createEmptyDataset(String name, String description) {
        Instant now = Instant.now();
        DatasetVersion entity = new DatasetVersion(
                UUID.randomUUID(),
                resolveCreateName(name),
                normalizeDescription(description),
                "",
                "",
                0L,
                "",
                null,
                DatasetStatus.EMPTY,
                0,
                0L,
                0,
                now,
                now);
        int inserted = datasetVersionMapper.insert(entity);
        if (inserted != 1) {
            throw new StorageException("数据集元数据保存失败");
        }
        return entity;
    }

    private DatasetDetailVO appendFile(DatasetVersion entity, MultipartFile file) {
        UUID datasetId = entity.getId();
        UUID fileId = UUID.randomUUID();
        String originalFilename = normalizeFilename(file.getOriginalFilename());
        DatasetProfile profile = datasetProfiler.profile(file, originalFilename);
        String storageKey = "datasets/" + datasetId + "/files/" + fileId + "/" + originalFilename;
        String sha256 = store(storageKey, file);
        boolean cleanupOnRollback = deleteAfterRollback(storageKey);
        Instant now = Instant.now();

        try {
            DatasetFile datasetFile = new DatasetFile(
                    fileId,
                    datasetId,
                    originalFilename,
                    storageKey,
                    file.getSize(),
                    sha256,
                    file.getContentType(),
                    now);
            if (datasetFileMapper.insert(datasetFile) != 1) {
                throw new StorageException("文件元数据保存失败");
            }

            if (!profile.tables().isEmpty()) {
                List<DatasetTable> tables = toTables(
                        datasetId, fileId, originalFilename, profile.tables(), entity.getTableCount());
                datasetTableMapper.insertBatch(tables);
            }

            if (entity.getFileCount() == 0) {
                entity.setOriginalFilename(originalFilename);
                entity.setStorageKey(storageKey);
                entity.setSizeBytes(file.getSize());
                entity.setSha256(sha256);
                entity.setContentType(file.getContentType());
            }
            entity.setStatus(DatasetStatus.UPLOADED);
            entity.setTableCount(entity.getTableCount() + profile.tableCount());
            entity.setRowCount(entity.getRowCount() + profile.rowCount());
            entity.setFileCount(entity.getFileCount() + 1);
            entity.setUpdatedAt(now);
            ensureUpdated(entity);
            return toDetail(entity);
        } catch (RuntimeException exception) {
            if (!cleanupOnRollback) {
                deleteQuietly(storageKey);
            }
            throw exception;
        }
    }

    private DatasetDetailVO toDetail(DatasetVersion entity) {
        return DatasetConverter.toDetailVO(
                entity,
                datasetFileMapper.findByDatasetId(entity.getId()),
                datasetTableMapper.findByDatasetId(entity.getId()));
    }

    private DatasetVersion requireDatasetForUpdate(UUID id) {
        DatasetVersion entity = datasetVersionMapper.findByIdForUpdate(id);
        if (entity == null) {
            throw new DatasetNotFoundException(id);
        }
        return entity;
    }

    private DatasetTable requireTable(UUID datasetId, UUID tableId) {
        DatasetTable table = datasetTableMapper.findByIdAndDatasetId(tableId, datasetId);
        if (table == null) {
            throw new DatasetTableNotFoundException(datasetId, tableId);
        }
        return table;
    }

    private String normalizeTableName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            throw new InvalidDatasetException("数据表名称不能为空");
        }
        if (value.length() > MAX_TABLE_NAME_LENGTH) {
            throw new InvalidDatasetException("数据表名称不能超过 512 个字符");
        }
        return value;
    }

    private void ensureUpdated(DatasetVersion entity) {
        if (datasetVersionMapper.update(entity) != 1) {
            throw new StorageException("数据集元数据更新失败");
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDatasetException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidDatasetException("单个文件不能超过 1GB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new InvalidDatasetException("文件名不能为空");
        }
        DatasetFormat.fromFilename(filename)
                .orElseThrow(() -> new InvalidDatasetException("仅支持 CSV、TSV、ZIP 或 XLSX 文件"));
    }

    private String keywordPattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private String formatPattern(String format) {
        if (format == null || format.isBlank()) {
            return null;
        }
        DatasetFormat resolved = DatasetFormat.fromValue(format)
                .orElseThrow(() -> new InvalidDatasetException("不支持的数据格式筛选"));
        return "%" + resolved.getExtension();
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String resolved = description.trim();
        if (resolved.isEmpty()) {
            return null;
        }
        return resolved.length() > MAX_DESCRIPTION_LENGTH
                ? resolved.substring(0, MAX_DESCRIPTION_LENGTH)
                : resolved;
    }

    private String resolveCreateName(String name) {
        if (name != null && !name.isBlank()) {
            return normalizeName(name);
        }
        int maxSequence = 0;
        for (String existingName : datasetVersionMapper.findNamesStartingWith(DEFAULT_NAME_PREFIX + "%")) {
            Matcher matcher = DEFAULT_NAME_PATTERN.matcher(existingName);
            if (matcher.matches()) {
                try {
                    maxSequence = Math.max(maxSequence, Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    LOGGER.debug("Ignoring oversized default dataset sequence {}", existingName);
                }
            }
        }
        return DEFAULT_NAME_PREFIX + (maxSequence + 1);
    }

    private String normalizeName(String name) {
        String resolved = name == null ? "" : name.trim();
        if (resolved.isEmpty()) {
            throw new InvalidDatasetException("数据集名称不能为空");
        }
        if (resolved.length() > MAX_NAME_LENGTH) {
            throw new InvalidDatasetException("数据集名称不能超过 255 个字符");
        }
        return resolved;
    }

    private List<DatasetTable> toTables(
            UUID datasetId,
            UUID fileId,
            String fileName,
            List<TableProfile> profiles,
            int startOrdinal) {
        List<DatasetTable> tables = new ArrayList<>(profiles.size());
        int ordinal = startOrdinal;
        for (TableProfile profile : profiles) {
            String sourceFile = profile.sourceFile() == null || profile.sourceFile().isBlank()
                    ? fileName
                    : profile.sourceFile();
            tables.add(new DatasetTable(
                    UUID.randomUUID(),
                    datasetId,
                    fileId,
                    sourceFile,
                    profile.name(),
                    profile.format(),
                    profile.rowCount(),
                    profile.columnCount(),
                    ordinal++));
        }
        return tables;
    }

    private String store(String key, MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
                datasetStorage.put(key, input, file.getSize(), file.getContentType());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new InvalidDatasetException("无法读取上传文件", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String normalizeFilename(String originalFilename) {
        String filename = originalFilename == null ? "dataset" : originalFilename.trim();
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (separator >= 0) {
            filename = filename.substring(separator + 1);
        }
        filename = filename.replaceAll("[\\p{Cntrl}]", "_")
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        if (filename.isBlank()) {
            filename = "dataset";
        }
        if (filename.length() > MAX_FILENAME_LENGTH) {
            String extension = extension(filename);
            int baseLength = MAX_FILENAME_LENGTH - extension.length();
            filename = filename.substring(0, Math.max(baseLength, 1)) + extension;
        }
        return filename;
    }

    private String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        String resolved = dot > 0 ? filename.substring(0, dot) : filename;
        return resolved.length() > MAX_NAME_LENGTH ? resolved.substring(0, MAX_NAME_LENGTH) : resolved;
    }

    private String extension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot >= 0 ? lower.substring(dot) : "";
    }

    private void deleteQuietly(String key) {
        try {
            datasetStorage.delete(key);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to clean up dataset object {}", key, exception);
        }
    }

    private void deleteAfterCommit(Set<String> storageKeys) {
        if (storageKeys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            storageKeys.forEach(this::deleteQuietly);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storageKeys.forEach(DatasetServiceImpl.this::deleteQuietly);
            }
        });
    }

    private boolean deleteAfterRollback(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(storageKey);
                }
            }
        });
        return true;
    }
}
