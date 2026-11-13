package com.timesavings.service;

import com.timesavings.ai.RuleEngine;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * 계약서 7.21 (2026-11-13 대표 결정): 책임 등급(×1.5)을 없애고 기본 1.0·수고 1.2만 쓴다.
 * 서버가 뜰 때 한 번, 아직 끝나지 않은 것만 새 규칙으로 옮긴다(여러 번 돌려도 결과가 같다).
 * - 열려 있는 공고(OPEN·MATCHED)와 끝나지 않은 요청(보냄~일지 제출) 가운데 책임 등급인 것 → 새 규칙으로 다시 정한 등급(기본 또는 수고)
 * - 받는 사람 통장에 묶인 보류가 새 예상 시간보다 많으면 그 차이만 먼저 풀어 준다
 * - 끝난 요청(DONE 등)·닫힌 공고·통장 원장에 적힌 1.5 는 건드리지 않는다
 */
@Component
@Order(0) // 시연 자료(DataSeeder 1)보다 먼저: 이미 있는 DB 를 옮기는 일이다
public class WeightRuleMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WeightRuleMigration.class);
    private final PostRepo posts; private final RequestRepo requests; private final BankService bank;
    private final NotificationService noti; private final TransactionTemplate tx;

    public WeightRuleMigration(PostRepo posts, RequestRepo requests, BankService bank, NotificationService noti, TransactionTemplate tx) {
        this.posts = posts; this.requests = requests; this.bank = bank; this.noti = noti; this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        int[] n = tx.execute(s -> apply());
        if (n != null && n[0] + n[1] > 0) log.info("가중치 전환(7.21): 열린 공고 {}건, 끝나지 않은 요청 {}건을 새 규칙으로 옮겼어요", n[0], n[1]);
    }

    /** @return [옮긴 공고 수, 옮긴 요청 수] */
    public int[] apply() {
        int np = 0, nr = 0;
        for (Post p : posts.findByDifficulty(Level.RESPONSIBLE)) {
            if (p.status != PostStatus.OPEN && p.status != PostStatus.MATCHED) continue;
            String text = String.join(" ", Objects.toString(p.title, ""), Objects.toString(p.detail, ""), Objects.toString(p.note, ""));
            List<String> cats = p.categories.stream().filter(Rules::isCategory).toList();
            RuleEngine.DifficultyResult d = RuleEngine.difficulty(text, cats);
            p.difficulty = d.level();
            p.weight = Rules.weight(d.level());
            p.difficultyReason = d.reason();
            np++;
        }
        for (Request r : requests.findByDifficulty(Level.RESPONSIBLE)) {
            if (r.status == null || r.status.terminal()) continue;
            String text = Objects.toString(r.content, "") + " " + Objects.toString(r.memo, "");
            Level lv = RuleEngine.difficulty(text, Rules.isCategory(r.category) ? List.of(r.category) : List.of()).level();
            double oldEst = r.estEarn;
            r.difficulty = lv;
            r.weight = Rules.weight(lv);
            r.estEarn = Rules.earn(r.hours, r.weight);
            if (r.recipientId != null) {
                double held = 0;
                for (Ledger l : bank.entries(r.recipientId)) {
                    if (!r.id.equals(l.requestId)) continue;
                    if (l.type == LedgerType.HOLD) held += l.amount; else if (l.type == LedgerType.RELEASE) held -= l.amount;
                }
                double over = Rules.round05(held - r.estEarn);
                if (over > 0) bank.add(r.recipientId, LedgerType.RELEASE, over, r.hours, r.weight, r.id, r.category + " 보류 일부 해제(가중치 변경)");
            }
            String body = r.category + " · 책임 1.5 → " + Rules.levelKo(lv) + " " + r.weight + " · 예상 " + Rules.fmtHours(oldEst) + "시간 → " + Rules.fmtHours(r.estEarn) + "시간";
            noti.toVolunteer(r, "RULE_CHANGE", "가중치 규칙이 바뀌어 예상 적립 시간이 달라졌어요", body);
            if (r.recipientId != null) noti.send(r.recipientId, "RULE_CHANGE", "가중치 규칙이 바뀌어 차감될 시간이 달라졌어요", body, "/requests/" + r.id);
            nr++;
        }
        return new int[]{np, nr};
    }
}
