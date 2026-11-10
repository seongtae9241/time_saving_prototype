package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications", indexes = @Index(name = "ix_noti_user", columnList = "user_id"))
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(length = 30) public String type;
    @Column(nullable = false, length = 200) public String title;
    @Column(length = 500) public String body;
    @Column(length = 200) public String link;
    public boolean isRead;
    /** LLM 추가 A(7.12, B-42): 음성 전화 알림으로 읽을 문장(voiceAlert 사용자만, 숫자는 한글 읽기) */
    @Column(name = "voice_text", length = 300) public String voiceText;
    public LocalDateTime createdAt = LocalDateTime.now();
}
