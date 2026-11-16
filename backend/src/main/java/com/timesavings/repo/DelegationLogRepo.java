package com.timesavings.repo;

import com.timesavings.domain.DelegationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DelegationLogRepo extends JpaRepository<DelegationLog, Long> {
    List<DelegationLog> findByElderIdOrderByIdDesc(Long elderId);
}
