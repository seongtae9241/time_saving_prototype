package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 공공 서비스 안내용 DB(P-01, P-02). AI는 여기 있는 것만 안내한다. */
@Entity
@Table(name = "public_services")
public class PublicService {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 100) public String name;
    @Column(length = 300) public String target;
    @Column(length = 1000) public String content;
    @Column(length = 300) public String applyHow;
    @Column(length = 200) public String contact;
    @Column(length = 50) public String region;
    @Convert(converter = StringListConverter.class) @Column(length = 500) public List<String> keywords = new ArrayList<>();
    public LocalDateTime createdAt = LocalDateTime.now();
}
