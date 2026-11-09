package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "notices")
public class Notice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 100) public String title;
    @Column(length = 4000) public String content;
    public LocalDateTime createdAt = LocalDateTime.now();
}
