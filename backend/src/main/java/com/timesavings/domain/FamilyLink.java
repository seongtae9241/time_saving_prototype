package com.timesavings.domain;

import com.timesavings.domain.Enums.LinkStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 가족 연결. share_report = 신청한 쪽이 자기 리포트를 상대에게 공유, target_share_report = 받은 쪽의 공유 동의. */
@Entity
@Table(name = "family_links")
public class FamilyLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long requesterId;
    @Column(nullable = false) public Long targetId;
    @Column(length = 20) public String relation;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public LinkStatus status = LinkStatus.PENDING;
    public boolean shareReport;
    public boolean targetShareReport;
    public LocalDateTime createdAt = LocalDateTime.now();
}
