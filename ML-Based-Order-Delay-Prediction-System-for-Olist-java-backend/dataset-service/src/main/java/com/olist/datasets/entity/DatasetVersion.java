package com.olist.datasets.entity;

import com.olist.datasets.enums.DatasetStatus;
import java.time.Instant;
import java.util.UUID;

public class DatasetVersion {

    private UUID id;
    private UUID datasetId;
    private int versionNo;
    private UUID parentVersionId;
    private String ownerId;
    private String name;
    private String description;
    private String originalFilename;
    private String storageKey;
    private long sizeBytes;
    private String sha256;
    private String contentType;
    private DatasetStatus status;
    private int tableCount;
    private long rowCount;
    private int fileCount;
    private String manifestHash;
    private String schemaHash;
    private String sourceType;
    private UUID sourceRunId;
    private UUID sourceWorkbookVersionId;
    private String sourceNodeId;
    private Instant readyAt;
    private Instant archivedAt;
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public DatasetVersion() {
    }

    public DatasetVersion(
            UUID id,
            String name,
            String description,
            String originalFilename,
            String storageKey,
            long sizeBytes,
            String sha256,
            String contentType,
            DatasetStatus status,
            int tableCount,
            long rowCount,
            int fileCount,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.contentType = contentType;
        this.status = status;
        this.tableCount = tableCount;
        this.rowCount = rowCount;
        this.fileCount = fileCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(UUID datasetId) {
        this.datasetId = datasetId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(int versionNo) {
        this.versionNo = versionNo;
    }

    public UUID getParentVersionId() {
        return parentVersionId;
    }

    public void setParentVersionId(UUID parentVersionId) {
        this.parentVersionId = parentVersionId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public DatasetStatus getStatus() {
        return status;
    }

    public void setStatus(DatasetStatus status) {
        this.status = status;
    }

    public int getTableCount() {
        return tableCount;
    }

    public void setTableCount(int tableCount) {
        this.tableCount = tableCount;
    }

    public long getRowCount() {
        return rowCount;
    }

    public void setRowCount(long rowCount) {
        this.rowCount = rowCount;
    }

    public int getFileCount() {
        return fileCount;
    }

    public void setFileCount(int fileCount) {
        this.fileCount = fileCount;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    public void setManifestHash(String manifestHash) {
        this.manifestHash = manifestHash;
    }

    public String getSchemaHash() {
        return schemaHash;
    }

    public void setSchemaHash(String schemaHash) {
        this.schemaHash = schemaHash;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public UUID getSourceRunId() {
        return sourceRunId;
    }

    public void setSourceRunId(UUID sourceRunId) {
        this.sourceRunId = sourceRunId;
    }

    public UUID getSourceWorkbookVersionId() {
        return sourceWorkbookVersionId;
    }

    public void setSourceWorkbookVersionId(UUID sourceWorkbookVersionId) {
        this.sourceWorkbookVersionId = sourceWorkbookVersionId;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    public Instant getReadyAt() {
        return readyAt;
    }

    public void setReadyAt(Instant readyAt) {
        this.readyAt = readyAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
