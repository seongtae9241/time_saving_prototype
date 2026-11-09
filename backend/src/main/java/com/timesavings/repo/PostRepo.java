package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface PostRepo extends JpaRepository<Post, Long> {
    List<Post> findByOwnerId(Long ownerId);
    List<Post> findByElderId(Long elderId);
    List<Post> findByStatus(Enums.PostStatus status);
}
