package com.timesavings.repo;

import com.timesavings.domain.RegularMark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegularMarkRepo extends JpaRepository<RegularMark, Long> {
    List<RegularMark> findByUserId(Long userId);
    Optional<RegularMark> findByUserIdAndVolunteerId(Long userId, Long volunteerId);
    List<RegularMark> findByVolunteerId(Long volunteerId);
}
