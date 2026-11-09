package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages", indexes = @Index(name = "ix_msg_req", columnList = "request_id"))
public class Message {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "request_id", nullable = false) public Long requestId;
    public Long senderId;
    @Column(length = 50) public String senderLabel;
    public boolean systemMsg;
    @Column(nullable = false, length = 1000) public String text;
    @Column(length = 200) public String warn;
    public LocalDateTime createdAt = LocalDateTime.now();
}
