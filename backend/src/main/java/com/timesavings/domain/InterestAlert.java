package com.timesavings.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "interest_alerts")
public class InterestAlert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long userId;
    @Column(length = 30) public String area;
    @Convert(converter = StringListConverter.class) @Column(length = 200) public List<String> categories = new ArrayList<>();
    /** 평일 / 주말 / 상관없음 */
    @Column(length = 10) public String dayType;
    public boolean enabled = true;
}
