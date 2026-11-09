package com.timesavings.domain;

import com.timesavings.domain.Enums.EnrollStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "class_enrollments")
public class ClassEnrollment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long classId;
    @Column(nullable = false) public Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public EnrollStatus status = EnrollStatus.APPLIED;
    @Column(length = 500) public String review;
    public LocalDateTime createdAt = LocalDateTime.now();
}
