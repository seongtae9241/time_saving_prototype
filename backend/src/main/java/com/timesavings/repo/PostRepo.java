package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface PostRepo extends JpaRepository<Post, Long> {
    List<Post> findByOwnerId(Long ownerId);
    List<Post> findByElderId(Long elderId);
    List<Post> findByStatus(Enums.PostStatus status);
    /** 7.21 가중치 전환: 예전 등급으로 남은 공고 찾기 */
    List<Post> findByDifficulty(Enums.Level difficulty);
    /** 기능 U-09: 정기 요청 한 건이 만든 회차 공고들 */
    List<Post> findBySeriesId(String seriesId);
}
