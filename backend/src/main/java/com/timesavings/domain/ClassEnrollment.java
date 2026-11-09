package com.timesavings.domain;

import com.timesavings.domain.Enums.EnrollStatus;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "class_enrollments")
public class ClassEnrollment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long classId;
    @Column(nullable = false) public Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public EnrollStatus status = EnrollStatus.APPLIED;
    @Column(length = 500) public String review;
    public LocalDateTime createdAt = LocalDateTime.now();

    // ---- 1:1 신청 (mode=ONE 클래스) ----
    /** WAIT(수락 대기) / OK(확정) / MET(만나는 중, 정산됨) / DONE(만남 끝) / CANCELLED. 같이 해 보기 신청은 비어 있음. */
    @Column(length = 10) public String oneStatus;
    public LocalDate slotDate;
    public Double slotHour;
    /** 궁금한 점 한 줄 */
    @Column(length = 300) public String question;
    /** 선생님이 다른 시간을 제안해 학생 답을 기다리는 중 */
    public Boolean proposed;
    /** 오늘 배운 한 줄(선생님께 전달, 배움 노트) */
    @Column(length = 300) public String note;
    public LocalDateTime checkInAt;
    public LocalDateTime checkOutAt;
}
