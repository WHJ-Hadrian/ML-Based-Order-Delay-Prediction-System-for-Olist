package com.olist.datasets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

    @BeforeEach
    void cleanStorageAndDatabase() throws IOException {
        jdbcTemplate.update("DELETE FROM dataset_versions");
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
    }

    @Test
    void parsesZipArchiveIntoTables() throws Exception {
        UUID id = createEmptyDataset("压缩包数据");

        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
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
                .andExpect(jsonPath("$.tables[1].fileName").value("olist_products_dataset.tsv"));
    }

    @Test
    void parsesXlsxAndZipWithEmbeddedXlsx() throws Exception {
        UUID xlsxId = createEmptyDataset("Excel 数据");
        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", xlsxId)
                        .file(new MockMultipartFile(
                                "file", "olist_analytics.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                xlsxBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(2))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.tables[0].name").value("orders"))
                .andExpect(jsonPath("$.tables[1].name").value("items"))
                .andExpect(jsonPath("$.tables[0].fileName").value("olist_analytics.xlsx"));

        UUID zipId = createEmptyDataset("压缩 Excel");
        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", zipId)
                        .file(new MockMultipartFile(
                                "file", "excel-bundle.zip", "application/zip", zipWithXlsxBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(2))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.tables[0].fileName").value("excel/olist_analytics.xlsx"))
                .andExpect(jsonPath("$.tables[0].name").value("olist_analytics / orders"))
                .andExpect(jsonPath("$.tables[1].name").value("olist_analytics / items"));
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
        UUID secondTableId = null;
        for (JsonNode table : objectMapper
                .readTree(secondUpload.getResponse().getContentAsString())
                .get("tables")) {
            if ("customers.csv".equals(table.get("fileName").asText())) {
                secondTableId = UUID.fromString(table.get("id").asText());
            }
        }
        assertThat(secondTableId).isNotNull();

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

        mockMvc.perform(delete("/api/v1/datasets/{datasetId}/tables/{tableId}", datasetId, secondTableId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(0))
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.fileCount").value(0))
                .andExpect(jsonPath("$.status").value("EMPTY"));

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
