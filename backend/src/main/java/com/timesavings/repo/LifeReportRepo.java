package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface LifeReportRepo extends JpaRepository<LifeReport, Long> {
    List<LifeReport> findBySubjectTypeAndSubjectIdOrderByMonthDesc(Enums.SubjectType t, Long id);
    List<LifeReport> findByMonth(String month);
}
