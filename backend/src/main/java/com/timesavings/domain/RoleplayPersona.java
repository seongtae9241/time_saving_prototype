package com.timesavings.domain;

import jakarta.persistence.*;

/** 교육 역할극 가상 상대. turns_json = [{options:[{t,q,f}], next}] */
@Entity
@Table(name = "roleplay_personas")
public class RoleplayPersona {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 30) public String name;
    @Column(length = 5) public String initial;
    @Column(length = 30) public String ageLabel;
    @Column(length = 20) public String fitCategory;
    @Column(length = 200) public String trait;
    @Column(length = 200) public String goal;
    @Column(length = 300) public String opening;
    @Lob @Column(columnDefinition = "TEXT") public String turnsJson;
    public boolean active = true;
}
