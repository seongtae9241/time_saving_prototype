package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface UserRepo extends JpaRepository<User, Long> {
    Optional<User> findByLoginId(String loginId);
    Optional<User> findFirstByPhone(String phone);
    List<User> findByRole(Enums.Role role);
    List<User> findByOrgId(Long orgId);
}
