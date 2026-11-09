package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ClassEnrollmentRepo extends JpaRepository<ClassEnrollment, Long> {
    List<ClassEnrollment> findByClassId(Long classId);
    List<ClassEnrollment> findByUserId(Long userId);
}
