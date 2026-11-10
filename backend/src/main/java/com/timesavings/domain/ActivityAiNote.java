package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 7.13 LLM 추가 B: 이벤트 때 한 번 만든 AI(또는 규칙) 결과를 저장해 다시 쓴다(비용 절약).
 * kind 별 ref_id: BRIEFING=요청 id, MESSAGE=메시지 id, ADJUST=요청 id, APPEAL=이의 신청 id, GOAL=사용자 id, MONTH=사용자 id(ref_key=YYYY-MM).
 * fingerprint 가 달라지면(그 달 거래가 바뀜, 목표 입력·활동이 바뀜) 다시 만든다.
 */
@Entity
@Table(name = "ai_notes", uniqueConstraints = @UniqueConstraint(name = "ux_ai_note", columnNames = {"kind", "ref_id", "ref_key"}),
        indexes = @Index(name = "ix_ai_note_req", columnList = "request_id"))
public class ActivityAiNote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 10) public String kind;
    @Column(name = "ref_id", nullable = false) public Long refId;
    @Column(name = "ref_key", nullable = false, length = 20) public String refKey = "";
    @Column(name = "request_id") public Long requestId;
    /** 메시지 안전 감지 수준: none | caution | warn (MESSAGE 만) */
    @Column(name = "warn_level", length = 10) public String warnLevel;
    @Column(length = 120) public String fingerprint;
    /** 결과 본문(JSON 문자열) */
    @Lob @Column(name = "body_json", columnDefinition = "TEXT") public String bodyJson;
    @Column(length = 10) public String source;
    @Column(length = 20) public String provider;
    public LocalDateTime createdAt = LocalDateTime.now();
}
