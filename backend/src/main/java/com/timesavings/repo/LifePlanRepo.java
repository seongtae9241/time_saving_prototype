package com.timesavings.repo;

import com.timesavings.domain.LifePlan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifePlanRepo extends JpaRepository<LifePlan, Long> {}
