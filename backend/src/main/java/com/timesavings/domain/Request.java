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
    /** 기능 U-16 사진 인증: 가벼운 집안일의 "한 뒤" 사진(logPhotoUrl 은 영수증 또는 "하기 전" 사진). */
    @Column(name = "log_photo_url2", length = 200) public String logPhotoUrl2;
    /** LLM 추가 A(7.12): "왜 이 봉사자?" 한 줄 이유(요청 생성 때 1회 계산·저장, 숫자는 코드 값) */
    @Column(name = "match_reason", length = 200) public String matchReason;
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
    /** B-51: 30초마다 바뀌는 QR·번호를 만드는 비밀값(일정 확정 때 생성). 화면에는 나가지 않는다. */
    @Column(name = "qr_secret", length = 64) public String qrSecret;
    /** B-52: 시작 30분 경과 "노쇼 의심" 알림을 보냈는지. */
    @Column(name = "noshow_notified", columnDefinition = "boolean default false") public boolean noshowNotified;
    /** B-53: 종료 QR 없이 예정 종료 시각으로 자동 마감됐는지. */
    @Column(name = "auto_closed", columnDefinition = "boolean default false") public boolean autoClosed;
    /** B-51: 기록된 시간이 예정과 30분 이상 달라 양쪽에 안내했는지. */
    @Column(name = "gap_notified", columnDefinition = "boolean default false") public boolean gapNotified;
    public LocalDateTime confirmedAt;
    public LocalDateTime doneAt;
    public LocalDateTime createdAt = LocalDateTime.now();

    /** 예정 종료 시각. */
    public LocalDateTime endAt() { return startAt().plusMinutes(Math.round(hours * 60)); }

    /** 30초 코드의 비밀값. 예전 자료(qr_secret 없음)는 요청 번호와 예전 6자리로 대신한다. */
    public String qrKey() { return qrSecret != null && !qrSecret.isBlank() ? qrSecret : "L" + id + ":" + checkCode; }

    public LocalDateTime startAt() {
        int h = (int) Math.floor(startHour);
        return date.atTime(h, (int) Math.round((startHour - h) * 60));
    }
}
