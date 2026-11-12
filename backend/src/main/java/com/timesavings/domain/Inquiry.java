package com.timesavings.domain;

import com.timesavings.domain.Enums.InquiryStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "inquiries")
public class Inquiry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long userId;
    @Column(length = 30) public String type;
    @Column(nullable = false, length = 100) public String title;
    @Column(length = 2000) public String content;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public InquiryStatus status = InquiryStatus.RECEIVED;
    /**
     * D-08 운영자가 따로 바꾸는 처리 단계: RECEIVED(접수) · CHECKING(확인 중) · ANSWERED(답변 완료) · CLOSED(처리 완료).
     * 41판 이전 문의는 null → status 값을 그대로 단계로 본다. status(접수/답변 완료)는 B-50 표시에 그대로 쓴다.
     */
    @Column(length = 10) public String stage;
    @Column(length = 2000) public String answer;
    public LocalDateTime answeredAt;
    public LocalDateTime createdAt = LocalDateTime.now();
}
