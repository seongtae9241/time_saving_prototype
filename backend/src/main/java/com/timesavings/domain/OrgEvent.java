package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 동네 소식: 기관(담당자)이 올리는 행사·클래스 홍보 글(2026-11-09 회의 2장·O3). */
@Entity
@Table(name = "org_events", indexes = @Index(name = "ix_org_events_date", columnList = "event_date"))
public class OrgEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long orgId;
    public Long createdBy;
    @Column(nullable = false, length = 100) public String title;
    @Column(length = 2000) public String content;
    @Column(name = "event_date") public LocalDate eventDate;
    public Double startHour;
    @Column(length = 100) public String place;
    /** 정원(0 = 제한 없음) */
    public int capacity;
    /** APP(앱에서 신청) | LINK(외부 링크로 신청) */
    @Column(nullable = false, length = 5) public String applyType = "APP";
    @Column(length = 500) public String url;
    /** URL 미리보기(서버가 og:title·og:image·title을 가져와 저장) */
    @Column(length = 200) public String ogTitle;
    @Column(length = 500) public String ogImage;
    @Column(length = 300) public String ogDesc;
    /** OPEN | CLOSED */
    @Column(nullable = false, length = 10) public String status = "OPEN";
    public LocalDateTime createdAt = LocalDateTime.now();
}
