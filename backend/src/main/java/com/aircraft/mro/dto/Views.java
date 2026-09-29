package com.aircraft.mro.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。
 *
 * <p>全部用 record：这些对象只在 service 里构造、直接序列化给前端，不需要 setter。
 * record 的访问器（如 {@code bayNo()}）Jackson 原生支持。
 */
public final class Views {

    private Views() {
    }

    /** 机库机位 */
    public record BayView(
            Long id,
            String bayNo,
            String sizeClass,
            String sizeClassName,
            String fitType,
            BigDecimal lengthM,
            String status,
            String statusName,
            Long currentAircraftId,
            String tailNo,
            String model,
            String airworthyName,
            LocalDateTime updatedAt) {
    }

    /** 飞机台账（工卡汇总列来自 @Subselect 视图实体） */
    public record AircraftView(
            Long id,
            String tailNo,
            String model,
            String airline,
            BigDecimal totalFh,
            Integer totalCycles,
            String airworthy,
            String airworthyName,
            String note,
            Long totalCard,
            Long closedCard,
            Long openCard,
            BigDecimal totalHour,
            String bayNo,
            LocalDateTime updatedAt) {
    }

    /** 定检工卡 */
    public record WorkCardView(
            Long id,
            String cardNo,
            Long aircraftId,
            String tailNo,
            String checkType,
            String checkTypeName,
            String title,
            BigDecimal plannedHour,
            BigDecimal actualHour,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String status,
            String statusName,
            String executor,
            String inspector,
            LocalDateTime updatedAt) {
    }

    /** 时间轴节点：派工 → 开工 → 完工 → 检验 → 关闭 */
    public record TimelineNode(
            Long cardId,
            String cardNo,
            String step,
            String label,
            String time,
            String color,
            boolean done) {
    }

    /** 故障保留 */
    public record DefectView(
            Long id,
            String defectNo,
            Long aircraftId,
            String tailNo,
            String ataChapter,
            String description,
            String limitUnit,
            String limitUnitName,
            Integer limitValue,
            String limitText,
            LocalDate foundDate,
            LocalDate dueDate,
            String status,
            String statusName,
            String reporter,
            Long daysLeft,
            boolean overdue) {
    }

    /** 航材件 */
    public record PartView(
            Long id,
            String partNo,
            String name,
            String spec,
            String unit,
            Integer stockQty,
            Integer minQty,
            BigDecimal unitPrice,
            String location,
            boolean lowStock,
            BigDecimal stockValue,
            LocalDateTime updatedAt) {
    }

    /** 航材借还记录 */
    public record LoanView(
            Long id,
            String loanNo,
            Long partId,
            String partNo,
            String partName,
            String unit,
            String borrower,
            String purpose,
            Integer quantity,
            LocalDateTime loanTime,
            LocalDateTime returnTime,
            String handler,
            boolean returned) {
    }

    /** @Subselect 汇总视图的一行（带机号，前端/接口直接读） */
    public record CardSummaryView(
            Long aircraftId,
            String tailNo,
            String model,
            String airline,
            Long totalCard,
            Long closedCard,
            Long openCard,
            BigDecimal totalHour) {
    }

    /** 机库总览计数器 */
    public record Overview(
            long bayTotal,
            long bayIdle,
            long bayOccupied,
            long bayDisabled,
            long aircraftTotal,
            long airworthy,
            long grounded,
            long inspection,
            long stored,
            long cardAssigned,
            long cardInProgress,
            long cardWaitInsp,
            long cardClosed,
            long defectOpen,
            long defectOverdue,
            long partTotal,
            long partLow,
            long loanOutstanding) {
    }

    // ==================== 模块六：出厂放行审签 ====================

    /** 放行单（列表/详情共用） */
    public record ReleaseOrderView(
            Long id,
            String releaseNo,
            Long aircraftId,
            String tailNo,
            String model,
            String airline,
            String status,
            String statusName,
            String initiator,
            String mechSigner,
            String qaSigner,
            String rejectReason,
            boolean stale,
            boolean departed,
            Integer currentRound,
            Long snapshotId,
            LocalDateTime initiatedAt,
            LocalDateTime mechSignedAt,
            LocalDateTime qaSignedAt,
            LocalDateTime finishedAt,
            LocalDateTime updatedAt) {
    }

