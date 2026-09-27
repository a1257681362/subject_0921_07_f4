package com.evops.settle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 结算状态迁移规则（配置化）：enabled=1 才放行；在线启停走请求号台账。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_settle_transition_rule")
public class SettleTransitionRule extends BaseEntity {
    private String fromStatus;
    private String toStatus;
    /** 1 启用 / 0 停用。 */
    private String enabled;
    private String remark;

    @Version
    private Integer version = 0;
}
