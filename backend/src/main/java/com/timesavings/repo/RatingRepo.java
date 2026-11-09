package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RatingRepo extends JpaRepository<Rating, Long> {
    List<Rating> findByTargetUserId(Long userId);
    List<Rating> findByRequestId(Long requestId);
}
