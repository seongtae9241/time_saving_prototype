package com.timesavings.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "spaces")
public class Space {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long orgId;
    @Column(nullable = false, length = 100) public String name;
    public int capacity;
    @Column(length = 300) public String equipment;
}
