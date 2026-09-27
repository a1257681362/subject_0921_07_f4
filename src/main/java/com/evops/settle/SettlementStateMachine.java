package com.evops.settle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.settle.entity.SettleTransitionRule;
import com.evops.settle.mapper.SettleTransitionRuleMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 结算单状态机。迁移规则以 t_settle_transition_rule 配置为准（可在线启停，重启不丢），
 * 每次校验实时读库，规则调整即刻生效。
 *
 * 这是所有状态变更的唯一校验入口：任何 from→to 必须先过 check()，
 * 规则不存在或已停用即拒绝，杜绝状态乱跳。
 */
@Component
public class SettlementStateMachine {

    private final SettleTransitionRuleMapper ruleMapper;

    public SettlementStateMachine(SettleTransitionRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /** 校验 from→to 是否放行；未配置或已停用即抛业务异常。 */
    public void check(String from, String to) {
        SettleTransitionRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<SettleTransitionRule>()
                .eq(SettleTransitionRule::getFromStatus, from)
                .eq(SettleTransitionRule::getToStatus, to));
        if (rule == null) {
            throw new BusinessException("结算单状态不允许从 " + from + " 流转到 " + to + "（未配置的迁移）");
        }
        if (!"1".equals(rule.getEnabled())) {
            throw new BusinessException("结算单状态从 " + from + " 到 " + to + " 的迁移规则已停用");
        }
    }

    /** 全部迁移规则（含停用），按登记顺序返回。 */
    public List<SettleTransitionRule> rules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<SettleTransitionRule>()
                .orderByAsc(SettleTransitionRule::getId));
    }
}
