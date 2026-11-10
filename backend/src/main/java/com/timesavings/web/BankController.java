package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.*;

/** 시간 통장(A-06, B-30~B-34, B-46), 마이페이지(B-44, B-45, P-04, P-06), 봉사 확인서. */
@RestController
@RequestMapping("/api")
@Transactional
public class BankController {
    private final BankService bank;
    private final UserRepo users;
    private final FamilyLinkRepo family;
    private final RequestRepo requests;
    private final PostRepo posts;
    private final LifeReportRepo reports;
    private final LifeReportService reportSvc;
    private final NotificationService noti;
    private final Views views;

    public BankController(BankService bank, UserRepo users, FamilyLinkRepo family, RequestRepo requests, PostRepo posts,
                          LifeReportRepo reports, LifeReportService reportSvc, NotificationService noti, Views views) {
        this.bank = bank; this.users = users; this.family = family; this.requests = requests; this.posts = posts;
        this.reports = reports; this.reportSvc = reportSvc; this.noti = noti; this.views = views;
    }

    public record GiftBody(Long toUserId, Double amount, String message) {}
    public record FamilyBody(String loginIdOrPhone, String relation) {}
    public record AnswerBody(Boolean accept) {}
    public record ShareBody(Boolean share) {}

    @GetMapping("/bank")
    public Map<String, Object> bank(@CurrentUser User u) {
        BankService.Summary s = bank.summary(u.id);
        List<Map<String, Object>> monthly = bank.monthly(u.id, 6);
        Map<String, Object> cur = monthly.get(monthly.size() - 1);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Ledger l : bank.entries(u.id))
            entries.add(Views.map("id", l.id, "type", l.type, "amount", l.amount, "baseHours", l.baseHours, "weight", l.weight,
                    "label", l.label, "requestId", l.requestId, "createdAt", l.createdAt));
        double[] tot = bank.totals(u.id);
        return Views.map("totalEarned", tot[0], "totalUsed", tot[1],
                "balance", s.balance(), "hold", s.hold(), "available", s.available(), "usable", s.usable(),
                "earnedSelf", s.earnedSelf(), "giftReceived", s.giftReceived(), "canUseSelf", s.canUseSelf(),
                "selfUseMin", BankService.SELF_USE_MIN, "monthIn", cur.get("in"), "monthOut", cur.get("out"),
                "monthly", monthly, "entries", entries);
    }

    private FamilyLink acceptedLink(Long a, Long b) {
        return family.findAll().stream().filter(f -> f.status == LinkStatus.ACCEPTED
                && ((f.requesterId.equals(a) && f.targetId.equals(b)) || (f.requesterId.equals(b) && f.targetId.equals(a)))).findFirst().orElse(null);
    }

    @PostMapping("/bank/gift")
    public Map<String, Object> gift(@CurrentUser User u, @RequestBody GiftBody b) {
        if (b.toUserId() == null || b.amount() == null) throw ApiException.bad("INVALID_INPUT", "받을 가족과 시간을 정해 주세요");
        double amt = b.amount();
        if (amt < 0.5 || !Rules.halfStep(amt)) throw ApiException.bad("INVALID_AMOUNT", "0.5시간 단위로 선물할 수 있어요");
        if (acceptedLink(u.id, b.toUserId()) == null) throw ApiException.forbidden("연결된 가족에게만 선물할 수 있어요 (B-32)");
        User to = users.findById(b.toUserId()).orElseThrow(() -> ApiException.notFound("받는 사람을 찾을 수 없어요"));
        if (bank.summary(u.id).available() < amt) throw ApiException.bad("INSUFFICIENT_BALANCE", "선물할 시간이 부족해요");
        String msg = b.message() == null || b.message().isBlank() ? "" : " · " + b.message();
        bank.add(u.id, LedgerType.GIFT_OUT, -amt, null, null, null, to.name + " 님에게 선물" + msg);
        bank.add(to.id, LedgerType.GIFT_IN, amt, null, null, null, u.name + " 님이 보낸 선물" + msg);
        noti.send(to.id, "GIFT", u.name + " 님이 " + amt + "시간을 선물했어요", b.message(), "/bank");
        return Views.map("ok", true, "balance", bank.summary(u.id).balance());
    }

    // ---------------- 내 활동 (B-45) ----------------
    @GetMapping("/my/activities")
    public List<Map<String, Object>> activities(@CurrentUser User u, @RequestParam(required = false) String seg) {
        String s = seg == null ? "all" : seg;
        List<Map<String, Object>> out = new ArrayList<>();
        if (s.equals("posts")) {
            posts.findByOwnerId(u.id).stream().filter(p -> p.status != PostStatus.DELETED)
                    .sorted(Comparator.comparing((Post p) -> p.date).reversed())
                    .forEach(p -> { Map<String, Object> m = views.postCard(p, u); m.put("itemType", "post"); out.add(m); });
            return out;
        }
        requests.findAll().stream().sorted(Comparator.comparing((Request r) -> r.date).reversed()).forEach(r -> {
            boolean help = u.id.equals(r.volunteerId);
            boolean helped = u.id.equals(r.recipientId);
            if ((s.equals("help") && help) || (s.equals("helped") && helped) || (s.equals("all") && (help || helped || u.id.equals(r.senderId)))) {
                Map<String, Object> m = views.requestView(r, u);
                m.put("itemType", "request");
                out.add(m);
            }
        });
        return out;
    }

    @GetMapping("/certificate")
    public Map<String, Object> certificate(@CurrentUser User u) {
        List<Request> done = requests.findAll().stream().filter(r -> u.id.equals(r.volunteerId) && r.status == ReqStatus.DONE)
                .sorted(Comparator.comparing((Request r) -> r.date)).toList();
        Map<String, double[]> byCat = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        double total = 0, earned = 0;
        for (Request r : done) {
            double h = "ACCEPTED".equals(r.adjustStatus) && r.adjustHours != null ? r.adjustHours : r.hours;
            total += h;
            earned += r.earned == null ? 0 : r.earned;
            byCat.computeIfAbsent(r.category, k -> new double[2]);
            byCat.get(r.category)[0]++;
            byCat.get(r.category)[1] += h;
            items.add(Views.map("date", r.date.toString(), "category", r.category, "hours", h, "earned", r.earned,
                    "recipient", noti.recipientLabel(r, false)));
        }
        List<Map<String, Object>> cats = new ArrayList<>();
        byCat.forEach((k, v) -> cats.add(Views.map("category", k, "count", (int) v[0], "hours", v[1])));
        String period = done.isEmpty() ? null : done.get(0).date + " ~ " + done.get(done.size() - 1).date;
        return Views.map("name", u.name, "period", period, "totalHours", total, "totalEarned", earned, "count", done.size(),
                "byCategory", cats, "items", items, "eduDone", u.eduDone, "issuedAt", java.time.LocalDate.now().toString());
    }

    // ---------------- 가족 연결 (B-44, P-06) ----------------
    @GetMapping("/family")
    public List<Map<String, Object>> family(@CurrentUser User u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FamilyLink f : family.findAll()) {
            boolean out_ = f.requesterId.equals(u.id);
            if (!out_ && !f.targetId.equals(u.id)) continue;
            if (f.status == LinkStatus.REJECTED) continue;
            User other = users.findById(out_ ? f.targetId : f.requesterId).orElse(null);
            out.add(Views.map("id", f.id, "user", views.userBrief(other), "relation", f.relation, "status", f.status,
                    "direction", out_ ? "out" : "in", "shareReport", out_ ? f.shareReport : f.targetShareReport,
                    "theyShare", out_ ? f.targetShareReport : f.shareReport, "createdAt", f.createdAt));
        }
        return out;
    }

    @PostMapping("/family")
    public Map<String, Object> addFamily(@CurrentUser User u, @RequestBody FamilyBody b) {
        String key = b.loginIdOrPhone() == null ? "" : b.loginIdOrPhone().trim();
        User t = users.findByLoginId(key).or(() -> users.findFirstByPhone(AuthService.normPhone(key)))
                .orElseThrow(() -> ApiException.notFound("그 아이디나 번호로 가입한 사용자를 찾지 못했어요"));
        if (t.id.equals(u.id)) throw ApiException.bad("SELF", "나 자신과는 연결할 수 없어요");
        if (t.role != Role.USER) throw ApiException.bad("NOT_USER", "사용자 계정과만 연결할 수 있어요");
        boolean exists = family.findAll().stream().anyMatch(f -> f.status != LinkStatus.REJECTED
                && ((f.requesterId.equals(u.id) && f.targetId.equals(t.id)) || (f.requesterId.equals(t.id) && f.targetId.equals(u.id))));
        if (exists) throw ApiException.conflict("DUPLICATE", "이미 연결했거나 신청한 사이예요");
        FamilyLink f = new FamilyLink();
        f.requesterId = u.id;
        f.targetId = t.id;
        f.relation = b.relation();
        family.save(f);
        noti.send(t.id, "FAMILY", u.name + " 님이 가족 연결을 신청했어요", "관계: " + Objects.toString(b.relation(), "가족"), "/my/family");
        return Views.map("id", f.id, "status", f.status, "user", views.userBrief(t));
    }

    private FamilyLink link(Long id, User u) {
        FamilyLink f = family.findById(id).orElseThrow(() -> ApiException.notFound("가족 연결을 찾을 수 없어요"));
        if (!f.requesterId.equals(u.id) && !f.targetId.equals(u.id)) throw ApiException.forbidden("내 가족 연결만 볼 수 있어요");
        return f;
    }

    @PostMapping("/family/{id}/answer")
    public Map<String, Object> answer(@CurrentUser User u, @PathVariable Long id, @RequestBody AnswerBody b) {
        FamilyLink f = link(id, u);
        if (!f.targetId.equals(u.id) || f.status != LinkStatus.PENDING) throw ApiException.conflict("INVALID_STATE", "응답할 수 있는 신청이 아니에요");
        f.status = Boolean.TRUE.equals(b.accept()) ? LinkStatus.ACCEPTED : LinkStatus.REJECTED;
        noti.send(f.requesterId, "FAMILY", u.name + " 님이 가족 연결을 " + (f.status == LinkStatus.ACCEPTED ? "수락" : "거절") + "했어요", null, "/my/family");
        return Views.map("id", f.id, "status", f.status);
    }

    @PutMapping("/family/{id}/share")
    public Map<String, Object> share(@CurrentUser User u, @PathVariable Long id, @RequestBody ShareBody b) {
        FamilyLink f = link(id, u);
        boolean v = Boolean.TRUE.equals(b.share());
        if (f.requesterId.equals(u.id)) f.shareReport = v; else f.targetShareReport = v;
        return Views.map("id", f.id, "shareReport", v);
    }

    @DeleteMapping("/family/{id}")
    public Map<String, Object> delete(@CurrentUser User u, @PathVariable Long id) {
        family.delete(link(id, u));
        return Views.map("ok", true);
    }

    /** P-04: 가족으로 연결되고 상대가 공유에 동의한 경우만. 본인 것은 조회하지 않는다. */
    @GetMapping("/family/{userId}/report")
    public Map<String, Object> familyReport(@CurrentUser User u, @PathVariable Long userId) {
        if (userId.equals(u.id)) throw ApiException.forbidden("본인 리포트는 가족만 볼 수 있어요");
        FamilyLink f = acceptedLink(u.id, userId);
        if (f == null) throw ApiException.forbidden("연결된 가족이 아니에요");
        if (!u.id.equals(LifeReportService.shareTarget(f, userId))) throw ApiException.forbidden("상대가 리포트 공유에 동의하지 않았어요");
        User s = users.findById(userId).orElseThrow();
        List<LifeReport> list = reports.findBySubjectTypeAndSubjectIdOrderByMonthDesc(SubjectType.USER, userId);
        return Views.map("user", views.userBrief(s), "reports", list.stream().map(r -> reportSvc.view(r, s.name + " 님")).toList(),
                "latestMonth", list.isEmpty() ? YearMonth.now().minusMonths(1).toString() : list.get(0).month);
    }
}
