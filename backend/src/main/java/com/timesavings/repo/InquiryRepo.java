package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface InquiryRepo extends JpaRepository<Inquiry, Long> {
    List<Inquiry> findByUserIdOrderByIdDesc(Long userId);
}
