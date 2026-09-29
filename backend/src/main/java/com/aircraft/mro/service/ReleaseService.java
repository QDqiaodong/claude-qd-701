package com.aircraft.mro.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Bay;
import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.ReleaseEvent;
import com.aircraft.mro.domain.ReleaseLock;
import com.aircraft.mro.domain.ReleaseOrder;
import com.aircraft.mro.domain.ReleaseSnapshot;
import com.aircraft.mro.domain.ReleaseSnapshotCard;
import com.aircraft.mro.domain.ReleaseSnapshotDefect;
import com.aircraft.mro.domain.WorkCard;
import com.aircraft.mro.dto.ReleaseDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.BayRepository;
import com.aircraft.mro.repository.DefectRepository;
import com.aircraft.mro.repository.ReleaseEventRepository;
import com.aircraft.mro.repository.ReleaseLockRepository;
import com.aircraft.mro.repository.ReleaseOrderRepository;
import com.aircraft.mro.repository.ReleaseSnapshotCardRepository;
import com.aircraft.mro.repository.ReleaseSnapshotDefectRepository;
import com.aircraft.mro.repository.ReleaseSnapshotRepository;
import com.aircraft.mro.repository.WorkCardRepository;

/**
 * 模块六：出厂放行审签。
 *
 * <p>设计要点（对应验收口径）：
 * <ol>
 *   <li><b>证据快照不可变</b>：每次提交都新插一轮快照（工卡/故障/适航/机位的编号、状态、更新时间），
 *       只追加不改写；历史快照永不随上游变化。</li>
 *   <li><b>发起/提交门禁在服务端</b>：非 AIRWORTHY、有未闭环工卡、有超期故障一律硬阻断；
 *       未超期开放故障可放行，但必须逐条保留依据并由质量终审逐条确认。</li>
 *   <li><b>三人不同人</b>：发起人、机械放行人、质量终审人两两不同，谁都不能替自己进下一环节。</li>
 *   <li><b>在途单失效</b>：快照之后关联工卡/故障/适航/机位占用一变，在审单立即 INVALID，
 *       旧签字随审计保留但不得沿用；已放行单事实漂移则 stale=1，不能出位。</li>
 *   <li><b>并发一致</b>：所有写动作先锁 {@link ReleaseLock} 的飞机行 + 对单据行加悲观写锁；
 *       两人同时终审只成一个，重复终审幂等返回同一结果；同机唯一在途单由库表唯一索引兜底。</li>
 *   <li><b>出位强校验</b>：没有有效已放行记录 / 已失效 / stale（对不上当前这轮维修事实）一律阻断，
 *       机位页与本模块用同一套判定，提示文案一致。</li>
 * </ol>
 */
@Service
public class ReleaseService {

    /** 系统自动失效/漂移时的操作人 */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    private final ReleaseLockRepository lockRepository;
    private final ReleaseOrderRepository orderRepository;
    private final ReleaseSnapshotRepository snapshotRepository;
    private final ReleaseSnapshotCardRepository snapshotCardRepository;
    private final ReleaseSnapshotDefectRepository snapshotDefectRepository;
    private final ReleaseEventRepository eventRepository;
    private final AircraftRepository aircraftRepository;
    private final BayRepository bayRepository;
    private final WorkCardRepository workCardRepository;
    private final DefectRepository defectRepository;

    /** 自身代理：让"立即失效并提交"能走 REQUIRES_NEW 独立事务（不被随后的业务回滚带走） */
    @Autowired
    @Lazy
    private ReleaseService self;

    public ReleaseService(ReleaseLockRepository lockRepository,
                          ReleaseOrderRepository orderRepository,
                          ReleaseSnapshotRepository snapshotRepository,
                          ReleaseSnapshotCardRepository snapshotCardRepository,
                          ReleaseSnapshotDefectRepository snapshotDefectRepository,
                          ReleaseEventRepository eventRepository,
                          AircraftRepository aircraftRepository,
                          BayRepository bayRepository,
                          WorkCardRepository workCardRepository,
                          DefectRepository defectRepository) {
        this.lockRepository = lockRepository;
        this.orderRepository = orderRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotCardRepository = snapshotCardRepository;
        this.snapshotDefectRepository = snapshotDefectRepository;
        this.eventRepository = eventRepository;
        this.aircraftRepository = aircraftRepository;
        this.bayRepository = bayRepository;
        this.workCardRepository = workCardRepository;
        this.defectRepository = defectRepository;
    }

    // ================================================================
    // 查询：总览 / 门禁 / 列表 / 详情
    // ================================================================

    @Transactional(readOnly = true)
    public Views.ReleaseOverview overview(Long aircraftId) {
        long pending = countActivePending("PENDING_MECH") + countActivePending("PENDING_QA");
        long invalid = orderRepository.countByStatus("INVALID");
        long released = orderRepository.countByStatus("RELEASED");
        long draft = orderRepository.countByStatus("DRAFT");
        long rejected = orderRepository.countByStatus("REJECTED");

        long acPending = 0;
        long acInvalid = 0;
        long acReleased = 0;
        Long currentOrderId = null;
        String currentStatus = null;
        boolean departable = false;
        if (aircraftId != null) {
            acPending = orderRepository.countByAircraftIdAndStatus(aircraftId, "PENDING_MECH")
                    + orderRepository.countByAircraftIdAndStatus(aircraftId, "PENDING_QA");
            acInvalid = orderRepository.countByAircraftIdAndStatus(aircraftId, "INVALID");
            acReleased = orderRepository.countByAircraftIdAndStatus(aircraftId, "RELEASED");
            Optional<ReleaseOrder> active = orderRepository.findByAircraftIdAndActiveFlag(aircraftId, 1);
            ReleaseOrder latest = latestOrder(aircraftId);
            ReleaseOrder ref = active.orElse(latest);
            if (ref != null) {
                currentOrderId = ref.getId();
                currentStatus = ref.getStatus();
            }
            departable = aircraftId != null && checkDepartable(aircraftId).isEmpty();
        }
        return new Views.ReleaseOverview(pending, invalid, released, draft, rejected,
                acPending, acInvalid, acReleased, currentOrderId, currentStatus,
                MroDict.releaseStatusName(currentStatus), departable);
    }

