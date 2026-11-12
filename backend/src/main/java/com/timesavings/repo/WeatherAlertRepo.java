package com.timesavings.repo;

import com.timesavings.domain.WeatherAlert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeatherAlertRepo extends JpaRepository<WeatherAlert, Long> {
}
