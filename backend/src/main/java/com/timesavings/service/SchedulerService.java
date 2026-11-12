package com.timesavings.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1분마다 도는 작업: 24시간 만료(B-19), 공고 자동 마감(B-11), 일지 24시간 뒤 자동 확정(C-10),
 * 시작 10분 지각·30분 노쇼 의심 알림(B-52), 예정 종료 30분 뒤 자동 마감(B-53), 전날 리마인드, 매월 1일 생활 변화 리포트(P-03).
 */
@Service
public class SchedulerService {
    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);
    private final RequestService requests;
    private final PostService posts;
    private final LifeReportService reports;
    private final boolean enabled;
    /** LLM 추가 A(7.12): 24시간 요청 없는 공고 고치기 제안(공고당 1번). 생성자를 바꾸지 않으려고 필드 주입. */
    @org.springframework.beans.factory.annotation.Autowired(required = false) private UnmatchedPostService unmatched;

    public SchedulerService(RequestService requests, PostService posts, LifeReportService reports,
                            @Value("${app.scheduler.enabled:true}") boolean enabled) {
        this.requests = requests; this.posts = posts; this.reports = reports; this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        if (!enabled) return;
        try {
            Map<String, Object> r = runAll(LocalDateTime.now());
            if (r.values().stream().anyMatch(v -> v instanceof Integer i && i > 0)) log.info("스케줄러 실행: {}", r);
        } catch (Exception e) {
            log.warn("스케줄러 오류: {}", e.toString());
        }
    }

    @Transactional
    public Map<String, Object> runAll(LocalDateTime now) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate today = now.toLocalDate();
        out.put("expired", requests.expireDue(now));
        out.put("postsClosed", posts.closePast(today));
        out.put("autoConfirmed", requests.autoConfirmDue(now));
        out.put("lateAlerts", requests.lateCheck(now));
        out.put("autoClosed", requests.autoCloseDue(now));
        out.put("reminders", requests.remind(today));
        int lr = 0;
        YearMonth prev = YearMonth.from(today).minusMonths(1);
        if (today.getDayOfMonth() == 1 && !reports.existsFor(prev)) lr = reports.generate(prev);
        out.put("lifeReports", lr);
        out.put("unmatchedTips", unmatched == null ? 0 : unmatched.run(now));
        out.put("ranAt", now.toString());
        return out;
    }
}
