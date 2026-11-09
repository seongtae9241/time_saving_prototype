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
    public LocalDateTime completedAt;
}
