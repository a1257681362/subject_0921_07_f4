package com.evops.settle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 结算单：一台农机一段时间内已完工作业单的汇总结算，金额生成时快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_settle_order")
public class SettleOrder extends BaseEntity {
    private String settleNo;
    private Long machineId;
    /** 农机编码/名称为生成时快照，农机后续改名不影响已生成结算单。 */
    private String machineCode;
    private String machineName;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Integer orderCount;
    /** 汇总轨迹核算面积（亩）。 */
    private BigDecimal totalArea;
    /** 结算单价（元/亩），生成时给定并快照。 */
    private BigDecimal unitPrice;
    /** 汇总金额（元）= Σ 明细 轨迹面积 × 单价。 */
    private BigDecimal totalAmount;
    private String status;
    private String remark;
    private String requestNo;

    @Version
    private Integer version = 0;
}
