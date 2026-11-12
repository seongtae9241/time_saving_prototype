package com.timesavings.service;

import com.timesavings.config.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 업로드 파일(신분증·범죄경력 회보서·활동 사진·프로필 사진)을 ./data/uploads 에 저장. */
@Service
public class FileService {
    private static final Set<String> EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "pdf", "heic");
    private final Path dir;

    public FileService(@Value("${app.upload-dir:./data/uploads}") String dir) { this.dir = Paths.get(dir); }

    public String save(MultipartFile f) {
        if (f == null || f.isEmpty()) throw ApiException.bad("FILE_REQUIRED", "파일을 골라 주세요");
        String name = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!EXT.contains(ext)) throw ApiException.bad("FILE_TYPE", "사진(jpg·png 등)이나 PDF만 올릴 수 있어요");
        String id = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Files.createDirectories(dir);
            f.transferTo(dir.resolve(id).toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("파일 저장 실패", e);
        }
        return id;
    }

    public Path path(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}\\.[a-z]{3,4}")) throw ApiException.notFound("파일을 찾을 수 없어요");
        Path p = dir.resolve(id);
        if (!Files.exists(p)) throw ApiException.notFound("파일을 찾을 수 없어요");
        return p;
    }
}
