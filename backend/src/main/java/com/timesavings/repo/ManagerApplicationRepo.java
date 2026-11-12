package com.timesavings.repo;

import com.timesavings.domain.ManagerApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManagerApplicationRepo extends JpaRepository<ManagerApplication, Long> {
    List<ManagerApplication> findAllByOrderByIdDesc();
}
