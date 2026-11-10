package com.timesavings.repo;

import com.timesavings.domain.AiResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiResultRepo extends JpaRepository<AiResult, Long> {
    Optional<AiResult> findByKindAndRefKey(String kind, String refKey);
    List<AiResult> findByKind(String kind);
}
