package com.evops.settle;

/**
 * 结算单状态常量。合法迁移不由本类硬编码——以 t_settle_transition_rule 配置为准
 * （见 SettlementStateMachine），新增/停用迁移只改配置，不改代码。
 */
public final class SettleStatus {
    /** 草稿：已生成未确认。 */
    public static final String DRAFT = "DRAFT";
    /** 已确认：金额与明细核对无误。 */
    public static final String CONFIRMED = "CONFIRMED";
    /** 已付款：终态。 */
    public static final String PAID = "PAID";
    /** 已作废：终态，作业单已释放可重新结算。 */
    public static final String VOID = "VOID";

    private SettleStatus() {
    }
}
