package com.timesavings.repo;

import com.timesavings.domain.PublicReferral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PublicReferralRepo extends JpaRepository<PublicReferral, Long> {
    List<PublicReferral> findByOrgIdOrderByIdDesc(Long orgId);
    List<PublicReferral> findByUserIdOrderByIdDesc(Long userId);
}
