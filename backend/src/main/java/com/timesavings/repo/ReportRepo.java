package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ReportRepo extends JpaRepository<Report, Long> {
    List<Report> findByReporterIdOrderByIdDesc(Long reporterId);
}
