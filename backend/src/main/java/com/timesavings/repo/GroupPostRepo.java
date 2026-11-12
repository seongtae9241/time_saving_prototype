package com.timesavings.repo;

import com.timesavings.domain.GroupPost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupPostRepo extends JpaRepository<GroupPost, Long> {
    List<GroupPost> findByStatus(String status);
}
