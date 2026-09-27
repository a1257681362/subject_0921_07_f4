package com.evops.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 结算接口层：统一返回结构、入口必填校验、生成→流转→详情闭环、规则列表。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettleApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generateThenTransitThenDetail_closedLoop() throws Exception {
        Long machineId = createMachine();
        createCompletedOrder(machineId, "10.00");

        String settleResponse = mockMvc.perform(post("/api/settlements/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineId\":" + machineId + ",\"unitPrice\":100.00,"
                                + "\"operator\":\"张三\",\"requestNo\":\"" + uniq("REQ-API-STG-") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.totalAmount").value(1000.00))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();
        Long settleId = ((Number) JsonPath.read(settleResponse, "$.data.id")).longValue();

        // 非法迁移：DRAFT → PAID 直接拒绝
        mockMvc.perform(post("/api/settlements/" + settleId + "/transit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"PAID\",\"version\":0,\"operator\":\"李四\","
                                + "\"requestNo\":\"" + uniq("REQ-API-STT-") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("不允许")));

        // 合法迁移：DRAFT → CONFIRMED，版本随变更递增
        mockMvc.perform(post("/api/settlements/" + settleId + "/transit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"CONFIRMED\",\"version\":0,\"operator\":\"李四\","
                                + "\"requestNo\":\"" + uniq("REQ-API-STT-") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.version").value(1));

        // 详情：明细 + 流转历史（带版本与操作人）
        mockMvc.perform(get("/api/settlements/" + settleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.statusLogs", hasSize(2)))
                .andExpect(jsonPath("$.data.statusLogs[0].operator").value("张三"))
                .andExpect(jsonPath("$.data.statusLogs[0].toVersion").value(0))
                .andExpect(jsonPath("$.data.statusLogs[1].fromStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.statusLogs[1].toStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.statusLogs[1].operator").value("李四"))
                .andExpect(jsonPath("$.data.statusLogs[1].fromVersion").value(0))
                .andExpect(jsonPath("$.data.statusLogs[1].toVersion").value(1));

        // 列表按状态过滤
        mockMvc.perform(get("/api/settlements").param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void entryValidation_missingOperatorOrVersion_rejected() throws Exception {
        Long machineId = createMachine();
        createCompletedOrder(machineId, "5.00");

        // 生成缺操作人
        mockMvc.perform(post("/api/settlements/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineId\":" + machineId + ",\"unitPrice\":80.00,"
                                + "\"requestNo\":\"" + uniq("REQ-API-STG-") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("操作人")));

        String settleResponse = mockMvc.perform(post("/api/settlements/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineId\":" + machineId + ",\"unitPrice\":80.00,"
                                + "\"operator\":\"张三\",\"requestNo\":\"" + uniq("REQ-API-STG-") + "\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        Long settleId = ((Number) JsonPath.read(settleResponse, "$.data.id")).longValue();

        // 流转缺版本
        mockMvc.perform(post("/api/settlements/" + settleId + "/transit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"CONFIRMED\",\"operator\":\"李四\","
                                + "\"requestNo\":\"" + uniq("REQ-API-STT-") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("版本")));

        // 流转缺操作人
        mockMvc.perform(post("/api/settlements/" + settleId + "/transit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"CONFIRMED\",\"version\":0,"
                                + "\"requestNo\":\"" + uniq("REQ-API-STT-") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("操作人")));
    }

    @Test
    void rulesListed_andToggleTakesEffect() throws Exception {
        String rulesResponse = mockMvc.perform(get("/api/settle-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andReturn().getResponse().getContentAsString();
        java.util.List<Integer> ruleIds = JsonPath.read(rulesResponse,
                "$.data[?(@.fromStatus=='DRAFT' && @.toStatus=='VOID')].id");
        Integer ruleId = ruleIds.get(0);

        // 停用 DRAFT→VOID 后立即生效
        mockMvc.perform(post("/api/settle-rules/" + ruleId + "/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"operator\":\"管理员\","
                                + "\"requestNo\":\"" + uniq("REQ-API-RULE-") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value("0"));

        Long machineId = createMachine();
        createCompletedOrder(machineId, "5.00");
        String settleResponse = mockMvc.perform(post("/api/settlements/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineId\":" + machineId + ",\"unitPrice\":80.00,"
                                + "\"operator\":\"张三\",\"requestNo\":\"" + uniq("REQ-API-STG-") + "\"}"))
                .andReturn().getResponse().getContentAsString();
        Long settleId = ((Number) JsonPath.read(settleResponse, "$.data.id")).longValue();
        mockMvc.perform(post("/api/settlements/" + settleId + "/transit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"VOID\",\"version\":0,\"operator\":\"张三\","
                                + "\"requestNo\":\"" + uniq("REQ-API-STT-") + "\"}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("停用")));

        // 恢复启用，避免影响其他用例
        mockMvc.perform(post("/api/settle-rules/" + ruleId + "/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"operator\":\"管理员\","
                                + "\"requestNo\":\"" + uniq("REQ-API-RULE-") + "\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value("1"));
    }

    private Long createMachine() throws Exception {
        String response = mockMvc.perform(post("/api/machines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineCode\":\"" + uniq("M-API-ST-") + "\",\"machineName\":\"结算接口机\","
                                + "\"requestNo\":\"" + uniq("REQ-API-M-") + "\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private void createCompletedOrder(Long machineId, String trackArea) throws Exception {
        String orderResponse = mockMvc.perform(post("/api/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + uniq("WO-API-ST-") + "\",\"machineId\":" + machineId
                                + ",\"operationType\":\"收割\",\"requestNo\":\"" + uniq("REQ-API-WO-") + "\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        Long orderId = ((Number) JsonPath.read(orderResponse, "$.data.id")).longValue();

        mockMvc.perform(post("/api/work-orders/" + orderId + "/track-area")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackArea\":" + trackArea + ",\"requestNo\":\"" + uniq("REQ-API-TA-") + "\"}"))
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(post("/api/work-orders/" + orderId + "/transit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"IN_PROGRESS\",\"requestNo\":\"" + uniq("REQ-API-WT-") + "\"}"))
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(post("/api/work-orders/" + orderId + "/transit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"COMPLETED\",\"requestNo\":\"" + uniq("REQ-API-WT-") + "\"}"))
                .andExpect(jsonPath("$.success").value(true));
    }

    private String uniq(String prefix) {
        return prefix + System.nanoTime();
    }
}
