package com.timesavings.repo;

import com.timesavings.domain.Delegation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DelegationRepo extends JpaRepository<Delegation, Long> {
    List<Delegation> findByElderId(Long elderId);
    List<Delegation> findByUserId(Long userId);
    List<Delegation> findByGuardianUserId(Long guardianUserId);
}
