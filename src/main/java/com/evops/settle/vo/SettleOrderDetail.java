package com.evops.settle.vo;

import com.evops.settle.entity.SettleItem;
import com.evops.settle.entity.SettleOrder;
import com.evops.settle.entity.SettleStatusLog;
import lombok.Data;

import java.util.List;

/** 结算单详情：结算单本体 + 明细快照 + 完整流转历史。 */
@Data
public class SettleOrderDetail {
    private SettleOrder order;
    private List<SettleItem> items;
    private List<SettleStatusLog> statusLogs;
}
