package com.aircraft.mro.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
}
