package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface AuthTokenRepo extends JpaRepository<AuthToken, String> {
    void deleteByUserId(Long userId);
}
