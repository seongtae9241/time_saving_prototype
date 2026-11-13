package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 공공 서비스 연계(2026-11-13 대표 지시): 사용자가 공공 서비스 안내에서 "담당자에게 전달하기"를 눌렀을 때만 만들어진다.
 * 그때 물어본 말과 AI가 찾은 서비스 기록, 사용자가 적은 한마디가 고른 기관의 담당자에게 전달된다.
 * 전달하지 않은 질문·답은 어디에도 저장하지 않는다.
 */
@Entity
@Table(name = "public_referrals")
public class PublicReferral {
    public static final String NEW = "NEW", CONTACTED = "CONTACTED", HELPING = "HELPING", DONE = "DONE", NOT_ELIGIBLE = "NOT_ELIGIBLE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long userId;
    @Column(nullable = false) public Long orgId;
    /** 사용자가 물어본 말(대화 기록). 여러 번 물었으면 줄바꿈으로 이어진다. */
    @Column(nullable = false, length = 1000) public String question;
    /** AI가 한 안내 문장 */
    @Column(length = 2000) public String answer;
    /** AI가 찾은 서비스 목록(JSON 배열: name, target, summary, apply, phone …) */
    @Column(name = "services_json", length = 6000) public String servicesJson;
    /** 담당자에게 한마디 */
    @Column(length = 300) public String note;
    @Column(nullable = false, length = 15) public String status = NEW;
    /** 담당자 처리 메모 */
    @Column(length = 500) public String memo;
    public Long handlerId;
    /** 전달에 동의한 시각(이름·연락처·동네·질문·AI 기록을 그 기관 담당자에게 전달) */
    public LocalDateTime consentAt;
    public LocalDateTime createdAt = LocalDateTime.now();
    public LocalDateTime updatedAt = LocalDateTime.now();
}
