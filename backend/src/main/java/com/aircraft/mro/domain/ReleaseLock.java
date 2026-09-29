package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 放行审签的飞机级互斥锁：每架飞机一行，id 即飞机 id。
 *
 * <p>所有会改变"放行事实"或审签状态的事务，先 {@code SELECT ... FOR UPDATE}
 * 锁住本行再动数据，把【上游变更 / 机械签字 / 终审 / 出位】强制按同一把锁串行化，
 * 从根上杜绝"终审与上游变更同时发生"的双花与死锁。
 */
@Entity
@Table(name = "release_lock")
public class ReleaseLock {

    /** 主键即飞机 id（懒创建：首次有放行动作时 upsert） */
    @Id
    @Column(name = "aircraft_id")
    private Long aircraftId;

    public ReleaseLock() {
    }

    public ReleaseLock(Long aircraftId) {
        this.aircraftId = aircraftId;
    }

    public Long getAircraftId() {
        return aircraftId;
    }

    public void setAircraftId(Long aircraftId) {
        this.aircraftId = aircraftId;
    }
}
