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
    /** B-21 날짜 조건: 이 기간(양 끝 포함)의 공고만 알림. 하루만이면 시작=끝. 둘 다 비면 날짜 상관없음. */
    @Column(name = "date_from") public java.time.LocalDate dateFrom;
    @Column(name = "date_to") public java.time.LocalDate dateTo;
    public boolean enabled = true;
}
