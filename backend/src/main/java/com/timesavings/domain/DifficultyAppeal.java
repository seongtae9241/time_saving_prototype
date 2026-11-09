package com.timesavings.domain;

import com.timesavings.domain.Enums.*;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "difficulty_appeals")
public class DifficultyAppeal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long requestId;
    @Column(nullable = false) public Long userId;
    @Column(length = 500) public String reason;
    @Enumerated(EnumType.STRING) @Column(length = 12) public Level requestedLevel;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public AppealStatus status = AppealStatus.RECEIVED;
    @Enumerated(EnumType.STRING) @Column(length = 12) public Level resultLevel;
    @Column(length = 500) public String adminNote;
    public LocalDateTime createdAt = LocalDateTime.now();
}
