package com.timesavings.domain;

import com.timesavings.domain.Enums.*;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 요청·제안·단골 요청 한 건과 그 활동 진행 기록. */
@Entity
@Table(name = "requests", indexes = {@Index(name = "ix_req_status", columnList = "status"), @Index(name = "ix_req_post", columnList = "post_id")})
public class Request {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public Kind kind;
    @Column(name = "post_id") public Long postId;
    @Column(nullable = false) public Long senderId;
    public Long elderId;
    @Column(nullable = false) public Long volunteerId;
    public Long recipientId;
    @Column(nullable = false) public LocalDate date;
    public double startHour;
    public double hours;
    @Column(length = 20) public String category;
    @Column(length = 1000) public String content;
    @Column(length = 500) public String memo;
    @Enumerated(EnumType.STRING) @Column(length = 12) public Level difficulty;
    public double weight;
    public double estEarn;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 15) public ReqStatus status = ReqStatus.SENT;
    @Column(length = 300) public String rejectReason;
    public LocalDateTime deadlineAt;
    @Column(length = 6) public String checkCode;
    public LocalDateTime checkInAt;
    public LocalDateTime checkOutAt;
    @Column(length = 10) public String checkMethod;
    @Column(length = 500) public String logRaw;
    @Column(length = 1000) public String logText;
    @Column(length = 200) public String logPhotoUrl;
    public boolean photoConsent;
    public LocalDateTime logSubmittedAt;
    /** 전달 메모(4장): 병원에서 들은 말·다음에 챙길 것. 봉사자 본인·받은 사람·담당자·공유 동의한 가족만 본다. */
    @Column(name = "care_memo", length = 500) public String careMemo;
    public Double adjustHours;
    @Column(length = 10) public String adjustStatus;
    @Column(length = 300) public String adjustReason;
    public Double earned;
    public boolean anomalyHold;
    public boolean reminded;
    public boolean lateNotified;
    public LocalDateTime confirmedAt;
    public LocalDateTime doneAt;
    public LocalDateTime createdAt = LocalDateTime.now();

    public LocalDateTime startAt() {
        int h = (int) Math.floor(startHour);
        return date.atTime(h, (int) Math.round((startHour - h) * 60));
    }
}
