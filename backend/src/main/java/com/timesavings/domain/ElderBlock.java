package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 담당자가 "이 봉사자 받지 않기"로 정한 기록(O1). 이 봉사자는 그 대상 건에 요청·제안할 수 없다. */
@Entity
@Table(name = "elder_blocks", indexes = @Index(name = "ix_block_elder_vol", columnList = "elder_id,volunteer_id"))
public class ElderBlock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "elder_id", nullable = false) public Long elderId;
    @Column(name = "volunteer_id", nullable = false) public Long volunteerId;
    @Column(nullable = false, length = 300) public String reason;
    public Long managerId;
    public Long requestId;
    public LocalDateTime createdAt = LocalDateTime.now();
}
