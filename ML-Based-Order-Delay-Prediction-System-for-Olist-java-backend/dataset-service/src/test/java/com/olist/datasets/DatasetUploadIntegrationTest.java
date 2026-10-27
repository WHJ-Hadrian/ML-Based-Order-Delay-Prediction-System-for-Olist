package com.olist.datasets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.datasets.config.TableSearchProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
class DatasetUploadIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TableSearchProperties tableSearchProperties;

    @BeforeEach
    void cleanStorageAndDatabase() throws IOException {
        jdbcTemplate.update("DELETE FROM rule_workbooks");
        jdbcTemplate.update("DELETE FROM dataset_field_bindings");
        jdbcTemplate.update("DELETE FROM dataset_versions");
        jdbcTemplate.update("DELETE FROM datasets");
        jdbcTemplate.update("DELETE FROM field_definitions");
        jdbcTemplate.update("DELETE FROM storage_blobs");
        deleteDirectory(Path.of("target/test-datasets"));
    }

    @Test
    void createsEmptyDatasetsWithSequentialDefaultNames() throws Exception {
        mockMvc.perform(post("/api/v1/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("数据集1"))
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.fileCount").value(0))
                .andExpect(jsonPath("$.tableCount").value(0))
                .andExpect(jsonPath("$.rowCount").value(0));

        mockMvc.perform(post("/api/v1/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("数据集2"));

        mockMvc.perform(post("/api/v1/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数据集9\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("数据集10"));
    }

    @Test
    void scopesDatasetAccessToAuthenticatedOwner() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/datasets")
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"private dataset\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID datasetId = UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("id")
                .asText());

        mockMvc.perform(get("/api/v1/datasets").with(jwt().jwt(token -> token.subject("owner-b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.items", hasSize(0)));

        mockMvc.perform(get("/api/v1/datasets/{id}", datasetId)
                        .with(jwt().jwt(token -> token.subject("owner-b"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/datasets/{id}", datasetId)
                        .with(jwt().jwt(token -> token.subject("owner-b"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/datasets/{id}", datasetId)
                        .with(jwt().jwt(token -> token.subject("owner-a"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("private dataset"));
    }

    @Test
    void rejectsMalformedJsonAsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数无效"))
                .andExpect(jsonPath("$.detail").value("请求正文格式不正确"));
    }

    @Test
    void renamesDatasetAndRejectsBlankName() throws Exception {
        UUID id = createEmptyDataset(null);

        mockMvc.perform(patch("/api/v1/datasets/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Olist 订单分析\",\"description\":\"用于延误预测\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Olist 订单分析"))
                .andExpect(jsonPath("$.description").value("用于延误预测"));

        mockMvc.perform(patch("/api/v1/datasets/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数无效"));

        mockMvc.perform(get("/api/v1/datasets/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Olist 订单分析"));
    }

    @Test
    void uploadsFilesToExistingDatasetAndAggregatesStats() throws Exception {
        UUID id = createEmptyDataset("Olist 原始数据");

        MvcResult firstUpload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
                        .file(csvFile("olist_orders_dataset.csv", "order_id,status\n1,delivered\n2,shipped\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.fileCount").value(1))
                .andExpect(jsonPath("$.tableCount").value(1))
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.originalFilename").value("olist_orders_dataset.csv"))
                .andExpect(jsonPath("$.files", hasSize(1)))
                .andExpect(jsonPath("$.tables", hasSize(1)))
                .andExpect(jsonPath("$.tables[0].name").value("olist_orders_dataset"))
                .andExpect(jsonPath("$.tables[0].fileName").value("olist_orders_dataset.csv"))
                .andExpect(jsonPath("$.tables[0].format").value("CSV"))
                .andExpect(jsonPath("$.tables[0].rowCount").value(2))
                .andExpect(jsonPath("$.tables[0].columnCount").value(2))
                .andReturn();

        JsonNode firstBody = objectMapper.readTree(firstUpload.getResponse().getContentAsString());
        UUID fileId = UUID.fromString(firstBody.path("files").get(0).path("id").asText());
        Path stored = Path.of(
                "target/test-datasets/datasets",
                id.toString(),
                "files",
                fileId.toString(),
                "olist_orders_dataset.csv");
        assertThat(stored).exists();

        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
                        .file(tsvFile("olist_products_dataset.tsv", "product_id\tcategory\n1\ttoy\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileCount").value(2))
                .andExpect(jsonPath("$.tableCount").value(2))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.originalFilename").value("olist_orders_dataset.csv"))
                .andExpect(jsonPath("$.files", hasSize(2)))
                .andExpect(jsonPath("$.tables", hasSize(2)))
                .andExpect(jsonPath("$.tables[1].fileName").value("olist_products_dataset.tsv"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dataset_files", Long.class))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dataset_tables", Long.class))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT column_name FROM dataset_columns WHERE dataset_version_id = ?",
                        String.class,
                        id))
                .containsExactlyInAnyOrder("order_id", "status", "product_id", "category");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_columns WHERE dataset_version_id = ? AND logical_type = 'STRING' AND nullable = TRUE",
                        Long.class,
                        id))
                .isEqualTo(4L);
    }

    @Test
    void marksUploadedDatasetReadyAndRejectsFurtherMutations() throws Exception {
        UUID id = createEmptyDataset("ready-dataset");
        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
                        .file(csvFile("orders.csv", "order_id,status\n1,delivered\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"));

        MvcResult ready = mockMvc.perform(post("/api/v1/datasets/{id}/ready", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.datasetId").isNotEmpty())
                .andExpect(jsonPath("$.datasetVersionId").value(id.toString()))
                .andExpect(jsonPath("$.manifestHash").isNotEmpty())
                .andExpect(jsonPath("$.schemaHash").isNotEmpty())
                .andExpect(jsonPath("$.readyAt").isNotEmpty())
                .andReturn();
        JsonNode readyBody = objectMapper.readTree(ready.getResponse().getContentAsString());
        String manifestHash = readyBody.path("manifestHash").asText();
        UUID logicalDatasetId = UUID.fromString(readyBody.path("datasetId").asText());

        mockMvc.perform(get("/api/v1/datasets/{id}/readiness", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.manifestHash").value(manifestHash));

        mockMvc.perform(post("/api/v1/datasets/{id}/ready", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifestHash").value(manifestHash));

        MvcResult appended = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
                        .file(csvFile("more.csv", "id,value\n1,x\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.datasetId").value(logicalDatasetId.toString()))
                .andExpect(jsonPath("$.fileCount").value(2))
                .andExpect(jsonPath("$.tableCount").value(2))
                .andExpect(jsonPath("$.rowCount").value(2))
                .andReturn();
        UUID secondVersionId = UUID.fromString(objectMapper
                .readTree(appended.getResponse().getContentAsString())
                .path("id")
                .asText());
        assertThat(secondVersionId).isNotEqualTo(id);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT version_no FROM dataset_versions WHERE id = ?",
                        Integer.class,
                        secondVersionId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT parent_version_id FROM dataset_versions WHERE id = ?",
                        String.class,
                        secondVersionId))
                .isEqualTo(id.toString());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT manifest_hash FROM dataset_versions WHERE id = ?",
                        String.class,
                        secondVersionId))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT current_version_id FROM datasets WHERE id = ?",
                        String.class,
                        logicalDatasetId))
                .isEqualTo(id.toString());

        mockMvc.perform(post("/api/v1/datasets/{id}/ready", secondVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.datasetVersionId").value(secondVersionId.toString()));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT current_version_id FROM datasets WHERE id = ?",
                        String.class,
                        logicalDatasetId))
                .isEqualTo(secondVersionId.toString());

        mockMvc.perform(get("/api/v1/datasets/{id}", logicalDatasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(secondVersionId.toString()))
                .andExpect(jsonPath("$.datasetVersionId").value(secondVersionId.toString()));

        mockMvc.perform(get("/api/v1/datasets/{id}/versions/current", logicalDatasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(secondVersionId.toString()))
                .andExpect(jsonPath("$.datasetId").value(logicalDatasetId.toString()));

        mockMvc.perform(get("/api/v1/datasets/{id}/versions", logicalDatasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].datasetVersionId").value(secondVersionId.toString()))
                .andExpect(jsonPath("$[0].versionNo").value(2))
                .andExpect(jsonPath("$[0].status").value("READY"))
                .andExpect(jsonPath("$[1].versionNo").value(1));
    }

    @Test
    @Transactional
    void removesStoredFileWhenUploadTransactionRollsBack() throws Exception {
        UUID datasetId = createEmptyDataset("rollback-upload");
        TestTransaction.flagForRollback();

        MvcResult upload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", datasetId)
                        .file(csvFile("rollback.csv", "id,value\n1,test\n")))
                .andExpect(status().isOk())
                .andReturn();
        UUID fileId = UUID.fromString(objectMapper
                .readTree(upload.getResponse().getContentAsString())
                .get("files")
                .get(0)
                .get("id")
                .asText());
        Path storedFile = Path.of(
                "target/test-datasets/datasets", datasetId.toString(), "files", fileId.toString(), "rollback.csv");
        assertThat(storedFile).exists();

        TestTransaction.end();

        assertThat(storedFile).doesNotExist();
    }

    @Test
    void parsesZipArchiveIntoTables() throws Exception {
        UUID id = createEmptyDataset("压缩包数据");

        MvcResult upload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
                        .file(new MockMultipartFile(
                                "file", "olist_raw_bundle.zip", "application/zip", zipBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileCount").value(1))
                .andExpect(jsonPath("$.tableCount").value(2))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.tables", hasSize(2)))
                .andExpect(jsonPath("$.tables[0].fileName").value("olist_orders_dataset.csv"))
                .andExpect(jsonPath("$.tables[0].name").value("olist_orders_dataset"))
                .andExpect(jsonPath("$.tables[1].name").value("olist_products_dataset"))
                .andExpect(jsonPath("$.tables[1].fileName").value("olist_products_dataset.tsv"))
                .andReturn();

        UUID tableId = UUID.fromString(objectMapper.readTree(upload.getResponse().getContentAsString())
                .path("tables")
                .get(0)
                .path("id")
                .asText());
        mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/search", id, tableId)
                        .param("keyword", "ship")
                        .param("column", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMatches").value(1))
                .andExpect(jsonPath("$.rows[0].rowNumber").value(2))
                .andExpect(jsonPath("$.rows[0].values[1]").value("shipped"));
    }

    @Test
    void parsesXlsxAndZipWithEmbeddedXlsx() throws Exception {
        UUID xlsxId = createEmptyDataset("Excel 数据");
        MvcResult xlsxUpload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", xlsxId)
                        .file(new MockMultipartFile(
                                "file", "olist_analytics.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                xlsxBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(2))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.tables[0].name").value("orders"))
                .andExpect(jsonPath("$.tables[1].name").value("items"))
                .andExpect(jsonPath("$.tables[0].fileName").value("olist_analytics.xlsx"))
                .andReturn();
        assertThat(jdbcTemplate.queryForList(
                        "SELECT column_name FROM dataset_columns WHERE dataset_version_id = ?",
                        String.class,
                        xlsxId))
                .containsExactlyInAnyOrder("order_id", "status", "item_id", "product_id");
        UUID xlsxTableId = UUID.fromString(objectMapper
                .readTree(xlsxUpload.getResponse().getContentAsString())
                .path("tables")
                .get(0)
                .path("id")
                .asText());
        mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/search", xlsxId, xlsxTableId)
                        .param("keyword", "shipped")
                        .param("column", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMatches").value(1))
                .andExpect(jsonPath("$.rows[0].rowNumber").value(2));

        UUID zipId = createEmptyDataset("压缩 Excel");
        MvcResult zipUpload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", zipId)
                        .file(new MockMultipartFile(
                                "file", "excel-bundle.zip", "application/zip", zipWithXlsxBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(2))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.tables[0].fileName").value("excel/olist_analytics.xlsx"))
                .andExpect(jsonPath("$.tables[0].name").value("olist_analytics / orders"))
                .andExpect(jsonPath("$.tables[1].name").value("olist_analytics / items"))
                .andReturn();
        UUID zippedTableId = UUID.fromString(objectMapper
                .readTree(zipUpload.getResponse().getContentAsString())
                .path("tables")
                .get(0)
                .path("id")
                .asText());
        mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/search", zipId, zippedTableId)
                        .param("keyword", "delivered")
                        .param("column", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMatches").value(1))
                .andExpect(jsonPath("$.rows[0].rowNumber").value(1));
    }

    @Test
    void previewsTableHeadTailAndAllRows() throws Exception {
        UUID id = createEmptyDataset("表预览测试");
        MvcResult upload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
                        .file(csvFile(
                                "orders.csv",
                                "order_id,status\n1,delivered\n2,shipped\n3,processing\n4,shipped\n5,canceled\n")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(upload.getResponse().getContentAsString());
        UUID tableId = UUID.fromString(body.path("tables").get(0).path("id").asText());

        mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/preview", id, tableId)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(2)))
                .andExpect(jsonPath("$.columns[0]").value("order_id"))
                .andExpect(jsonPath("$.columns[1]").value("status"))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0][0]").value("1"))
                .andExpect(jsonPath("$.rows[0][1]").value("delivered"))
                .andExpect(jsonPath("$.rows[1][1]").value("shipped"))
                .andExpect(jsonPath("$.tailRows", hasSize(2)))
                .andExpect(jsonPath("$.tailRows[0][0]").value("4"))
                .andExpect(jsonPath("$.tailRows[1][0]").value("5"))
                .andExpect(jsonPath("$.tailRows[1][1]").value("canceled"))
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.truncated").value(true));

        mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/preview", id, tableId)
                        .param("limit", "100")
                        .param("mode", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(5)))
                .andExpect(jsonPath("$.tailRows", hasSize(0)))
                .andExpect(jsonPath("$.rows[4][0]").value("5"))
                .andExpect(jsonPath("$.rows[4][1]").value("canceled"))
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.truncated").value(false));

        mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/rows", id, tableId)
                        .param("offset", "1")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offset").value(1))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0][0]").value("2"))
                .andExpect(jsonPath("$.rows[1][0]").value("3"));
        mockMvc.perform(get(
                        "/api/v1/datasets/{datasetId}/tables/{tableId}/preview",
                        id,
                        UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("数据表不存在"));
    }

    @Test
    void searchesTableRowsByColumnAndPaginatesMatches() throws Exception {
        UUID datasetId = createEmptyDataset("行搜索测试");
        MvcResult upload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", datasetId)
                        .file(csvFile(
                                "orders.csv",
                                "id,status,region\n"
                                        + "1,delivered,North\n"
                                        + "2,shipped,South\n"
                                        + "3,delivered,West\n"
                                        + "4,shipped,North\n"
                                        + "5,shipped,East\n")))
                .andExpect(status().isOk())
                .andReturn();
        UUID tableId = UUID.fromString(objectMapper.readTree(upload.getResponse().getContentAsString())
                .path("tables")
                .get(0)
                .path("id")
                .asText());

        mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/search", datasetId, tableId)
                        .param("keyword", "ShIp")
                        .param("column", "1")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[1]").value("status"))
                .andExpect(jsonPath("$.totalMatches").value(3))
                .andExpect(jsonPath("$.scannedRows").value(5))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].rowNumber").value(2))
                .andExpect(jsonPath("$.rows[1].rowNumber").value(4));

        mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/search", datasetId, tableId)
                        .param("keyword", "north")
                        .param("column", "2")
                        .param("offset", "1")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMatches").value(2))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].rowNumber").value(4));

        mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/search", datasetId, tableId)
                        .param("keyword", "not-found"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMatches").value(0))
                .andExpect(jsonPath("$.rows", hasSize(0)));

        mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/search", datasetId, tableId)
                        .param("keyword", "north")
                        .param("column", "-2"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsWhenRowSearchReachesConfiguredScanLimit() throws Exception {
        UUID datasetId = createEmptyDataset("行搜索截断测试");
        MvcResult upload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", datasetId)
                        .file(csvFile("orders.csv", "id,status\n1,other\n2,target\n3,target\n")))
                .andExpect(status().isOk())
                .andReturn();
        UUID tableId = UUID.fromString(objectMapper.readTree(upload.getResponse().getContentAsString())
                .path("tables")
                .get(0)
                .path("id")
                .asText());
        long originalMaxScannedRows = tableSearchProperties.getMaxScannedRows();

        try {
            tableSearchProperties.setMaxScannedRows(2);
            mockMvc.perform(get("/api/v1/datasets/{datasetId}/tables/{tableId}/search", datasetId, tableId)
                            .param("keyword", "target"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scannedRows").value(2))
                    .andExpect(jsonPath("$.totalMatches").value(1))
                    .andExpect(jsonPath("$.truncated").value(true))
                    .andExpect(jsonPath("$.rows", hasSize(1)))
                    .andExpect(jsonPath("$.rows[0].rowNumber").value(2));
        } finally {
            tableSearchProperties.setMaxScannedRows(originalMaxScannedRows);
        }
    }

    @Test
    void renamesAndDeletesTablesThenDeletesDataset() throws Exception {
        UUID datasetId = createEmptyDataset("表操作测试");
        MvcResult firstUpload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", datasetId)
                        .file(csvFile("orders.csv", "id,status\n1,delivered\n2,shipped\n")))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult secondUpload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", datasetId)
                        .file(csvFile("customers.csv", "id,name\n1,ana\n2,bruno\n3,carla\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(2))
                .andExpect(jsonPath("$.rowCount").value(5))
                .andReturn();

        JsonNode firstTable = objectMapper
                .readTree(firstUpload.getResponse().getContentAsString())
                .get("tables")
                .get(0);
        UUID firstTableId = UUID.fromString(firstTable.get("id").asText());
        UUID firstFileId = UUID.fromString(objectMapper
                .readTree(firstUpload.getResponse().getContentAsString())
                .get("files")
                .get(0)
                .get("id")
                .asText());
        UUID secondFileId = null;
        for (JsonNode file : objectMapper
                .readTree(secondUpload.getResponse().getContentAsString())
                .get("files")) {
            if ("customers.csv".equals(file.get("originalFilename").asText())) {
                secondFileId = UUID.fromString(file.get("id").asText());
            }
        }
        UUID secondTableId = null;
        for (JsonNode table : objectMapper
                .readTree(secondUpload.getResponse().getContentAsString())
                .get("tables")) {
            if ("customers.csv".equals(table.get("fileName").asText())) {
                secondTableId = UUID.fromString(table.get("id").asText());
            }
        }
        assertThat(secondFileId).isNotNull();
        assertThat(secondTableId).isNotNull();
        Path firstStoredFile = Path.of(
                "target/test-datasets/datasets", datasetId.toString(), "files", firstFileId.toString(), "orders.csv");
        Path secondStoredFile = Path.of(
                "target/test-datasets/datasets", datasetId.toString(), "files", secondFileId.toString(), "customers.csv");
        assertThat(firstStoredFile).exists();
        assertThat(secondStoredFile).exists();

        MvcResult renamed = mockMvc.perform(
                        patch("/api/v1/datasets/{datasetId}/tables/{tableId}", datasetId, firstTableId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"订单主表\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode renamedDetail = objectMapper.readTree(renamed.getResponse().getContentAsString());
        assertThat(renamedDetail.get("tables").get(0).get("name").asText()).isEqualTo("订单主表");

        mockMvc.perform(delete("/api/v1/datasets/{datasetId}/tables/{tableId}", datasetId, firstTableId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(1))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.fileCount").value(1));
        assertThat(firstStoredFile).doesNotExist();
        assertThat(secondStoredFile).exists();

        mockMvc.perform(delete("/api/v1/datasets/{datasetId}/tables/{tableId}", datasetId, secondTableId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(0))
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.fileCount").value(0))
                .andExpect(jsonPath("$.status").value("EMPTY"));
        assertThat(secondStoredFile).doesNotExist();

        mockMvc.perform(delete("/api/v1/datasets/{id}", datasetId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/datasets/{id}", datasetId))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyMultipartUploadStillCreatesDataset() throws Exception {
        mockMvc.perform(multipart("/api/v1/datasets")
                        .file(csvFile("orders.csv", "id\n1\n2\n"))
                        .param("name", "旧入口数据集")
                        .param("description", "兼容旧版上传"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("旧入口数据集"))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.fileCount").value(1))
                .andExpect(jsonPath("$.tableCount").value(1))
                .andExpect(jsonPath("$.rowCount").value(2));
    }

    @Test
    void filtersByHasDataSearchFormatAndPaginates() throws Exception {
        createEmptyDataset("空数据集 A");
        createEmptyDataset("空数据集 B");

        UUID uploadedId = createEmptyDataset("含数据 CSV");
        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", uploadedId)
                        .file(csvFile("olist_orders.csv", "id\n1\n")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/datasets").param("hasData", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("含数据 CSV"));

        mockMvc.perform(get("/api/v1/datasets").param("hasData", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/v1/datasets")
                        .param("keyword", "CSV")
                        .param("format", "CSV"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/datasets")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items", hasSize(1)));

        mockMvc.perform(get("/api/v1/datasets")
                        .param("page", String.valueOf(Integer.MAX_VALUE))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void rejectsUnsupportedBrokenAndEmptyFiles() throws Exception {
        UUID id = createEmptyDataset("无效文件测试");

        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
                        .file(new MockMultipartFile(
                                "file", "notes.txt", "text/plain", "not a dataset".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("上传请求无效"));

        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
                        .file(new MockMultipartFile(
                                "file", "broken.zip", "application/zip", "not a zip".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("上传请求无效"));

        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
                        .file(new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("上传请求无效"));
    }

    @Test
    void returnsNotFoundForUnknownDataset() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/datasets/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("数据集不存在"));

        mockMvc.perform(patch("/api/v1/datasets/{id}", unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不存在\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", unknown)
                        .file(csvFile("orders.csv", "id\n1\n")))
                .andExpect(status().isNotFound());
    }

    @Test
    void migratesFieldBindingsWhenAddingFileToReadyVersion() throws Exception {
        UUID version1 = createEmptyDataset("binding-migration");
        MvcResult upload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", version1)
                        .file(csvFile("orders.csv", "order_id,status\n1,delivered\n")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(upload.getResponse().getContentAsString());
        UUID logicalDatasetId = UUID.fromString(body.path("datasetId").asText());
        UUID tableId = UUID.fromString(body.path("tables").get(0).path("id").asText());
        String columnId = jdbcTemplate.queryForObject(
                "SELECT id FROM dataset_columns WHERE table_id = ? AND column_name = ?",
                String.class,
                tableId,
                "order_id");
        String ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM dataset_versions WHERE id = ?",
                String.class,
                version1);

        UUID boundFieldId = UUID.randomUUID();
        UUID rebindFieldId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        insertField(boundFieldId, ownerId, "order_id", now);
        insertField(rebindFieldId, ownerId, "status", now);
        jdbcTemplate.update(
                "INSERT INTO dataset_field_bindings (id, owner_id, field_id, dataset_version_id, table_id, column_id, binding_status, created_at) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), ownerId, boundFieldId, version1, tableId, UUID.fromString(columnId), "BOUND", now);
        jdbcTemplate.update(
                "INSERT INTO dataset_field_bindings (id, owner_id, field_id, dataset_version_id, table_id, column_id, binding_status, created_at) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), ownerId, rebindFieldId, version1, null, null, "NEEDS_REBIND", now);

        mockMvc.perform(post("/api/v1/datasets/{id}/ready", version1))
                .andExpect(status().isOk());

        MvcResult appended = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", version1)
                        .file(tsvFile("products.tsv", "product_id\tcategory\n1\ttoy\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andReturn();
        JsonNode appendedBody = objectMapper.readTree(appended.getResponse().getContentAsString());
        UUID version2 = UUID.fromString(appendedBody.path("id").asText());
        UUID newTableId = null;
        for (JsonNode table : appendedBody.path("tables")) {
            if ("orders.csv".equals(table.path("fileName").asText())) {
                newTableId = UUID.fromString(table.path("id").asText());
            }
        }
        assertThat(newTableId).isNotNull();

        String newColumnId = jdbcTemplate.queryForObject(
                "SELECT id FROM dataset_columns WHERE table_id = ? AND column_name = ?",
                String.class,
                newTableId,
                "order_id");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT dataset_version_id FROM dataset_field_bindings WHERE field_id = ? AND dataset_version_id = ?",
                        String.class,
                        boundFieldId,
                        version2))
                .isEqualTo(version2.toString());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT table_id FROM dataset_field_bindings WHERE field_id = ? AND dataset_version_id = ?",
                        String.class,
                        boundFieldId,
                        version2))
                .isEqualTo(newTableId.toString());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT column_id FROM dataset_field_bindings WHERE field_id = ? AND dataset_version_id = ?",
                        String.class,
                        boundFieldId,
                        version2))
                .isEqualTo(newColumnId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT binding_status FROM dataset_field_bindings WHERE field_id = ? AND dataset_version_id = ?",
                        String.class,
                        boundFieldId,
                        version2))
                .isEqualTo("BOUND");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT table_id FROM dataset_field_bindings WHERE field_id = ? AND dataset_version_id = ?",
                        String.class,
                        rebindFieldId,
                        version2))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT column_id FROM dataset_field_bindings WHERE field_id = ? AND dataset_version_id = ?",
                        String.class,
                        rebindFieldId,
                        version2))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT binding_status FROM dataset_field_bindings WHERE field_id = ? AND dataset_version_id = ?",
                        String.class,
                        rebindFieldId,
                        version2))
                .isEqualTo("NEEDS_REBIND");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_files WHERE dataset_id = ?", Long.class, version2))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_tables WHERE dataset_id = ?", Long.class, version2))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_files WHERE dataset_id = ?", Long.class, version1))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_tables WHERE dataset_id = ?", Long.class, version1))
                .isEqualTo(1L);
        String parentPhysicalTable = jdbcTemplate.queryForObject(
                "SELECT physical_table FROM dataset_tables WHERE dataset_id = ? AND table_name = ?",
                String.class,
                version1,
                "orders");
        String clonedPhysicalTable = jdbcTemplate.queryForObject(
                "SELECT physical_table FROM dataset_tables WHERE dataset_id = ? AND table_name = ?",
                String.class,
                version2,
                "orders");
        assertThat(clonedPhysicalTable).isNotBlank().isNotEqualTo(parentPhysicalTable);

        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", version1)
                        .file(csvFile("extra.csv", "id\n1\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(version2.toString()))
                .andExpect(jsonPath("$.fileCount").value(3))
                .andExpect(jsonPath("$.tableCount").value(3));

        mockMvc.perform(post("/api/v1/datasets/{id}/ready", version2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT current_version_id FROM datasets WHERE id = ?",
                        String.class,
                        logicalDatasetId))
                .isEqualTo(version2.toString());
    }

    @Test
    void backfillsColumnsForLegacyVersionOnReady() throws Exception {
        UUID versionId = createEmptyDataset("legacy-columns");
        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", versionId)
                        .file(csvFile("legacy.csv", "order_id,status\n1,delivered\n")))
                .andExpect(status().isOk());
        jdbcTemplate.update("DELETE FROM dataset_columns WHERE dataset_version_id = ?", versionId);

        mockMvc.perform(post("/api/v1/datasets/{id}/ready", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_columns WHERE dataset_version_id = ?",
                        Long.class,
                        versionId))
                .isEqualTo(2L);
    }
    @Test
    void returnsCatalogAndExecutionSnapshotForReadyVersion() throws Exception {
        UUID versionId = createEmptyDataset("catalog-dataset");
        MvcResult upload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", versionId)
                        .file(csvFile("orders.csv", "order_id,status\n1,delivered\n")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode uploadBody = objectMapper.readTree(upload.getResponse().getContentAsString());
        UUID logicalDatasetId = UUID.fromString(uploadBody.path("datasetId").asText());
        UUID tableId = UUID.fromString(uploadBody.path("tables").get(0).path("id").asText());
        mockMvc.perform(post("/api/v1/datasets/{id}/ready", versionId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/datasets/{id}/catalog", logicalDatasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetId").value(logicalDatasetId.toString()))
                .andExpect(jsonPath("$.datasetVersionId").value(versionId.toString()))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.manifestHash").isNotEmpty())
                .andExpect(jsonPath("$.schemaHash").isNotEmpty())
                .andExpect(jsonPath("$.tables", hasSize(1)))
                .andExpect(jsonPath("$.tables[0].tableId").value(tableId.toString()))
                .andExpect(jsonPath("$.tables[0].tableKey").isNotEmpty())
                .andExpect(jsonPath("$.tables[0].tableName").value("orders"))
                .andExpect(jsonPath("$.tables[0].columns", hasSize(2)))
                .andExpect(jsonPath("$.tables[0].columns[0].name").value("order_id"))
                .andExpect(jsonPath("$.tables[0].columns[0].logicalType").value("STRING"))
                .andExpect(jsonPath("$.tables[0].columns[0].nullable").value(true))
                .andExpect(jsonPath("$.tables[0].physicalTable").doesNotExist())
                .andExpect(jsonPath("$.tables[0].columns[0].physicalName").doesNotExist());

        mockMvc.perform(get("/api/v1/dataset-versions/{id}/catalog", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersionId").value(versionId.toString()));

        mockMvc.perform(get("/internal/dataset-versions/{id}/execution-snapshot", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersionId").value(versionId.toString()))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.clickhouseDatabase").value("olist_datasets"))
                .andExpect(jsonPath("$.tables[0].tableId").value(tableId.toString()))
                .andExpect(jsonPath("$.tables[0].tableKey").isNotEmpty())
                .andExpect(jsonPath("$.tables[0].tableName").value("orders"))
                .andExpect(jsonPath("$.tables[0].physicalTable").isNotEmpty())
                .andExpect(jsonPath("$.tables[0].rowCount").value(1))
                .andExpect(jsonPath("$.tables[0].columns[0].sourceName").value("order_id"))
                .andExpect(jsonPath("$.tables[0].columns[0].logicalType").value("STRING"))
                .andExpect(jsonPath("$.tables[0].columns[0].physicalName").value("c000"))
                .andExpect(jsonPath("$.tables[0].columns[0].clickhouseType").value("Nullable(String)"));

        jdbcTemplate.update("UPDATE dataset_columns SET physical_name = NULL WHERE table_id = ?", tableId);

        mockMvc.perform(get("/internal/dataset-versions/{id}/execution-snapshot", versionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("数据集未就绪"));

        UUID notReady = createEmptyDataset("not-ready");
        mockMvc.perform(get("/api/v1/datasets/{id}/catalog", notReady))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("数据集未就绪"));
    }

    @Test
    void deletingClonedTableKeepsParentVersionBlob() throws Exception {
        UUID version1 = createEmptyDataset("shared-blob");
        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", version1)
                        .file(csvFile("orders.csv", "order_id,status\n1,delivered\n")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/datasets/{id}/ready", version1))
                .andExpect(status().isOk());

        MvcResult appended = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", version1)
                        .file(csvFile("customers.csv", "customer_id,name\n1,ana\n")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode appendedBody = objectMapper.readTree(appended.getResponse().getContentAsString());
        UUID version2 = UUID.fromString(appendedBody.path("id").asText());
        UUID clonedOrdersTableId = null;
        for (JsonNode table : appendedBody.path("tables")) {
            if ("orders.csv".equals(table.path("fileName").asText())) {
                clonedOrdersTableId = UUID.fromString(table.path("id").asText());
            }
        }
        assertThat(clonedOrdersTableId).isNotNull();
        String storageKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM dataset_files WHERE dataset_id = ? AND original_filename = ?",
                String.class,
                version1,
                "orders.csv");
        Path stored = Path.of("target/test-datasets").resolve(storageKey);
        assertThat(stored).exists();

        mockMvc.perform(delete(
                        "/api/v1/datasets/{datasetId}/tables/{tableId}", version2, clonedOrdersTableId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(1));

        assertThat(stored).exists();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_files WHERE storage_key = ?",
                        Long.class,
                        storageKey))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_files WHERE dataset_id = ?",
                        Long.class,
                        version1))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_tables WHERE dataset_id = ?",
                        Long.class,
                        version1))
                .isEqualTo(1L);
    }

    @Test
    void archivesReferencedDatasetInsteadOfHardDeleting() throws Exception {
        UUID versionId = createEmptyDataset("referenced-dataset");
        MvcResult upload = mockMvc.perform(multipart("/api/v1/datasets/{id}/files", versionId)
                        .file(csvFile("orders.csv", "order_id,status\n1,delivered\n")))
                .andExpect(status().isOk())
                .andReturn();
        UUID logicalDatasetId = UUID.fromString(objectMapper
                .readTree(upload.getResponse().getContentAsString())
                .path("datasetId")
                .asText());
        mockMvc.perform(post("/api/v1/datasets/{id}/ready", versionId))
                .andExpect(status().isOk());

        String ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM dataset_versions WHERE id = ?", String.class, versionId);
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO rule_workbooks (id, owner_id, name, description, dataset_version_id, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), ownerId, "referenced-workbook", null, versionId, "DRAFT", now, now);

        mockMvc.perform(delete("/api/v1/datasets/{id}", logicalDatasetId))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT deleted_at FROM datasets WHERE id = ?", String.class, logicalDatasetId))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT archived_at FROM dataset_versions WHERE id = ?", String.class, versionId))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_files WHERE dataset_id = ?", Long.class, versionId))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_tables WHERE dataset_id = ?", Long.class, versionId))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM rule_workbooks WHERE dataset_version_id = ?", Long.class, versionId))
                .isEqualTo(1L);

        mockMvc.perform(get("/api/v1/datasets/{id}", logicalDatasetId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/datasets/{id}", versionId))
                .andExpect(status().isNotFound());
    }

    private void insertField(UUID id, String ownerId, String name, Timestamp now) {
        jdbcTemplate.update(
                "INSERT INTO field_definitions (id, owner_id, name, display_name, data_type, semantic_role, source_table, description, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id,
                ownerId,
                name,
                name,
                "STRING",
                "ORDER_ID",
                "orders",
                null,
                "ACTIVE",
                now,
                now);
    }

    private UUID createEmptyDataset(String name) throws Exception {
        String body = name == null ? "{}" : objectMapper.writeValueAsString(java.util.Map.of("name", name));
        MvcResult result = mockMvc.perform(post("/api/v1/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("id").asText());
    }

    private MockMultipartFile csvFile(String filename, String content) {
        return new MockMultipartFile(
                "file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile tsvFile(String filename, String content) {
        return new MockMultipartFile(
                "file", filename, "text/tab-separated-values", content.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] zipBytes() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("olist_orders_dataset.csv"));
            zip.write("order_id,status\n1,delivered\n2,shipped\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("olist_products_dataset.tsv"));
            zip.write("product_id\tcategory\n1\ttoy\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private byte[] zipWithXlsxBytes() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("excel/olist_analytics.xlsx"));
            zip.write(xlsxBytes());
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private byte[] xlsxBytes() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet orders = workbook.createSheet("orders");
            Row ordersHeader = orders.createRow(0);
            ordersHeader.createCell(0).setCellValue("order_id");
            ordersHeader.createCell(1).setCellValue("status");
            orders.createRow(1).createCell(0).setCellValue("1");
            orders.getRow(1).createCell(1).setCellValue("delivered");
            orders.createRow(2).createCell(0).setCellValue("2");
            orders.getRow(2).createCell(1).setCellValue("shipped");

            Sheet items = workbook.createSheet("items");
            Row itemsHeader = items.createRow(0);
            itemsHeader.createCell(0).setCellValue("item_id");
            itemsHeader.createCell(1).setCellValue("product_id");
            items.createRow(1).createCell(0).setCellValue("1");
            items.getRow(1).createCell(1).setCellValue("100");

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }
}
