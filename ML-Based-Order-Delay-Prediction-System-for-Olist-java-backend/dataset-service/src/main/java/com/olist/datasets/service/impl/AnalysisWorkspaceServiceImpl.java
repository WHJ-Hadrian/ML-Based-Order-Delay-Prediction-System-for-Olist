package com.olist.datasets.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.datasets.dto.AnalysisWorkspaceRequest;
import com.olist.datasets.entity.AnalysisWorkspace;
import com.olist.datasets.exception.AnalysisWorkspaceConflictException;
import com.olist.datasets.exception.AnalysisWorkspaceNotFoundException;
import com.olist.datasets.exception.InvalidDatasetException;
import com.olist.datasets.exception.InvalidLibraryRequestException;
import com.olist.datasets.mapper.AnalysisWorkspaceMapper;
import com.olist.datasets.security.DatasetOwnerProvider;
import com.olist.datasets.service.AnalysisWorkspaceService;
import com.olist.datasets.service.DatasetService;
import com.olist.datasets.vo.DatasetCatalogVO;
import com.olist.datasets.vo.AnalysisWorkspaceVO;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisWorkspaceServiceImpl implements AnalysisWorkspaceService {

    private static final TypeReference<List<AnalysisWorkspaceRequest.CardRef>> CARD_LIST = new TypeReference<>() {};

    private final AnalysisWorkspaceMapper mapper;
    private final DatasetOwnerProvider ownerProvider;
    private final DatasetService datasetService;
    private final ObjectMapper objectMapper;

    public AnalysisWorkspaceServiceImpl(
            AnalysisWorkspaceMapper mapper,
            DatasetOwnerProvider ownerProvider,
            DatasetService datasetService,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.ownerProvider = ownerProvider;
        this.datasetService = datasetService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnalysisWorkspaceVO> list() {
        return mapper.findByOwner(ownerProvider.currentOwnerId()).stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public AnalysisWorkspaceVO create(AnalysisWorkspaceRequest request) {
        validateRequest(request, false);
        String ownerId = ownerProvider.currentOwnerId();
        String name = normalizeName(request.name());
        validateCards(request.cards());
        requireUniqueName(name, ownerId, null);
        Instant now = Instant.now();
        AnalysisWorkspace workspace = new AnalysisWorkspace();
        workspace.setId(UUID.randomUUID());
        workspace.setOwnerId(ownerId);
        workspace.setName(name);
        workspace.setRevision(1);
        workspace.setCardsJson(serializeCards(request.cards()));
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        try {
            mapper.insert(workspace);
        } catch (DuplicateKeyException exception) {
            throw new AnalysisWorkspaceConflictException("同名分析方案已存在");
        }
        return toVO(workspace);
    }

    @Override
    @Transactional
    public AnalysisWorkspaceVO copy(UUID id, String requestedName) {
        AnalysisWorkspace source = requireWorkspace(id);
        String name = normalizeName(requestedName);
        String ownerId = source.getOwnerId();
        requireUniqueName(name, ownerId, null);
        Instant now = Instant.now();
        AnalysisWorkspace copy = new AnalysisWorkspace();
        copy.setId(UUID.randomUUID());
        copy.setOwnerId(ownerId);
        copy.setName(name);
        copy.setRevision(1);
        copy.setCardsJson(source.getCardsJson());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        try {
            mapper.insert(copy);
        } catch (DuplicateKeyException exception) {
            throw new AnalysisWorkspaceConflictException("同名分析方案已存在");
        }
        return toVO(copy);
    }

    @Override
    @Transactional(readOnly = true)
    public AnalysisWorkspaceVO get(UUID id) {
        return toVO(requireWorkspace(id));
    }

    @Override
    @Transactional
    public AnalysisWorkspaceVO update(UUID id, AnalysisWorkspaceRequest request) {
        validateRequest(request, true);
        AnalysisWorkspace workspace = requireWorkspace(id);
        long expectedRevision = request.expectedRevision();
        if (workspace.getRevision() != expectedRevision) {
            throw new AnalysisWorkspaceConflictException("分析方案已被其他操作更新，请重新载入后再保存");
        }
        String name = normalizeName(request.name());
        validateCards(request.cards());
        requireUniqueName(name, workspace.getOwnerId(), id);
        workspace.setName(name);
        workspace.setCardsJson(serializeCards(request.cards()));
        workspace.setRevision(expectedRevision + 1);
        workspace.setUpdatedAt(Instant.now());
        try {
            if (mapper.update(workspace, expectedRevision) != 1) {
                throw new AnalysisWorkspaceConflictException("分析方案已被其他操作更新，请重新载入后再保存");
            }
        } catch (DuplicateKeyException exception) {
            throw new AnalysisWorkspaceConflictException("同名分析方案已存在");
        }
        return toVO(workspace);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        String ownerId = ownerProvider.currentOwnerId();
        if (mapper.deleteById(id, ownerId) != 1) {
            throw new AnalysisWorkspaceNotFoundException(id);
        }
    }

    private AnalysisWorkspace requireWorkspace(UUID id) {
        AnalysisWorkspace workspace = mapper.findById(id, ownerProvider.currentOwnerId());
        if (workspace == null) {
            throw new AnalysisWorkspaceNotFoundException(id);
        }
        return workspace;
    }

    private void validateRequest(AnalysisWorkspaceRequest request, boolean requireRevision) {
        if (request == null || request.name() == null || request.cards() == null) {
            throw new InvalidLibraryRequestException("分析方案名称和分析卡片不能为空");
        }
        if (requireRevision && (request.expectedRevision() == null || request.expectedRevision() < 1)) {
            throw new InvalidLibraryRequestException("保存分析方案时必须提供有效版本号");
        }
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new InvalidLibraryRequestException("分析方案名称不能为空且不能超过 255 个字符");
        }
        return normalized;
    }

    private void requireUniqueName(String name, String ownerId, UUID excludeId) {
        if (mapper.countByName(ownerId, name, excludeId) > 0) {
            throw new AnalysisWorkspaceConflictException("同名分析方案已存在");
        }
    }

    private void validateCards(List<AnalysisWorkspaceRequest.CardRef> cards) {
        for (AnalysisWorkspaceRequest.CardRef card : cards) {
            DatasetCatalogVO catalog = datasetService.catalog(card.datasetVersionId());
            DatasetCatalogVO.CatalogTableVO table = catalog.tables().stream()
                    .filter(candidate -> candidate.tableId().equals(card.tableId()))
                    .findFirst()
                    .orElseThrow(() -> new InvalidDatasetException("分析卡片中的数据表不属于所选数据版本"));
            boolean columnExists = table.columns().stream()
                    .anyMatch(candidate -> candidate.columnId().equals(card.columnId()));
            if (!columnExists) {
                throw new InvalidDatasetException("分析卡片中的字段不属于所选数据表");
            }
            if (card.relationColumnId() != null) {
                if (card.relationColumnId().equals(card.columnId())) {
                    throw new InvalidDatasetException("关系分析需要选择两个不同的字段");
                }
                boolean relationColumnExists = table.columns().stream()
                        .anyMatch(candidate -> candidate.columnId().equals(card.relationColumnId()));
                if (!relationColumnExists) {
                    throw new InvalidDatasetException("关系分析字段不属于所选数据表");
                }
            }
        }
    }

    private String serializeCards(List<AnalysisWorkspaceRequest.CardRef> cards) {
        try {
            return objectMapper.writeValueAsString(cards);
        } catch (JsonProcessingException exception) {
            throw new InvalidLibraryRequestException("分析卡片无法保存");
        }
    }

    private AnalysisWorkspaceVO toVO(AnalysisWorkspace workspace) {
        try {
            return new AnalysisWorkspaceVO(
                    workspace.getId(),
                    workspace.getName(),
                    workspace.getRevision(),
                    objectMapper.readValue(workspace.getCardsJson(), CARD_LIST),
                    workspace.getCreatedAt(),
                    workspace.getUpdatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("分析方案中保存的卡片数据无效", exception);
        }
    }
}
