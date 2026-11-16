package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 위임 요청(프로토타입 O-DLG-01 "받은 요청"): 앱을 쓰는 사용자가 기관에 보낸 "담당 기관 연결"(NEW_LINK) 또는 "맡긴 일 바꾸기"(SCOPE_CHANGE).
 * status: PENDING | ACCEPTED | REJECTED | CANCELLED. 담당자가 눌러야 바뀐다.
 */
@Entity
@Table(name = "delegation_requests", indexes = {@Index(name = "ix_dlg_req_org", columnList = "org_id"), @Index(name = "ix_dlg_req_user", columnList = "user_id")})
public class DelegationRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 14) public String kind;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(name = "org_id", nullable = false) public Long orgId;
    /** SCOPE_CHANGE: 바꿀 위임 */
    public Long delegationId;
    public Long elderId;
    /** 요청한 범위 전체(바뀐 뒤의 범위) */
    @Convert(converter = StringListConverter.class) @Column(length = 200) public List<String> scopes = new ArrayList<>();
    /** SCOPE_CHANGE 로 범위를 줄이기만 한 요청은 본인 뜻이라 요청과 동시에 반영된다(담당자는 확인만) */
    public Boolean applied;
    @Column(length = 300) public String message;
    @Column(nullable = false, length = 10) public String status = "PENDING";
    @Column(length = 200) public String rejectReason;
    public Long answeredBy;
    public LocalDateTime answeredAt;
    public LocalDateTime createdAt = LocalDateTime.now();
}
