package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ElderRepo extends JpaRepository<Elder, Long> {
    List<Elder> findByManagerId(Long managerId);
    List<Elder> findByOrgId(Long orgId);
}
