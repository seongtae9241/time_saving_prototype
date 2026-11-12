package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "notices")
public class Notice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 100) public String title;
    @Column(length = 4000) public String content;
    /** 기능 A-16: 고정 공지(목록 맨 위). null·false 는 고정 아님. */
    public Boolean pinned;
    public LocalDateTime createdAt = LocalDateTime.now();
}
