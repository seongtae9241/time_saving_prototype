package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface LedgerRepo extends JpaRepository<Ledger, Long> {
    List<Ledger> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);
    List<Ledger> findByRequestId(Long requestId);
}
