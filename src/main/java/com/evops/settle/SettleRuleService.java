package com.evops.settle;

import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.request.RequestRecordService;
import com.evops.settle.dto.SettleRuleToggleRequest;
import com.evops.settle.entity.SettleTransitionRule;
import com.evops.settle.mapper.SettleTransitionRuleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 迁移规则在线管理：启停即改库，状态机下次校验即刻生效（规则实时读库，无缓存）。
 * 启停同样走请求号台账，规则行带乐观锁。
 */
@Service
public class SettleRuleService {

    private final SettleTransitionRuleMapper ruleMapper;
    private final RequestRecordService requestRecordService;

    public SettleRuleService(SettleTransitionRuleMapper ruleMapper,
                             RequestRecordService requestRecordService) {
        this.ruleMapper = ruleMapper;
        this.requestRecordService = requestRecordService;
    }

    public List<SettleTransitionRule> list() {
        return ruleMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<SettleTransitionRule>().orderByAsc(SettleTransitionRule::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public SettleTransitionRule setEnabled(Long id, SettleRuleToggleRequest req) {
        SettleTransitionRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException("迁移规则不存在: " + id);
        }
        String action = Boolean.TRUE.equals(req.getEnabled()) ? "ENABLE" : "DISABLE";
        requestRecordService.register(req.getRequestNo(), BizType.SETTLE_RULE_UPDATE,
                "RULE#" + id + " " + action + " by " + req.getOperator());

        rule.setEnabled(Boolean.TRUE.equals(req.getEnabled()) ? "1" : "0");
        if (req.getRemark() != null) {
            rule.setRemark(req.getRemark());
        }
        if (ruleMapper.updateById(rule) == 0) {
            throw new BusinessException("迁移规则已变化，请刷新后重试");
        }
        return rule;
    }
}
