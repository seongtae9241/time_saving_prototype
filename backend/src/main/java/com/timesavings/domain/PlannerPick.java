package com.timesavings.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 플래너의 "추천 봉사"에서 내 계획에 넣은 봉사 요청 공고. 계획일 뿐이고, 제안을 보내는 것은 사용자가 버튼으로 한다. */
@Entity
@Table(name = "planner_picks", uniqueConstraints = @UniqueConstraint(name = "uq_planner_pick", columnNames = {"user_id", "post_id"}))
public class PlannerPick {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(name = "post_id", nullable = false) public Long postId;
    public LocalDateTime createdAt = LocalDateTime.now();
    /** 41판 B-61: 계획 만들기에서 정한 시작 시각·활동 시간(공고 시간 범위 안, 내 다른 일정과 겹치지 않게). 예전 자료는 null → 공고 시작 시각. */
    @Column(name = "start_hour") public Double startHour;
    public Double hours;
    /** 시각을 누가 골랐는지: ai | rule */
    @Column(name = "plan_source", length = 10) public String planSource;
}
