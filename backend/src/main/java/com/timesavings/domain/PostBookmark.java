package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 관심 공고(북마크, 2026-11-13 대표 지시). 사용자가 ☆로 담아 둔 1:1 공고. 플래너 달력에는 넣지 않고 목록으로만 보여 준다. */
@Entity
@Table(name = "post_bookmarks",
        uniqueConstraints = @UniqueConstraint(name = "uq_post_bookmark", columnNames = {"user_id", "post_id"}),
        indexes = @Index(name = "ix_post_bookmark_user", columnList = "user_id"))
public class PostBookmark {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(name = "post_id", nullable = false) public Long postId;
    public LocalDateTime createdAt = LocalDateTime.now();
}
