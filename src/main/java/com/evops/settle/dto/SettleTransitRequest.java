package com.evops.settle.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 状态流转请求：所有状态变更的统一入口报文，必须带目标状态、版本、操作人、请求号。 */
@Data
public class SettleTransitRequest {
    @NotBlank(message = "目标状态不能为空")
    private String targetStatus;
    @NotNull(message = "版本号不能为空")
    private Integer version;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    private String remark;
}
