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
}
