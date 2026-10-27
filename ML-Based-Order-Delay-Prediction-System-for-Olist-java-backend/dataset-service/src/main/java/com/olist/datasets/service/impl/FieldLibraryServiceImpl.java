package com.olist.datasets.service.impl;

import com.olist.datasets.convert.LibraryConverter;
import com.olist.datasets.dto.FieldDefinitionQuery;
import com.olist.datasets.dto.FieldDefinitionRequest;
import com.olist.datasets.dto.FieldImportRequest;
import com.olist.datasets.entity.DatasetFieldBinding;
import com.olist.datasets.entity.FieldCatalogRow;
import com.olist.datasets.entity.FieldDefinition;
import com.olist.datasets.enums.BindingStatus;
import com.olist.datasets.enums.FieldDataType;
import com.olist.datasets.enums.FieldStatus;
import com.olist.datasets.exception.InvalidLibraryRequestException;
import com.olist.datasets.exception.LibraryItemNotFoundException;
import com.olist.datasets.mapper.DatasetColumnMapper;
import com.olist.datasets.mapper.DatasetFieldBindingMapper;
import com.olist.datasets.mapper.DatasetVersionMapper;
import com.olist.datasets.mapper.FieldDefinitionMapper;
import com.olist.datasets.security.DatasetOwnerProvider;
import com.olist.datasets.service.FieldLibraryService;
import com.olist.datasets.vo.FieldCatalogItemVO;
import com.olist.datasets.vo.FieldDefinitionVO;
import com.olist.datasets.vo.FieldImportResultVO;
import com.olist.datasets.vo.PageVO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FieldLibraryServiceImpl implements FieldLibraryService {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_FIELD_NAME_LENGTH = 128;
    private static final int MAX_SOURCE_TABLE_LENGTH = 255;

    private final FieldDefinitionMapper fieldDefinitionMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final DatasetFieldBindingMapper datasetFieldBindingMapper;
    private final DatasetVersionMapper datasetVersionMapper;
    private final DatasetOwnerProvider datasetOwnerProvider;

    public FieldLibraryServiceImpl(
            FieldDefinitionMapper fieldDefinitionMapper,
            DatasetColumnMapper datasetColumnMapper,
            DatasetFieldBindingMapper datasetFieldBindingMapper,
            DatasetVersionMapper datasetVersionMapper,
            DatasetOwnerProvider datasetOwnerProvider) {
        this.fieldDefinitionMapper = fieldDefinitionMapper;
        this.datasetColumnMapper = datasetColumnMapper;
        this.datasetFieldBindingMapper = datasetFieldBindingMapper;
        this.datasetVersionMapper = datasetVersionMapper;
        this.datasetOwnerProvider = datasetOwnerProvider;
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<FieldDefinitionVO> list(FieldDefinitionQuery query) {
        FieldDefinitionQuery effective = query == null ? new FieldDefinitionQuery() : query;
        int page = Math.max(effective.getPage(), 0);
        int size = Math.min(Math.max(effective.getSize(), MIN_PAGE_SIZE), MAX_PAGE_SIZE);
        String ownerId = datasetOwnerProvider.currentOwnerId();
        String keywordPattern = keywordPattern(effective.getKeyword());
        String semanticRole = normalizeOptional(effective.getSemanticRole());
        List<FieldDefinitionVO> items = fieldDefinitionMapper
                .findPage(
                        keywordPattern,
                        effective.getStatus(),
                        semanticRole,
                        ownerId,
                        (long) page * size,
                        size)
                .stream()
                .map(LibraryConverter::toVO)
                .toList();
        long total = fieldDefinitionMapper.count(
                keywordPattern, effective.getStatus(), semanticRole, ownerId);
        return PageVO.of(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public FieldDefinitionVO get(UUID id) {
        return LibraryConverter.toVO(require(id));
    }

    @Override
    @Transactional
    public FieldDefinitionVO create(FieldDefinitionRequest request) {
        FieldDefinition entity = new FieldDefinition();
        entity.setId(UUID.randomUUID());
        entity.setOwnerId(datasetOwnerProvider.currentOwnerId());
        apply(entity, request, null);
        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            fieldDefinitionMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw duplicateName(entity.getName());
        }
        return LibraryConverter.toVO(entity);
    }

    @Override
    @Transactional
    public FieldDefinitionVO update(UUID id, FieldDefinitionRequest request) {
        FieldDefinition entity = require(id);
        apply(entity, request, id);
        entity.setUpdatedAt(Instant.now());
        try {
            if (fieldDefinitionMapper.update(entity) != 1) {
                throw new LibraryItemNotFoundException("字段不存在或无权访问");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateName(entity.getName());
        }
        return LibraryConverter.toVO(entity);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        if (fieldDefinitionMapper.deleteById(id, datasetOwnerProvider.currentOwnerId()) != 1) {
            throw new LibraryItemNotFoundException("字段不存在或无权访问");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<FieldCatalogItemVO> catalog(UUID datasetVersionId) {
        String ownerId = datasetOwnerProvider.currentOwnerId();
        requireDataset(datasetVersionId, ownerId);
        return datasetColumnMapper.findFieldCatalog(datasetVersionId, ownerId).stream()
                .map(FieldLibraryServiceImpl::toCatalogItem)
                .toList();
    }

    @Override
    @Transactional
    public FieldImportResultVO importFromDataset(FieldImportRequest request) {
        if (request == null || request.getDatasetVersionId() == null) {
            throw new InvalidLibraryRequestException("数据集版本不能为空");
        }
        UUID datasetId = request.getDatasetVersionId();
        String ownerId = datasetOwnerProvider.currentOwnerId();
        requireDataset(datasetId, ownerId);

        Set<UUID> selectedColumns = request.getColumnIds() == null
                ? Set.of()
                : new HashSet<>(request.getColumnIds());
        Set<UUID> selectedTables = request.getTableIds() == null
                ? Set.of()
                : new HashSet<>(request.getTableIds());
        List<FieldCatalogRow> rows = datasetColumnMapper.findFieldCatalog(datasetId, ownerId).stream()
                .filter(row -> selectedColumns.isEmpty() || selectedColumns.contains(row.getColumnId()))
                .filter(row -> selectedTables.isEmpty() || selectedTables.contains(row.getTableId()))
                .toList();
        if (rows.isEmpty()) {
            throw new InvalidLibraryRequestException("没有可导入的字段，请先上传数据或重新选择字段");
        }

        Instant now = Instant.now();
        List<FieldCatalogItemVO> items = new ArrayList<>(rows.size());
        int created = 0;
        int skipped = 0;
        for (FieldCatalogRow row : rows) {
            if (row.getFieldId() != null) {
                skipped++;
                items.add(toCatalogItem(row));
                continue;
            }

            String qualifiedName = sanitizeFieldName(row.getTableName(), row.getColumnName());
            String fieldName = uniqueFieldName(qualifiedName, ownerId);
            FieldDefinition field = new FieldDefinition();
            field.setId(UUID.randomUUID());
            field.setOwnerId(ownerId);
            field.setName(fieldName);
            field.setDisplayName(truncate(
                    ((row.getTableName() == null ? "" : row.getTableName()) + "." +
                            (row.getColumnName() == null ? "" : row.getColumnName())).trim(),
                    255));
            field.setDataType(row.getDataType() == null ? FieldDataType.STRING : row.getDataType());
            field.setSourceTable(truncate(
                    ((row.getDatasetName() == null ? "" : row.getDatasetName()) + "." +
                            (row.getTableName() == null ? "" : row.getTableName())).trim(),
                    MAX_SOURCE_TABLE_LENGTH));
            field.setDescription("从数据集字段自动导入");
            field.setStatus(FieldStatus.ACTIVE);
            field.setCreatedAt(now);
            field.setUpdatedAt(now);
            fieldDefinitionMapper.insert(field);

            DatasetFieldBinding binding = new DatasetFieldBinding();
            binding.setId(UUID.randomUUID());
            binding.setOwnerId(ownerId);
            binding.setFieldId(field.getId());
            binding.setDatasetVersionId(datasetId);
            binding.setTableId(row.getTableId());
            binding.setColumnId(row.getColumnId());
            binding.setBindingStatus(BindingStatus.BOUND);
            binding.setCreatedAt(now);
            datasetFieldBindingMapper.insertBatch(List.of(binding));

            created++;
            items.add(new FieldCatalogItemVO(
                    row.getColumnId(),
                    field.getId(),
                    field.getName(),
                    field.getDisplayName(),
                    row.getDatasetId(),
                    row.getDatasetName(),
                    row.getTableId(),
                    row.getTableName(),
                    row.getColumnName(),
                    field.getDataType(),
                    row.getOrdinal(),
                    true));
        }
        return new FieldImportResultVO(datasetId, created, skipped, items);
    }

    private void requireDataset(UUID datasetVersionId, String ownerId) {
        if (datasetVersionMapper.findById(datasetVersionId, ownerId) == null) {
            throw new InvalidLibraryRequestException("数据集不存在或无权访问");
        }
    }

    private static FieldCatalogItemVO toCatalogItem(FieldCatalogRow row) {
        return new FieldCatalogItemVO(
                row.getColumnId(),
                row.getFieldId(),
                row.getFieldName(),
                row.getFieldDisplayName(),
                row.getDatasetId(),
                row.getDatasetName(),
                row.getTableId(),
                row.getTableName(),
                row.getColumnName(),
                row.getDataType(),
                row.getOrdinal(),
                row.getFieldId() != null);
    }

    private String uniqueFieldName(String baseName, String ownerId) {
        String candidate = baseName;
        int suffixNumber = 2;
        while (fieldDefinitionMapper.countByName(candidate, ownerId, null) > 0) {
            String suffix = "_" + suffixNumber++;
            int maxBaseLength = Math.max(MAX_FIELD_NAME_LENGTH - suffix.length(), 1);
            String trimmedBase = candidate.length() > maxBaseLength
                    ? candidate.substring(0, maxBaseLength)
                    : candidate;
            candidate = trimmedBase + suffix;
        }
        return candidate;
    }

    private String sanitizeFieldName(String tableName, String columnName) {
        String raw = (tableName == null ? "" : tableName) + "." + (columnName == null ? "" : columnName);
        String normalized = raw.replaceAll("[^A-Za-z0-9_.]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^\\.+|\\.+$", "")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "field";
        }
        if (!Character.isLetter(normalized.charAt(0)) && normalized.charAt(0) != '_') {
            normalized = "f_" + normalized;
        }
        normalized = truncate(normalized, MAX_FIELD_NAME_LENGTH);
        return normalized.matches("[A-Za-z_][A-Za-z0-9_.]*")
                ? normalized
                : "field_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
    private void apply(FieldDefinition entity, FieldDefinitionRequest request, UUID excludeId) {
        if (request == null || request.getDataType() == null) {
            throw new InvalidLibraryRequestException("字段类型不能为空");
        }
        String name = trimToNull(request.getName());
        if (name == null) {
            throw new InvalidLibraryRequestException("字段名不能为空");
        }
        long duplicates = fieldDefinitionMapper.countByName(
                name, datasetOwnerProvider.currentOwnerId(), excludeId);
        if (duplicates > 0) {
            throw duplicateName(name);
        }
        entity.setName(name);
        entity.setDisplayName(trimToNull(request.getDisplayName()));
        entity.setDataType(request.getDataType());
        entity.setSemanticRole(upperOrNull(request.getSemanticRole()));
        entity.setSourceTable(trimToNull(request.getSourceTable()));
        entity.setDescription(trimToNull(request.getDescription()));
        entity.setStatus(request.getStatus() == null ? FieldStatus.ACTIVE : request.getStatus());
    }

    private FieldDefinition require(UUID id) {
        if (id == null) {
            throw new LibraryItemNotFoundException("字段不存在或无权访问");
        }
        FieldDefinition entity = fieldDefinitionMapper.findById(
                id, datasetOwnerProvider.currentOwnerId());
        if (entity == null) {
            throw new LibraryItemNotFoundException("字段不存在或无权访问");
        }
        return entity;
    }

    private InvalidLibraryRequestException duplicateName(String name) {
        return new InvalidLibraryRequestException("字段名「" + name + "」已存在");
    }

    private static String keywordPattern(String keyword) {
        String value = trimToNull(keyword);
        return value == null ? null : "%" + value.toLowerCase(Locale.ROOT) + "%";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeOptional(String value) {
        return trimToNull(value);
    }

    private static String upperOrNull(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
