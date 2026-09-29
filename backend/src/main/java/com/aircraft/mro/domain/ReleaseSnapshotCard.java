package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 快照中的未闭环工卡：编号、状态、执行人/检验员与工卡当时更新时间逐列留痕。 */
@Data
@Entity
@Table(name = "release_snapshot_card")
public class ReleaseSnapshotCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "card_no", nullable = false, length = 32)
    private String cardNo;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "status_name", nullable = false, length = 16)
    private String statusName;

    @Column(name = "executor", length = 32)
    private String executor;

    @Column(name = "inspector", length = 32)
    private String inspector;

    @Column(name = "card_updated_at")
    private LocalDateTime cardUpdatedAt;
}
