package com.timesavings.repo;

import com.timesavings.domain.ElderBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ElderBlockRepo extends JpaRepository<ElderBlock, Long> {
    boolean existsByElderIdAndVolunteerId(Long elderId, Long volunteerId);
    List<ElderBlock> findByElderIdOrderByIdDesc(Long elderId);
}
