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
 * 放行证据快照（主表）。
 *
 * <p>每次提交（首次提交 / 被驳回后再次提交）都 INSERT 新一轮 {@link #roundNo}，
 * 全表只追加、绝不 UPDATE、没有外键级联 —— 上游工卡、故障、机位后续怎么改，
 * 这里记下的适航状态、机位、未闭环工卡与开放/超期故障（编号、状态、更新时间）都不会被改写。
 */
@Data
@Entity
@Table(name = "release_snapshot")
public class ReleaseSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    /** 快照轮次，从 1 递增 */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "tail_no", nullable = false, length = 16)
    private String tailNo;

    @Column(name = "model", nullable = false, length = 16)
    private String model;

    @Column(name = "airworthy", nullable = false, length = 16)
    private String airworthy;

    @Column(name = "airworthy_name", nullable = false, length = 16)
    private String airworthyName;

    @Column(name = "bay_id")
    private Long bayId;

    @Column(name = "bay_no", length = 16)
    private String bayNo;

    @Column(name = "bay_status", length = 16)
    private String bayStatus;

    @Column(name = "open_card_count", nullable = false)
    private Integer openCardCount = 0;

    @Column(name = "open_defect_count", nullable = false)
    private Integer openDefectCount = 0;

    @Column(name = "overdue_defect_count", nullable = false)
    private Integer overdueDefectCount = 0;

    @Column(name = "taken_at", nullable = false)
    private LocalDateTime takenAt;
}
