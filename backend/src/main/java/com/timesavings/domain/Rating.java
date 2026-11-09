package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ratings")
public class Rating {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long requestId;
    @Column(nullable = false) public Long raterId;
    @Column(nullable = false) public Long targetUserId;
    public int stars;
    @Convert(converter = StringListConverter.class) @Column(length = 300) public List<String> tags = new ArrayList<>();
    @Column(length = 500) public String comment;
    public boolean isPrivate;
    public LocalDateTime createdAt = LocalDateTime.now();
}
