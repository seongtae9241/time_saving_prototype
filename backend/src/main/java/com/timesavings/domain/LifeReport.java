package com.timesavings.domain;

import com.timesavings.domain.Enums.SubjectType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 생활 변화 리포트(P-03). 숫자(stats_json)는 코드가 집계, narrative는 AI/규칙 서술. */
@Entity
@Table(name = "life_reports", indexes = @Index(name = "ix_lr_subject", columnList = "subject_type,subject_id,report_month"))
public class LifeReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Enumerated(EnumType.STRING) @Column(name = "subject_type", nullable = false, length = 10) public SubjectType subjectType;
    @Column(name = "subject_id", nullable = false) public Long subjectId;
    /** YYYY-MM */
    @Column(name = "report_month", nullable = false, length = 7) public String month;
    @Lob @Column(columnDefinition = "TEXT") public String statsJson;
    @Column(length = 2000) public String narrative;
    public boolean bigChange;
    @Column(length = 5) public String source;
    public LocalDateTime createdAt = LocalDateTime.now();
}
