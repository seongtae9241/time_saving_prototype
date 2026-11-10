package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 동네 소식 행사 앱 신청. */
@Entity
@Table(name = "event_applications")
public class EventApplication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long eventId;
    @Column(nullable = false) public Long userId;
    /** APPLIED | CANCELLED */
    @Column(nullable = false, length = 10) public String status = "APPLIED";
    public LocalDateTime createdAt = LocalDateTime.now();
}