    private long countActivePending(String status) {
        return orderRepository.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public Views.AircraftGateView gate(Long aircraftId) {
        Aircraft aircraft = requireAircraft(aircraftId);
        Facts facts = collectFacts(aircraftId);
        List<String> blockers = initBlockers(aircraft, facts);
        Optional<ReleaseOrder> active = orderRepository.findByAircraftIdAndActiveFlag(aircraftId, 1);
        ReleaseOrder activeOrder = active.orElse(null);
        return new Views.AircraftGateView(
                aircraft.getId(), aircraft.getTailNo(), aircraft.getModel(),
                aircraft.getAirworthy(), MroDict.airworthyName(aircraft.getAirworthy()),
                "AIRWORTHY".equals(aircraft.getAirworthy()),
                facts.bay == null ? null : facts.bay.getId(),
                facts.bay == null ? null : facts.bay.getBayNo(),
                facts.bay == null ? null : facts.bay.getStatus(),
                facts.bay != null && "OCCUPIED".equals(facts.bay.getStatus())
                        && aircraftId.equals(facts.bay.getCurrentAircraftId()),
                facts.openCards.size(), facts.openDefects.size(), facts.overdueDefects.size(),
                blockers, defectViews(facts.openDefects), blockers.isEmpty(),
                activeOrder != null, activeOrder == null ? null : activeOrder.getId(),
                activeOrder == null ? null : activeOrder.getStatus(),
                activeOrder == null ? null : MroDict.releaseStatusName(activeOrder.getStatus()));
    }

    @Transactional(readOnly = true)
    public List<Views.ReleaseOrderView> list(Long aircraftId) {
        Map<Long, Aircraft> aircraftMap = aircraftMap();
        List<ReleaseOrder> orders = aircraftId == null
                ? orderRepository.findAllByOrderByIdDesc()
                : orderRepository.findAllByAircraftIdOrderByIdDesc(aircraftId);
        List<Views.ReleaseOrderView> list = new ArrayList<>();
        for (ReleaseOrder order : orders) {
            list.add(toOrderView(order, aircraftMap.get(order.getAircraftId())));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public Views.ReleaseDetailView detail(Long id) {
        ReleaseOrder order = requireOrder(id);
        Aircraft aircraft = aircraftRepository.findById(order.getAircraftId()).orElse(null);
        List<ReleaseSnapshot> snapshots = snapshotRepository.findAllByReleaseIdOrderByRoundNoAsc(id);
        ReleaseSnapshot latest = snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);

        Views.ReleaseSnapshotView latestView = latest == null ? null : toSnapshotView(latest);
        Views.SnapshotDiffView diff = latest == null
                ? new Views.SnapshotDiffView(null, null, null, false, false, "无快照", List.of())
                : buildDiff(order, latest);
        List<Views.ReleaseSnapshotView> snapViews = snapshots.stream().map(this::toSnapshotView).toList();
        List<Views.ReleaseEventView> events = eventRepository.findAllByReleaseIdOrderByIdAsc(id)
                .stream().map(this::toEventView).toList();
        return new Views.ReleaseDetailView(toOrderView(order, aircraft), latestView, diff,
                snapViews, events, availableActions(order, diff));
    }

    @Transactional(readOnly = true)
    public Views.SnapshotDiffView diff(Long id, Integer roundNo) {
        ReleaseOrder order = requireOrder(id);
        ReleaseSnapshot snapshot = roundNo == null
                ? snapshotRepository.findTopByReleaseIdOrderByRoundNoDesc(id)
                        .orElseThrow(() -> new BizException("放行单 " + order.getReleaseNo() + " 还没有证据快照"))
                : snapshotRepository.findByReleaseIdAndRoundNo(id, roundNo)
                        .orElseThrow(() -> new BizException("第 " + roundNo + " 轮快照不存在"));
        return buildDiff(order, snapshot);
    }

    // ================================================================
    // 写动作：发起 → 提交 → 机械放行 → 终审 / 驳回
    // ================================================================

    /** 发起放行：锁飞机 → 门禁 → 同机唯一在途 → 建草稿 + 第 1 轮快照 */
    @Transactional
    public Views.ReleaseDetailView create(ReleaseDtos.CreateReq req) {
        Long aircraftId = req.getAircraftId();
        if (aircraftId == null) {
            throw new BizException("请先选择要发起放行的飞机");
        }
        String initiator = trim(req.getInitiator());
        if (initiator.isEmpty()) {
            throw new BizException("发起人不能为空");
        }
        lockAircraft(aircraftId);
        Aircraft aircraft = requireAircraft(aircraftId);
        Facts facts = collectFacts(aircraftId);
        List<String> blockers = initBlockers(aircraft, facts);
        if (!blockers.isEmpty()) {
            throw new BizException("当前飞机不满足出厂放行发起条件：\n" + String.join("\n", blockers));
        }
        if (orderRepository.findByAircraftIdAndActiveFlag(aircraftId, 1).isPresent()) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 已存在一条在途放行单，请在原单上继续或等其终结");
        }
        ReleaseOrder usable = usableReleased(aircraftId);
        if (usable != null) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 已持有有效放行单 "
                    + usable.getReleaseNo() + "，同一飞机不能同时存在两条有效审签；请先凭单出位，"
                    + "或待其事实失效后再重新发起");
        }

        LocalDateTime now = LocalDateTime.now();
        ReleaseOrder order = new ReleaseOrder();
        order.setReleaseNo("RO-" + java.time.Year.now().getValue() + "-" + String.format("%04d", 0));
        order.setAircraftId(aircraftId);
        order.setStatus("DRAFT");
        order.setInitiator(initiator);
        order.setActiveFlag(1);
        order.setStale(0);
        order.setInitiatedAt(now);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order = orderRepository.saveAndFlush(order);
        // 编号用自增主键生成，保证肉眼可读且全局唯一
        order.setReleaseNo("RO-" + java.time.Year.now().getValue() + "-" + String.format("%04d", order.getId()));
        orderRepository.saveAndFlush(order);

