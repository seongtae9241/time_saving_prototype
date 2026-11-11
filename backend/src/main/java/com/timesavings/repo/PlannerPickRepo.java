package com.timesavings.repo;

import com.timesavings.domain.PlannerPick;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PlannerPickRepo extends JpaRepository<PlannerPick, Long> {
    List<PlannerPick> findByUserId(Long userId);
    Optional<PlannerPick> findByUserIdAndPostId(Long userId, Long postId);
}
