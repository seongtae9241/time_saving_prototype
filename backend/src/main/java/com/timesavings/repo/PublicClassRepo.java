package com.timesavings.repo;

import com.timesavings.domain.PublicClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PublicClassRepo extends JpaRepository<PublicClass, Long> {
    List<PublicClass> findBySource(String source);
    List<PublicClass> findBySourceAndSearchKey(String source, String searchKey);
    Optional<PublicClass> findFirstByOrgEventId(Long orgEventId);
    long countBySource(String source);
}
