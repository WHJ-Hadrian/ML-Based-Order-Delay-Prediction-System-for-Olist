package com.olist.datasets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
class FieldLibraryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM field_definitions");
        jdbcTemplate.update("DELETE FROM cleaning_rules");
    }

    @Test
    void createsListsUpdatesAndDeletesField() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "order_id",
                                  "displayName": "订单编号",
                                  "dataType": "STRING",
                                  "semanticRole": "order_id",
                                  "sourceTable": "olist_orders_dataset",
                                  "description": "订单主键"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("order_id"))
                .andExpect(jsonPath("$.semanticRole").value("ORDER_ID"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id")
                .asText();

        mockMvc.perform(get("/api/v1/fields").param("keyword", "订单"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("订单编号"));

        mockMvc.perform(put("/api/v1/fields/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "order_id",
                                  "displayName": "订单主键",
                                  "dataType": "STRING",
                                  "semanticRole": "ORDER_ID",
                                  "sourceTable": "olist_orders_dataset",
                                  "description": "业务确认后的字段定义",
                                  "status": "ARCHIVED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("订单主键"))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(get("/api/v1/fields/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("业务确认后的字段定义"));

        mockMvc.perform(delete("/api/v1/fields/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/fields/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDuplicateFieldNamesAndFiltersByRole() throws Exception {
        mockMvc.perform(post("/api/v1/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "order_id",
                                  "displayName": "订单编号",
                                  "dataType": "STRING",
                                  "semanticRole": "ORDER_ID"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "ORDER_ID",
                                  "dataType": "STRING"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("字段名「ORDER_ID」已存在"));

        mockMvc.perform(post("/api/v1/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "order_status",
                                  "dataType": "STRING",
                                  "semanticRole": "ORDER_STATUS"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/fields").param("semanticRole", "ORDER_ID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("order_id"));

        mockMvc.perform(get("/api/v1/fields").param("status", "ARCHIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectsInvalidFieldName() throws Exception {
        mockMvc.perform(post("/api/v1/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "1order",
                                  "dataType": "STRING"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
