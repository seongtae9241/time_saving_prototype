package com.timesavings.domain;

import com.timesavings.domain.Enums.OrgStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "organizations")
public class Organization {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 100) public String name;
    @Column(length = 30) public String orgType;
    @Column(length = 200) public String address;
    @Column(length = 20) public String phone;
    @Column(length = 50) public String managerName;
    @Column(length = 20) public String managerPhone;
    @Column(length = 100) public String managerEmail;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public OrgStatus status = OrgStatus.PENDING;
    @Column(length = 500) public String memo;
    public LocalDateTime createdAt = LocalDateTime.now();
}
