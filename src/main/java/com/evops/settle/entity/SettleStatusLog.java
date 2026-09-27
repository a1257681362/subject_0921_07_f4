package com.evops.settle.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 结算单状态流转日志：只追加、不修改；每次变更带前后状态、前后版本、操作人、请求号。 */
@Data
@TableName("t_settle_status_log")
public class SettleStatusLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long settleId;
    private String settleNo;
    private String fromStatus;
    private String toStatus;
    /** 变更前版本（乐观锁版本）。 */
    private Integer fromVersion;
    /** 变更后版本。 */
    private Integer toVersion;
    /** 操作人（业务姓名，由请求方携带，入口统一校验非空）。 */
    private String operator;
    private String requestNo;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
