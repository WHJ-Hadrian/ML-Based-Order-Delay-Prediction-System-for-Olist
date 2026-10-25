package com.olist.datasets.service.impl;

import com.olist.datasets.convert.LibraryConverter;
import com.olist.datasets.dto.FieldDefinitionQuery;
import com.olist.datasets.dto.FieldDefinitionRequest;
import com.olist.datasets.entity.FieldDefinition;
import com.olist.datasets.enums.FieldStatus;
import com.olist.datasets.exception.InvalidLibraryRequestException;
import com.olist.datasets.exception.LibraryItemNotFoundException;
import com.olist.datasets.mapper.FieldDefinitionMapper;
import com.olist.datasets.security.DatasetOwnerProvider;
import com.olist.datasets.service.FieldLibraryService;
import com.olist.datasets.vo.FieldDefinitionVO;
import com.olist.datasets.vo.PageVO;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FieldLibraryServiceImpl implements FieldLibraryService {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final FieldDefinitionMapper fieldDefinitionMapper;
    private final DatasetOwnerProvider datasetOwnerProvider;

    public FieldLibraryServiceImpl(
            FieldDefinitionMapper fieldDefinitionMapper,
            DatasetOwnerProvider datasetOwnerProvider) {
        this.fieldDefinitionMapper = fieldDefinitionMapper;
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
