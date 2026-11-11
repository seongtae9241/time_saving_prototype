package com.timesavings.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 카테고리 추가 건의(2026-11-11 역할별 기능 목록). 실제 카테고리 6종은 바꾸지 않고 운영자 결정용으로 기록만 한다. */
@Entity
@Table(name = "category_suggestions", indexes = @Index(name = "ix_cat_sug_user", columnList = "user_id"))
public class CategorySuggestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "user_id", nullable = false) public Long userId;
    /** 건의한 카테고리 이름 */
    @Column(nullable = false, length = 30) public String name;
    /** 같은 이름 묶기용(공백·가운뎃점 제거, 소문자) */
    @Column(name = "name_key", nullable = false, length = 30) public String nameKey;
    /** 필요한 이유 */
    @Column(length = 500) public String reason;
    /** RECEIVED(접수) | REVIEWING(검토 중) | ADOPTED(반영) | HELD(보류) */
    @Column(nullable = false, length = 10) public String status = "RECEIVED";
    @Column(length = 500) public String answer;
    public Long answeredBy;
    public LocalDateTime answeredAt;
    public LocalDateTime createdAt = LocalDateTime.now();
}
