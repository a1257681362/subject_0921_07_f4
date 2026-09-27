package com.evops.settle.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 结算单生成请求：圈定农机已完工未结算作业单，按单价快照金额。 */
@Data
public class SettleGenerateRequest {
    @NotNull(message = "农机不能为空")
    private Long machineId;
    @NotNull(message = "结算单价不能为空")
    @DecimalMin(value = "0.01", message = "结算单价必须大于0")
    private BigDecimal unitPrice;
    /** 结算周期（按轨迹核算时间过滤），可不传表示不限。 */
    private LocalDate periodStart;
    private LocalDate periodEnd;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    private String remark;
}
