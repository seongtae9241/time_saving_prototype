package com.timesavings.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.timesavings.domain.Enums.*;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users", indexes = @Index(name = "ux_users_login", columnList = "login_id", unique = true))
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "login_id", nullable = false, length = 50) public String loginId;
    @JsonIgnore @Column(nullable = false, length = 100) public String passwordHash;
    @Column(nullable = false, length = 50) public String name;
    @Column(length = 20) public String phone;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public Role role = Role.USER;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public UserStatus status = UserStatus.ACTIVE;
    @Column(length = 30) public String area;
    @Column(length = 20) public String ageGroup;
    @Column(length = 300) public String intro;
    @Column(length = 300) public String experience;
    @Column(length = 300) public String personality;
    @Convert(converter = StringListConverter.class) @Column(length = 200) public List<String> categories = new ArrayList<>();
    @Convert(converter = StringListConverter.class) @Column(length = 200) public List<String> timeSlots = new ArrayList<>();
    @Column(length = 200) public String photoUrl;
    public Long orgId;
    @Enumerated(EnumType.STRING) @Column(length = 10) public VerifyStatus idStatus = VerifyStatus.NONE;
    @Column(length = 200) public String idFileUrl;
    @Enumerated(EnumType.STRING) @Column(length = 10) public VerifyStatus crimeStatus = VerifyStatus.NONE;
    @Column(length = 200) public String crimeFileUrl;
    public boolean eduDone;
    public boolean voiceAlert;
    @Column(length = 10) public String textSize = "normal";
    /** 41판 A-01: 가입 약관 동의. 필수 2개는 동의 시각, 선택 2개는 여부(null = 예전 가입자, 묻지 않음). */
    public LocalDateTime termsAgreedAt;
    public LocalDateTime privacyAgreedAt;
    public Boolean locConsent;
    public Boolean interestConsent;
    /** 41판 B-58: 알림 종류별 켜고 끄기(null = 켜짐). B-43: 문자 병행. */
    public Boolean notiReq;
    public Boolean notiMsg;
    public Boolean notiResult;
    public Boolean notiRemind;
    public Boolean notiInterest;
    public Boolean smsAlert;
    public int penaltyCount;
    public int warningCount;
    public LocalDateTime createdAt = LocalDateTime.now();
}
