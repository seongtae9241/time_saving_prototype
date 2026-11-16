package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 위임 변경 기록(등록·동의·범위 변경·연장·회수·요청 수락/거절). 본인·보호자·담당자가 볼 수 있다. 지우거나 고치지 않는다. */
@Entity
@Table(name = "delegation_logs", indexes = @Index(name = "ix_dlg_log_elder", columnList = "elder_id"))
public class DelegationLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long delegationId;
    @Column(name = "elder_id") public Long elderId;
    public Long actorId;
    /** MANAGER | USER | GUARDIAN */
    @Column(length = 10) public String actorRole;
    @Column(nullable = false, length = 20) public String action;
    @Column(length = 300) public String detail;
    public LocalDateTime createdAt = LocalDateTime.now();
}
