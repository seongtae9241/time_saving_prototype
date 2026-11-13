package com.timesavings.repo;

import com.timesavings.domain.PostBookmark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PostBookmarkRepo extends JpaRepository<PostBookmark, Long> {
    List<PostBookmark> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);
    Optional<PostBookmark> findByUserIdAndPostId(Long userId, Long postId);
}
