package com.timesavings.domain;

import com.timesavings.domain.Enums.ElderStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 도움 대상(독거노인). 이름·주소·연락처는 저장하지 않는다(C-02). */
@Entity
@Table(name = "elders")
public class Elder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long orgId;
    public Long managerId;
    @Column(nullable = false, length = 30) public String alias;
    @Column(length = 30) public String dong;
    @Column(length = 20) public String ageGroup;
    @Convert(converter = StringListConverter.class) @Column(length = 200) public List<String> needs = new ArrayList<>();
    @Column(length = 500) public String caution;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public ElderStatus status = ElderStatus.ACTIVE;
    public boolean reportConsent;
    /** C-11 현관 QR 카드 번호(6자리). 다시 발급하면 값이 바뀌어 이전 카드는 쓸 수 없다. */
    @Column(name = "qr_code", length = 12) public String qrCode;
    @Column(name = "qr_issued_at") public LocalDateTime qrIssuedAt;
    /** C-02 등록할 때 "대상에게 서비스 이용을 설명하고 동의를 받았음"을 확인한 시각·확인한 담당자. 41판 이전 등록분은 null */
    @Column(name = "service_consent_at") public LocalDateTime serviceConsentAt;
    @Column(name = "service_consent_by") public Long serviceConsentBy;
    public LocalDateTime createdAt = LocalDateTime.now();
}
