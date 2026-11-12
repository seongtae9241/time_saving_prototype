package com.timesavings.service;

import com.timesavings.domain.Elder;
import com.timesavings.domain.User;
import com.timesavings.repo.ElderRepo;
import com.timesavings.repo.RecurringPostRepo;
import com.timesavings.repo.UserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 데모 데이터(기능 U-09 정기 요청): 시연 계정으로 "내 정기 요청"과 담당자의 "대상 정기 요청"이 바로 보이게 한 건씩 넣는다.
 * 데모 계정이 있고 정기 요청이 하나도 없을 때만. 등록은 화면과 같은 길(RecurringService.create)로 해서 첫 회차 공고도 규칙대로 올라간다.
 * 실패해도 서버 시작을 막지 않는다.
 */
@Component
@Order(130)
public class FuncLinkSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FuncLinkSeeder.class);
    private final UserRepo users;
    private final ElderRepo elders;
    private final RecurringPostRepo recurring;
    private final RecurringService svc;
    private final boolean enabled;

    public FuncLinkSeeder(UserRepo users, ElderRepo elders, RecurringPostRepo recurring, RecurringService svc, @Value("${app.seed.enabled:true}") boolean enabled) {
        this.users = users; this.elders = elders; this.recurring = recurring; this.svc = svc; this.enabled = enabled;
    }

    @Override // 등록마다 따로 트랜잭션(하나가 실패해도 나머지와 서버 시작에 영향 없게 여기에는 @Transactional 을 붙이지 않는다)
    public void run(ApplicationArguments args) {
        if (!enabled || recurring.count() > 0) return;
        User yujin = users.findByLoginId("yujin").orElse(null), org1 = users.findByLoginId("org1").orElse(null);
        if (yujin == null || org1 == null) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDate start = now.toLocalDate().plusDays(2);
        try {
            // 사용자: 매주 같은 요일 디지털 도움(첫 회차가 7일 안이라 공고 한 건이 바로 올라간다)
            svc.create(yujin, new RecurringService.Cmd(null, start, null, 8, 15.0, 17.0, 1.0, "행복동", List.of("디지털 도움"),
                    "매주 스마트폰 사진 정리 같이 해 주세요", "찍어 둔 사진을 폴더로 나누고 가족에게 보내는 법을 천천히 알려 주세요.", null, null, null, null), now);
        } catch (RuntimeException e) { log.info("정기 요청 데모(사용자)를 넣지 못했어요: {}", e.getMessage()); }
        try {
            // 담당자: 관리 대상 대신 매주 안부 방문
            Elder e = elders.findAll().stream().filter(x -> org1.id.equals(x.managerId) && "오덕수".equals(x.alias)).findFirst().orElse(null);
            if (e != null) svc.create(org1, new RecurringService.Cmd(null, start.plusDays(1), null, 8, 14.0, 16.0, 1.0, e.dong, List.of("말동무·정서"),
                    "매주 안부 방문과 말벗", "오후에 찾아가 안부를 여쭙고 이야기를 나눠 주세요.", null, e.id, null, "오전에는 주무시는 경우가 많아요"), now);
        } catch (RuntimeException e) { log.info("정기 요청 데모(담당자)를 넣지 못했어요: {}", e.getMessage()); }
    }
}
