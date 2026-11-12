package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 기능 C-04 담당자 계정 신청: 이미 승인된 기관의 소속 담당자로 신청하고, 휴대폰 본인인증이 되면 자동 승인되어 담당자 계정이 생긴다.
 * 자동 승인이라 재직 증빙 파일을 함께 받아 두고, 운영자가 내역을 보고(기능 A-11) 잘못된 계정은 취소(REVOKED)할 수 있다.
 */
@Entity
@Table(name = "manager_applications", indexes = @Index(name = "ix_mgrapp_org", columnList = "org_id"))
public class ManagerApplication {
    public static final String APPROVED = "APPROVED", REVOKED = "REVOKED";
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "org_id", nullable = false) public Long orgId;
    /** 자동 승인으로 만든 담당자 계정(users.id) */
    @Column(name = "user_id") public Long userId;
    @Column(length = 50) public String name;
    @Column(length = 20) public String phone;
    @Column(name = "login_id", length = 50) public String loginId;
    /** 재직 증빙 파일 주소(/api/files/…). 운영자만 열 수 있다 */
    @Column(name = "proof_file_url", length = 200) public String proofFileUrl;
    /** APPROVED(본인인증으로 자동 승인) · REVOKED(운영자가 취소) */
    @Column(nullable = false, length = 10) public String status = APPROVED;
    /** 신청 때 그 기관에 이미 있던 담당자 수(운영자가 눈여겨볼 수 있게) */
    public Integer existingManagers;
    @Column(length = 300) public String revokeReason;
    public LocalDateTime revokedAt;
    public LocalDateTime createdAt = LocalDateTime.now();
}
