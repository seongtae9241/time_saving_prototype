package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 이벤트 때 한 번 만든 LLM 결과를 저장해 다시 쓰는 칸(7.14). kind + refKey 하나에 한 줄.
 * kind: ORG_BRIEFING(refKey=담당자 id) · REPORT_TRIAGE(신고 id) · ANOMALY_NOTE(봉사자:대상 키) · INQUIRY_DRAFT(문의 id).
 * fingerprint 가 바뀌면(큰 변화) 다시 만든다. payload 는 화면에 그대로 주는 JSON.
 */
@Entity
@Table(name = "ai_results", indexes = @Index(name = "ix_ai_result_key", columnList = "kind,ref_key", unique = true))
public class AiResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 20) public String kind;
    @Column(name = "ref_key", nullable = false, length = 60) public String refKey;
    @Column(length = 300) public String fingerprint;
    @Lob @Column(columnDefinition = "TEXT") public String payload;
    @Column(length = 10) public String source;
    @Column(length = 20) public String provider;
    public LocalDateTime createdAt = LocalDateTime.now();
}