    /** 快照中的未闭环工卡 */
    public record SnapCardView(
            Long cardId,
            String cardNo,
            String title,
            String status,
            String statusName,
            String executor,
            String inspector,
            LocalDateTime cardUpdatedAt) {
    }

    /** 快照中的开放/超期故障（含保留依据与终审确认标记） */
    public record SnapDefectView(
            Long defectId,
            String defectNo,
            String ataChapter,
            String description,
            String status,
            String statusName,
            LocalDate dueDate,
            String retainBasis,
            boolean qaConfirmed,
            LocalDateTime defectUpdatedAt) {
    }

    /** 证据快照（含明细） */
    public record ReleaseSnapshotView(
            Long id,
            Long releaseId,
            Integer roundNo,
            String tailNo,
            String model,
            String airworthy,
            String airworthyName,
            Long bayId,
            String bayNo,
            String bayStatus,
            int openCardCount,
            int openDefectCount,
            int overdueDefectCount,
            LocalDateTime takenAt,
            List<SnapCardView> cards,
            List<SnapDefectView> defects) {
    }

    /** 快照与当前事实的单条差异 */
    public record SnapshotDiffItem(
            String kind,
            String refNo,
            String title,
            String snapshotText,
            String currentText,
            boolean blocking) {
    }

    /** 快照差异对比结果 */
    public record SnapshotDiffView(
            Long snapshotId,
            Integer roundNo,
            LocalDateTime takenAt,
            boolean changed,
            boolean blocking,
            String summary,
            List<SnapshotDiffItem> items) {
    }

    /** 审计时间线事件 */
    public record ReleaseEventView(
            Long id,
            String eventType,
            String eventTypeName,
            String actor,
            String detail,
            LocalDateTime eventAt) {
    }

    /** 放行单完整详情（最新快照 + 差异 + 时间线） */
    public record ReleaseDetailView(
            ReleaseOrderView order,
            ReleaseSnapshotView latestSnapshot,
            SnapshotDiffView diff,
            List<ReleaseSnapshotView> snapshots,
            List<ReleaseEventView> events,
            /** 服务端判定的当前可执行动作（前端只按它渲染按钮，不自行判阻断） */
            List<String> actions) {
    }

    /** 发起门禁：当前飞机的放行总览事实，全部服务端计算 */
    public record AircraftGateView(
            Long aircraftId,
            String tailNo,
            String model,
            String airworthy,
            String airworthyName,
            boolean airworthyOk,
            Long bayId,
            String bayNo,
            String bayStatus,
            boolean onBay,
            int openCardCount,
            int openDefectCount,
            int overdueDefectCount,
            /** 硬阻断项（不满足不能发起/提交） */
            List<String> blockers,
            /** 允许但须逐条保留依据 + 终审确认的开放故障 */
            List<Views.DefectView> openDefects,
            boolean canInitiate,
            boolean hasActiveOrder,
            Long activeOrderId,
            String activeOrderStatus,
            String activeOrderStatusName) {
    }

    /** 放行总览计数器（全局 + 当前机号） */
    public record ReleaseOverview(
            long pendingCount,
            long invalidCount,
            long releasedCount,
            long draftCount,
            long rejectedCount,
            long aircraftPendingCount,
            long aircraftInvalidCount,
            long aircraftReleasedCount,
            Long currentAircraftOrderId,
            String currentAircraftOrderStatus,
            String currentAircraftOrderStatusName,
            boolean currentAircraftDepartable) {
    }

    /** 两人同时终审的验收演示结果 */
    public record ConcurrentQaResult(
            String actor,
            boolean success,
            String message,
            String finalStatus,
            String finalStatusName,
            String qaSigner,
            LocalDateTime qaSignedAt) {
    }
}
