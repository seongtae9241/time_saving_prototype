package com.timesavings.repo;

import com.timesavings.domain.RecurringPost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecurringPostRepo extends JpaRepository<RecurringPost, Long> {
    List<RecurringPost> findByStatus(String status);
}
