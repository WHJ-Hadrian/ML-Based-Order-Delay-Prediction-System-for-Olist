package com.olist.datasets.service.impl;

import com.olist.datasets.convert.LibraryConverter;
import com.olist.datasets.dto.FieldBulkCreateRequest;
import com.olist.datasets.dto.FieldDefinitionRequest;
import com.olist.datasets.dto.FieldImportRequest;
import com.olist.datasets.dto.FieldLibraryQuery;
import com.olist.datasets.dto.FieldLibraryRequest;
import com.olist.datasets.entity.DatasetFieldBinding;
import com.olist.datasets.entity.DatasetVersion;
import com.olist.datasets.entity.FieldCatalogRow;
import com.olist.datasets.entity.FieldDefinition;
import com.olist.datasets.entity.FieldLibrary;
import com.olist.datasets.enums.BindingStatus;
import com.olist.datasets.enums.FieldDataType;
import com.olist.datasets.enums.FieldLibrarySourceType;
import com.olist.datasets.enums.FieldStatus;
import com.olist.datasets.exception.InvalidLibraryRequestException;
import com.olist.datasets.exception.LibraryItemNotFoundException;
import com.olist.datasets.mapper.DatasetColumnMapper;
import com.olist.datasets.mapper.DatasetFieldBindingMapper;
import com.olist.datasets.mapper.DatasetVersionMapper;
import com.olist.datasets.mapper.FieldDefinitionMapper;
import com.olist.datasets.mapper.FieldLibraryMapper;
import com.olist.datasets.security.DatasetOwnerProvider;
import com.olist.datasets.service.FieldLibraryGroupService;
import com.olist.datasets.vo.FieldBulkResultVO;
import com.olist.datasets.vo.FieldCatalogItemVO;
import com.olist.datasets.vo.FieldDefinitionVO;
import com.olist.datasets.vo.FieldImportResultVO;
import com.olist.datasets.vo.FieldLibraryFieldVO;
import com.olist.datasets.vo.FieldLibraryVO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FieldLibraryGroupServiceImpl implements FieldLibraryGroupService {

    private static final int MAX_FIELD_NAME_LENGTH = 128;
    private static final int MAX_SOURCE_TABLE_LENGTH = 255;
    private static final String DEFAULT_NAME_PREFIX = "字段库";
    private static final Pattern DEFAULT_NAME_PATTERN = Pattern.compile("^字段库(\\d+)$");

    private final FieldLibraryMapper fieldLibraryMapper;
    private final FieldDefinitionMapper fieldDefinitionMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final DatasetFieldBindingMapper datasetFieldBindingMapper;
    private final DatasetVersionMapper datasetVersionMapper;
    private final DatasetOwnerProvider datasetOwnerProvider;

    public FieldLibraryGroupServiceImpl(
            FieldLibraryMapper fieldLibraryMapper,
            FieldDefinitionMapper fieldDefinitionMapper,
            DatasetColumnMapper datasetColumnMapper,
            DatasetFieldBindingMapper datasetFieldBindingMapper,
            DatasetVersionMapper datasetVersionMapper,
            DatasetOwnerProvider datasetOwnerProvider) {
        this.fieldLibraryMapper = fieldLibraryMapper;
        this.fieldDefinitionMapper = fieldDefinitionMapper;
        this.datasetColumnMapper = datasetColumnMapper;
        this.datasetFieldBindingMapper = datasetFieldBindingMapper;
        this.datasetVersionMapper = datasetVersionMapper;
        this.datasetOwnerProvider = datasetOwnerProvider;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FieldLibraryVO> list(FieldLibraryQuery query) {
        String keywordPattern = keywordPattern(query == null ? null : query.getKeyword());
        return fieldLibraryMapper.findAll(ownerId(), keywordPattern).stream()
                .map(LibraryConverter::toVO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FieldLibraryVO get(UUID id) {
        return LibraryConverter.toVO(requireLibrary(id));
    }

    @Override
    @Transactional
    public FieldLibraryVO create(FieldLibraryRequest request) {
        String ownerId = ownerId();
        String name = resolveCreateName(request == null ? null : request.getName(), ownerId);
        if (fieldLibraryMapper.countByName(name, ownerId, null) > 0) {
            throw duplicateLibraryName(name);
        }
        FieldLibrary library = new FieldLibrary();
        library.setId(UUID.randomUUID());
        library.setOwnerId(ownerId);
        library.setName(name);
        library.setDescription(trimToNull(request.getDescription()));
        library.setSourceType(FieldLibrarySourceType.MANUAL);
        Instant now = Instant.now();
        library.setCreatedAt(now);
        library.setUpdatedAt(now);
        try {
            fieldLibraryMapper.insert(library);
        } catch (DuplicateKeyException exception) {
            throw duplicateLibraryName(name);
        }
        return LibraryConverter.toVO(library);
    }

    @Override
    @Transactional
    public FieldLibraryVO update(UUID id, FieldLibraryRequest request) {
        FieldLibrary library = requireLibrary(id);
        if (request == null) {
            throw new InvalidLibraryRequestException("字段库信息不能为空");
        }
        String name = requireLibraryName(request.getName());
        if (fieldLibraryMapper.countByName(name, library.getOwnerId(), id) > 0) {
            throw duplicateLibraryName(name);
        }
        library.setName(name);
        library.setDescription(trimToNull(request.getDescription()));
        library.setUpdatedAt(Instant.now());
        try {
            if (fieldLibraryMapper.update(library) != 1) {
                throw new LibraryItemNotFoundException("字段库不存在或无权访问");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateLibraryName(name);
        }
        return LibraryConverter.toVO(requireLibrary(id));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        if (fieldLibraryMapper.deleteById(id, ownerId()) != 1) {
            throw new LibraryItemNotFoundException("字段库不存在或无权访问");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<FieldLibraryFieldVO> listFields(UUID libraryId) {
        FieldLibrary library = requireLibrary(libraryId);
        return fieldDefinitionMapper.findByLibraryId(libraryId, library.getOwnerId()).stream()
                .map(LibraryConverter::toLibraryFieldVO)
                .toList();
    }

    @Override
    @Transactional
    public FieldLibraryFieldVO createField(UUID libraryId, FieldDefinitionRequest request) {
        FieldLibrary library = requireLibrary(libraryId);
        FieldDefinition field = persistField(library, request);
        touch(library);
        return LibraryConverter.toLibraryFieldVO(field);
    }

    @Override
    @Transactional
    public FieldLibraryFieldVO updateField(
            UUID libraryId, UUID fieldId, FieldDefinitionRequest request) {
        FieldLibrary library = requireLibrary(libraryId);
        FieldDefinition field = fieldDefinitionMapper.findById(fieldId, library.getOwnerId());
        if (field == null || !libraryId.equals(field.getLibraryId())) {
            throw new LibraryItemNotFoundException("字段不存在或无权访问");
        }
        applyField(field, request, fieldId);
        field.setUpdatedAt(Instant.now());
        try {
            if (fieldDefinitionMapper.update(field) != 1) {
                throw new LibraryItemNotFoundException("字段不存在或无权访问");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateName(field.getName());
        }
        touch(library);
        return LibraryConverter.toLibraryFieldVO(field);
    }

    @Override
    @Transactional
    public void deleteField(UUID libraryId, UUID fieldId) {
        FieldLibrary library = requireLibrary(libraryId);
        FieldDefinition field = fieldDefinitionMapper.findById(fieldId, library.getOwnerId());
        if (field == null || !libraryId.equals(field.getLibraryId())) {
            throw new LibraryItemNotFoundException("字段不存在或无权访问");
        }
        if (fieldDefinitionMapper.deleteById(fieldId, library.getOwnerId()) != 1) {
            throw new LibraryItemNotFoundException("字段不存在或无权访问");
        }
        touch(library);
    }

    @Override
    @Transactional
    public FieldBulkResultVO bulkCreateFields(UUID libraryId, FieldBulkCreateRequest request) {
        FieldLibrary library = requireLibrary(libraryId);
        if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
            throw new InvalidLibraryRequestException("请至少提供一个字段");
        }
        List<String> errors = new ArrayList<>();
        int created = 0;
        int skipped = 0;
        for (FieldDefinitionRequest row : request.getRows()) {
            try {
                persistField(library, row);
                created++;
            } catch (InvalidLibraryRequestException exception) {
                skipped++;
                errors.add(exception.getMessage());
            }
        }
        touch(library);
        return new FieldBulkResultVO(created, skipped, errors);
    }

    @Override
    @Transactional
    public FieldImportResultVO importFromDataset(UUID libraryId, FieldImportRequest request) {
        FieldLibrary library = requireLibrary(libraryId);
        if (request == null || request.getDatasetVersionId() == null) {
            throw new InvalidLibraryRequestException("数据集版本不能为空");
        }
        UUID datasetVersionId = request.getDatasetVersionId();
        String ownerId = library.getOwnerId();
        if (datasetVersionMapper.findById(datasetVersionId, ownerId) == null) {
            throw new InvalidLibraryRequestException("数据集不存在或无权访问");
        }

        Set<UUID> selectedColumns = request.getColumnIds() == null
                ? Set.of()
                : new HashSet<>(request.getColumnIds());
        Set<UUID> selectedTables = request.getTableIds() == null
                ? Set.of()
                : new HashSet<>(request.getTableIds());
        List<FieldCatalogRow> rows = datasetColumnMapper
                .findFieldCatalog(datasetVersionId, ownerId).stream()
                .filter(row -> selectedColumns.isEmpty()
                        || selectedColumns.contains(row.getColumnId()))
                .filter(row -> selectedTables.isEmpty()
                        || selectedTables.contains(row.getTableId()))
                .toList();
        if (rows.isEmpty()) {
            throw new InvalidLibraryRequestException("没有可导入的字段，请先上传数据或重新选择字段");
        }

        Set<String> existingKeys = new HashSet<>();
        for (FieldDefinition field : fieldDefinitionMapper.findByLibraryId(libraryId, ownerId)) {
            if (field.getSourceDatasetId() != null) {
                existingKeys.add(sourceKey(
                        field.getSourceDatasetId(),
                        field.getSourceTableName(),
                        field.getSourceColumnName()));
            }
        }
        Set<UUID> boundColumns = new HashSet<>();
        for (DatasetFieldBinding binding : datasetFieldBindingMapper
                .findByDatasetVersionId(datasetVersionId)) {
            if (binding.getColumnId() != null) {
                boundColumns.add(binding.getColumnId());
            }
        }

        Instant now = Instant.now();
        List<FieldCatalogItemVO> items = new ArrayList<>(rows.size());
        int created = 0;
        int skipped = 0;
        for (FieldCatalogRow row : rows) {
            String key = sourceKey(datasetVersionId, row.getTableName(), row.getColumnName());
            if (existingKeys.contains(key)) {
                skipped++;
                items.add(toCatalogItem(row));
                continue;
            }

            String qualifiedName = sanitizeFieldName(row.getTableName(), row.getColumnName());
            String fieldName = uniqueFieldName(qualifiedName, ownerId);
            FieldDefinition field = new FieldDefinition();
            field.setId(UUID.randomUUID());
            field.setOwnerId(ownerId);
            field.setLibraryId(libraryId);
            field.setName(fieldName);
            field.setDisplayName(truncate(displayText(row), 255));
            field.setDataType(row.getDataType() == null
                    ? FieldDataType.STRING
                    : row.getDataType());
            field.setSourceTable(truncate(
                    ((row.getDatasetName() == null ? "" : row.getDatasetName()) + "."
                            + (row.getTableName() == null ? "" : row.getTableName())).trim(),
                    MAX_SOURCE_TABLE_LENGTH));
            field.setSourceDatasetId(datasetVersionId);
            field.setSourceDatasetName(row.getDatasetName());
            field.setSourceTableName(row.getTableName());
            field.setSourceColumnName(row.getColumnName());
            field.setDescription("从数据集字段自动导入");
            field.setStatus(FieldStatus.ACTIVE);
            field.setCreatedAt(now);
            field.setUpdatedAt(now);
            fieldDefinitionMapper.insert(field);

            if (row.getColumnId() != null && !boundColumns.contains(row.getColumnId())) {
                DatasetFieldBinding binding = new DatasetFieldBinding();
                binding.setId(UUID.randomUUID());
                binding.setOwnerId(ownerId);
                binding.setFieldId(field.getId());
                binding.setDatasetVersionId(datasetVersionId);
                binding.setTableId(row.getTableId());
                binding.setColumnId(row.getColumnId());
                binding.setBindingStatus(BindingStatus.BOUND);
                binding.setCreatedAt(now);
                datasetFieldBindingMapper.insertBatch(List.of(binding));
                boundColumns.add(row.getColumnId());
            }

            existingKeys.add(key);
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
        touch(library);
        return new FieldImportResultVO(datasetVersionId, created, skipped, items);
    }

    private FieldDefinition persistField(FieldLibrary library, FieldDefinitionRequest request) {
        FieldDefinition field = new FieldDefinition();
        field.setId(UUID.randomUUID());
        field.setOwnerId(library.getOwnerId());
        field.setLibraryId(library.getId());
        applyField(field, request, null);
        Instant now = Instant.now();
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        try {
            fieldDefinitionMapper.insert(field);
        } catch (DuplicateKeyException exception) {
            throw duplicateName(field.getName());
        }
        return field;
    }

    private void applyField(
            FieldDefinition field, FieldDefinitionRequest request, UUID excludeId) {
        if (request == null || request.getDataType() == null) {
            throw new InvalidLibraryRequestException("字段类型不能为空");
        }
        String name = trimToNull(request.getName());
        if (name == null) {
            throw new InvalidLibraryRequestException("字段名不能为空");
        }
        if (fieldDefinitionMapper.countByName(name, field.getOwnerId(), excludeId) > 0) {
            throw duplicateName(name);
        }
        String sourceTable = trimToNull(request.getSourceTable());
        String sourceTableName = trimToNull(request.getSourceTableName());
        field.setName(name);
        field.setDisplayName(trimToNull(request.getDisplayName()));
        field.setDataType(request.getDataType());
        field.setSemanticRole(upperOrNull(request.getSemanticRole()));
        field.setSourceTable(sourceTable);
        UUID sourceDatasetId = request.getSourceDatasetId();
        if (sourceDatasetId != null) {
            DatasetVersion dataset = datasetVersionMapper.findById(sourceDatasetId, field.getOwnerId());
            if (dataset == null) {
                throw new InvalidLibraryRequestException("数据集不存在或无权访问");
            }
            field.setSourceDatasetId(sourceDatasetId);
            field.setSourceDatasetName(dataset.getName());
        }
        if (sourceTableName != null) {
            field.setSourceTableName(sourceTableName);
        } else if (field.getSourceDatasetId() == null) {
            field.setSourceTableName(sourceTable);
        }
        String sourceColumnName = trimToNull(request.getSourceColumnName());
        if (sourceColumnName != null) {
            field.setSourceColumnName(sourceColumnName);
        }
        field.setDescription(trimToNull(request.getDescription()));
        field.setStatus(request.getStatus() == null ? FieldStatus.ACTIVE : request.getStatus());
    }

    private void touch(FieldLibrary library) {
        if (library == null) {
            return;
        }
        library.setUpdatedAt(Instant.now());
        fieldLibraryMapper.update(library);
    }

    private FieldLibrary requireLibrary(UUID id) {
        if (id == null) {
            throw new LibraryItemNotFoundException("字段库不存在或无权访问");
        }
        FieldLibrary library = fieldLibraryMapper.findById(id, ownerId());
        if (library == null) {
            throw new LibraryItemNotFoundException("字段库不存在或无权访问");
        }
        return library;
    }

    private String ownerId() {
        return datasetOwnerProvider.currentOwnerId();
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

    private static String sanitizeFieldName(String tableName, String columnName) {
        String raw = (tableName == null ? "" : tableName)
                + "." + (columnName == null ? "" : columnName);
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

    private static String displayText(FieldCatalogRow row) {
        return ((row.getTableName() == null ? "" : row.getTableName()) + "."
                + (row.getColumnName() == null ? "" : row.getColumnName())).trim();
    }

    private static String sourceKey(UUID datasetId, String tableName, String columnName) {
        return datasetId + "|" + (tableName == null ? "" : tableName)
                + "|" + (columnName == null ? "" : columnName);
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

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private static String keywordPattern(String keyword) {
        String value = trimToNull(keyword);
        return value == null ? null : "%" + value.toLowerCase(Locale.ROOT) + "%";
    }

    private String resolveCreateName(String value, String ownerId) {
        String name = trimToNull(value);
        if (name != null) {
            if (name.length() > 128) {
                throw new InvalidLibraryRequestException("字段库名称不能超过 128 个字符");
            }
            return name;
        }
        int maxSequence = 0;
        for (FieldLibrary library : fieldLibraryMapper.findAll(ownerId, null)) {
            Matcher matcher = DEFAULT_NAME_PATTERN.matcher(library.getName());
            if (matcher.matches()) {
                try {
                    maxSequence = Math.max(maxSequence, Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    // Ignore oversized default names and keep the previous sequence.
                }
            }
        }
        return DEFAULT_NAME_PREFIX + (maxSequence + 1);
    }
    private static String requireLibraryName(String value) {
        String name = trimToNull(value);
        if (name == null) {
            throw new InvalidLibraryRequestException("字段库名称不能为空");
        }
        if (name.length() > 128) {
            throw new InvalidLibraryRequestException("字段库名称不能超过 128 个字符");
        }
        return name;
    }

    private static InvalidLibraryRequestException duplicateLibraryName(String name) {
        return new InvalidLibraryRequestException("字段库「" + name + "」已存在");
    }

    private static InvalidLibraryRequestException duplicateName(String name) {
        return new InvalidLibraryRequestException("字段名「" + name + "」已存在");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String upperOrNull(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}