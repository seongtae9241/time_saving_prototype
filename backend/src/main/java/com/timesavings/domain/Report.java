package com.timesavings.domain;

import com.timesavings.domain.Enums.*;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 신고(B-37)와 독거노인 이상 징후 접수(B-36). */
@Entity
@Table(name = "reports")
public class Report {
    public static final String WELFARE = "WELFARE";
    public static final String WELFARE_TYPE = "이상 징후(안부 확인)";
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
    /** D-02: 종류. null 은 일반 신고, WELFARE 는 독거노인 이상 징후(안부 확인) 접수 — 제재 없이 기록 확인용. */
    @Column(length = 10) public String kind;
    /** B-36: 이상 징후 접수가 가리키는 도움 대상. */
    @Column(name = "elder_id") public Long elderId;
    public LocalDateTime createdAt = LocalDateTime.now();
    public LocalDateTime handledAt;

    public boolean welfare() { return WELFARE.equals(kind); }
}
