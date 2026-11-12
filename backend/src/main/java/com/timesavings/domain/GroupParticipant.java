package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 단체 봉사 참여자. status: JOINED(참여 신청) | LEFT(취소) | ATTENDED(참석 확인·적립) | ABSENT(불참). */
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
}
