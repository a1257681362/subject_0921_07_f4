package com.evops.settle.controller;

import com.evops.common.ApiResponse;
import com.evops.settle.SettlementService;
import com.evops.settle.dto.SettleGenerateRequest;
import com.evops.settle.dto.SettleTransitRequest;
import com.evops.settle.entity.SettleOrder;
import com.evops.settle.vo.SettleOrderDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** 生成结算单：圈定农机已完工未结算作业单，按单价快照金额。 */
    @PostMapping("/generate")
    public ApiResponse<SettleOrder> generate(@Valid @RequestBody SettleGenerateRequest request) {
        return ApiResponse.ok(settlementService.generate(request));
    }

    /** 状态流转统一入口：版本 + 状态机规则校验，非法迁移即拒绝。 */
    @PostMapping("/{id}/transit")
    public ApiResponse<SettleOrder> transit(@PathVariable Long id,
                                            @Valid @RequestBody SettleTransitRequest request) {
        return ApiResponse.ok(settlementService.transit(id, request));
    }

    /** 结算单详情：本体 + 明细 + 完整流转历史。 */
    @GetMapping("/{id}")
    public ApiResponse<SettleOrderDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(settlementService.detail(id));
    }

    /** 列表检索：可按状态、结算单号过滤。 */
    @GetMapping
    public ApiResponse<List<SettleOrder>> list(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String settleNo) {
        return ApiResponse.ok(settlementService.list(status, settleNo));
    }
}
