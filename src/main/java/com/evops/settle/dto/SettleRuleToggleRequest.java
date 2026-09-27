package com.evops.settle.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 迁移规则启停请求。 */
@Data
public class SettleRuleToggleRequest {
    @NotNull(message = "启停标记不能为空")
    private Boolean enabled;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    private String remark;
}
