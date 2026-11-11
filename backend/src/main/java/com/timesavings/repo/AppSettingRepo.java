package com.timesavings.repo;

import com.timesavings.domain.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepo extends JpaRepository<AppSetting, String> {}
