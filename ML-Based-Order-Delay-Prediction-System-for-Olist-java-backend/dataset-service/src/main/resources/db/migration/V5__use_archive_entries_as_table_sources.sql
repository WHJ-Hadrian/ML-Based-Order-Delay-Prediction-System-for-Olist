UPDATE dataset_tables
SET file_name = CASE
    WHEN table_format = 'XLSX' AND table_name LIKE '% / %' THEN
        CONCAT(SUBSTRING(table_name, 1, LOCATE(' / ', table_name) - 1), '.xlsx')
    WHEN table_format IN ('CSV', 'TSV') THEN
        CONCAT(table_name, '.', LOWER(table_format))
    ELSE file_name
END
WHERE file_name IN (
    SELECT original_filename
    FROM dataset_files
    WHERE LOWER(original_filename) LIKE '%.zip'
);