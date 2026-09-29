package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.ReleaseSnapshot;

public interface ReleaseSnapshotRepository extends JpaRepository<ReleaseSnapshot, Long> {

    List<ReleaseSnapshot> findAllByReleaseIdOrderByRoundNoAsc(Long releaseId);

    Optional<ReleaseSnapshot> findByReleaseIdAndRoundNo(Long releaseId, Integer roundNo);

    Optional<ReleaseSnapshot> findTopByReleaseIdOrderByRoundNoDesc(Long releaseId);
}
