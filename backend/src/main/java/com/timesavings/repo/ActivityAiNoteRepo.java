package com.timesavings.repo;

import com.timesavings.domain.ActivityAiNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface ActivityAiNoteRepo extends JpaRepository<ActivityAiNote, Long> {
    Optional<ActivityAiNote> findByKindAndRefIdAndRefKey(String kind, Long refId, String refKey);
    List<ActivityAiNote> findByKindAndRequestId(String kind, Long requestId);
    long countByKind(String kind);
}
