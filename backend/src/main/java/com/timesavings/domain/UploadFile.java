package com.timesavings.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 올린 파일의 주인 기록(2026-11-12): 파일을 누가 올렸는지 남겨, 아직 어디에도 붙지 않은 파일은 올린 사람만 열 수 있게 한다.
 * 로그인 없이 올린 파일(기관 신청의 재직 증빙)은 uploaderId 가 null 이고 운영자만 연다.
 */
@Entity
@Table(name = "upload_files")
public class UploadFile {
    /** 파일 이름(무작위 32자 + 확장자). 주소는 /api/files/{fileId} */
    @Id @Column(name = "file_id", length = 40) public String fileId;
    @Column(name = "uploader_id") public Long uploaderId;
    public LocalDateTime createdAt = LocalDateTime.now();
}
