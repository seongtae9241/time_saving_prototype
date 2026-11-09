package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface SpaceRepo extends JpaRepository<Space, Long> {
    List<Space> findByOrgId(Long orgId);
}
