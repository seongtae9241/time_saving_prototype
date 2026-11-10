package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 기관 클래스 추천(2026-11-10): 공공기관(주민센터·평생학습관·복지관·도서관)이 여는 강좌.
 * 세 경로가 한 테이블에 모인다 — AI_SEARCH(Gemini 구글 검색 그라운딩, 서버가 주소를 열어 확인한 것만 보임),
 * PUBLIC_DATA(공공데이터포털 평생학습강좌 표준데이터 CSV), ORG(동네 소식 기관 행사 org_events 를 옮겨 둔 것, org_event_id).
 * 신청은 기관 사이트(apply_url)에서 한다. 이 앱은 시간을 쓰지 않는다.
 */
@Entity
@Table(name = "public_classes", indexes = {
        @Index(name = "ix_pc_source", columnList = "source"),
        @Index(name = "ix_pc_start", columnList = "start_date"),
        @Index(name = "ix_pc_search_key", columnList = "search_key")})
public class PublicClass {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    /** AI_SEARCH | PUBLIC_DATA | ORG */
    @Column(nullable = false, length = 12) public String source;
    @Column(nullable = false, length = 200) public String title;
    @Column(name = "org_name", length = 100) public String orgName;
    /** 시군구·동 (예: "금천구 독산동") */
    @Column(length = 60) public String region;
    @Column(length = 200) public String place;
    @Column(name = "start_date") public LocalDate startDate;
    @Column(name = "end_date") public LocalDate endDate;
    @Column(name = "schedule_text", length = 200) public String scheduleText;
    @Column(length = 200) public String target;
    @Column(name = "fee_text", length = 60) public String feeText;
    @Column(name = "capacity_text", length = 60) public String capacityText;
    @Column(name = "apply_url", length = 500) public String applyUrl;
    @Column(name = "apply_how", length = 200) public String applyHow;
    @Column(name = "image_url", length = 500) public String imageUrl;
    /** 디지털 | 정서·건강 | 생활 | 기타 */
    @Column(nullable = false, length = 10) public String category = "기타";
    @Column(length = 1000) public String description;
    /** 서버가 apply_url 을 실제로 열어 제목을 얻었으면(AI_SEARCH) / 공공데이터·기관 등록이면 true */
    public boolean verified;
    @Column(name = "verified_at") public LocalDateTime verifiedAt;
    /** 확인한 페이지 제목(AI_SEARCH) */
    @Column(name = "page_title", length = 200) public String pageTitle;
    @Column(name = "fetched_at") public LocalDateTime fetchedAt = LocalDateTime.now();
    @Column(name = "expires_at") public LocalDateTime expiresAt;
    /** 운영자가 숨김 또는 검증 실패 */
    public boolean hidden;
    /** AI_SEARCH: 어느 지역 검색에서 나왔는지(하루 1회 캐시 키) */
    @Column(name = "search_key", length = 80) public String searchKey;
    /** ORG: 원래 동네 소식 id */
    @Column(name = "org_event_id") public Long orgEventId;
    public Double lat;
    public Double lng;
    /** 원본 한 줄(CSV 나머지 열·AI 응답 항목·근거 주소) */
    @Lob @Column(name = "raw_json", columnDefinition = "TEXT") public String rawJson;
    public LocalDateTime createdAt = LocalDateTime.now();
}
