package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aircraft.mro.domain.ReleaseOrder;

import jakarta.persistence.LockModeType;

public interface ReleaseOrderRepository extends JpaRepository<ReleaseOrder, Long> {

    List<ReleaseOrder> findAllByOrderByIdDesc();

    List<ReleaseOrder> findAllByAircraftIdOrderByIdDesc(Long aircraftId);

    Optional<ReleaseOrder> findByReleaseNo(String releaseNo);

    /** 同机唯一在途单据（active_flag=1） */
    Optional<ReleaseOrder> findByAircraftIdAndActiveFlag(Long aircraftId, Integer activeFlag);

    List<ReleaseOrder> findAllByStatus(String status);

    /** 全部在审单据（待机械放行 / 待质量终审）：上游变更时逐机失效用 */
    List<ReleaseOrder> findAllByStatusIn(List<String> statuses);

    long countByStatus(String status);

    long countByAircraftIdAndStatus(Long aircraftId, String status);

    /** 行级悲观锁：两人同时终审同一单时，第二个请求阻塞后看到的已是 RELEASED，从而幂等返回同一结果 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ReleaseOrder o where o.id = :id")
    Optional<ReleaseOrder> findByIdForUpdate(@Param("id") Long id);
}
