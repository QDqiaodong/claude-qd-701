package com.aircraft.mro.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 快照中的开放/超期故障。
 *
 * <p>开放故障允许带保留放行：{@link #retainBasis} 由发起人逐条填写（提交时强制），
 * {@link #qaConfirmed} 由质量终审逐条确认后才置 1。
 */
@Data
@Entity
@Table(name = "release_snapshot_defect")
public class ReleaseSnapshotDefect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Column(name = "defect_id", nullable = false)
    private Long defectId;

    @Column(name = "defect_no", nullable = false, length = 32)
    private String defectNo;

    @Column(name = "ata_chapter", nullable = false, length = 16)
    private String ataChapter;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    /** 快照当时：OPEN / OVERDUE */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "status_name", nullable = false, length = 16)
    private String statusName;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** 保留依据（MEL 条款 / 工程批准），逐条必填 */
    @Column(name = "retain_basis", nullable = false, length = 255)
    private String retainBasis;

    /** 质量终审是否逐条确认 */
    @Column(name = "qa_confirmed", nullable = false)
    private Integer qaConfirmed = 0;

    @Column(name = "defect_updated_at")
    private LocalDateTime defectUpdatedAt;
}
