#!/bin/bash
set -euo pipefail

reader_password="${CLICKHOUSE_RULE_READER_PASSWORD:-local-rule-reader-only}"
escaped_password="${reader_password//\'/\'\'}"

clickhouse-client \
  --user "${CLICKHOUSE_USER:-default}" \
  --password "${CLICKHOUSE_PASSWORD:-}" \
  --multiquery <<SQL
CREATE USER IF NOT EXISTS olist_rule_reader
IDENTIFIED WITH sha256_password BY '${escaped_password}';
GRANT SELECT ON olist_datasets.* TO olist_rule_reader;
ALTER USER olist_rule_reader SETTINGS readonly = 1;
SQL
