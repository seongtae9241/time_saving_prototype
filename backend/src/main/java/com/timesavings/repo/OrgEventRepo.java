package com.timesavings.repo;

import com.timesavings.domain.OrgEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrgEventRepo extends JpaRepository<OrgEvent, Long> {
    List<OrgEvent> findByOrgId(Long orgId);
}
