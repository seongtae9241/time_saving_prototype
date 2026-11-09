package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface InterestAlertRepo extends JpaRepository<InterestAlert, Long> {
    List<InterestAlert> findByUserId(Long userId);
    List<InterestAlert> findByEnabledTrue();
}
