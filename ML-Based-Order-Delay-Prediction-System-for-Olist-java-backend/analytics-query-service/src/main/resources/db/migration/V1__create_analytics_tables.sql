-- 通用分析平台：指标库、方案与版本、运行记录、系统模板

CREATE TABLE metric_definitions (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    owner_id        VARCHAR(255) NULL,
    metric_key      VARCHAR(100) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(1000) NULL,
    value_type      VARCHAR(16)  NOT NULL,
    expression_json TEXT         NOT NULL,
    format_json     TEXT         NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_metric_owner_key (owner_id, metric_key)
);

CREATE TABLE analysis_plans (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    owner_id        VARCHAR(255) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(1000) NULL,
    template_key    VARCHAR(100) NULL,
    current_version INT          NOT NULL DEFAULT 1,
    status          VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    updated_at      TIMESTAMP(6) NOT NULL,
    KEY idx_plans_owner (owner_id, updated_at)
);

CREATE TABLE analysis_plan_versions (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    plan_id         VARCHAR(36)  NOT NULL,
    version_no      INT          NOT NULL,
    spec_json       LONGTEXT     NOT NULL,
    schema_hash     VARCHAR(64)  NULL,
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_plan_version (plan_id, version_no)
);

CREATE TABLE analysis_runs (
    id                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    owner_id            VARCHAR(255) NOT NULL,
    plan_id             VARCHAR(36)  NULL,
    plan_version        INT          NULL,
    dataset_version_id  VARCHAR(36)  NOT NULL,
    table_id            VARCHAR(36)  NOT NULL,
    spec_json           LONGTEXT     NOT NULL,
    spec_hash           VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    row_count           INT          NULL,
    elapsed_ms          INT          NULL,
    result_columns_json TEXT         NULL,
    result_rows_json    LONGTEXT     NULL,
    result_ref          VARCHAR(500) NULL,
    error_message       VARCHAR(1000) NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    finished_at         TIMESTAMP(6) NULL,
    KEY idx_runs_owner (owner_id, created_at),
    KEY idx_runs_plan (plan_id, created_at)
);

CREATE TABLE analysis_templates (
    template_key VARCHAR(100) NOT NULL PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    description  VARCHAR(1000) NULL,
    category     VARCHAR(64)  NULL,
    spec_json    LONGTEXT     NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL
);