        takeSnapshot(order, facts, Map.of(), now);
        addEvent(order.getId(), "CREATED", initiator, "发起出厂放行审签，生成第 1 轮证据快照", now);
        order.setUpdatedAt(now);
        return detail(order.getId());
    }

    /**
     * 提交审签（草稿 / 被驳回后再次提交）：必须形成【新一轮】证据快照，并重新做硬阻断校验。
     */
    @Transactional
    public Views.ReleaseDetailView submit(Long id, ReleaseDtos.SubmitReq req) {
        String submitter = trim(req.getSubmitter());
        if (submitter.isEmpty()) {
            throw new BizException("提交人不能为空");
        }
        Long aircraftId = requireOrder(id).getAircraftId();
        lockAircraft(aircraftId);
        ReleaseOrder order = requireOrderForUpdate(id);
        if (!"DRAFT".equals(order.getStatus()) && !"REJECTED".equals(order.getStatus())) {
            throw new BizException("放行单 " + order.getReleaseNo() + " 当前为"
                    + MroDict.releaseStatusName(order.getStatus()) + "，只有草稿或被驳回的单子可以提交");
        }
        if (!submitter.equals(order.getInitiator())) {
            throw new BizException("提交人必须是发起人本人（" + order.getInitiator() + "），收到的是：" + submitter);
        }
        Aircraft aircraft = requireAircraft(order.getAircraftId());
        Facts facts = collectFacts(order.getAircraftId());
        List<String> blockers = initBlockers(aircraft, facts);
        if (!blockers.isEmpty()) {
            throw new BizException("当前维修事实已不满足放行条件，提交被阻断：\n" + String.join("\n", blockers));
        }
        // 未超期开放故障：逐条保留依据
        Map<Long, String> basisMap = basisMapOf(req, facts);

        LocalDateTime now = LocalDateTime.now();
        int nextRound = snapshotRepository.findAllByReleaseIdOrderByRoundNoAsc(id).size() + 1;
        takeSnapshot(order, facts, basisMap, now, nextRound);
        boolean firstSubmit = "DRAFT".equals(order.getStatus());
        order.setStatus("PENDING_MECH");
        order.setRejectReason(null);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        String detailText = facts.openDefects.isEmpty()
                ? "提交审签：适航、机位在位、无未闭环工卡、无开放/超期故障"
                : "提交审签：" + facts.openDefects.size() + " 条开放故障未超期，已逐条填写保留依据，待质量终审确认";
        addEvent(order.getId(), "SUBMITTED", submitter,
                (firstSubmit ? "" : "驳回修订后再次提交，生成第 " + nextRound + " 轮证据快照。") + detailText, now);
        return detail(order.getId());
    }

    /** 机械放行：待机械放行 → 待质量终审；放行人不能是发起人本人 */
    @Transactional
    public Views.ReleaseDetailView mechSign(Long id, ReleaseDtos.MechSignReq req) {
        String signer = trim(req.getSigner());
        Long aircraftId = requireOrder(id).getAircraftId();
        // 固定加锁顺序：先飞机锁（与上游变更/出位互斥），再单据行锁（与并发终审/重复点击互斥）
        lockAircraft(aircraftId);
        // 持飞机锁期间事实不会再变；若快照已过期，先在独立事务里落"已失效"再阻断（不会被本次回滚带走）
        rejectIfStale(aircraftId, id, "机械放行");
        ReleaseOrder order = requireOrderForUpdate(id);
        // 幂等：同一放行人重复点击 → 返回同一结果
        if ("PENDING_QA".equals(order.getStatus()) && signer.equals(order.getMechSigner())) {
            return detail(id);
        }
        if ("RELEASED".equals(order.getStatus())) {
            throw new BizException("放行单 " + order.getReleaseNo() + " 已终审放行，无需重复机械放行");
        }
        if (!"PENDING_MECH".equals(order.getStatus())) {
            throw new BizException("放行单 " + order.getReleaseNo() + " 当前为"
                    + MroDict.releaseStatusName(order.getStatus()) + "，不能进行机械放行");
        }
        if (signer.isEmpty()) {
            throw new BizException("机械放行人不能为空");
        }
        if (signer.equals(order.getInitiator())) {
            throw new BizException("机械放行人不能是发起人本人（" + order.getInitiator()
                    + "），发起人不能替自己完成下一环节");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setMechSigner(signer);
        order.setMechSignedAt(now);
        order.setStatus("PENDING_QA");
        order.setUpdatedAt(now);
        orderRepository.save(order);
        addEvent(order.getId(), "MECH_SIGNED", signer, "机械放行通过，提交质量终审", now);
        return detail(id);
    }

    /** 质量终审：待质量终审 → 已放行；终审人第三人，且逐条确认开放故障保留依据 */
    @Transactional
    public Views.ReleaseDetailView qaApprove(Long id, ReleaseDtos.QaApproveReq req) {
        String signer = trim(req.getSigner());
        Long aircraftId = requireOrder(id).getAircraftId();
        // 固定加锁顺序：先飞机锁，再单据行锁 —— 两人同时终审时第二个请求在此串行后看到 RELEASED
        lockAircraft(aircraftId);
        // 快照过期先独立事务失效再阻断，杜绝用旧快照放行
        rejectIfStale(aircraftId, id, "质量终审");
        ReleaseOrder order = requireOrderForUpdate(id);
        // 幂等：同一终审人重复点击同一个终审动作 → 返回同一结果
        if ("RELEASED".equals(order.getStatus()) && signer.equals(order.getQaSigner())) {
            return detail(id);
        }
        if (!"PENDING_QA".equals(order.getStatus())) {
            throw new BizException("放行单 " + order.getReleaseNo() + " 当前为"
                    + MroDict.releaseStatusName(order.getStatus()) + "，该终审动作已被处理或不能终审");
        }
        if (signer.isEmpty()) {
            throw new BizException("质量终审人不能为空");
        }
        if (signer.equals(order.getInitiator())) {
            throw new BizException("质量终审人不能是发起人本人（" + order.getInitiator() + "）");
        }
        if (signer.equals(order.getMechSigner())) {
            throw new BizException("质量终审人不能是机械放行人本人（" + order.getMechSigner()
                    + "），终审必须由第三人完成");
        }
        ReleaseSnapshot latest = latestSnapshot(id);
        List<ReleaseSnapshotDefect> snapDefects =
                snapshotDefectRepository.findAllBySnapshotIdOrderByDefectNoAsc(latest.getId());
        List<ReleaseSnapshotDefect> openOnes =
                snapDefects.stream().filter(d -> "OPEN".equals(d.getStatus())).toList();
        Set<Long> confirmed = req.getConfirmedDefectIds() == null
                ? Set.of() : new HashSet<>(req.getConfirmedDefectIds());
        for (ReleaseSnapshotDefect d : openOnes) {
            if (!confirmed.contains(d.getDefectId())) {
                throw new BizException("开放故障 " + d.getDefectNo()
                        + " 的保留依据尚未逐条确认，质量终审不能通过");
            }
            d.setQaConfirmed(1);
            snapshotDefectRepository.save(d);
        }
        LocalDateTime now = LocalDateTime.now();
        order.setQaSigner(signer);
        order.setQaSignedAt(now);
        order.setStatus("RELEASED");
        order.setActiveFlag(null);
        order.setStale(0);
        order.setFinishedAt(now);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        addEvent(order.getId(), "QA_APPROVED", signer,
                openOnes.isEmpty() ? "质量终审通过，准许出厂出位"
                        : "质量终审通过，已逐条确认 " + openOnes.size() + " 条开放故障的保留依据，准许出厂出位", now);
        return detail(id);
    }

    /** 驳回：在机械放行或质量终审环节驳回，必须留原因，回到可修订（REJECTED）状态 */
    @Transactional
    public Views.ReleaseDetailView reject(Long id, ReleaseDtos.RejectReq req) {
        String signer = trim(req.getSigner());
        String reason = trim(req.getReason());
        Long aircraftId = requireOrder(id).getAircraftId();
        lockAircraft(aircraftId);
        // 在审期间事实变化的单子先独立事务失效，再拒绝驳回（已无可驳回对象）
        rejectIfStale(aircraftId, id, "驳回");
        ReleaseOrder order = requireOrderForUpdate(id);
        if (!"PENDING_MECH".equals(order.getStatus()) && !"PENDING_QA".equals(order.getStatus())) {
            throw new BizException("放行单 " + order.getReleaseNo() + " 当前为"
                    + MroDict.releaseStatusName(order.getStatus()) + "，不能驳回");
        }
        String stage = "PENDING_QA".equals(order.getStatus()) ? "质量终审" : "机械放行";
        if (signer.isEmpty()) {
            throw new BizException("驳回人不能为空");
        }
        if (signer.equals(order.getInitiator())) {
            throw new BizException("驳回人不能是发起人本人，不能替自己驳回");
        }
        if ("PENDING_QA".equals(order.getStatus()) && signer.equals(order.getMechSigner())) {
            throw new BizException("机械放行人不能替质量环节驳回本单");
        }
        if (reason.isEmpty()) {
            throw new BizException("驳回必须留下原因");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setStatus("REJECTED");
        order.setRejectReason(reason);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        addEvent(order.getId(), "REJECTED", signer, stage + "环节驳回：" + reason, now);
        return detail(id);
    }

    // ================================================================
    // 上游联动：事实变化 / 出位核销（供 BayService、工卡/故障/飞机/机位 service 调用）
    // ================================================================

    /**
     * 上游事实变化后的联动（在调用方事务内执行，共用同一把飞机锁）：
     * <ul>
     *   <li>在审单（PENDING_MECH/PENDING_QA）→ 立即 INVALID，旧签字保留作审计；</li>
     *   <li>已放行单 → stale=1（放行对应的不再是当前这轮维修事实，不能出位，须重新发起）。</li>
     * </ul>
     *
     * @return 是否有在审单因此失效
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean afterFactsChanged(Long aircraftId, String changeDetail) {
        boolean invalidatedAny = false;
        LocalDateTime now = LocalDateTime.now();
        for (ReleaseOrder order : orderRepository.findAllByAircraftIdOrderByIdDesc(aircraftId)) {
            if (MroDict.isReleasePending(order.getStatus())) {
                order.setStatus("INVALID");
                order.setActiveFlag(null);
                order.setFinishedAt(now);
                order.setUpdatedAt(now);
                orderRepository.save(order);
                addEvent(order.getId(), "INVALIDATED", SYSTEM_ACTOR,
                        "审签期间关联事实发生变化：" + changeDetail
                                + "；证据快照已过期，已有签字保留作审计但不得沿用，须重新发起", now);
                invalidatedAny = true;
            } else if ("RELEASED".equals(order.getStatus())
                    && (order.getStale() == null || order.getStale() == 0)
                    && (order.getDeparted() == null || order.getDeparted() == 0)) {
                order.setStale(1);
                order.setUpdatedAt(now);
                orderRepository.save(order);
                addEvent(order.getId(), "FACT_DRIFTED", SYSTEM_ACTOR,
                        "放行后关联事实发生变化：" + changeDetail
                                + "；本单放行对应的不再是当前这轮维修事实，不能再凭其出位，须重新发起", now);
            }
        }
        return invalidatedAny;
    }

    /**
     * 出位强校验（机位页 release 与本模块共用）。只读：返回空列表表示可以出位，
     * 否则返回一致的阻断提示。任何状态写入都不在只读路径里做。
     */
    @Transactional(readOnly = true)
    public List<String> checkDepartable(Long aircraftId) {
        return departureBlockers(aircraftId);
    }

    /**
     * 出位闸门（在 BayService 的写事务内调用）：核验不通过直接抛业务异常（文案与只读校验一致）；
     * 通过则把放行后漂移的单子懒置 stale 并留痕。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void gateDeparture(Long aircraftId) {
        ReleaseOrder released = latestReleased(aircraftId);
        if (released == null) {
            throw new BizException("没有有效的已放行记录：该飞机尚未完成出厂放行审签，请先在「出厂放行审签」发起并完成三人审签");
        }
        if (released.getStale() != null && released.getStale() == 1) {
            throw new BizException("放行单 " + released.getReleaseNo()
                    + " 对应的维修事实在放行后已发生变化（记录已失效），不能凭旧快照出位，请重新发起审签");
        }
        if (released.getDeparted() != null && released.getDeparted() == 1) {
            throw new BizException("放行单 " + released.getReleaseNo()
                    + " 的放行额度已被出位核销，不能重复放行出位；如需再次出厂请重新发起审签");
        }
        ReleaseSnapshot latest = latestSnapshot(released.getId());
        Facts facts = collectFacts(aircraftId);
        List<Views.SnapshotDiffItem> blocking = diffItems(latest, facts).stream()
                .filter(Views.SnapshotDiffItem::blocking).toList();
        if (!blocking.isEmpty()) {
            // 懒置 stale 并留痕，再以统一文案阻断
            released.setStale(1);
            released.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(released);
            addEvent(released.getId(), "FACT_DRIFTED", SYSTEM_ACTOR,
                    "出位核验发现快照与当前事实不一致：" + blocking.get(0).title() + "，本单已标记失效",
                    LocalDateTime.now());
            throw new BizException("放行单 " + released.getReleaseNo()
                    + " 的证据快照与当前维修事实对不上：" + blocking.get(0).title()
                    + "（" + blocking.get(0).snapshotText() + " → " + blocking.get(0).currentText()
                    + "），放行对应的不是当前这一轮维修事实，须重新发起审签");
        }
        if (facts.bay == null || !"OCCUPIED".equals(facts.bay.getStatus())
                || !aircraftId.equals(facts.bay.getCurrentAircraftId())) {
            throw new BizException("飞机当前未占用机位，无法执行出位");
        }
    }

    private List<String> departureBlockers(Long aircraftId) {
        List<String> blockers = new ArrayList<>();
        ReleaseOrder released = latestReleased(aircraftId);
        if (released == null) {
            blockers.add("没有有效的已放行记录：该飞机尚未完成出厂放行审签，请先在「出厂放行审签」发起并完成三人审签");
            return blockers;
        }
        if (released.getStale() != null && released.getStale() == 1) {
            blockers.add("放行单 " + released.getReleaseNo()
                    + " 对应的维修事实在放行后已发生变化（记录已失效），不能凭旧快照出位，请重新发起审签");
            return blockers;
        }
        if (released.getDeparted() != null && released.getDeparted() == 1) {
            blockers.add("放行单 " + released.getReleaseNo()
                    + " 的放行额度已被出位核销，不能重复放行出位；如需再次出厂请重新发起审签");
            return blockers;
        }
        ReleaseSnapshot latest = latestSnapshot(released.getId());
        Facts facts = collectFacts(aircraftId);
        List<Views.SnapshotDiffItem> blocking = diffItems(latest, facts).stream()
                .filter(Views.SnapshotDiffItem::blocking).toList();
        if (!blocking.isEmpty()) {
            blockers.add("放行单 " + released.getReleaseNo()
                    + " 的证据快照与当前维修事实对不上：" + blocking.get(0).title()
                    + "（" + blocking.get(0).snapshotText() + " → " + blocking.get(0).currentText()
                    + "），放行对应的不是当前这一轮维修事实，须重新发起审签");
            return blockers;
        }
        if (facts.bay == null || !"OCCUPIED".equals(facts.bay.getStatus())
                || !aircraftId.equals(facts.bay.getCurrentAircraftId())) {
            blockers.add("飞机当前未占用机位，无法执行出位");
        }
        return blockers;
    }

    private ReleaseOrder latestReleased(Long aircraftId) {
        for (ReleaseOrder o : orderRepository.findAllByAircraftIdOrderByIdDesc(aircraftId)) {
            if ("RELEASED".equals(o.getStatus())) {
                return o;
            }
        }
        return null;
    }

    /** 当前仍可凭其出位的放行单：已放行、未漂移、未被出位核销 */
    private ReleaseOrder usableReleased(Long aircraftId) {
        ReleaseOrder released = latestReleased(aircraftId);
        if (released == null) {
            return null;
        }
        if (released.getStale() != null && released.getStale() == 1) {
            return null;
        }
        if (released.getDeparted() != null && released.getDeparted() == 1) {
            return null;
        }
        return released;
    }

    /** 出位成功后核销放行单（同一事务内调用）：一次性凭证，置 departed=1 杜绝重复放行出位。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void markDeparted(Long aircraftId, String actor, String bayNo) {
        for (ReleaseOrder o : orderRepository.findAllByAircraftIdOrderByIdDesc(aircraftId)) {
            if ("RELEASED".equals(o.getStatus()) && (o.getStale() == null || o.getStale() == 0)
                    && (o.getDeparted() == null || o.getDeparted() == 0)) {
                o.setDeparted(1);
                o.setUpdatedAt(LocalDateTime.now());
                orderRepository.save(o);
                addEvent(o.getId(), "DEPARTED", actor == null ? SYSTEM_ACTOR : actor,
                        "飞机已凭本放行单从机位 " + bayNo + " 出位，放行额度一次性核销（不可重复出位）",
                        LocalDateTime.now());
                return;
            }
        }
    }

    /** 两人同时终审的验收演示：只允许对「待质量终审」的单子发起 */
    @Transactional(readOnly = true)
    public void requirePendingQaForDemo(Long id) {
        ReleaseOrder order = requireOrder(id);
        if (!"PENDING_QA".equals(order.getStatus())) {
            throw new BizException("并发演示要求放行单当前为「待质量终审」，"
                    + order.getReleaseNo() + " 当前为「" + MroDict.releaseStatusName(order.getStatus()) + "」");
        }
    }

    /** 只读出位核验：机位页弹窗与放行总览共用，返回一致的阻断文案与放行单号。 */
    @Transactional(readOnly = true)
    public Map<String, Object> departureCheck(Long aircraftId) {
        List<String> blockers = departureBlockers(aircraftId);
        Map<String, Object> result = new HashMap<>();
        result.put("aircraftId", aircraftId);
        result.put("departable", blockers.isEmpty());
        result.put("message", blockers.isEmpty() ? "已持有有效出厂放行，可执行出位" : blockers.get(0));
        result.put("blockers", blockers);
        ReleaseOrder released = latestReleased(aircraftId);
        result.put("releaseNo", released == null ? null : released.getReleaseNo());
        result.put("staleOrChanged", released != null
                && ((released.getStale() != null && released.getStale() == 1)
                    || (released.getDeparted() != null && released.getDeparted() == 1)));
        result.put("departed", released != null && released.getDeparted() != null && released.getDeparted() == 1);
        return result;
    }

    // ================================================================
    // 快照取证与差异对比
    // ================================================================

    private void takeSnapshot(ReleaseOrder order, Facts facts, Map<Long, String> basisMap, LocalDateTime now) {
        int round = snapshotRepository.findAllByReleaseIdOrderByRoundNoAsc(order.getId()).size() + 1;
        takeSnapshot(order, facts, basisMap, now, round);
    }

    private void takeSnapshot(ReleaseOrder order, Facts facts, Map<Long, String> basisMap,
                              LocalDateTime now, int round) {
        Aircraft aircraft = facts.aircraft;
        ReleaseSnapshot snapshot = new ReleaseSnapshot();
        snapshot.setReleaseId(order.getId());
        snapshot.setRoundNo(round);
        snapshot.setTailNo(aircraft.getTailNo());
        snapshot.setModel(aircraft.getModel());
        snapshot.setAirworthy(aircraft.getAirworthy());
        snapshot.setAirworthyName(MroDict.airworthyName(aircraft.getAirworthy()));
        snapshot.setBayId(facts.bay == null ? null : facts.bay.getId());
        snapshot.setBayNo(facts.bay == null ? null : facts.bay.getBayNo());
        snapshot.setBayStatus(facts.bay == null ? null : facts.bay.getStatus());
        snapshot.setOpenCardCount(facts.openCards.size());
        snapshot.setOpenDefectCount(facts.openDefects.size());
        snapshot.setOverdueDefectCount(facts.overdueDefects.size());
        snapshot.setTakenAt(now);
        snapshot = snapshotRepository.save(snapshot);

        for (WorkCard card : facts.openCards) {
            ReleaseSnapshotCard sc = new ReleaseSnapshotCard();
            sc.setSnapshotId(snapshot.getId());
            sc.setCardId(card.getId());
            sc.setCardNo(card.getCardNo());
            sc.setTitle(card.getTitle());
            sc.setStatus(card.getStatus());
            sc.setStatusName(MroDict.cardStatusName(card.getStatus()));
            sc.setExecutor(card.getExecutor());
            sc.setInspector(card.getInspector());
            sc.setCardUpdatedAt(card.getUpdatedAt());
            snapshotCardRepository.save(sc);
        }
        List<Defect> both = new ArrayList<>(facts.openDefects);
        both.addAll(facts.overdueDefects);
        for (Defect defect : both) {
            ReleaseSnapshotDefect sd = new ReleaseSnapshotDefect();
            sd.setSnapshotId(snapshot.getId());
            sd.setDefectId(defect.getId());
            sd.setDefectNo(defect.getDefectNo());
            sd.setAtaChapter(defect.getAtaChapter());
            sd.setDescription(defect.getDescription());
            sd.setStatus(defect.getStatus());
            sd.setStatusName(MroDict.defectStatusName(defect.getStatus()));
            sd.setDueDate(defect.getDueDate());
            sd.setRetainBasis(basisMap.getOrDefault(defect.getId(), ""));
            sd.setQaConfirmed(0);
            sd.setDefectUpdatedAt(defect.getUpdatedAt());
            snapshotDefectRepository.save(sd);
        }
    }

    private Views.SnapshotDiffView buildDiff(ReleaseOrder order, ReleaseSnapshot snapshot) {
        Facts facts = collectFacts(order.getAircraftId());
        List<Views.SnapshotDiffItem> items = diffItems(snapshot, facts);
        boolean changed = !items.isEmpty();
        boolean blocking = items.stream().anyMatch(Views.SnapshotDiffItem::blocking);
        String summary;
        if (!changed) {
            summary = "快照与当前维修事实一致";
        } else {
            long blockingCount = items.stream().filter(Views.SnapshotDiffItem::blocking).count();
            summary = "发现 " + items.size() + " 处变化，其中 " + blockingCount + " 处影响放行有效性";
        }
        return new Views.SnapshotDiffView(snapshot.getId(), snapshot.getRoundNo(), snapshot.getTakenAt(),
                changed, blocking, summary, items);
    }

    /**
     * 快照 vs 当前事实：适航、机位占用、未闭环工卡集合、开放/超期故障集合逐类比对。
     * 阻断级（blocking=true）变化：适航变化、机位占用变化、出现新的未闭环工卡、开放故障变超期等；
     * 非阻断（如故障已关闭）只提示。
     */
    private List<Views.SnapshotDiffItem> diffItems(ReleaseSnapshot snapshot, Facts facts) {
        List<Views.SnapshotDiffItem> items = new ArrayList<>();
        Aircraft aircraft = facts.aircraft;
        if (!snapshot.getAirworthy().equals(aircraft.getAirworthy())) {
            items.add(new Views.SnapshotDiffItem("AIRWORTHY", aircraft.getTailNo(), "飞机适航状态",
                    snapshot.getAirworthyName(),
                    MroDict.airworthyName(aircraft.getAirworthy()), true));
        }
        String currentBayText = facts.bay == null ? "场外（无机位）"
                : facts.bay.getBayNo() + "·" + MroDict.bayStatusName(facts.bay.getStatus());
        boolean bayChanged = !java.util.Objects.equals(snapshot.getBayId(),
                facts.bay == null ? null : facts.bay.getId())
                || facts.bay == null
                || !"OCCUPIED".equals(facts.bay.getStatus())
                || !aircraft.getId().equals(facts.bay.getCurrentAircraftId());
        if (bayChanged) {
            String snapBay = snapshot.getBayNo() == null ? "场外" : snapshot.getBayNo();
            items.add(new Views.SnapshotDiffItem("BAY", aircraft.getTailNo(), "所在机位/占用",
                    snapBay, currentBayText, true));
        }

        Map<Long, ReleaseSnapshotCard> snapCardMap = new HashMap<>();
        for (ReleaseSnapshotCard c : snapshotCardRepository.findAllBySnapshotIdOrderByCardNoAsc(snapshot.getId())) {
            snapCardMap.put(c.getCardId(), c);
        }
        // 快照里未闭环、现在仍存在的：状态/更新时间变了
        for (ReleaseSnapshotCard snap : snapCardMap.values()) {
            WorkCard current = workCardRepository.findById(snap.getCardId()).orElse(null);
            if (current == null) {
                continue;
            }
            if (MroDict.isCardOpen(current.getStatus())) {
                if (!snap.getStatus().equals(current.getStatus())) {
                    items.add(new Views.SnapshotDiffItem("CARD", snap.getCardNo(),
                            "未闭环工卡状态变化：" + snap.getTitle(),
                            snap.getStatusName(), MroDict.cardStatusName(current.getStatus()), true));
                }
            } else {
                // 未闭环 → 已闭环：不阻断（变得更好），但仍属事实变化，列在差异里
                items.add(new Views.SnapshotDiffItem("CARD", snap.getCardNo(),
                        "快照中的未闭环工卡已闭环：" + snap.getTitle(),
                        snap.getStatusName(), MroDict.cardStatusName(current.getStatus()), false));
            }
        }
        // 当前新出现的未闭环工卡（快照时不存在）→ 阻断
        for (WorkCard current : facts.openCards) {
            if (!snapCardMap.containsKey(current.getId())) {
                items.add(new Views.SnapshotDiffItem("CARD", current.getCardNo(),
                        "快照后新增未闭环工卡：" + current.getTitle(),
                        "快照时不存在", MroDict.cardStatusName(current.getStatus()), true));
            }
        }

        Map<Long, ReleaseSnapshotDefect> snapDefectMap = new HashMap<>();
        for (ReleaseSnapshotDefect d : snapshotDefectRepository.findAllBySnapshotIdOrderByDefectNoAsc(snapshot.getId())) {
            snapDefectMap.put(d.getDefectId(), d);
        }
        Set<Long> currentRelevant = new HashSet<>();
        for (Defect d : facts.openDefects) {
            currentRelevant.add(d.getId());
        }
        for (Defect d : facts.overdueDefects) {
            currentRelevant.add(d.getId());
        }
        for (ReleaseSnapshotDefect snap : snapDefectMap.values()) {
            Defect current = defectRepository.findById(snap.getDefectId()).orElse(null);
            if (current == null) {
                continue;
            }
            if (currentRelevant.contains(current.getId())) {
                if (!snap.getStatus().equals(current.getStatus())) {
                    // OPEN → OVERDUE 是阻断级（出现超期故障）
                    boolean blocking = "OVERDUE".equals(current.getStatus());
                    items.add(new Views.SnapshotDiffItem("DEFECT", snap.getDefectNo(),
                            "故障状态变化：" + snap.getDescription(),
                            snap.getStatusName(), MroDict.defectStatusName(current.getStatus()), blocking));
                }
            } else if ("CLOSED".equals(current.getStatus())) {
                items.add(new Views.SnapshotDiffItem("DEFECT", snap.getDefectNo(),
                        "快照中的故障已关闭：" + snap.getDescription(),
                        snap.getStatusName(), "已关闭", false));
            }
        }
        // 快照后新出现的开放/超期故障
        Map<Long, Defect> allCurrent = new HashMap<>();
        for (Defect d : facts.openDefects) {
            allCurrent.put(d.getId(), d);
        }
        for (Defect d : facts.overdueDefects) {
            allCurrent.put(d.getId(), d);
        }
        for (Long defectId : currentRelevant) {
            if (!snapDefectMap.containsKey(defectId)) {
                Defect d = allCurrent.get(defectId);
                items.add(new Views.SnapshotDiffItem("DEFECT", d.getDefectNo(),
                        "快照后新增" + ("OVERDUE".equals(d.getStatus()) ? "超期" : "开放") + "故障：" + d.getDescription(),
                        "快照时不存在", MroDict.defectStatusName(d.getStatus()),
                        "OVERDUE".equals(d.getStatus())));
            }
        }
        items.sort(Comparator.comparing(Views.SnapshotDiffItem::blocking).reversed()
                .thenComparing(i -> i.kind() + i.refNo()));
        return items;
    }

    // ================================================================
    // 事实收集 / 门禁 / 锁 / 装配
    // ================================================================

    /** 某架飞机当前一轮维修事实：机位 + 未闭环工卡 + 开放故障 + 超期故障 */
    private Facts collectFacts(Long aircraftId) {
        Aircraft aircraft = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        Bay bay = null;
        List<Bay> occupied = bayRepository.findAllByCurrentAircraftId(aircraftId);
        if (!occupied.isEmpty()) {
            bay = occupied.get(0);
        }
        List<WorkCard> openCards = workCardRepository.findAllByAircraftIdOrderByCardNoAsc(aircraftId)
                .stream().filter(c -> MroDict.isCardOpen(c.getStatus())).collect(Collectors.toList());
        LocalDate today = LocalDate.now();
        List<Defect> openDefects = new ArrayList<>();
        List<Defect> overdueDefects = new ArrayList<>();
        for (Defect d : defectRepository.findAllByAircraftIdOrderByDefectNoAsc(aircraftId)) {
            if ("OVERDUE".equals(d.getStatus())
                    || ("OPEN".equals(d.getStatus()) && d.getDueDate().isBefore(today))) {
                overdueDefects.add(d);
            } else if ("OPEN".equals(d.getStatus())) {
                openDefects.add(d);
            }
        }
        return new Facts(aircraft, bay, openCards, openDefects, overdueDefects);
    }

    /** 发起/提交硬阻断条件（服务端判，前端只展示） */
    private List<String> initBlockers(Aircraft aircraft, Facts facts) {
        List<String> blockers = new ArrayList<>();
        if (!"AIRWORTHY".equals(aircraft.getAirworthy())) {
            blockers.add("飞机适航状态为「" + MroDict.airworthyName(aircraft.getAirworthy())
                    + "」，不是适航状态，不能进入出厂放行审签");
        }
        if (facts.bay == null || !"OCCUPIED".equals(facts.bay.getStatus())
                || !aircraft.getId().equals(facts.bay.getCurrentAircraftId())) {
            blockers.add("飞机当前未在机库机位上（不在位/机位非占用），不能发起放行");
        }
        if (!facts.openCards.isEmpty()) {
            String nos = facts.openCards.stream().map(WorkCard::getCardNo).collect(Collectors.joining("、"));
            blockers.add("仍有 " + facts.openCards.size() + " 张未闭环工卡：" + nos);
        }
        if (!facts.overdueDefects.isEmpty()) {
            String nos = facts.overdueDefects.stream().map(Defect::getDefectNo).collect(Collectors.joining("、"));
            blockers.add("存在 " + facts.overdueDefects.size() + " 条超期故障：" + nos + "，超期故障不允许放行");
        }
        return blockers;
    }

    /**
     * 持飞机锁后、对单据上写锁前的新鲜度预检：
     * 持锁后事实不可能再变化，因此若此刻快照与当前事实已对不上，说明变化发生在拿到锁之前，
     * 通过 {@link #invalidateOrderInNewTx} 独立事务把"已失效 + 审计事件"先提交，
     * 再抛出阻断（外层事务即使回滚也带不走这次失效留痕）。
     */
    private void rejectIfStale(Long aircraftId, Long orderId, String action) {
        ReleaseOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || !MroDict.isReleasePending(order.getStatus())) {
            return;
        }
        ReleaseSnapshot latest = latestSnapshot(orderId);
        Facts facts = collectFacts(aircraftId);
        List<Views.SnapshotDiffItem> blocking =
                diffItems(latest, facts).stream().filter(Views.SnapshotDiffItem::blocking).toList();
        if (blocking.isEmpty()) {
            return;
        }
        String detail = action + "时检测到快照已与当前事实不一致：" + blocking.get(0).title()
                + "（" + blocking.get(0).snapshotText() + " → " + blocking.get(0).currentText() + "）";
        // 独立事务落库：返回 true 表示确实由本次置为失效
        if (self.invalidateOrderInNewTx(orderId, detail)) {
            throw new BizException(action + "未执行：关联事实在审签期间已变化，放行单 "
                    + order.getReleaseNo() + " 已自动标记为「已失效」，请重新发起（已有签字保留作审计）");
        }
    }

    /**
     * 独立事务（REQUIRES_NEW）把指定在审单置为已失效并追加审计事件。
     * 用乐观条件保证幂等：只在仍是在审状态时落库，返回是否发生了状态变更。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean invalidateOrderInNewTx(Long orderId, String detail) {
        ReleaseOrder order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || !MroDict.isReleasePending(order.getStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        order.setStatus("INVALID");
        order.setActiveFlag(null);
        order.setFinishedAt(now);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        addEvent(orderId, "INVALIDATED", SYSTEM_ACTOR, detail
                + "；证据快照已过期，已有签字保留作审计但不得沿用，须重新发起", now);
        return true;
    }

    private Map<Long, String> basisMapOf(ReleaseDtos.SubmitReq req, Facts facts) {
        Map<Long, String> provided = new HashMap<>();
        if (req.getRetainBasisList() != null) {
            for (ReleaseDtos.RetainBasisItem item : req.getRetainBasisList()) {
                if (item.getDefectId() != null && item.getRetainBasis() != null && !item.getRetainBasis().isBlank()) {
                    provided.put(item.getDefectId(), item.getRetainBasis().trim());
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (Defect d : facts.openDefects) {
            if (!provided.containsKey(d.getId())) {
                missing.add(d.getDefectNo());
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException("以下开放故障必须逐条写明保留依据后才能提交：" + String.join("、", missing));
        }
        return provided;
    }

    /** 获取飞机级互斥锁（行不存在则懒插），把并发的事实变更/签字/出位串行化 */
    private void lockAircraft(Long aircraftId) {
        if (lockRepository.lockAircraft(aircraftId).isEmpty()) {
            try {
                lockRepository.saveAndFlush(new ReleaseLock(aircraftId));
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                // 并发首插：别人已插入，直接去拿锁即可
            }
            lockRepository.lockAircraft(aircraftId);
        }
    }

    private ReleaseOrder requireOrderForUpdate(Long id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("放行单不存在：id=" + id));
    }

    private ReleaseOrder requireOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BizException("放行单不存在：id=" + id));
    }

    private Aircraft requireAircraft(Long id) {
        return aircraftRepository.findById(id)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + id));
    }

    private ReleaseOrder latestOrder(Long aircraftId) {
        List<ReleaseOrder> all = orderRepository.findAllByAircraftIdOrderByIdDesc(aircraftId);
        return all.isEmpty() ? null : all.get(0);
    }

    private ReleaseSnapshot latestSnapshot(Long releaseId) {
        return snapshotRepository.findTopByReleaseIdOrderByRoundNoDesc(releaseId)
                .orElseThrow(() -> new BizException("放行单缺少证据快照，数据不完整"));
    }

    private void addEvent(Long releaseId, String type, String actor, String detail, LocalDateTime at) {
        ReleaseEvent event = new ReleaseEvent();
        event.setReleaseId(releaseId);
        event.setEventType(type);
        event.setActor(actor);
        event.setDetail(detail);
        event.setEventAt(at == null ? LocalDateTime.now() : at);
        eventRepository.save(event);
    }

    private List<Views.DefectView> defectViews(List<Defect> defects) {
        LocalDate today = LocalDate.now();
        List<Views.DefectView> list = new ArrayList<>();
        for (Defect d : defects) {
            long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, d.getDueDate());
            list.add(new Views.DefectView(d.getId(), d.getDefectNo(), d.getAircraftId(), null,
                    d.getAtaChapter(), d.getDescription(), d.getLimitUnit(),
                    MroDict.limitUnitName(d.getLimitUnit()), d.getLimitValue(),
                    d.getLimitValue() + MroDict.limitUnitName(d.getLimitUnit()),
                    d.getFoundDate(), d.getDueDate(), d.getStatus(),
                    MroDict.defectStatusName(d.getStatus()), d.getReporter(), daysLeft, false));
        }
        return list;
    }

    private Map<Long, Aircraft> aircraftMap() {
        Map<Long, Aircraft> map = new HashMap<>();
        for (Aircraft a : aircraftRepository.findAllByOrderByTailNoAsc()) {
            map.put(a.getId(), a);
        }
        return map;
    }

    private Views.ReleaseOrderView toOrderView(ReleaseOrder order, Aircraft aircraft) {
        ReleaseSnapshot latest = snapshotRepository.findTopByReleaseIdOrderByRoundNoDesc(order.getId()).orElse(null);
        return new Views.ReleaseOrderView(order.getId(), order.getReleaseNo(), order.getAircraftId(),
                aircraft == null ? null : aircraft.getTailNo(),
                aircraft == null ? null : aircraft.getModel(),
                aircraft == null ? null : aircraft.getAirline(),
                order.getStatus(), MroDict.releaseStatusName(order.getStatus()),
                order.getInitiator(), order.getMechSigner(), order.getQaSigner(), order.getRejectReason(),
                order.getStale() != null && order.getStale() == 1,
                order.getDeparted() != null && order.getDeparted() == 1,
                latest == null ? null : latest.getRoundNo(),
                latest == null ? null : latest.getId(),
                order.getInitiatedAt(), order.getMechSignedAt(), order.getQaSignedAt(),
                order.getFinishedAt(), order.getUpdatedAt());
    }

    private Views.ReleaseSnapshotView toSnapshotView(ReleaseSnapshot snapshot) {
        List<Views.SnapCardView> cards = snapshotCardRepository
                .findAllBySnapshotIdOrderByCardNoAsc(snapshot.getId()).stream()
                .map(c -> new Views.SnapCardView(c.getCardId(), c.getCardNo(), c.getTitle(),
                        c.getStatus(), c.getStatusName(), c.getExecutor(), c.getInspector(), c.getCardUpdatedAt()))
                .toList();
        List<Views.SnapDefectView> defects = snapshotDefectRepository
                .findAllBySnapshotIdOrderByDefectNoAsc(snapshot.getId()).stream()
                .map(d -> new Views.SnapDefectView(d.getDefectId(), d.getDefectNo(), d.getAtaChapter(),
                        d.getDescription(), d.getStatus(), d.getStatusName(), d.getDueDate(),
                        d.getRetainBasis(), d.getQaConfirmed() != null && d.getQaConfirmed() == 1,
                        d.getDefectUpdatedAt()))
                .toList();
        return new Views.ReleaseSnapshotView(snapshot.getId(), snapshot.getReleaseId(), snapshot.getRoundNo(),
                snapshot.getTailNo(), snapshot.getModel(), snapshot.getAirworthy(), snapshot.getAirworthyName(),
                snapshot.getBayId(), snapshot.getBayNo(), snapshot.getBayStatus(),
                snapshot.getOpenCardCount(), snapshot.getOpenDefectCount(), snapshot.getOverdueDefectCount(),
                snapshot.getTakenAt(), cards, defects);
    }

    private Views.ReleaseEventView toEventView(ReleaseEvent event) {
        return new Views.ReleaseEventView(event.getId(), event.getEventType(),
                MroDict.releaseEventName(event.getEventType()), event.getActor(), event.getDetail(), event.getEventAt());
    }

    /** 当前状态下可执行的动作（服务端事实，前端只渲染） */
    private List<String> availableActions(ReleaseOrder order, Views.SnapshotDiffView diff) {
        List<String> actions = new ArrayList<>();
        switch (order.getStatus()) {
            case "DRAFT", "REJECTED" -> actions.add("SUBMIT");
            case "PENDING_MECH" -> {
                actions.add("MECH_SIGN");
                actions.add("REJECT");
            }
            case "PENDING_QA" -> {
                actions.add("QA_APPROVE");
                actions.add("REJECT");
            }
            case "RELEASED" -> {
                boolean usable = (order.getStale() == null || order.getStale() == 0)
                        && (order.getDeparted() == null || order.getDeparted() == 0)
                        && !diff.blocking();
                if (usable) {
                    actions.add("DEPART");
                }
            }
            default -> { }
        }
        return actions;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** 一次取证的事实集合 */
    private record Facts(Aircraft aircraft, Bay bay, List<WorkCard> openCards,
                         List<Defect> openDefects, List<Defect> overdueDefects) {
    }
}
