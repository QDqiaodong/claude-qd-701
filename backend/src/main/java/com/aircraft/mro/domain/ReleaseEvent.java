package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 放行审签审计事件（只追加，绝不修改/删除）。
 *
 * <p>完整覆盖：CREATED 发起、SUBMITTED 提交、MECH_SIGNED 机械放行、QA_APPROVED 终审通过、
 * REJECTED 驳回（含原因）、INVALIDATED 上游变更自动失效、FACT_DRIFTED 放行后事实漂移、
 * DEPARTED 凭单出位核销。并发冲突时对端结果也以事件留痕。
 */
@Data
@Entity
@Table(name = "release_event")
public class ReleaseEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    /** CREATED / SUBMITTED / MECH_SIGNED / QA_APPROVED / REJECTED / INVALIDATED / FACT_DRIFTED / DEPARTED */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    /** 操作人；系统自动失效/漂移时为 SYSTEM */
    @Column(name = "actor", length = 32)
    private String actor;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;
}
