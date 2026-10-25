INSERT INTO dataset_files (
    id, dataset_id, original_filename, storage_key, size_bytes, sha256, content_type, created_at
)
SELECT id, id, original_filename, storage_key, size_bytes, sha256, content_type, created_at
FROM dataset_versions
WHERE file_count = 0
  AND size_bytes > 0
  AND storage_key <> ''
  AND NOT EXISTS (
      SELECT 1 FROM dataset_files existing WHERE existing.dataset_id = dataset_versions.id
  );

UPDATE dataset_versions
SET file_count = 1
WHERE file_count = 0
  AND size_bytes > 0
  AND storage_key <> '';

UPDATE dataset_tables
SET file_id = dataset_id,
    file_name = (
        SELECT dataset_versions.original_filename
        FROM dataset_versions
        WHERE dataset_versions.id = dataset_tables.dataset_id
    )
WHERE file_id IS NULL;
