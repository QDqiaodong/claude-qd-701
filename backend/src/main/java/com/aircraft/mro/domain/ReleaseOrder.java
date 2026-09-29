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
 * 出厂放行审签单。
 *
 * <p>状态机：DRAFT 草稿 →（提交）PENDING_MECH 待机械放行 →（机械放行）PENDING_QA 待质量终审
 * →（终审通过）RELEASED 已放行；任一在审环节可被驳回 REJECTED（回到可修订，再次提交生成新快照）；
 * 快照之后关联事实变化则在途单立即 INVALID 已失效。
 *
 * <p>{@link #activeFlag}：在途状态（DRAFT/PENDING_MECH/PENDING_QA/REJECTED）恒为 1，终态置 NULL，
 * 配合库表唯一索引 {@code (aircraft_id, active_flag)} 保证同一飞机同时只有一条在途单据。
 *
 * <p>{@link #stale}：仅 RELEASED 单据使用。放行后上游事实漂移时置 1 —— 单据仍是已放行留痕，
 * 但不能再用于出位，必须重新发起一轮。
 */
@Data
@Entity
@Table(name = "release_order")
public class ReleaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_no", nullable = false, length = 32)
    private String releaseNo;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    /** DRAFT / PENDING_MECH / PENDING_QA / RELEASED / REJECTED / INVALID */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "initiator", nullable = false, length = 32)
    private String initiator;

    @Column(name = "mech_signer", length = 32)
    private String mechSigner;

    @Column(name = "qa_signer", length = 32)
    private String qaSigner;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    /** 在途=1，终态=NULL，配合唯一索引限制同机仅一条在途单据 */
    @Column(name = "active_flag")
    private Integer activeFlag;

    /** 已放行后事实是否已漂移：1 不能再出位 */
    @Column(name = "stale", nullable = false)
    private Integer stale = 0;

    /** 放行额度是否已被出位核销：1 不可重复出位（放行单是一次性凭证） */
    @Column(name = "departed", nullable = false)
    private Integer departed = 0;

    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    @Column(name = "mech_signed_at")
    private LocalDateTime mechSignedAt;

    @Column(name = "qa_signed_at")
    private LocalDateTime qaSignedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
