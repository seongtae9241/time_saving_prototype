package com.timesavings.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

/**
 * 프론트 빌드 결과(static/index.html)를 SPA로 서빙한다.
 * /api, /h2-console, /assets 와 점(.)이 있는 파일 경로(4단계까지)는 제외하고 index.html을 돌려준다.
 */
@Controller
public class SpaController {
    @GetMapping({"/", "/{a:^(?!api$|h2-console$|assets$)[^.]*$}", "/{a:^(?!api$|h2-console$|assets$)[^.]*$}/{b:[^.]*}",
            "/{a:^(?!api$|h2-console$|assets$)[^.]*$}/{b:[^.]*}/{c:[^.]*}", "/{a:^(?!api$|h2-console$|assets$)[^.]*$}/{b:[^.]*}/{c:[^.]*}/{d:[^.]*}"})
    public ResponseEntity<byte[]> index() throws IOException {
        ClassPathResource r = new ClassPathResource("static/index.html");
        if (!r.exists()) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                    .body("시간 적금 백엔드가 실행 중입니다. 프론트 빌드 결과를 static/ 에 넣으면 여기서 화면이 열립니다.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(r.getInputStream().readAllBytes());
    }
}
