package com.timesavings.service;

import com.timesavings.config.ApiException;
import com.timesavings.domain.Enums.LedgerType;
import com.timesavings.domain.Ledger;
import com.timesavings.repo.LedgerRepo;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * 시간 통장(계약서 3.3).
 * balance = 보류(HOLD/RELEASE)를 뺀 모든 거래의 합, hold = 아직 풀리지 않은 보류 합, available = balance - hold.
 */
@Service
public class BankService {
    public static final double SELF_USE_MIN = 10.0;
    private final LedgerRepo repo;

    public BankService(LedgerRepo repo) { this.repo = repo; }

    /** 41판 A-06: 시간 통장 번호. 가입할 때 정해지는 회원 번호로 만든 고유 번호(TS-000123). */
    public static String accountNo(Long userId) { return userId == null ? null : String.format("TS-%06d", userId); }

    public record Summary(double balance, double hold, double available, double earnedSelf, double giftReceived,
                          boolean canUseSelf, double usable) {}

    public Summary summary(Long userId) {
        List<Ledger> list = repo.findByUserIdOrderByCreatedAtDescIdDesc(userId);
        double bal = 0, hold = 0, earned = 0, giftIn = 0, spent = 0;
        for (Ledger l : list) {
            switch (l.type) {
                case HOLD -> hold += l.amount;
                case RELEASE -> hold -= l.amount;
                default -> bal += l.amount;
            }
            if (l.type == LedgerType.EARN) earned += l.amount;
            if (l.type == LedgerType.ADJUST && l.requestId != null) earned += l.amount; // 이의 신청 차액도 본인 적립
            if (l.type == LedgerType.GIFT_IN) giftIn += l.amount;
            if (l.type == LedgerType.USE) spent += -l.amount;
        }
        hold = Math.max(0, r2(hold));
        bal = r2(bal);
        double avail = r2(bal - hold);
        boolean can = earned >= SELF_USE_MIN;
        // B-33: 10시간 미만이면 선물받은 시간 잔액만 쓸 수 있다(선물받은 시간부터 쓴 것으로 본다)
        double giftLeft = Math.max(0, giftIn - spent - hold);
        double usable = can ? avail : Math.max(0, Math.min(avail, giftLeft));
        return new Summary(bal, hold, avail, r2(earned), r2(giftIn), can, r2(usable));
    }

    static double r2(double v) { return Math.round(v * 100) / 100.0; }

    /** U1 시간 통장 맨 위: [전체 적립(+거래 합), 전체 사용(−거래 합의 절댓값)]. 보류(HOLD/RELEASE)는 빼고, 적립 − 사용 = balance. */
    public double[] totals(Long userId) {
        double in = 0, out = 0;
        for (Ledger l : repo.findByUserIdOrderByCreatedAtDescIdDesc(userId)) {
            if (l.type == LedgerType.HOLD || l.type == LedgerType.RELEASE) continue;
            if (l.amount > 0) in += l.amount; else out += -l.amount;
        }
        return new double[]{r2(in), r2(out)};
    }

    /** 받는 쪽이 이 만큼 쓸 수 있는지 검사(B-30, B-33). */
    public void requireUsable(Long userId, double amount) {
        Summary s = summary(userId);
        if (s.usable() + 1e-9 >= amount) return;
        if (!s.canUseSelf() && s.available() >= amount)
            throw ApiException.bad("SELF_USE_LOCKED", "직접 적립한 시간이 10시간 이상이어야 내 시간을 쓸 수 있어요 (지금 " + s.earnedSelf() + "시간). 선물받은 시간은 바로 쓸 수 있어요");
        throw ApiException.bad("INSUFFICIENT_BALANCE", "시간이 부족해요. 쓸 수 있는 시간 " + s.usable() + "시간, 필요한 시간 " + amount + "시간");
    }

    public Ledger add(Long userId, LedgerType type, double amount, Double baseHours, Double weight, Long requestId, String label) {
        Ledger l = new Ledger();
        l.userId = userId;
        l.type = type;
        l.amount = amount;
        l.baseHours = baseHours;
        l.weight = weight;
        l.requestId = requestId;
        l.label = label;
        l.createdAt = LocalDateTime.now();
        return repo.save(l);
    }

    public List<Map<String, Object>> monthly(Long userId, int months) {
        List<Ledger> list = repo.findByUserIdOrderByCreatedAtDescIdDesc(userId);
        YearMonth now = YearMonth.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) {
            YearMonth ym = now.minusMonths(i);
            double in = 0, outv = 0;
            for (Ledger l : list) {
                if (l.type == LedgerType.HOLD || l.type == LedgerType.RELEASE) continue;
                if (!YearMonth.from(l.createdAt).equals(ym)) continue;
                if (l.amount > 0) in += l.amount; else outv += -l.amount;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", ym.toString());
            m.put("in", r2(in));
            m.put("out", r2(outv));
            out.add(m);
        }
        return out;
    }

    /** 기능 U-22: 거래가 있는 모든 달의 합계(최근 달부터). [{month, in(번 시간), out(쓴 시간), count}] 보류·보류 해제는 뺀다. */
    public List<Map<String, Object>> monthsAll(Long userId) {
        Map<String, double[]> by = new LinkedHashMap<>();
        for (Ledger l : repo.findByUserIdOrderByCreatedAtDescIdDesc(userId)) {
            if (l.type == LedgerType.HOLD || l.type == LedgerType.RELEASE) continue;
            double[] v = by.computeIfAbsent(YearMonth.from(l.createdAt).toString(), k -> new double[3]);
            if (l.amount > 0) v[0] += l.amount; else v[1] += -l.amount;
            v[2]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        by.forEach((m, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", m); row.put("in", r2(v[0])); row.put("out", r2(v[1])); row.put("count", (int) v[2]);
            out.add(row);
        });
        return out;
    }

    public List<Ledger> entries(Long userId) { return repo.findByUserIdOrderByCreatedAtDescIdDesc(userId); }
}
