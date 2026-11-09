package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "auth_token")
public class AuthToken {
    @Id @Column(length = 64) public String token;
    @Column(nullable = false) public Long userId;
    @Column(nullable = false) public LocalDateTime expiresAt;
}
