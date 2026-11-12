package com.timesavings.service;

import com.timesavings.domain.Enums.*;
import com.timesavings.domain.Ledger;
import com.timesavings.domain.Request;
import com.timesavings.domain.User;
import com.timesavings.repo.LedgerRepo;
import com.timesavings.repo.RequestRepo;
import com.timesavings.repo.UserRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 시연 자료(기능 U-24 시간 플랜): "비슷한 사람들의 실제 사용 패턴"이 보이도록 70대 이웃 5명과 그분들이 서로 주고받은
 * 최근 석 달의 완료 기록(동행·장보기·말동무·집안일)을 넣는다. 시연 계정(bok)이 있고 이 자료가 아직 없을 때만 넣는다.
 * 기존 시연 계정의 통장·기록은 건드리지 않는다(이웃 5명끼리만 주고받는다).
 */
@Component
@Order(120)
public class FuncActSeeder implements ApplicationRunner {
    private final UserRepo users;
    private final RequestRepo requests;
    private final LedgerRepo ledger;
    private final AuthService auth;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public FuncActSeeder(UserRepo users, RequestRepo requests, LedgerRepo ledger, AuthService auth, TransactionTemplate tx,
                         @Value("${app.seed.enabled:true}") boolean enabled) {
        this.users = users; this.requests = requests; this.ledger = ledger; this.auth = auth; this.tx = tx; this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || users.findByLoginId("bok").isEmpty() || users.findByLoginId("nb1").isPresent()) return;
        tx.executeWithoutResult(s -> seed());
    }

    private static final String[][] PEERS = {
            {"nb1", "박영수", "행복동"}, {"nb2", "정말순", "행복동"}, {"nb3", "한기철", "희망동"}, {"nb4", "오금자", "사랑동"}, {"nb5", "윤태식", "행복동"}};

    private void led(User u, LedgerType t, double amount, Double base, Double w, Long reqId, String label, LocalDateTime at) {
        Ledger l = new Ledger();
        l.userId = u.id; l.type = t; l.amount = amount; l.baseHours = base; l.weight = w; l.requestId = reqId; l.label = label; l.createdAt = at;
        ledger.save(l);
    }

    private void done(User vol, User rec, LocalDate date, double start, double hours, String cat, String content) {
        Request r = new Request();
        r.kind = Kind.REGULAR; r.senderId = rec.id; r.volunteerId = vol.id; r.recipientId = rec.id; r.date = date; r.startHour = start; r.hours = hours;
        r.category = cat; r.content = content; r.status = ReqStatus.DONE; r.createdAt = date.minusDays(3).atTime(10, 0);
        Level lv = Rules.categoryLevel(cat);
        r.difficulty = lv; r.weight = Rules.weight(lv); r.estEarn = Rules.earn(hours, lv); r.earned = r.estEarn;
        r.checkInAt = date.atTime((int) start, 0); r.checkOutAt = r.checkInAt.plusMinutes(Math.round(hours * 60)); r.checkMethod = "qr";
        r.logRaw = content; r.logText = content + "을(를) 함께했어요."; r.logSubmittedAt = r.checkOutAt.plusMinutes(30);
        r.confirmedAt = r.createdAt.plusHours(5); r.doneAt = r.logSubmittedAt.plusHours(2);
        requests.save(r);
        led(vol, LedgerType.EARN, r.earned, hours, r.weight, r.id, cat + " · " + rec.name + " 님", r.doneAt);
        led(rec, LedgerType.USE, -r.earned, hours, r.weight, r.id, cat + " · " + vol.name + " 님에게 받음", r.doneAt);
    }

    void seed() {
        LocalDate today = LocalDate.now();
        String pw = auth.hash("1234");
        List<User> nb = new ArrayList<>();
        for (int i = 0; i < PEERS.length; i++) {
            User u = new User();
            u.loginId = PEERS[i][0]; u.passwordHash = pw; u.name = PEERS[i][1]; u.phone = "010-1111-01" + (10 + i); u.area = PEERS[i][2];
            u.ageGroup = "70대"; u.intro = "이웃과 서로 돕고 지내요"; u.role = Role.USER; u.idStatus = VerifyStatus.APPROVED;
            u.categories = new ArrayList<>(List.of("말동무·정서")); u.timeSlots = new ArrayList<>(List.of("평일 오전"));
            u.createdAt = today.minusMonths(9).atTime(9, 0);
            users.save(u);
            led(u, LedgerType.EARN, 40, 40.0, 1.0, null, "지난 봉사 적립", today.minusMonths(8).atTime(12, 0));
            nb.add(u);
        }
        for (int i = 0; i < nb.size(); i++) {
            User rec = nb.get(i);
            int k = 0;
            for (int m = 2; m >= 0; m--) {
                LocalDate base = today.minusDays(m * 30L);
                // 한 건이 1시간을 넘도록(짧은 활동이 반복되면 운영 점검 대상이 된다) 넣고, 돕는 사람은 돌아가며
                done(nb.get((i + 1 + k++ % 4) % 5), rec, base.minusDays(3), 10, 2.0, "동행", "병원 진료 동행");
                if (i < 4) done(nb.get((i + 1 + k++ % 4) % 5), rec, base.minusDays(8), 14, 1.5, "장보기·심부름", "마트 장보기");
                if (i % 2 == 1) done(nb.get((i + 1 + k++ % 4) % 5), rec, base.minusDays(13), 15, 1.5, "말동무·정서", "산책하며 이야기");
                if (i == 4 && m > 0) done(nb.get((i + 1 + k++ % 4) % 5), rec, base.minusDays(18), 10, 2.0, "가벼운 집안일", "냉장고 정리");
            }
        }
    }
}
