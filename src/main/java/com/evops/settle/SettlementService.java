package com.evops.settle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.machine.entity.Machine;
import com.evops.machine.mapper.MachineMapper;
import com.evops.request.RequestRecordService;
import com.evops.settle.dto.SettleGenerateRequest;
import com.evops.settle.dto.SettleTransitRequest;
import com.evops.settle.entity.SettleItem;
import com.evops.settle.entity.SettleOrder;
import com.evops.settle.entity.SettleStatusLog;
import com.evops.settle.mapper.SettleItemMapper;
import com.evops.settle.mapper.SettleOrderMapper;
import com.evops.settle.mapper.SettleStatusLogMapper;
import com.evops.settle.vo.SettleOrderDetail;
import com.evops.workorder.WorkOrderStatus;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.mapper.WorkOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 结算单主链路。
 *
 * 生成：圈定农机已完工、未红冲、未被占用的作业单，金额 = 轨迹核算面积 × 单价逐单快照，
 * 下游直接可用，不再二次加工；作业单占用标记、结算单、明细、初始日志同一事务。
 *
 * 流转：所有状态变更只走 {@link #transit} 统一入口——请求号台账登记 → 版本校验 →
 * 状态机规则校验（配置化），任一不通过即拒绝；变更落库带版本递增，流转日志记录
 * 前后状态、前后版本、操作人、请求号，历史只追加不修改。
 */
@Service
public class SettlementService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger NO_SEQ = new AtomicInteger();

    private final SettlementStateMachine stateMachine;
    private final SettleOrderMapper settleOrderMapper;
    private final SettleItemMapper settleItemMapper;
    private final SettleStatusLogMapper statusLogMapper;
    private final WorkOrderMapper workOrderMapper;
    private final MachineMapper machineMapper;
    private final RequestRecordService requestRecordService;

    public SettlementService(SettlementStateMachine stateMachine,
                             SettleOrderMapper settleOrderMapper,
                             SettleItemMapper settleItemMapper,
                             SettleStatusLogMapper statusLogMapper,
                             WorkOrderMapper workOrderMapper,
                             MachineMapper machineMapper,
                             RequestRecordService requestRecordService) {
        this.stateMachine = stateMachine;
        this.settleOrderMapper = settleOrderMapper;
        this.settleItemMapper = settleItemMapper;
        this.statusLogMapper = statusLogMapper;
        this.workOrderMapper = workOrderMapper;
        this.machineMapper = machineMapper;
        this.requestRecordService = requestRecordService;
    }

    /**
     * 生成结算单。作业单占用用条件更新（settle_id 仍为空才写入）兜底并发：
     * 两笔生成抢同一批作业单时，后到者占用失败、整体回滚，不会重复结算。
     */
    @Transactional(rollbackFor = Exception.class)
    public SettleOrder generate(SettleGenerateRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.SETTLE_GENERATE,
                String.valueOf(req.getMachineId()));

        Machine machine = machineMapper.selectById(req.getMachineId());
        if (machine == null) {
            throw new BusinessException("农机不存在: " + req.getMachineId());
        }
        if (req.getPeriodStart() != null && req.getPeriodEnd() != null
                && req.getPeriodStart().isAfter(req.getPeriodEnd())) {
            throw new BusinessException("结算周期起不能晚于止");
        }

        List<WorkOrder> orders = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getMachineId, req.getMachineId())
                .eq(WorkOrder::getStatus, WorkOrderStatus.COMPLETED)
                .eq(WorkOrder::getReversalFlag, "0")
                .isNull(WorkOrder::getSettleId)
                .ge(req.getPeriodStart() != null, WorkOrder::getTrackTime,
                        req.getPeriodStart() == null ? null : req.getPeriodStart().atStartOfDay())
                .lt(req.getPeriodEnd() != null, WorkOrder::getTrackTime,
                        req.getPeriodEnd() == null ? null : req.getPeriodEnd().plusDays(1).atStartOfDay())
                .orderByAsc(WorkOrder::getId));
        if (orders.isEmpty()) {
            throw new BusinessException("该农机没有可结算的完工作业单");
        }

        SettleOrder settle = new SettleOrder();
        settle.setSettleNo("ST-" + LocalDateTime.now().format(NO_FMT)
                + "-" + machine.getId() + "-" + NO_SEQ.incrementAndGet());
        settle.setMachineId(machine.getId());
        settle.setMachineCode(machine.getMachineCode());
        settle.setMachineName(machine.getMachineName());
        settle.setPeriodStart(req.getPeriodStart());
        settle.setPeriodEnd(req.getPeriodEnd());
        settle.setUnitPrice(req.getUnitPrice());
        settle.setStatus(SettleStatus.DRAFT);
        settle.setRemark(req.getRemark());
        settle.setRequestNo(req.getRequestNo());

        BigDecimal totalArea = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (WorkOrder order : orders) {
            if (order.getTrackArea() == null) {
                throw new BusinessException("作业单 " + order.getOrderNo() + " 缺少轨迹核算面积，不能结算");
            }
            totalArea = totalArea.add(order.getTrackArea());
            totalAmount = totalAmount.add(
                    order.getTrackArea().multiply(req.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
        }
        settle.setOrderCount(orders.size());
        settle.setTotalArea(totalArea.setScale(2, RoundingMode.HALF_UP));
        settle.setTotalAmount(totalAmount);
        settleOrderMapper.insert(settle);

        for (WorkOrder order : orders) {
            int claimed = workOrderMapper.update(null, new LambdaUpdateWrapper<WorkOrder>()
                    .eq(WorkOrder::getId, order.getId())
                    .isNull(WorkOrder::getSettleId)
                    .set(WorkOrder::getSettleId, settle.getId()));
            if (claimed == 0) {
                throw new BusinessException("作业单 " + order.getOrderNo() + " 已被其他结算单占用，请刷新后重试");
            }
            SettleItem item = new SettleItem();
            item.setSettleId(settle.getId());
            item.setWorkOrderId(order.getId());
            item.setOrderNo(order.getOrderNo());
            item.setOperationType(order.getOperationType());
            item.setTrackArea(order.getTrackArea());
            item.setUnitPrice(req.getUnitPrice());
            item.setAmount(order.getTrackArea().multiply(req.getUnitPrice())
                    .setScale(2, RoundingMode.HALF_UP));
            settleItemMapper.insert(item);
        }

        writeLog(settle, null, SettleStatus.DRAFT, null, settle.getVersion(),
                req.getOperator(), req.getRequestNo(), "结算单生成");
        return settle;
    }

    /**
     * 状态流转统一入口：请求号登记 → 版本校验 → 状态机规则校验，全部通过才变更。
     * 作废（→VOID）同事务释放作业单占用，使其可进入新结算单。
     */
    @Transactional(rollbackFor = Exception.class)
    public SettleOrder transit(Long id, SettleTransitRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.SETTLE_TRANSIT, String.valueOf(id));

        SettleOrder settle = requireSettle(id);
        String from = settle.getStatus();
        String target = req.getTargetStatus();

        if (!settle.getVersion().equals(req.getVersion())) {
            throw new BusinessException("结算单版本已变化（当前 " + settle.getVersion()
                    + "，提交 " + req.getVersion() + "），请刷新后重试");
        }
        stateMachine.check(from, target);

        if (SettleStatus.VOID.equals(target)) {
            releaseWorkOrders(settle.getId());
        }
        Integer fromVersion = settle.getVersion();
        settle.setStatus(target);
        if (settleOrderMapper.updateById(settle) == 0) {
            throw new BusinessException("结算单状态已变化，请刷新后重试");
        }
        writeLog(settle, from, target, fromVersion, settle.getVersion(),
                req.getOperator(), req.getRequestNo(), req.getRemark());
        return settle;
    }

    /** 结算单详情：本体 + 明细 + 完整流转历史。 */
    public SettleOrderDetail detail(Long id) {
        SettleOrder settle = requireSettle(id);
        SettleOrderDetail detail = new SettleOrderDetail();
        detail.setOrder(settle);
        detail.setItems(settleItemMapper.selectList(new LambdaQueryWrapper<SettleItem>()
                .eq(SettleItem::getSettleId, id)
                .orderByAsc(SettleItem::getId)));
        detail.setStatusLogs(statusLogMapper.selectList(new LambdaQueryWrapper<SettleStatusLog>()
                .eq(SettleStatusLog::getSettleId, id)
                .orderByAsc(SettleStatusLog::getId)));
        return detail;
    }

    /** 列表检索：可按状态、结算单号过滤。 */
    public List<SettleOrder> list(String status, String settleNo) {
        return settleOrderMapper.selectList(new LambdaQueryWrapper<SettleOrder>()
                .eq(StringUtils.hasText(status), SettleOrder::getStatus, status)
                .eq(StringUtils.hasText(settleNo), SettleOrder::getSettleNo, settleNo)
                .orderByDesc(SettleOrder::getId));
    }

    private SettleOrder requireSettle(Long id) {
        SettleOrder settle = settleOrderMapper.selectById(id);
        if (settle == null) {
            throw new BusinessException("结算单不存在: " + id);
        }
        return settle;
    }

    private void releaseWorkOrders(Long settleId) {
        workOrderMapper.update(null, new LambdaUpdateWrapper<WorkOrder>()
                .eq(WorkOrder::getSettleId, settleId)
                .set(WorkOrder::getSettleId, null));
    }

    private void writeLog(SettleOrder settle, String fromStatus, String toStatus,
                          Integer fromVersion, Integer toVersion,
                          String operator, String requestNo, String remark) {
        SettleStatusLog log = new SettleStatusLog();
        log.setSettleId(settle.getId());
        log.setSettleNo(settle.getSettleNo());
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setFromVersion(fromVersion);
        log.setToVersion(toVersion);
        log.setOperator(operator);
        log.setRequestNo(requestNo);
        log.setRemark(remark);
        statusLogMapper.insert(log);
    }
}
