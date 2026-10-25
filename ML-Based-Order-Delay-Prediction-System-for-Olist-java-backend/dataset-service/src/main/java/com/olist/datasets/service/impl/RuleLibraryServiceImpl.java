package com.olist.datasets.service.impl;

import com.olist.datasets.convert.LibraryConverter;
import com.olist.datasets.dto.CleaningRuleQuery;
import com.olist.datasets.dto.CleaningRuleRequest;
import com.olist.datasets.entity.CleaningRule;
import com.olist.datasets.enums.RuleSeverity;
import com.olist.datasets.exception.InvalidLibraryRequestException;
import com.olist.datasets.exception.LibraryItemNotFoundException;
import com.olist.datasets.mapper.CleaningRuleMapper;
import com.olist.datasets.security.DatasetOwnerProvider;
import com.olist.datasets.service.RuleLibraryService;
import com.olist.datasets.vo.CleaningRuleVO;
import com.olist.datasets.vo.PageVO;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleLibraryServiceImpl implements RuleLibraryService {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PRIORITY = 100;

    private final CleaningRuleMapper cleaningRuleMapper;
    private final DatasetOwnerProvider datasetOwnerProvider;

    public RuleLibraryServiceImpl(
            CleaningRuleMapper cleaningRuleMapper,
            DatasetOwnerProvider datasetOwnerProvider) {
        this.cleaningRuleMapper = cleaningRuleMapper;
        this.datasetOwnerProvider = datasetOwnerProvider;
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<CleaningRuleVO> list(CleaningRuleQuery query) {
        CleaningRuleQuery effective = query == null ? new CleaningRuleQuery() : query;
        int page = Math.max(effective.getPage(), 0);
        int size = Math.min(Math.max(effective.getSize(), MIN_PAGE_SIZE), MAX_PAGE_SIZE);
        String ownerId = datasetOwnerProvider.currentOwnerId();
        String keywordPattern = keywordPattern(effective.getKeyword());
        List<CleaningRuleVO> items = cleaningRuleMapper
                .findPage(
                        keywordPattern,
                        effective.getRuleType(),
                        effective.getEnabled(),
                        ownerId,
                        (long) page * size,
                        size)
                .stream()
                .map(LibraryConverter::toVO)
                .toList();
        long total = cleaningRuleMapper.count(
                keywordPattern, effective.getRuleType(), effective.getEnabled(), ownerId);
        return PageVO.of(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public CleaningRuleVO get(UUID id) {
        return LibraryConverter.toVO(require(id));
    }

    @Override
    @Transactional
    public CleaningRuleVO create(CleaningRuleRequest request) {
        CleaningRule entity = new CleaningRule();
        entity.setId(UUID.randomUUID());
        entity.setOwnerId(datasetOwnerProvider.currentOwnerId());
        apply(entity, request, null);
        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            cleaningRuleMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw duplicateName(entity.getName());
        }
        return LibraryConverter.toVO(entity);
    }

    @Override
    @Transactional
    public CleaningRuleVO update(UUID id, CleaningRuleRequest request) {
        CleaningRule entity = require(id);
        apply(entity, request, id);
        entity.setUpdatedAt(Instant.now());
        try {
            if (cleaningRuleMapper.update(entity) != 1) {
                throw new LibraryItemNotFoundException("规则不存在或无权访问");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateName(entity.getName());
        }
        return LibraryConverter.toVO(entity);
    }

    @Override
    @Transactional
    public CleaningRuleVO setEnabled(UUID id, boolean enabled) {
        CleaningRule entity = require(id);
        entity.setEnabled(enabled);
        entity.setUpdatedAt(Instant.now());
        if (cleaningRuleMapper.update(entity) != 1) {
            throw new LibraryItemNotFoundException("规则不存在或无权访问");
        }
        return LibraryConverter.toVO(entity);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        if (cleaningRuleMapper.deleteById(id, datasetOwnerProvider.currentOwnerId()) != 1) {
            throw new LibraryItemNotFoundException("规则不存在或无权访问");
        }
    }

    private void apply(CleaningRule entity, CleaningRuleRequest request, UUID excludeId) {
        if (request == null || request.getRuleType() == null) {
            throw new InvalidLibraryRequestException("规则类型不能为空");
        }
        String name = trimToNull(request.getName());
        if (name == null) {
            throw new InvalidLibraryRequestException("规则名称不能为空");
        }
        long duplicates = cleaningRuleMapper.countByName(
                name, datasetOwnerProvider.currentOwnerId(), excludeId);
        if (duplicates > 0) {
            throw duplicateName(name);
        }
        entity.setName(name);
        entity.setRuleType(request.getRuleType());
        entity.setTargetField(trimToNull(request.getTargetField()));
        entity.setSeverity(request.getSeverity() == null ? RuleSeverity.WARNING : request.getSeverity());
        entity.setRuleExpression(trimToNull(request.getExpression()));
        entity.setDescription(trimToNull(request.getDescription()));
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        entity.setPriority(request.getPriority() == null ? DEFAULT_PRIORITY : request.getPriority());
    }

    private CleaningRule require(UUID id) {
        if (id == null) {
            throw new LibraryItemNotFoundException("规则不存在或无权访问");
        }
        CleaningRule entity = cleaningRuleMapper.findById(
                id, datasetOwnerProvider.currentOwnerId());
        if (entity == null) {
            throw new LibraryItemNotFoundException("规则不存在或无权访问");
        }
        return entity;
    }

    private InvalidLibraryRequestException duplicateName(String name) {
        return new InvalidLibraryRequestException("规则名称「" + name + "」已存在");
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
}
