package com.timesavings.service;

import com.timesavings.config.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 없이 쓰는 주소(기관 신청)의 간단한 횟수 제한(파일을 저장하는 신청만 센다). 서버 메모리에만 두므로 서버를 다시 띄우면 지워지고, 서버가 여러 대면 대마다 따로 센다.
 * 같은 key(IP)가 WINDOW_MS 안에 MAX 번을 넘기면 429.
 */
@Component
public class RateLimiter {
    public static final int MAX = 5;
    public static final long WINDOW_MS = 10 * 60 * 1000L;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public void check(String bucket, String key) { check(bucket, key, System.currentTimeMillis()); }

    void check(String bucket, String key, long now) {
        Deque<Long> q = hits.computeIfAbsent(bucket + "|" + key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
            if (q.size() >= MAX)
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "짧은 시간에 여러 번 신청했어요. 10분쯤 뒤에 다시 시도해 주세요");
            q.addLast(now);
        }
        if (hits.size() > 5000) hits.entrySet().removeIf(e -> { synchronized (e.getValue()) { return e.getValue().isEmpty() || now - e.getValue().peekLast() >= WINDOW_MS; } });
    }

    /** 테스트용: 기록을 비운다. */
    public void reset() { hits.clear(); }

    /** 프록시 뒤에서는 X-Forwarded-For 의 첫 주소, 없으면 연결 주소. */
    public static String clientIp(jakarta.servlet.http.HttpServletRequest req) {
        String f = req.getHeader("X-Forwarded-For");
        if (f != null && !f.isBlank()) return f.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
