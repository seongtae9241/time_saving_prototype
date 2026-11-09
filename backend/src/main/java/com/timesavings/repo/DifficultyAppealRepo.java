package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface DifficultyAppealRepo extends JpaRepository<DifficultyAppeal, Long> {
    List<DifficultyAppeal> findByRequestId(Long requestId);
}
