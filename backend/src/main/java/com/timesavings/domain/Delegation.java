package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 권한 위임(2026-11-16 담당자 추가 기능, 프로토타입 O-DLG-01~03): 관리 대상(또는 보호자)이 담당자에게 맡긴 일의 범위.
 * 저장하는 것은 범위 코드·동의 방법 코드·날짜·계정 id 뿐이다. 동의서 사본·서명·연락처·실명은 저장하지 않는다.
 * status: PENDING(앱·보호자 동의를 기다림) | ACTIVE | REJECTED(동의하지 않음) | REVOKED(회수). 만료(EXPIRED)는 저장하지 않고 endDate 로 계산한다.
 */
@Entity
@Table(name = "delegations", indexes = {@Index(name = "ix_delegations_elder", columnList = "elder_id"), @Index(name = "ix_delegations_user", columnList = "user_id")})
public class Delegation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "elder_id", nullable = false) public Long elderId;
    public Long orgId;
    /** 등록한 담당자(지금 담당자는 elders.manager_id 를 따른다) */
    public Long createdBy;
    /** 관리 대상 본인이 앱 계정이 있을 때만 그 계정 id(앱에서 연결을 요청해 수락된 경우). 없으면 null */
    @Column(name = "user_id") public Long userId;
    /** 보호자: 본인 계정과 가족으로 연결(family_links ACCEPTED)된 계정 id 만. 없으면 null */
    @Column(name = "guardian_user_id") public Long guardianUserId;
    /** 맡긴 일 범위 코드(DelegationService.SCOPES) */
    @Convert(converter = StringListConverter.class) @Column(length = 200) public List<String> scopes = new ArrayList<>();
    /** 동의 방법 코드: APP(본인 앱) | PAPER(서면) | GUARDIAN(보호자). 서류 사본은 저장하지 않는다 */
    @Column(name = "consent_method", nullable = false, length = 10) public String consentMethod;
    /** 동의를 받은 날(PENDING 이면 null) */
    @Column(name = "consent_date") public LocalDate consentDate;
    @Column(name = "start_date") public LocalDate startDate;
    @Column(name = "end_date") public LocalDate endDate;
    @Column(nullable = false, length = 10) public String status = "ACTIVE";
    public LocalDateTime revokedAt;
    public Long revokedBy;
    public LocalDateTime createdAt = LocalDateTime.now();
}
