package com.timesavings.domain;

import com.timesavings.domain.Enums.*;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 신고(B-36). */
@Entity
@Table(name = "reports")
public class Report {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long reporterId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public ReportTarget targetType;
    public Long targetId;
    @Column(length = 100) public String targetLabel;
    @Column(length = 30) public String type;
    @Column(length = 1000) public String content;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public ReportStatus status = ReportStatus.RECEIVED;
    @Enumerated(EnumType.STRING) @Column(length = 10) public ReportAction action = ReportAction.NONE;
    @Column(length = 500) public String result;
    public LocalDateTime createdAt = LocalDateTime.now();
    public LocalDateTime handledAt;
}
