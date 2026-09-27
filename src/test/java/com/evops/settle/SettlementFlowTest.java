package com.evops.settle;

import com.evops.common.BusinessException;
import com.evops.machine.MachineService;
import com.evops.machine.dto.MachineCreateRequest;
import com.evops.machine.entity.Machine;
import com.evops.settle.dto.SettleGenerateRequest;
import com.evops.settle.dto.SettleRuleToggleRequest;
import com.evops.settle.dto.SettleTransitRequest;
import com.evops.settle.entity.SettleOrder;
import com.evops.settle.entity.SettleTransitionRule;
import com.evops.settle.vo.SettleOrderDetail;
import com.evops.workorder.WorkOrderService;
import com.evops.workorder.WorkOrderStatus;
import com.evops.workorder.dto.StatusTransitRequest;
import com.evops.workorder.dto.TrackAreaRequest;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结算单状态机主链路：生成 → 确认 → 付款；非法迁移、版本、规则配置、作废释放、历史完整。 */
@SpringBootTest
@ActiveProfiles("test")
class SettlementFlowTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MachineService machineService;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private WorkOrderMapper workOrderMapper;
    @Autowired
    private SettlementService settlementService;
    @Autowired
    private SettleRuleService settleRuleService;

    @Test
    void fullLifecycle_draftConfirmPaid_historyCompleteWithVersionAndOperator() {
        Machine machine = createMachine();
        completeOrder(machine.getId(), new BigDecimal("10.00"));
        completeOrder(machine.getId(), new BigDecimal("5.50"));

        SettleOrder settle = settlementService.generate(generateReq(machine.getId(), "100.00", "张三"));
        assertEquals(SettleStatus.DRAFT, settle.getStatus());
        assertEquals(2, settle.getOrderCount());
        assertEquals(0, new BigDecimal("15.50").compareTo(settle.getTotalArea()));
        assertEquals(0, new BigDecimal("1550.00").compareTo(settle.getTotalAmount()));
        assertEquals(0, settle.getVersion());
        assertNotNull(settle.getSettleNo());

        SettleOrder confirmed = settlementService.transit(settle.getId(),
                transitReq(SettleStatus.CONFIRMED, 0, "李四"));
        assertEquals(SettleStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(1, confirmed.getVersion(), "每次变更版本递增");

        SettleOrder paid = settlementService.transit(settle.getId(),
                transitReq(SettleStatus.PAID, 1, "王五"));
        assertEquals(SettleStatus.PAID, paid.getStatus());
        assertEquals(2, paid.getVersion());

        SettleOrderDetail detail = settlementService.detail(settle.getId());
        assertEquals(2, detail.getItems().size(), "明细逐单快照");
        assertEquals(3, detail.getStatusLogs().size(), "生成/确认/付款三条流转，历史完整");
        assertNull(detail.getStatusLogs().get(0).getFromStatus());
        assertEquals(SettleStatus.DRAFT, detail.getStatusLogs().get(0).getToStatus());
        assertEquals("张三", detail.getStatusLogs().get(0).getOperator());
        assertEquals(Integer.valueOf(0), detail.getStatusLogs().get(0).getToVersion());
        assertEquals(SettleStatus.DRAFT, detail.getStatusLogs().get(1).getFromStatus());
        assertEquals(SettleStatus.CONFIRMED, detail.getStatusLogs().get(1).getToStatus());
        assertEquals("李四", detail.getStatusLogs().get(1).getOperator());
        assertEquals(Integer.valueOf(0), detail.getStatusLogs().get(1).getFromVersion());
        assertEquals(Integer.valueOf(1), detail.getStatusLogs().get(1).getToVersion());
        assertEquals("王五", detail.getStatusLogs().get(2).getOperator());
        assertEquals(Integer.valueOf(1), detail.getStatusLogs().get(2).getFromVersion());
        assertEquals(Integer.valueOf(2), detail.getStatusLogs().get(2).getToVersion());
        detail.getStatusLogs().forEach(log -> assertNotNull(log.getRequestNo(), "每次变更带请求号"));
    }

    @Test
    void illegalTransitions_rejected() {
        Machine machine = createMachine();
        completeOrder(machine.getId(), new BigDecimal("8.00"));
        SettleOrder settle = settlementService.generate(generateReq(machine.getId(), "80.00", "张三"));

        // 草稿不能直接付款（跳状态）
        BusinessException skip = assertThrows(BusinessException.class,
                () -> settlementService.transit(settle.getId(), transitReq(SettleStatus.PAID, 0, "李四")));
        assertTrue(skip.getMessage().contains("不允许"));
        // 原地踏步也是非法迁移
        assertThrows(BusinessException.class,
                () -> settlementService.transit(settle.getId(), transitReq(SettleStatus.DRAFT, 0, "李四")));
        // 未知目标状态
        assertThrows(BusinessException.class,
                () -> settlementService.transit(settle.getId(), transitReq("ARCHIVED", 0, "李四")));

        // 付款是终态：任何再流转都拒绝
        settlementService.transit(settle.getId(), transitReq(SettleStatus.CONFIRMED, 0, "李四"));
        settlementService.transit(settle.getId(), transitReq(SettleStatus.PAID, 1, "王五"));
        BusinessException afterPaid = assertThrows(BusinessException.class,
                () -> settlementService.transit(settle.getId(), transitReq(SettleStatus.VOID, 2, "王五")));
        assertTrue(afterPaid.getMessage().contains("不允许"));

        // 作废也是终态
        Machine machine2 = createMachine();
        completeOrder(machine2.getId(), new BigDecimal("3.00"));
        SettleOrder settle2 = settlementService.generate(generateReq(machine2.getId(), "80.00", "张三"));
        settlementService.transit(settle2.getId(), transitReq(SettleStatus.VOID, 0, "张三"));
        assertThrows(BusinessException.class,
                () -> settlementService.transit(settle2.getId(), transitReq(SettleStatus.CONFIRMED, 1, "张三")));
    }

    @Test
    void staleVersion_rejected() {
        Machine machine = createMachine();
        completeOrder(machine.getId(), new BigDecimal("6.00"));
        SettleOrder settle = settlementService.generate(generateReq(machine.getId(), "80.00", "张三"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> settlementService.transit(settle.getId(), transitReq(SettleStatus.CONFIRMED, 7, "李四")));
        assertTrue(ex.getMessage().contains("版本"));

        // 版本校验在状态机之前：版本不对时即使迁移合法也拒绝，且状态保持 DRAFT
        assertEquals(SettleStatus.DRAFT, settlementService.detail(settle.getId()).getOrder().getStatus());
    }

    @Test
    void ruleConfigurable_disableBlocks_enableRestores() {
        Machine machine = createMachine();
        completeOrder(machine.getId(), new BigDecimal("4.00"));
        SettleOrder settle = settlementService.generate(generateReq(machine.getId(), "80.00", "张三"));

        SettleTransitionRule rule = settleRuleService.list().stream()
                .filter(r -> SettleStatus.DRAFT.equals(r.getFromStatus())
                        && SettleStatus.CONFIRMED.equals(r.getToStatus()))
                .findFirst().orElseThrow(IllegalStateException::new);

        // 停用 DRAFT→CONFIRMED：迁移立即被拒
        settleRuleService.setEnabled(rule.getId(), toggleReq(false, "管理员"));
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> settlementService.transit(settle.getId(), transitReq(SettleStatus.CONFIRMED, 0, "李四")));
        assertTrue(blocked.getMessage().contains("停用"));

        // 重新启用：迁移恢复放行
        settleRuleService.setEnabled(rule.getId(), toggleReq(true, "管理员"));
        SettleOrder confirmed = settlementService.transit(settle.getId(),
                transitReq(SettleStatus.CONFIRMED, 0, "李四"));
        assertEquals(SettleStatus.CONFIRMED, confirmed.getStatus());
    }

    @Test
    void voidReleasesWorkOrders_forResettlement() {
        Machine machine = createMachine();
        WorkOrder order = completeOrder(machine.getId(), new BigDecimal("9.00"));
        SettleOrder settle = settlementService.generate(generateReq(machine.getId(), "80.00", "张三"));
        assertEquals(settle.getId(), workOrderMapper.selectById(order.getId()).getSettleId(),
                "生成后作业单被结算单占用");

        // 已被占用的作业单不再进入新结算单
        BusinessException none = assertThrows(BusinessException.class,
                () -> settlementService.generate(generateReq(machine.getId(), "80.00", "张三")));
        assertTrue(none.getMessage().contains("没有可结算"));

        settlementService.transit(settle.getId(), transitReq(SettleStatus.VOID, 0, "张三"));
        assertNull(workOrderMapper.selectById(order.getId()).getSettleId(), "作废后释放作业单");

        SettleOrder regen = settlementService.generate(generateReq(machine.getId(), "80.00", "张三"));
        assertEquals(1, regen.getOrderCount(), "释放后可重新结算");
    }

    @Test
    void replayRequestNo_rejected() {
        Machine machine = createMachine();
        completeOrder(machine.getId(), new BigDecimal("2.00"));

        SettleGenerateRequest req = generateReq(machine.getId(), "80.00", "张三");
        settlementService.generate(req);
        // 同一请求号重放 → 拒绝（请求号台账唯一约束）
        assertThrows(BusinessException.class, () -> settlementService.generate(req));

        SettleOrder settle = settlementService.list(null, null).stream()
                .filter(s -> s.getRequestNo().equals(req.getRequestNo()))
                .findFirst().orElseThrow(IllegalStateException::new);
        SettleTransitRequest transit = transitReq(SettleStatus.CONFIRMED, 0, "李四");
        settlementService.transit(settle.getId(), transit);
        assertThrows(BusinessException.class,
                () -> settlementService.transit(settle.getId(), transit));
    }

    @Test
    void noEligibleOrders_rejected() {
        Machine machine = createMachine();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> settlementService.generate(generateReq(machine.getId(), "80.00", "张三")));
        assertTrue(ex.getMessage().contains("没有可结算"));
    }

    private Machine createMachine() {
        MachineCreateRequest req = new MachineCreateRequest();
        req.setMachineCode(uniq("M-ST-"));
        req.setMachineName("结算测试机");
        req.setRequestNo(uniq("REQ-M-"));
        return machineService.create(req);
    }

    /** 建档 → 轨迹面积 → 开工 → 完工，返回完工作业单。 */
    private WorkOrder completeOrder(Long machineId, BigDecimal trackArea) {
        WorkOrderCreateRequest create = new WorkOrderCreateRequest();
        create.setOrderNo(uniq("WO-ST-"));
        create.setMachineId(machineId);
        create.setOperationType("收割");
        create.setRequestNo(uniq("REQ-WO-"));
        WorkOrder order = workOrderService.create(create);

        TrackAreaRequest track = new TrackAreaRequest();
        track.setTrackArea(trackArea);
        track.setRequestNo(uniq("REQ-TRACK-"));
        workOrderService.reportTrackArea(order.getId(), track);

        workOrderService.transit(order.getId(), workOrderTransit(WorkOrderStatus.IN_PROGRESS));
        return workOrderService.transit(order.getId(), workOrderTransit(WorkOrderStatus.COMPLETED));
    }

    private StatusTransitRequest workOrderTransit(String target) {
        StatusTransitRequest req = new StatusTransitRequest();
        req.setTargetStatus(target);
        req.setRequestNo(uniq("REQ-WT-"));
        return req;
    }

    private SettleGenerateRequest generateReq(Long machineId, String unitPrice, String operator) {
        SettleGenerateRequest req = new SettleGenerateRequest();
        req.setMachineId(machineId);
        req.setUnitPrice(new BigDecimal(unitPrice));
        req.setOperator(operator);
        req.setRequestNo(uniq("REQ-STG-"));
        return req;
    }

    private SettleTransitRequest transitReq(String target, Integer version, String operator) {
        SettleTransitRequest req = new SettleTransitRequest();
        req.setTargetStatus(target);
        req.setVersion(version);
        req.setOperator(operator);
        req.setRequestNo(uniq("REQ-STT-"));
        return req;
    }

    private SettleRuleToggleRequest toggleReq(boolean enabled, String operator) {
        SettleRuleToggleRequest req = new SettleRuleToggleRequest();
        req.setEnabled(enabled);
        req.setOperator(operator);
        req.setRequestNo(uniq("REQ-RULE-"));
        return req;
    }

    private String uniq(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }
}
