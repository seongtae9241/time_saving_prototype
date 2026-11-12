package com.timesavings.repo;

import com.timesavings.domain.GroupParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupParticipantRepo extends JpaRepository<GroupParticipant, Long> {
    List<GroupParticipant> findByGroupPostId(Long groupPostId);
    List<GroupParticipant> findByUserId(Long userId);
}
