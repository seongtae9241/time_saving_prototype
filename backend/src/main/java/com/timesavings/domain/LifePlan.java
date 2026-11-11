package com.timesavings.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 생애 플랜 조건(플래너 3번째 칸). 결과 숫자는 저장하지 않고 볼 때마다 코드가 다시 계산한다. */
@Entity
@Table(name = "life_plans")
public class LifePlan {
    @Id @Column(name = "user_id") public Long userId;
    public int age;
    public int startAge;
    /** 한 달에 모을 시간 */
    public double monthlyHours;
    /** 받고 싶은 도움 {카테고리: 한 달 시간} JSON */
    @Column(name = "needs_json", length = 500) public String needsJson;
    public LocalDateTime updatedAt = LocalDateTime.now();
}
