package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 수동 단골 등록(2026-11-09 회의 U7). 자동 단골(DONE 3회 이상)과 합쳐서 보여 준다. */
@Entity
@Table(name = "regular_marks", uniqueConstraints = @UniqueConstraint(name = "uq_regular_mark", columnNames = {"user_id", "volunteer_id"}))
public class RegularMark {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    /** 도움 받는 사람(등록한 사람) */
    @Column(name = "user_id", nullable = false) public Long userId;
    /** 단골로 등록한 봉사자 */
    @Column(name = "volunteer_id", nullable = false) public Long volunteerId;
    public LocalDateTime createdAt = LocalDateTime.now();
}
