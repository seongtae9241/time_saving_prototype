package com.timesavings.repo;

import com.timesavings.domain.EventApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventApplicationRepo extends JpaRepository<EventApplication, Long> {
    List<EventApplication> findByEventId(Long eventId);
    List<EventApplication> findByUserId(Long userId);
}
