package com.olist.datasets.service.impl;

import com.olist.datasets.config.TableSearchProperties;
import com.olist.datasets.convert.DatasetConverter;
import com.olist.datasets.dto.DatasetCreateRequest;
import com.olist.datasets.dto.DatasetQuery;
import com.olist.datasets.dto.DatasetTableUpdateRequest;
import com.olist.datasets.dto.DatasetUpdateRequest;
import com.olist.datasets.dto.DatasetUploadRequest;
import com.olist.datasets.entity.Dataset;
import com.olist.datasets.entity.DatasetColumn;
import com.olist.datasets.entity.DatasetFieldBinding;
import com.olist.datasets.entity.DatasetFile;
import com.olist.datasets.entity.DatasetTable;
import com.olist.datasets.entity.DatasetVersion;
import com.olist.datasets.entity.StorageBlob;
import com.olist.datasets.enums.BindingStatus;
import com.olist.datasets.enums.DatasetFormat;
import com.olist.datasets.enums.DatasetStatus;
import com.olist.datasets.exception.DatasetNotFoundException;
import com.olist.datasets.exception.DatasetTableNotFoundException;
import com.olist.datasets.exception.DatasetVersionImmutableException;
import com.olist.datasets.exception.InvalidDatasetException;
import com.olist.datasets.exception.StorageException;
import com.olist.datasets.mapper.DatasetMapper;
import com.olist.datasets.mapper.DatasetColumnMapper;
import com.olist.datasets.mapper.DatasetFieldBindingMapper;
import com.olist.datasets.mapper.DatasetFileMapper;
import com.olist.datasets.mapper.DatasetTableMapper;
import com.olist.datasets.mapper.DatasetVersionMapper;
import com.olist.datasets.mapper.StorageBlobMapper;
import com.olist.datasets.profiler.DatasetProfile;
import com.olist.datasets.profiler.DatasetProfiler;
import com.olist.datasets.profiler.ColumnProfile;
import com.olist.datasets.profiler.TablePreview;
import com.olist.datasets.profiler.TablePreviewOptions;
import com.olist.datasets.profiler.TablePreviewReader;
import com.olist.datasets.profiler.TableRowSearch;
import com.olist.datasets.profiler.TableRows;
import com.olist.datasets.profiler.TableProfile;
import com.olist.datasets.reference.DatasetReferenceChecker;
import com.olist.datasets.security.DatasetOwnerProvider;
import com.olist.datasets.service.DatasetService;
import com.olist.datasets.storage.DatasetStorage;
import com.olist.datasets.vo.DatasetDetailVO;
import com.olist.datasets.vo.DatasetReadinessVO;
import com.olist.datasets.vo.DatasetVersionVO;
import com.olist.datasets.vo.DatasetVO;
import com.olist.datasets.vo.PageVO;
import com.olist.datasets.vo.TablePreviewVO;
import com.olist.datasets.vo.TableRowSearchVO;
import com.olist.datasets.vo.TableRowsVO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final int MAX_SEARCH_RESULT_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_KEYWORD_LENGTH = 128;
    private static final String DEFAULT_NAME_PREFIX = "数据集";
    private static final Pattern DEFAULT_NAME_PATTERN = Pattern.compile("^数据集(\\d+)$");

    private final DatasetVersionMapper datasetVersionMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final DatasetTableMapper datasetTableMapper;
    private final DatasetFileMapper datasetFileMapper;
    private final DatasetFieldBindingMapper datasetFieldBindingMapper;
    private final StorageBlobMapper storageBlobMapper;
    private final DatasetReferenceChecker datasetReferenceChecker;
    private final DatasetProfiler datasetProfiler;
    private final DatasetStorage datasetStorage;
    private final TablePreviewReader tablePreviewReader;
    private final DatasetOwnerProvider datasetOwnerProvider;
    private final TableSearchProperties tableSearchProperties;

    public DatasetServiceImpl(
            DatasetVersionMapper datasetVersionMapper,
            DatasetMapper datasetMapper,
            DatasetColumnMapper datasetColumnMapper,
            DatasetTableMapper datasetTableMapper,
            DatasetFileMapper datasetFileMapper,
            DatasetFieldBindingMapper datasetFieldBindingMapper,
            StorageBlobMapper storageBlobMapper,
            DatasetReferenceChecker datasetReferenceChecker,
            DatasetProfiler datasetProfiler,
            DatasetStorage datasetStorage,
            TablePreviewReader tablePreviewReader,
            DatasetOwnerProvider datasetOwnerProvider,
            TableSearchProperties tableSearchProperties) {
        this.datasetVersionMapper = datasetVersionMapper;
        this.datasetMapper = datasetMapper;
        this.datasetColumnMapper = datasetColumnMapper;
        this.datasetTableMapper = datasetTableMapper;
        this.datasetFileMapper = datasetFileMapper;
        this.datasetFieldBindingMapper = datasetFieldBindingMapper;
        this.storageBlobMapper = storageBlobMapper;
        this.datasetReferenceChecker = datasetReferenceChecker;
        this.datasetProfiler = datasetProfiler;
        this.datasetStorage = datasetStorage;
        this.tablePreviewReader = tablePreviewReader;
        this.datasetOwnerProvider = datasetOwnerProvider;
        this.tableSearchProperties = tableSearchProperties;
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
        DatasetVersion entity = requireAnyVersionForUpdate(id);
        String name = normalizeName(request.getName());
        entity.setName(name);
        if (request.getDescription() != null) {
            entity.setDescription(normalizeDescription(request.getDescription()));
        }
        entity.setUpdatedAt(Instant.now());
        ensureUpdated(entity);
        if (entity.getDatasetId() != null
                && datasetMapper.updateMetadata(
                                entity.getDatasetId(),
                                datasetOwnerProvider.currentOwnerId(),
                                entity.getName(),
                                entity.getDescription(),
                                entity.getUpdatedAt())
                        != 1) {
            throw new StorageException("数据集元数据更新失败");
        }
        return DatasetConverter.toVO(entity);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        String ownerId = datasetOwnerProvider.currentOwnerId();
        DatasetVersion probe = resolveAnyVersion(id, ownerId);
        Dataset dataset = datasetMapper.findByIdForUpdate(probe.getDatasetId(), ownerId);
        if (dataset == null) {
            throw new DatasetNotFoundException(id);
        }
        List<DatasetVersion> versions = datasetVersionMapper.findByDatasetId(dataset.getId(), ownerId);
        if (datasetReferenceChecker.isReferenced(
                versions.stream().map(DatasetVersion::getId).toList())) {
            Instant now = Instant.now();
            datasetVersionMapper.archiveByDatasetId(dataset.getId(), ownerId, now, now);
            if (datasetMapper.softDelete(dataset.getId(), ownerId, now, now) != 1) {
                throw new StorageException("数据集归档失败");
            }
            return;
        }
        Map<String, Set<String>> storageKeysByBlob = new LinkedHashMap<>();
        Set<String> legacyStorageKeys = new LinkedHashSet<>();
        for (DatasetVersion version : versions) {
            collectStorageKeys(version, storageKeysByBlob, legacyStorageKeys);
        }

        for (DatasetVersion version : versions) {
            if (datasetVersionMapper.deleteById(version.getId(), ownerId) != 1) {
                throw new StorageException("数据集删除失败");
            }
        }
        datasetMapper.deleteIfNoVersions(dataset.getId(), ownerId);

        Set<String> removableStorageKeys = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : storageKeysByBlob.entrySet()) {
            if (datasetFileMapper.countByBlobSha256(entry.getKey()) == 0) {
                for (String storageKey : entry.getValue()) {
                    if (datasetFileMapper.countByStorageKey(storageKey) == 0) {
                        removableStorageKeys.add(storageKey);
                    }
                }
                storageBlobMapper.deleteIfUnreferenced(entry.getKey());
            }
        }
        for (String storageKey : legacyStorageKeys) {
            if (datasetFileMapper.countByStorageKey(storageKey) == 0) {
                removableStorageKeys.add(storageKey);
            }
        }
        deleteAfterCommit(removableStorageKeys);
    }
    @Override
    @Transactional
    public DatasetDetailVO renameTable(
            UUID datasetId, UUID tableId, DatasetTableUpdateRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new InvalidDatasetException("数据表名称不能为空");
        }
        String name = normalizeTableName(request.getName());
        DatasetVersion entity = requireAnyVersionForUpdate(datasetId);
        ensureMutable(entity);
        UUID versionId = entity.getId();
        requireTable(versionId, tableId);
        if (datasetTableMapper.updateName(tableId, versionId, name) != 1) {
            throw new StorageException("数据表名称更新失败");
        }
        entity.setUpdatedAt(Instant.now());
        ensureUpdated(entity);
        return toDetail(entity);
    }

    @Override
    @Transactional
    public DatasetDetailVO deleteTable(UUID datasetId, UUID tableId) {
        DatasetVersion entity = requireAnyVersionForUpdate(datasetId);
        ensureMutable(entity);
        UUID versionId = entity.getId();
        requireTable(versionId, tableId);
        if (datasetTableMapper.deleteByIdAndDatasetId(tableId, versionId) != 1) {
            throw new StorageException("数据表删除失败");
        }

        List<DatasetFile> remainingFiles = new ArrayList<>(datasetFileMapper.findByDatasetId(versionId));
        for (DatasetFile file : new ArrayList<>(remainingFiles)) {
            if (datasetTableMapper.countByFileId(file.getId()) > 0) {
                continue;
            }
            if (datasetFileMapper.deleteByIdAndDatasetId(file.getId(), versionId) == 1) {
                if (file.getStorageKey() != null
                        && !file.getStorageKey().isBlank()
                        && datasetFileMapper.countByStorageKey(file.getStorageKey()) == 0) {
                    deleteAfterCommit(Set.of(file.getStorageKey()));
                }
                if (file.getBlobSha256() != null && !file.getBlobSha256().isBlank()) {
                    storageBlobMapper.deleteIfUnreferenced(file.getBlobSha256());
                }
                remainingFiles.remove(file);
            }
        }

        List<DatasetTable> remainingTables = datasetTableMapper.findByDatasetId(versionId);
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
        String ownerId = datasetOwnerProvider.currentOwnerId();
        DatasetVersion probe = resolveAnyVersion(id, ownerId);
        Dataset dataset = datasetMapper.findByIdForUpdate(probe.getDatasetId(), ownerId);
        if (dataset == null) {
            throw new DatasetNotFoundException(id);
        }
        List<DatasetVersion> versions = datasetVersionMapper.findByDatasetId(dataset.getId(), ownerId);
        DatasetVersion entity = versions.isEmpty()
                ? datasetVersionMapper.findById(probe.getId(), ownerId)
                : versions.get(0);
        if (entity == null) {
            throw new DatasetNotFoundException(id);
        }
        if (entity.getStatus() == DatasetStatus.READY) {
            return appendFileToNewVersion(entity, dataset, file);
        }
        ensureMutable(entity);
        return appendFile(entity, file, DatasetStatus.UPLOADED);
    }

    @Override
    @Transactional
    public DatasetReadinessVO ready(UUID id) {
        String ownerId = datasetOwnerProvider.currentOwnerId();
        DatasetVersion probe = resolveAnyVersion(id, ownerId);
        Dataset dataset = datasetMapper.findByIdForUpdate(probe.getDatasetId(), ownerId);
        if (dataset == null) {
            throw new DatasetNotFoundException(id);
        }
        DatasetVersion entity = datasetVersionMapper.findByIdForUpdate(probe.getId(), ownerId);
        if (entity == null) {
            throw new DatasetNotFoundException(id);
        }
        if (entity.getStatus() == DatasetStatus.READY) {
            return toReadiness(entity);
        }
        if (entity.getStatus() != DatasetStatus.UPLOADED
                && entity.getStatus() != DatasetStatus.PROCESSING) {
            throw new InvalidDatasetException("只有已上传或处理中的数据集版本才能标记为 READY");
        }
        List<DatasetFile> files = datasetFileMapper.findByDatasetId(entity.getId());
        List<DatasetTable> tables = datasetTableMapper.findByDatasetId(entity.getId());
        List<DatasetColumn> columns = datasetColumnMapper.findByDatasetVersionId(entity.getId());
        if (files.isEmpty() || tables.isEmpty()) {
            throw new InvalidDatasetException("数据集没有可发布的数据，不能标记为 READY");
        }
        if (columns.isEmpty()) {
            throw new InvalidDatasetException("数据集缺少列元数据，不能标记为 READY");
        }
        Instant now = Instant.now();
        entity.setName(dataset.getName());
        entity.setDescription(dataset.getDescription());
        entity.setManifestHash(hash(manifestText(entity, files, tables, columns)));
        entity.setSchemaHash(hash(schemaText(tables, columns)));
        entity.setStatus(DatasetStatus.READY);
        entity.setReadyAt(now);
        entity.setUpdatedAt(now);
        ensureUpdated(entity);
        if (datasetMapper.updateCurrentVersion(
                        entity.getDatasetId(), ownerId, entity.getId(), now)
                != 1) {
            throw new StorageException("数据集当前版本更新失败");
        }
        return toReadiness(entity);
    }
    @Override
    @Transactional(readOnly = true)
    public DatasetReadinessVO readiness(UUID id) {
        return toReadiness(resolveAnyVersion(id, datasetOwnerProvider.currentOwnerId()));
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
        appendFile(entity, file, DatasetStatus.UPLOADED);
        return DatasetConverter.toVO(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public DatasetDetailVO get(UUID id) {
        return toDetail(resolveAnyVersion(id, datasetOwnerProvider.currentOwnerId()));
    }
    @Override
    @Transactional(readOnly = true)
    public List<DatasetVersionVO> versions(UUID id) {
        String ownerId = datasetOwnerProvider.currentOwnerId();
        DatasetVersion probe = resolveAnyVersion(id, ownerId);
        return datasetVersionMapper.findByDatasetId(probe.getDatasetId(), ownerId).stream()
                .map(DatasetConverter::toVersionVO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DatasetDetailVO currentVersion(UUID id) {
        String ownerId = datasetOwnerProvider.currentOwnerId();
        DatasetVersion probe = resolveAnyVersion(id, ownerId);
        Dataset dataset = datasetMapper.findById(probe.getDatasetId(), ownerId);
        if (dataset == null || dataset.getCurrentVersionId() == null) {
            throw new DatasetNotFoundException(id);
        }
        DatasetVersion version = datasetVersionMapper.findById(dataset.getCurrentVersionId(), ownerId);
        if (version == null) {
            throw new DatasetNotFoundException(id);
        }
        return toDetail(version);
    }

    @Override
    @Transactional(readOnly = true)
    public TablePreviewVO previewTable(UUID datasetId, UUID tableId, int limit, boolean all) {
        DatasetVersion version = resolveAnyVersion(datasetId, datasetOwnerProvider.currentOwnerId());
        DatasetTable table = datasetTableMapper.findByIdAndDatasetId(tableId, version.getId());
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
        DatasetVersion version = resolveAnyVersion(datasetId, datasetOwnerProvider.currentOwnerId());
        DatasetTable table = datasetTableMapper.findByIdAndDatasetId(tableId, version.getId());
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
    public TableRowSearchVO searchRows(
            UUID datasetId, UUID tableId, String keyword, int columnIndex, long offset, int limit) {
        if (keyword == null || keyword.isBlank() || keyword.trim().length() > MAX_SEARCH_KEYWORD_LENGTH) {
            throw new InvalidDatasetException("搜索内容长度需为 1 到 " + MAX_SEARCH_KEYWORD_LENGTH + " 个字符");
        }
        if (columnIndex < -1) {
            throw new InvalidDatasetException("搜索列索引无效");
        }
        DatasetVersion version = resolveAnyVersion(datasetId, datasetOwnerProvider.currentOwnerId());
        DatasetTable table = datasetTableMapper.findByIdAndDatasetId(tableId, version.getId());
        if (table == null) {
            throw new DatasetTableNotFoundException(datasetId, tableId);
        }
        DatasetFile file = datasetFileMapper.findById(table.getFileId());
        if (file == null) {
            throw new DatasetTableNotFoundException(datasetId, tableId);
        }

        long effectiveOffset = Math.max(offset, 0);
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_SEARCH_RESULT_PAGE_SIZE);
        try (InputStream input = datasetStorage.open(file.getStorageKey())) {
            TableRowSearch result = tablePreviewReader.searchRows(
                    file.getOriginalFilename(),
                    table.getFileName(),
                    table.getTableName(),
                    table.getTableFormat(),
                    input,
                    keyword.trim(),
                    columnIndex,
                    effectiveOffset,
                    effectiveLimit,
                    tableSearchProperties.getMaxScannedRows());
            List<TableRowSearchVO.RowMatch> rows = result.rows().stream()
                    .map(match -> new TableRowSearchVO.RowMatch(match.rowNumber(), match.values()))
                    .toList();
            return new TableRowSearchVO(
                    table.getId(),
                    table.getTableName(),
                    effectiveOffset,
                    effectiveLimit,
                    result.totalMatches(),
                    result.scannedRows(),
                    result.truncated(),
                    result.columns(),
                    rows);
        } catch (IOException exception) {
            throw new StorageException("搜索数据表失败", exception);
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
        String ownerId = datasetOwnerProvider.currentOwnerId();
        List<DatasetVersion> entities = datasetVersionMapper.findPage(
                effectiveQuery.getStatus(),
                keywordPattern,
                formatPattern,
                effectiveQuery.getHasData(),
                ownerId,
                offset,
                size);
        long total = datasetVersionMapper.count(
                effectiveQuery.getStatus(), keywordPattern, formatPattern, effectiveQuery.getHasData(), ownerId);
        List<DatasetVO> items = entities.stream().map(DatasetConverter::toVO).toList();
        return PageVO.of(items, page, size, total);
    }

    private DatasetVersion createEmptyDataset(String name, String description) {
        Instant now = Instant.now();
        String ownerId = datasetOwnerProvider.currentOwnerId();
        String resolvedName = name == null || name.isBlank()
                ? resolveCreateName(name, ownerId)
                : normalizeName(name);
        String resolvedDescription = normalizeDescription(description);
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Dataset dataset = new Dataset(
                datasetId,
                ownerId,
                resolvedName,
                resolvedDescription,
                null,
                now,
                now,
                null);
        if (datasetMapper.insert(dataset) != 1) {
            throw new StorageException("数据集元数据保存失败");
        }
        DatasetVersion entity = new DatasetVersion(
                versionId,
                resolvedName,
                resolvedDescription,
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
        entity.setOwnerId(ownerId);
        entity.setDatasetId(datasetId);
        entity.setVersionNo(1);
        entity.setSourceType("UPLOAD");
        int inserted = datasetVersionMapper.insert(entity);
        if (inserted != 1) {
            throw new StorageException("数据集版本保存失败");
        }
        if (datasetMapper.updateCurrentVersion(datasetId, ownerId, versionId, now) != 1) {
            throw new StorageException("数据集当前版本更新失败");
        }
        return entity;
    }

    private DatasetDetailVO appendFile(DatasetVersion entity, MultipartFile file, DatasetStatus targetStatus) {
        UUID datasetId = entity.getId();
        UUID fileId = UUID.randomUUID();
        String originalFilename = normalizeFilename(file.getOriginalFilename());
        DatasetProfile profile = datasetProfiler.profile(file, originalFilename);
        String storageKey = "datasets/" + datasetId + "/files/" + fileId + "/" + originalFilename;
        String sha256 = store(storageKey, file);
        boolean cleanupOnRollback = deleteAfterRollback(storageKey);
        Instant now = Instant.now();

        try {
            StorageBlob blob = new StorageBlob(
                    sha256, storageKey, file.getSize(), file.getContentType(), now);
            storageBlobMapper.insertIfAbsent(blob);
            DatasetFile datasetFile = new DatasetFile(
                    fileId,
                    datasetId,
                    originalFilename,
                    storageKey,
                    file.getSize(),
                    sha256,
                    file.getContentType(),
                    now);
            datasetFile.setBlobSha256(sha256);
            if (datasetFileMapper.insert(datasetFile) != 1) {
                throw new StorageException("文件元数据保存失败");
            }

            if (!profile.tables().isEmpty()) {
                List<DatasetTable> tables = toTables(
                        datasetId, fileId, originalFilename, profile.tables(), entity.getTableCount());
                datasetTableMapper.insertBatch(tables);
                List<DatasetColumn> columns = toColumns(
                        datasetId, fileId, tables, profile.tables(), now);
                if (!columns.isEmpty()) {
                    datasetColumnMapper.insertBatch(columns);
                }
            }

            if (entity.getFileCount() == 0) {
                entity.setOriginalFilename(originalFilename);
                entity.setStorageKey(storageKey);
                entity.setSizeBytes(file.getSize());
                entity.setSha256(sha256);
                entity.setContentType(file.getContentType());
            }
            entity.setStatus(targetStatus);
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

    private DatasetDetailVO appendFileToNewVersion(DatasetVersion base, Dataset dataset, MultipartFile file) {
        Instant now = Instant.now();
        String ownerId = datasetOwnerProvider.currentOwnerId();
        DatasetVersion version = new DatasetVersion(
                UUID.randomUUID(),
                dataset.getName(),
                dataset.getDescription(),
                base.getOriginalFilename(),
                base.getStorageKey(),
                base.getSizeBytes(),
                base.getSha256(),
                base.getContentType(),
                DatasetStatus.PROCESSING,
                base.getTableCount(),
                base.getRowCount(),
                base.getFileCount(),
                now,
                now);
        version.setOwnerId(ownerId);
        version.setDatasetId(base.getDatasetId());
        version.setVersionNo(datasetVersionMapper.nextVersionNo(base.getDatasetId()));
        version.setParentVersionId(base.getId());
        version.setSourceType(base.getSourceType() == null ? "UPLOAD" : base.getSourceType());
        if (datasetVersionMapper.insert(version) != 1) {
            throw new StorageException("数据集版本保存失败");
        }
        cloneMetadata(base, version, now);
        return appendFile(version, file, DatasetStatus.PROCESSING);
    }

    private void cloneMetadata(DatasetVersion base, DatasetVersion target, Instant now) {
        Map<UUID, UUID> fileIds = new HashMap<>();
        List<DatasetFile> files = datasetFileMapper.findByDatasetId(base.getId());
        for (DatasetFile source : files) {
            DatasetFile copy = new DatasetFile(
                    UUID.randomUUID(),
                    target.getId(),
                    source.getOriginalFilename(),
                    source.getStorageKey(),
                    source.getSizeBytes(),
                    source.getSha256(),
                    source.getContentType(),
                    now);
            copy.setBlobSha256(source.getBlobSha256());
            if (datasetFileMapper.insert(copy) != 1) {
                throw new StorageException("文件元数据复制失败");
            }
            fileIds.put(source.getId(), copy.getId());
        }

        Map<UUID, UUID> tableIds = new HashMap<>();
        List<DatasetTable> sourceTables = datasetTableMapper.findByDatasetId(base.getId());
        List<DatasetTable> targetTables = new ArrayList<>(sourceTables.size());
        for (DatasetTable source : sourceTables) {
            DatasetTable copy = new DatasetTable(
                    UUID.randomUUID(),
                    target.getId(),
                    remap(fileIds, source.getFileId()),
                    source.getFileName(),
                    source.getTableName(),
                    source.getTableFormat(),
                    source.getRowCount(),
                    source.getColumnCount(),
                    source.getOrdinal());
            copy.setTableKey(source.getTableKey());
            copy.setPhysicalTable(null);
            targetTables.add(copy);
            tableIds.put(source.getId(), copy.getId());
        }
        if (!targetTables.isEmpty()) {
            datasetTableMapper.insertBatch(targetTables);
        }

        List<DatasetColumn> sourceColumns = datasetColumnMapper.findByDatasetVersionId(base.getId());
        List<DatasetColumn> targetColumns = new ArrayList<>(sourceColumns.size());
        for (DatasetColumn source : sourceColumns) {
            DatasetColumn copy = new DatasetColumn(
                    UUID.randomUUID(),
                    target.getId(),
                    remap(tableIds, source.getTableId()),
                    source.getColumnName(),
                    source.getOrdinal(),
                    source.getDataType(),
                    source.getLogicalType(),
                    source.getPhysicalName(),
                    source.getClickhouseType(),
                    source.isNullable(),
                    remap(fileIds, source.getFileId()),
                    now);
            targetColumns.add(copy);
        }
        if (!targetColumns.isEmpty()) {
            datasetColumnMapper.insertBatch(targetColumns);
        }

        migrateBindings(base.getId(), target.getId(), sourceTables, sourceColumns, targetTables, targetColumns, now);
    }

    private void migrateBindings(
            UUID sourceVersionId,
            UUID targetVersionId,
            List<DatasetTable> sourceTables,
            List<DatasetColumn> sourceColumns,
            List<DatasetTable> targetTables,
            List<DatasetColumn> targetColumns,
            Instant now) {
        List<DatasetFieldBinding> bindings = datasetFieldBindingMapper.findByDatasetVersionId(sourceVersionId);
        if (bindings.isEmpty()) {
            return;
        }
        Map<UUID, DatasetTable> sourceTablesById = new HashMap<>();
        for (DatasetTable table : sourceTables) {
            sourceTablesById.put(table.getId(), table);
        }
        Map<UUID, DatasetColumn> sourceColumnsById = new HashMap<>();
        for (DatasetColumn column : sourceColumns) {
            sourceColumnsById.put(column.getId(), column);
        }
        Map<UUID, DatasetTable> targetTablesById = new HashMap<>();
        for (DatasetTable table : targetTables) {
            targetTablesById.put(table.getId(), table);
        }
        Map<String, DatasetColumn> targetColumnsByKey = new HashMap<>();
        for (DatasetColumn column : targetColumns) {
            DatasetTable table = targetTablesById.get(column.getTableId());
            if (table == null) {
                continue;
            }
            targetColumnsByKey.putIfAbsent(
                    bindingKey(table.getTableKey(), column.getColumnName(), column.getLogicalType()),
                    column);
        }

        Set<UUID> usedColumns = new HashSet<>();
        List<DatasetFieldBinding> copies = new ArrayList<>(bindings.size());
        for (DatasetFieldBinding binding : bindings) {
            DatasetColumn sourceColumn = binding.getColumnId() == null
                    ? null
                    : sourceColumnsById.get(binding.getColumnId());
            DatasetTable sourceTable = sourceColumn == null
                    ? sourceTablesById.get(binding.getTableId())
                    : sourceTablesById.get(sourceColumn.getTableId());
            DatasetColumn matched = null;
            if (sourceColumn != null && sourceTable != null) {
                matched = targetColumnsByKey.get(bindingKey(
                        sourceTable.getTableKey(),
                        sourceColumn.getColumnName(),
                        sourceColumn.getLogicalType()));
            }
            if (matched != null && !usedColumns.add(matched.getId())) {
                matched = null;
            }
            copies.add(new DatasetFieldBinding(
                    UUID.randomUUID(),
                    binding.getOwnerId(),
                    binding.getFieldId(),
                    targetVersionId,
                    matched == null ? null : matched.getTableId(),
                    matched == null ? null : matched.getId(),
                    matched == null ? BindingStatus.NEEDS_REBIND : BindingStatus.BOUND,
                    now));
        }
        datasetFieldBindingMapper.insertBatch(copies);
    }

    private String bindingKey(String tableKey, String columnName, String logicalType) {
        return String.valueOf(tableKey)
                + '\u0000'
                + String.valueOf(columnName)
                + '\u0000'
                + String.valueOf(logicalType);
    }

    private UUID remap(Map<UUID, UUID> mapping, UUID id) {
        return id == null ? null : mapping.get(id);
    }

    private void collectStorageKeys(
            DatasetVersion version,
            Map<String, Set<String>> storageKeysByBlob,
            Set<String> legacyStorageKeys) {
        List<DatasetFile> files = datasetFileMapper.findByDatasetId(version.getId());
        if (files.isEmpty()) {
            if (version.getStorageKey() != null && !version.getStorageKey().isBlank()) {
                legacyStorageKeys.add(version.getStorageKey());
            }
            return;
        }
        for (DatasetFile file : files) {
            String storageKey = file.getStorageKey();
            if (storageKey == null || storageKey.isBlank()) {
                continue;
            }
            if (file.getBlobSha256() == null || file.getBlobSha256().isBlank()) {
                legacyStorageKeys.add(storageKey);
            } else {
                storageKeysByBlob
                        .computeIfAbsent(file.getBlobSha256(), ignored -> new LinkedHashSet<>())
                        .add(storageKey);
            }
        }
    }

    private DatasetDetailVO toDetail(DatasetVersion entity) {
        return DatasetConverter.toDetailVO(
                entity,
                datasetFileMapper.findByDatasetId(entity.getId()),
                datasetTableMapper.findByDatasetId(entity.getId()));
    }

    private DatasetVersion resolveAnyVersion(UUID id, String ownerId) {
        DatasetVersion version = datasetVersionMapper.findById(id, ownerId);
        if (version != null) {
            return version;
        }
        Dataset dataset = datasetMapper.findById(id, ownerId);
        if (dataset != null && dataset.getCurrentVersionId() != null) {
            version = datasetVersionMapper.findById(dataset.getCurrentVersionId(), ownerId);
            if (version != null) {
                return version;
            }
        }
        throw new DatasetNotFoundException(id);
    }

    private DatasetVersion requireAnyVersionForUpdate(UUID id) {
        String ownerId = datasetOwnerProvider.currentOwnerId();
        DatasetVersion probe = resolveAnyVersion(id, ownerId);
        if (datasetMapper.findByIdForUpdate(probe.getDatasetId(), ownerId) == null) {
            throw new DatasetNotFoundException(id);
        }
        DatasetVersion version = datasetVersionMapper.findByIdForUpdate(probe.getId(), ownerId);
        if (version == null) {
            throw new DatasetNotFoundException(id);
        }
        return version;
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

    private String resolveCreateName(String name, String ownerId) {
        if (name != null && !name.isBlank()) {
            return normalizeName(name);
        }
        int maxSequence = 0;
        for (String existingName : datasetVersionMapper.findNamesStartingWith(
                DEFAULT_NAME_PREFIX + "%", ownerId)) {
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
            UUID tableId = UUID.randomUUID();
            DatasetTable table = new DatasetTable(
                    tableId,
                    datasetId,
                    fileId,
                    sourceFile,
                    profile.name(),
                    profile.format(),
                    profile.rowCount(),
                    profile.columnCount(),
                    ordinal++);
            table.setTableKey(tableKey(sourceFile, profile.name()));
            table.setPhysicalTable("ds_" + compactUuid(datasetId) + "_" + compactUuid(tableId));
            tables.add(table);
        }
        return tables;
    }

    private List<DatasetColumn> toColumns(
            UUID datasetVersionId,
            UUID fileId,
            List<DatasetTable> tables,
            List<TableProfile> profiles,
            Instant now) {
        List<DatasetColumn> columns = new ArrayList<>();
        for (int index = 0; index < tables.size(); index++) {
            DatasetTable table = tables.get(index);
            for (ColumnProfile profile : profiles.get(index).columns()) {
                columns.add(new DatasetColumn(
                        UUID.randomUUID(),
                        datasetVersionId,
                        table.getId(),
                        profile.name(),
                        profile.ordinal(),
                        profile.dataType(),
                        profile.dataType(),
                        null,
                        null,
                        true,
                        fileId,
                        now));
            }
        }
        return columns;
    }

    private DatasetReadinessVO toReadiness(DatasetVersion entity) {
        return new DatasetReadinessVO(
                entity.getDatasetId(),
                entity.getId(),
                entity.getStatus(),
                entity.getReadyAt(),
                entity.getManifestHash(),
                entity.getSchemaHash(),
                entity.getFileCount(),
                entity.getTableCount(),
                entity.getRowCount());
    }

    private String manifestText(
            DatasetVersion entity,
            List<DatasetFile> files,
            List<DatasetTable> tables,
            List<DatasetColumn> columns) {
        StringBuilder builder = new StringBuilder();
        builder.append("datasetVersionId=").append(entity.getId()).append('\n');
        builder.append("datasetId=").append(entity.getDatasetId()).append('\n');
        builder.append("ownerId=").append(entity.getOwnerId()).append('\n');
        for (DatasetFile file : files) {
            builder.append("file=").append(file.getId())
                    .append('|').append(file.getOriginalFilename())
                    .append('|').append(file.getSizeBytes())
                    .append('|').append(file.getSha256()).append('\n');
        }
        for (DatasetTable table : tables) {
            builder.append("rows=").append(table.getTableKey())
                    .append('|').append(table.getRowCount()).append('\n');
        }
        builder.append(schemaText(tables, columns));
        return builder.toString();
    }

    private String schemaText(List<DatasetTable> tables, List<DatasetColumn> columns) {
        StringBuilder builder = new StringBuilder();
        for (DatasetTable table : tables) {
            builder.append("table=").append(table.getTableKey())
                    .append('|').append(table.getTableName())
                    .append('|').append(table.getTableFormat())
                    .append('|').append(table.getColumnCount()).append('\n');
        }
        for (DatasetColumn column : columns) {
            builder.append("column=").append(column.getTableId())
                    .append('|').append(column.getOrdinal())
                    .append('|').append(column.getColumnName())
                    .append('|').append(column.getDataType())
                    .append('|').append(column.getLogicalType())
                    .append('|').append(column.isNullable()).append('\n');
        }
        return builder.toString();
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void ensureMutable(DatasetVersion entity) {
        if (entity.getStatus() == DatasetStatus.READY) {
            throw new DatasetVersionImmutableException(
                    "数据集版本 " + entity.getId() + " 已 READY，不能追加文件或修改表结构");
        }
    }

    private String tableKey(String sourceFile, String tableName) {
        String key = sourceFile + "::" + tableName;
        return key.length() > MAX_TABLE_NAME_LENGTH ? key.substring(0, MAX_TABLE_NAME_LENGTH) : key;
    }

    private String compactUuid(UUID id) {
        return id.toString().replace("-", "");
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
