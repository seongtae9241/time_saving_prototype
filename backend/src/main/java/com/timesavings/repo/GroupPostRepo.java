package com.timesavings.repo;

import com.timesavings.domain.GroupPost;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GroupPostRepo extends JpaRepository<GroupPost, Long> {
    List<GroupPost> findByStatus(String status);

    /** 참여 신청·참석 확인처럼 인원을 세고 바꾸는 일은 공고 행을 잠그고 한다(마지막 한 자리에 둘이 동시에 들어가지 않게). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GroupPost g where g.id = :id")
    Optional<GroupPost> findForUpdate(@Param("id") Long id);
}
