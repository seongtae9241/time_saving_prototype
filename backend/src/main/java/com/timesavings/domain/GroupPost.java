package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 단체 봉사 공고(기능 U-10·U-11·O-07). 1:1 공고(Post)·요청(Request)과 섞지 않는 별도 표.
 * kind: RECRUIT(모집형 — 기관 담당자가 "이 일에 n명 필요") | OFFER(제공형 — 단체가 "우리 n명이 봉사 가능").
 * status: OPEN | DONE | CANCELLED. 날짜가 지났는지·정원이 찼는지는 저장하지 않고 볼 때 계산한다.
 * 2026-11-16 기관 공고 3종(프로토타입 O-POST-05·07~09)도 이 표를 넓혀 쓴다: orgKind 가 있으면 "기관 공고"이고 신청 → 담당자 승인 흐름을 탄다.
 *   VOLUNTEER(봉사자 모집, 1~2명, kind=RECRUIT) | GROUP(단체 봉사, kind=RECRUIT) | SERVICE(해 드려요 — 기관이 해 주는 도움, kind=SERVICE, 적립·차감 없음).
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
    /** 기관 공고 종류(VOLUNTEER | GROUP | SERVICE). null 이면 예전 단체 봉사 공고(선착순 참여) */
    @Column(name = "org_kind", length = 10) public String orgKind;
    /** 해 드려요: 가능한 때를 적은 글(예: 평일 오후 2~5시). date 는 신청을 받는 마지막 날 */
    @Column(name = "when_text", length = 100) public String whenText;
    /** 해 드려요 대상 안내: ANYONE | SENIOR65 | ALONE (안내 문구일 뿐, 신청자를 검사하지 않는다) */
    @Column(length = 10) public String target;
    /** 준비물 */
    @Column(length = 100) public String supplies;
    /** 단체 봉사: 동아리·모임(함께 오는 인원)도 받는지 */
    @Column(name = "team_allowed") public Boolean teamAllowed;
    /** 신청 마감(담당자가 누름). 마감해도 확정된 사람의 활동·방문은 그대로 진행된다 */
    @Column(name = "closed_at") public LocalDateTime closedAt;
    @Column(name = "closed_by") public Long closedBy;
}
