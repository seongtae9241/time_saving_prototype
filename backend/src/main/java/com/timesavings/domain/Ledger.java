package com.timesavings.domain;

import com.timesavings.domain.Enums.LedgerType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 시간 통장 거래. user_id가 비어 있으면 시스템 기록(공동 적립 POOL, 기관 대신 받은 선생님 적립). */
@Entity
@Table(name = "ledger", indexes = @Index(name = "ix_ledger_user", columnList = "user_id"))
public class Ledger {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "user_id") public Long userId;
    public Long elderId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) public LedgerType type;
    public double amount;
    public Double baseHours;
    public Double weight;
    public Long requestId;
    public Long classId;
    @Column(length = 200) public String label;
    public LocalDateTime createdAt = LocalDateTime.now();
}
