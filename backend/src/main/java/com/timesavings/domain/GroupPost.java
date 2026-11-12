package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 단체 봉사 공고(기능 U-10·U-11·O-07). 1:1 공고(Post)·요청(Request)과 섞지 않는 별도 표.
 * kind: RECRUIT(모집형 — 기관 담당자가 "이 일에 n명 필요") | OFFER(제공형 — 단체가 "우리 n명이 봉사 가능").
 * status: OPEN | DONE | CANCELLED. 날짜가 지났는지·정원이 찼는지는 저장하지 않고 볼 때 계산한다.
 */
@Entity
@Table(name = "group_posts", indexes = {@Index(name = "ix_group_posts_date", columnList = "date"), @Index(name = "ix_group_posts_owner", columnList = "owner_id")})
public class GroupPost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 8) public String kind;
    /** 올린 사람(사용자 또는 기관 담당자) */
    @Column(name = "owner_id", nullable = false) public Long ownerId;
    /** 담당자가 올렸으면 그 기관 */
    public Long orgId;
    /** 단체 이름(동아리·봉사단 등). 모집형은 비울 수 있다 */
    @Column(length = 50) public String groupName;
    @Column(nullable = false) public LocalDate date;
    public double startHour;
    public double hours;
    @Column(length = 30) public String area;
    /** 모이는 곳(기관·공공장소 이름). 개인 주소는 적지 않는다 */
    @Column(length = 100) public String place;
    @Column(length = 30) public String category;
    @Column(length = 100) public String title;
    @Column(length = 1000) public String detail;
    /** 모집 인원(모집형) 또는 함께 봉사할 인원(제공형). 2명 이상 */
    public int capacity;
    @Column(nullable = false, length = 10) public String status = "OPEN";
    /** 제공형: 이 단체의 봉사를 받기로 한 기관·담당자(사람이 눌러 정한다) */
    public Long hostOrgId;
    public Long hostBy;
    public LocalDateTime hostedAt;
    public Long doneBy;
    public LocalDateTime doneAt;
    public LocalDateTime createdAt = LocalDateTime.now();
}
