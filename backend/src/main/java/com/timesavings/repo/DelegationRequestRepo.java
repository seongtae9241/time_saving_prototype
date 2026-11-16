package com.timesavings.repo;

import com.timesavings.domain.DelegationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DelegationRequestRepo extends JpaRepository<DelegationRequest, Long> {
    List<DelegationRequest> findByOrgId(Long orgId);
    List<DelegationRequest> findByUserId(Long userId);
}
