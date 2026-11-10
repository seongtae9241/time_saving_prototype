package com.timesavings.domain;

import com.timesavings.domain.Enums.*;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "posts", indexes = {@Index(name = "ix_posts_date", columnList = "date"), @Index(name = "ix_posts_owner", columnList = "owner_id")})
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public PostType type;
    @Column(name = "owner_id") public Long ownerId;
    public Long elderId;
    public Long createdBy;
    @Column(nullable = false) public LocalDate date;
    public double startHour;
    public double endHour;
    @Column(length = 30) public String area;
    @Convert(converter = StringListConverter.class) @Column(length = 200) public List<String> categories = new ArrayList<>();
    @Column(length = 100) public String title;
    @Column(length = 1000) public String detail;
    @Column(length = 500) public String note;
    public Double estHours;
    @Enumerated(EnumType.STRING) @Column(length = 12) public Level difficulty;
    @Column(length = 300) public String difficultyReason;
    public Double weight;
    @Column(length = 50) public String repeatRule;
    @Column(length = 40) public String seriesId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) public PostStatus status = PostStatus.OPEN;
    public LocalDateTime createdAt = LocalDateTime.now();
    /** LLM 추가 A(7.12): 쉬운 말 읽어 주기 문장(공고별 1회 생성, 수정하면 지움) */
    @Column(name = "easy_text", length = 300) public String easyText;
    /** LLM 추가 A(7.12): 안 맞춰지는 공고 고치기 제안 JSON {tips, evidence, source}, 한 번 확인한 시각(공고당 1회) */
    @Column(name = "fix_tips_json", length = 3000) public String fixTipsJson;
    @Column(name = "fix_checked_at") public LocalDateTime fixCheckedAt;
}
