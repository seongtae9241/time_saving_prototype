package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 기능 U-09 정기 요청: "매주 ○요일 ○시"와 기간을 정해 두면 회차마다 도움 요청 공고가 자동으로 생긴다.
 * 회차 공고는 스케줄러가 다가오는 것(7일 안)만 만들어 가고, 만든 공고에는 seriesId("RC{id}")·repeatRule("매주 목요일")을 남긴다.
 * 공고 1개에 요청 1건만 받는 규칙은 회차마다 그대로다.
 */
@Entity
@Table(name = "recurring_posts", indexes = {@Index(name = "ix_recur_owner", columnList = "owner_id"), @Index(name = "ix_recur_status", columnList = "status")})
public class RecurringPost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    /** 도움 받는 사용자(가족 대신이면 그 가족). 담당자의 관리 대상 대리 요청이면 null. */
    @Column(name = "owner_id") public Long ownerId;
    @Column(name = "elder_id") public Long elderId;
    /** 등록한 사람(본인·가족 대신 올린 사람·담당자) */
    @Column(name = "created_by", nullable = false) public Long createdBy;
    /** 반복 요일: 1=월 … 7=일 */
    @Column(nullable = false) public int weekday;
    @Column(name = "start_hour") public double startHour;
    @Column(name = "end_hour") public double endHour;
    @Column(name = "est_hours") public Double estHours;
    @Column(length = 30) public String area;
    @Convert(converter = StringListConverter.class) @Column(length = 200) public List<String> categories = new ArrayList<>();
    @Column(length = 100) public String title;
    @Column(length = 1000) public String detail;
    @Column(length = 500) public String note;
    @Column(name = "issue_note", length = 300) public String issueNote;
    /** 기간(양 끝 포함). startDate 는 첫 회차 날짜, endDate 는 마지막 회차 날짜로 맞춰 저장한다. */
    @Column(name = "start_date", nullable = false) public LocalDate startDate;
    @Column(name = "end_date", nullable = false) public LocalDate endDate;
    /** ACTIVE(진행) | PAUSED(일시정지) | ENDED(기간 끝) | CANCELLED(해지) */
    @Column(nullable = false, length = 10) public String status = "ACTIVE";
    /** 자동으로 멈췄을 때의 까닭(예: 가족 연결이 끊김). 사용자가 멈춘 것이면 null. */
    @Column(name = "stop_reason", length = 200) public String stopReason;
    /** 회차 공고를 만든(또는 건너뛴) 마지막 날짜. 이 다음 회차부터 만든다. */
    @Column(name = "last_generated") public LocalDate lastGenerated;
    @Column(name = "created_at") public LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "updated_at") public LocalDateTime updatedAt;

    public String seriesId() { return "RC" + id; }
}
