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
    @Column(length = 2000) public String answer;
    public LocalDateTime answeredAt;
    public LocalDateTime createdAt = LocalDateTime.now();
}
