package com.timesavings.domain;

import com.timesavings.domain.Enums.SubjectType;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 전달 메모 정리(7.14 C1): 일지·전달 메모를 낼 때 한 번 정리해 저장한다(요청 한 건에 하나, 다시 내면 덮어씀).
 * 병명·약 이름은 저장하지 않는다(hospital 은 "병원 안내 있음 (…)" 요약만). 진단 문장 없음.
 */
@Entity
@Table(name = "care_notes", indexes = {@Index(name = "ix_care_note_req", columnList = "request_id", unique = true),
        @Index(name = "ix_care_note_subject", columnList = "subject_type,subject_id")})
public class CareNote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "request_id", nullable = false) public Long requestId;
    @Enumerated(EnumType.STRING) @Column(name = "subject_type", nullable = false, length = 10) public SubjectType subjectType;
    @Column(name = "subject_id", nullable = false) public Long subjectId;
    /** 활동 날짜(최근 몇 건 판단용) */
    public LocalDate activityDate;
    /** 다음에 챙길 것 (줄바꿈으로 구분) */
    @Column(length = 600) public String nextSteps;
    /** 병원 안내 요약 — 병명 없이 "병원 안내 있음 (다음 진료 일정)" 형태. 없으면 null */
    @Column(length = 200) public String hospital;
    /** 생활 변화 문장 (줄바꿈으로 구분) */
    @Column(length = 600) public String lifeChanges;
    /** 생활 변화 신호 이름(정해진 목록: 식사 거름, 외출 감소, 기운 없음, 잠 못 잠, 말수·기분 변화) */
    @Convert(converter = StringListConverter.class) @Column(length = 200) public List<String> signals = new ArrayList<>();
    /** 이 메모로 '확인 권장'이 처음 켜졌는지(알림을 한 번만 보내려고) */
    public boolean flagRaised;
    @Column(length = 10) public String source;
    @Column(length = 20) public String provider;
    public LocalDateTime createdAt = LocalDateTime.now();
    public LocalDateTime updatedAt = LocalDateTime.now();
}
