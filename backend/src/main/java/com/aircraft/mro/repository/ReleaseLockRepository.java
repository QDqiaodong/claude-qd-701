package com.aircraft.mro.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aircraft.mro.domain.ReleaseLock;

import jakarta.persistence.LockModeType;

public interface ReleaseLockRepository extends JpaRepository<ReleaseLock, Long> {

    /** 飞机级悲观写锁：把该机的上游变更 / 签字 / 终审 / 出位强制串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from ReleaseLock l where l.aircraftId = :aircraftId")
    Optional<ReleaseLock> lockAircraft(@Param("aircraftId") Long aircraftId);
}
