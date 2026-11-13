package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RequestRepo extends JpaRepository<Request, Long> {
    List<Request> findByPostId(Long postId);
    List<Request> findByStatusIn(Collection<Enums.ReqStatus> statuses);
    List<Request> findByElderId(Long elderId);
    /** 7.21 가중치 전환: 예전 등급으로 남은 요청 찾기 */
    List<Request> findByDifficulty(Enums.Level difficulty);
}
