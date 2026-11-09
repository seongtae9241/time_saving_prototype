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
    public LocalDateTime createdAt = LocalDateTime.now();
}
