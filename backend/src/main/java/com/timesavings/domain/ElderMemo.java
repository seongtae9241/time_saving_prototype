package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "elder_memos")
public class ElderMemo {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long elderId;
    /** 담당자가 적은 원문(그대로 보존) */
    @Column(nullable = false, length = 1000) public String text;
    /** C-13 읽기 쉽게 정리한 글(줄바꿈으로 구분). 41판 이전 메모는 null → 화면은 원문을 보여 준다 */
    @Column(name = "tidy_text", length = 1000) public String tidyText;
    /** 정리한 쪽: ai | rule */
    @Column(length = 5) public String source;
    public LocalDateTime createdAt = LocalDateTime.now();
}
