package com.evops.settle.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 结算明细：逐单快照作业单号/权威面积/单价/金额；历史保留，作废不删行。 */
@Data
@TableName("t_settle_item")
public class SettleItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long settleId;
    private Long workOrderId;
    private String orderNo;
    private String operationType;
    /** 轨迹核算面积（权威口径快照）。 */
    private BigDecimal trackArea;
    private BigDecimal unitPrice;
    private BigDecimal amount;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
