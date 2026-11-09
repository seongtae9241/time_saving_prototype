package com.timesavings.domain;

import com.timesavings.domain.Enums.ClassStatus;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    // ---- 1:1 클래스 (황수빈 v2) ----
    /** ONE(1:1 배우기) / GROUP(같이 해 보기, 4명까지). 비어 있으면 GROUP. */
    @Column(length = 5) public String mode = "GROUP";
    /** 선생님 한 줄 소개 */
    @Column(length = 200) public String bio;
    @Convert(converter = StringListConverter.class) @Column(length = 200) public List<String> tags = new ArrayList<>();
    /** 지금까지 만난 수(시드 값 + 만남 끝날 때 +1) */
    public Integer met;
    /** 한 주에 받을 수 있는 1:1 신청 수 */
    public Integer weeklyCap;
    /** 물어볼 수 있는 질문 예시 [문장] */
    @Lob @Column(columnDefinition = "TEXT") public String qsJson;
    /** 가능한 시간 [{date:'YYYY-MM-DD', hour:19.0}] */
    @Lob @Column(columnDefinition = "TEXT") public String slotsJson;
    /** 만나는 곳(열린 공공장소) */
    @Column(length = 100) public String meet;
    /** 표시 이름(어르신 선생님 가림 표기, 예: 박○호) — 비면 계정 이름 */
    @Column(length = 30) public String teacherLabel;
    /** 표시 나이(예: 72세) — 비면 나이대 */
    @Column(length = 10) public String teacherAgeLabel;

    public boolean one() { return "ONE".equals(mode); }
    public LocalDateTime createdAt = LocalDateTime.now();
}
