package com.olist.datasets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class RuleLibraryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM cleaning_rules");
        jdbcTemplate.update("DELETE FROM field_definitions");
    }

    @Test
    void createsTogglesUpdatesAndDeletesRule() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "订单时间必填",
                                  "ruleType": "MISSING_VALUE",
                                  "targetField": "order_purchase_timestamp",
                                  "expression": "order_purchase_timestamp IS NOT NULL",
                                  "description": "下单时间缺失的记录需要剔除"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.severity").value("WARNING"))
                .andExpect(jsonPath("$.priority").value(100))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id")
                .asText();

        mockMvc.perform(patch("/api/v1/rules/{id}/enabled", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/v1/rules").param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(put("/api/v1/rules/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "订单时间必填",
                                  "ruleType": "MISSING_VALUE",
                                  "targetField": "order_purchase_timestamp",
                                  "severity": "ERROR",
                                  "expression": "order_purchase_timestamp IS NOT NULL",
                                  "description": "下单时间缺失的记录需要剔除",
                                  "enabled": true,
                                  "priority": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("ERROR"))
                .andExpect(jsonPath("$.priority").value(10))
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/v1/rules").param("ruleType", "MISSING_VALUE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(delete("/api/v1/rules/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/rules/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDuplicateNamesAndInvalidPriority() throws Exception {
        mockMvc.perform(post("/api/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "类目筛选",
                                  "ruleType": "CATEGORY_FILTER"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "类目筛选",
                                  "ruleType": "CATEGORY_FILTER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("规则名称「类目筛选」已存在"));

        mockMvc.perform(post("/api/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "异常优先级",
                                  "ruleType": "CUSTOM",
                                  "priority": 20000
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
