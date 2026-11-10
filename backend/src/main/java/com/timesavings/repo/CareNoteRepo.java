package com.timesavings.repo;

import com.timesavings.domain.CareNote;
import com.timesavings.domain.Enums.SubjectType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CareNoteRepo extends JpaRepository<CareNote, Long> {
    Optional<CareNote> findByRequestId(Long requestId);
    List<CareNote> findBySubjectTypeAndSubjectId(SubjectType type, Long subjectId);
    List<CareNote> findByRequestIdIn(Collection<Long> ids);
}
