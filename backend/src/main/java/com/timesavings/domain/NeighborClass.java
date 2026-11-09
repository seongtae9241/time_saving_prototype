package com.timesavings.domain;

import com.timesavings.domain.Enums.ClassStatus;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 동네 클래스(거꾸로 수업). */
@Entity
@Table(name = "classes")
public class NeighborClass {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long teacherId;
    public Long teacherElderId;
    public Long createdBy;
    @Column(length = 20) public String kind;
    @Column(nullable = false, length = 100) public String title;
    @Column(length = 1000) public String intro;
    @Lob @Column(columnDefinition = "TEXT") public String stepsJson;
    @Column(length = 300) public String items;
    @Column(length = 500) public String caution;
    /** 허용 / 주의 */
    @Column(length = 10) public String level;
    @Column(length = 200) public String levelReason;
    @Column(nullable = false) public LocalDate date;
    public double startHour;
    public double hours;
    @Column(length = 100) public String place;
    public Long spaceId;
    public int capacity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 15) public ClassStatus status = ClassStatus.SPACE_PENDING;
    @Lob @Column(columnDefinition = "TEXT") public String noteJson;
    @Column(length = 6) public String checkCode;
    public LocalDateTime createdAt = LocalDateTime.now();
}
