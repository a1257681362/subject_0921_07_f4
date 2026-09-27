package com.evops.settle.controller;

import com.evops.common.ApiResponse;
import com.evops.settle.SettleRuleService;
import com.evops.settle.dto.SettleRuleToggleRequest;
import com.evops.settle.entity.SettleTransitionRule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/settle-rules")
public class SettleRuleController {

    private final SettleRuleService settleRuleService;

    public SettleRuleController(SettleRuleService settleRuleService) {
        this.settleRuleService = settleRuleService;
    }

    /** 全部迁移规则（含停用）。 */
    @GetMapping
    public ApiResponse<List<SettleTransitionRule>> list() {
        return ApiResponse.ok(settleRuleService.list());
    }

    /** 启停迁移规则：即刻生效，走请求号台账。 */
    @PostMapping("/{id}/toggle")
    public ApiResponse<SettleTransitionRule> toggle(@PathVariable Long id,
                                                    @Valid @RequestBody SettleRuleToggleRequest request) {
        return ApiResponse.ok(settleRuleService.setEnabled(id, request));
    }
}
