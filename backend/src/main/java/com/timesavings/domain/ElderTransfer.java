package com.timesavings.domain;

import com.timesavings.domain.Enums.TransferStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "elder_transfers")
public class ElderTransfer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long elderId;
    public Long fromManagerId;
    @Column(nullable = false) public Long toManagerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public TransferStatus status = TransferStatus.PENDING;
    public LocalDateTime createdAt = LocalDateTime.now();
}
