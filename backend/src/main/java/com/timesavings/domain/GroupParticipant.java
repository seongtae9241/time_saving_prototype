package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 단체 봉사 참여자. status: JOINED(참여 신청) | LEFT(취소) | ATTENDED(참석 확인·적립) | ABSENT(불참).
 * 2026-11-16 기관 공고 신청도 이 표를 쓴다: APPLIED(신청, 담당자 답 대기) → JOINED(확정) | REJECTED(거절). 해 드려요는 방문을 마치면 SERVED(적립 없음).
 */
@Entity
@Table(name = "group_participants", indexes = {@Index(name = "ix_group_part_post", columnList = "group_post_id"), @Index(name = "ix_group_part_user", columnList = "user_id")})
public class GroupParticipant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "group_post_id", nullable = false) public Long groupPostId;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(nullable = false, length = 10) public String status = "JOINED";
    /** 참석이 확인돼 적립된 시간 */
    public Double earned;
    public LocalDateTime createdAt = LocalDateTime.now();
    /** 기관 공고 신청 메시지(연락처·주소는 적지 않는다) */
    @Column(length = 300) public String message;
    /** 함께 오는 인원(신청자 포함). null = 1명 */
    @Column(name = "head_count") public Integer headCount;
    @Column(name = "decided_by") public Long decidedBy;
    @Column(name = "decided_at") public LocalDateTime decidedAt;
    @Column(name = "reject_reason", length = 200) public String rejectReason;
    /** 해 드려요: 담당자가 정한 방문 날짜·시각 */
    @Column(name = "visit_date") public java.time.LocalDate visitDate;
    @Column(name = "visit_start_hour") public Double visitStartHour;
    @Column(name = "visit_hours") public Double visitHours;

    public int heads() { return headCount == null || headCount < 1 ? 1 : headCount; }
}
