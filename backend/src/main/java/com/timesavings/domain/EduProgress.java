package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "edu_progress")
public class EduProgress {
    @Id public Long userId;
    @Lob @Column(columnDefinition = "TEXT") public String diagJson;
    public boolean cardsDone;
    public Integer quizScore;
    @Lob @Column(columnDefinition = "TEXT") public String roleplayJson;
    /** 기능 U-34: 첫 활동 교육 이수 시각(상황을 모두 마친 때) */
    public LocalDateTime completedAt;
    /** 기능 U-34: 끝낸 연습 상황 key 목록(JSON 배열) */
    @Lob @Column(columnDefinition = "TEXT") public String practiceJson;
}
