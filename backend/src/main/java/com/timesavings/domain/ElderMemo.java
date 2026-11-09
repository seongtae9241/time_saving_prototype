package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "elder_memos")
public class ElderMemo {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long elderId;
    @Column(nullable = false, length = 1000) public String text;
    public LocalDateTime createdAt = LocalDateTime.now();
}
