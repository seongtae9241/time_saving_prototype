package com.timesavings.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 운영 설정 한 줄(키-값). 지금은 봉사 인증 항목(verify.items) 설정에만 쓴다. */
@Entity
@Table(name = "app_settings")
public class AppSetting {
    @Id @Column(name = "setting_key", length = 50) public String key;
    @Column(name = "setting_value", length = 2000) public String value;
    public LocalDateTime updatedAt = LocalDateTime.now();
}
