package com.timesavings.web;

import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/** 운영자(D-01~D-09, P-02, P-07). /api/admin/** 는 ADMIN 역할만(인터셉터). */
@RestController
@RequestMapping("/api/admin")
@Transactional
public class AdminController {
    private final UserRepo users;
    private final OrganizationRepo orgs;
    private final ElderRepo elders;
    private final ElderTransferRepo transfers;
    private final RequestRepo requests;
    private final PostRepo posts;
    private final LedgerRepo ledger;
    private final MessageRepo messages;
    private final DifficultyAppealRepo appeals;
    private final ReportRepo reports;
    private final InquiryRepo inquiries;
    private final NoticeRepo notices;
    private final PublicServiceRepo services;
    private final RoleplayPersonaRepo personas;
    private final EduProgressRepo edu;
    private final AuthService auth;
    private final BankService bank;
    private final NotificationService noti;
    private final LifeReportService lifeReports;
    private final SchedulerService scheduler;
    private final EduService eduSvc;
    private final Views views;
    @org.springframework.beans.factory.annotation.Autowired private RequestService reqSvc;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private GroupService groups;

    public AdminController(UserRepo users, OrganizationRepo orgs, ElderRepo elders, ElderTransferRepo transfers, RequestRepo requests,
                           PostRepo posts, LedgerRepo ledger, MessageRepo messages, DifficultyAppealRepo appeals, ReportRepo reports,
                           InquiryRepo inquiries, NoticeRepo notices, PublicServiceRepo services, RoleplayPersonaRepo personas,
                           EduProgressRepo edu, AuthService auth, BankService bank, NotificationService noti,
                           LifeReportService lifeReports, SchedulerService scheduler, EduService eduSvc, Views views) {
        this.users = users; this.orgs = orgs; this.elders = elders; this.transfers = transfers; this.requests = requests;
        this.posts = posts; this.ledger = ledger; this.messages = messages; this.appeals = appeals; this.reports = reports;
        this.inquiries = inquiries; this.notices = notices; this.services = services; this.personas = personas;
        this.edu = edu; this.auth = auth; this.bank = bank; this.noti = noti;
        this.lifeReports = lifeReports; this.scheduler = scheduler; this.eduSvc = eduSvc; this.views = views;
    }

    public record TransferBody(Long toManagerId) {}
    public record AppealAnswer(Boolean accept, Level resultLevel, String note) {}
    public record HandleBody(ReportAction action, String result) {}
    public record AnswerBody(String answer) {}
    public record InquiryStatusBody(String status) {}
    static final List<String> INQUIRY_STAGES = List.of("RECEIVED", "CHECKING", "ANSWERED", "CLOSED");
    static String stageOf(Inquiry q) { return q.stage != null ? q.stage : q.status.name(); }
    public record NoticeBody(String title, String content) {}
    public record ServiceBody(String name, String target, String content, String applyHow, String contact, String region, List<String> keywords) {}
    public record PersonaBody(String name, String initial, String ageLabel, String fitCategory, String trait, String goal, String opening,
                              List<Map<String, Object>> turns, Boolean active) {}
    public record MonthBody(String month) {}

    private static double r1(double v) { return Math.round(v * 10) / 10.0; }

    // ======================= 대시보드 (D-04, D-05) =======================
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        List<Request> all = requests.findAll();
        List<Post> allPosts = posts.findAll();
        List<Ledger> led = ledger.findAll();
        YearMonth now = YearMonth.now();
        // 단체 봉사(기능 U-10): 참석이 확인된 한 사람 한 번을 활동 한 건으로 함께 센다
        List<GroupService.Attended> grp = groups == null ? List.of() : groups.attendedAll();
        List<Map<String, Object>> monthly = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth m = now.minusMonths(i);
            long groupActs = grp.stream().filter(a -> YearMonth.from(a.date()).equals(m)).count();
            monthly.add(Views.map("month", m.toString(),
                    "groupActivities", groupActs,
                    "activities", groupActs + all.stream().filter(r -> r.status == ReqStatus.DONE && YearMonth.from(r.date).equals(m)).count(),
                    "elderUsage", all.stream().filter(r -> r.status == ReqStatus.DONE && r.elderId != null && YearMonth.from(r.date).equals(m)).count(),
                    "supply", allPosts.stream().filter(p -> p.type == PostType.OFFER && YearMonth.from(p.createdAt).equals(m)).count(),
                    "demand", allPosts.stream().filter(p -> p.type == PostType.NEED && YearMonth.from(p.createdAt).equals(m)).count(),
                    "issued", r1(led.stream().filter(l -> l.type == LedgerType.EARN && YearMonth.from(l.createdAt).equals(m)).mapToDouble(l -> l.amount).sum()),
                    "consumed", r1(-led.stream().filter(l -> l.type == LedgerType.USE && YearMonth.from(l.createdAt).equals(m)).mapToDouble(l -> l.amount).sum())));
        }
        List<Map<String, Object>> weekly = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int w = 3; w >= 0; w--) {
            LocalDate from = today.minusDays(7L * w + 6), to = today.minusDays(7L * w);
            weekly.add(Views.map("label", (4 - w) + "주", "from", from.toString(), "to", to.toString(),
                    "demand", allPosts.stream().filter(p -> p.type == PostType.NEED && !p.createdAt.toLocalDate().isBefore(from) && !p.createdAt.toLocalDate().isAfter(to)).count(),
                    "supply", allPosts.stream().filter(p -> p.type == PostType.OFFER && !p.createdAt.toLocalDate().isBefore(from) && !p.createdAt.toLocalDate().isAfter(to)).count()));
        }
        // 지역별 매칭 성공률: 최근 90일 요청 중 확정까지 간 비율
        Map<String, int[]> area = new TreeMap<>();
        LocalDateTime since = LocalDateTime.now().minusDays(90);
        for (Request r : all) {
            if (r.createdAt.isBefore(since) || r.status == ReqStatus.SENT || r.status == ReqStatus.VOL_ACCEPTED) continue;
            String a = areaOf(r);
            if (a == null) continue;
            int[] c = area.computeIfAbsent(a, k -> new int[2]);
            c[1]++;
            if (r.status.active() || r.status == ReqStatus.DONE || r.status == ReqStatus.NOSHOW) c[0]++;
        }
        List<Map<String, Object>> rate = new ArrayList<>();
        area.forEach((k, v) -> rate.add(Views.map("area", k, "rate", v[1] == 0 ? 0 : Math.round(v[0] * 100.0 / v[1]), "total", v[1], "matched", v[0])));
        rate.sort((a, b) -> Long.compare((Long) b.get("rate"), (Long) a.get("rate")));
        Map<String, Object> cur = monthly.get(5), prev = monthly.get(4);
        double issued = (Double) cur.get("issued"), consumed = (Double) cur.get("consumed");
        double elderFree = r1(all.stream().filter(r -> r.status == ReqStatus.DONE && r.elderId != null && r.earned != null && r.doneAt != null
                && YearMonth.from(r.doneAt).equals(now)).mapToDouble(r -> r.earned).sum());
        int tot = rate.stream().mapToInt(m -> (Integer) m.get("total")).sum(), mat = rate.stream().mapToInt(m -> (Integer) m.get("matched")).sum();
        long volunteers = users.findAll().stream().filter(u -> u.role == Role.USER && u.idStatus == VerifyStatus.APPROVED).count();
        return Views.map(
                "supply", cur.get("supply"), "demand", cur.get("demand"), "elderUsage", cur.get("elderUsage"),
                "kpi", Views.map("activities", cur.get("activities"), "prevActivities", prev.get("activities"), "groupActivities", cur.get("groupActivities"),
                        "matchRate", tot == 0 ? 0 : Math.round(mat * 100.0 / tot), "elderUsage", cur.get("elderUsage"), "prevElderUsage", prev.get("elderUsage"),
                        // 대시보드 상단 네 칸(2026-11-13): 이번 달 활동 · 매칭률 · 신규 가입/총 가입자 · 등록 기관 수
                        "newUsers", users.findAll().stream().filter(u -> u.role == Role.USER && u.status != UserStatus.WITHDRAWN && u.createdAt != null && YearMonth.from(u.createdAt).equals(now)).count(),
                        "totalUsers", users.findAll().stream().filter(u -> u.role == Role.USER && u.status != UserStatus.WITHDRAWN).count(),
                        "orgCount", orgs.findAll().stream().filter(o -> o.status == OrgStatus.APPROVED).count()),
                "matchRateByArea", rate,
                "timeBalance", Views.map("issued", issued, "consumed", consumed, "elderFree", elderFree,
                        "useRatio", issued == 0 ? 0 : Math.round(consumed * 100 / issued)),
                "monthly", monthly, "weekly", weekly,
                "edu", Views.map("volunteers", volunteers),
                "pending", Views.map(
                        "orgs", orgs.findAll().stream().filter(o -> o.status == OrgStatus.PENDING).count(),
                        "ids", users.findAll().stream().filter(u -> u.idStatus == VerifyStatus.PENDING).count(),
                        "crimes", users.findAll().stream().filter(u -> u.crimeStatus == VerifyStatus.PENDING).count(),
                        "appeals", appeals.findAll().stream().filter(a -> a.status == AppealStatus.RECEIVED).count(),
                        // 안부 확인(이상 징후) 접수는 제재 없는 기록이라 신고 대기와 따로 센다
                        "reports", reports.findAll().stream().filter(r -> r.status != ReportStatus.DONE && !r.welfare()).count(),
                        "welfare", reports.findAll().stream().filter(r -> r.status != ReportStatus.DONE && r.welfare()).count(),
                        "inquiries", inquiries.findAll().stream().filter(q -> List.of("RECEIVED", "CHECKING").contains(stageOf(q))).count(),
                        "anomalies", anomalyGroups().size()));
    }

    private String areaOf(Request r) {
        if (r.postId != null) { Post p = posts.findById(r.postId).orElse(null); if (p != null) return p.area; }
        if (r.elderId != null) return elders.findById(r.elderId).map(e -> e.dong).orElse(null);
        return r.recipientId == null ? null : users.findById(r.recipientId).map(u -> u.area).orElse(null);
    }

    // ======================= 기관·담당자 (D-01, C-01, C-03) =======================
    @GetMapping("/orgs")
    public List<Organization> orgs() { return orgs.findAll().stream().sorted(Comparator.comparing((Organization o) -> o.status).thenComparing(o -> -o.id)).toList(); }

    @PostMapping("/orgs/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id) {
        Organization o = orgs.findById(id).orElseThrow(() -> ApiException.notFound("기관 신청을 찾을 수 없어요"));
        if (o.status != OrgStatus.PENDING) throw ApiException.conflict("INVALID_STATE", "대기 중인 신청이 아니에요");
        o.status = OrgStatus.APPROVED;
        String loginId;
        do { loginId = "mgr" + auth.sixDigits().substring(0, 4); } while (users.findByLoginId(loginId).isPresent());
        String temp = auth.randomHex(4);
        User m = new User();
        m.loginId = loginId; m.passwordHash = auth.hash(temp); m.name = o.managerName == null ? o.name + " 담당자" : o.managerName;
        m.phone = AuthService.normPhone(o.managerPhone); m.role = Role.ORG; m.orgId = o.id; m.area = null;
        users.save(m);
        return Views.map("orgId", o.id, "status", o.status, "managerId", m.id, "managerLoginId", loginId, "tempPassword", temp,
                "message", "임시 비밀번호는 이 화면에서만 보여요. 담당자에게 전달하고 첫 로그인 뒤 바꾸게 안내해 주세요");
    }

    @PostMapping("/orgs/{id}/reject")
    public Organization reject(@PathVariable Long id) {
        Organization o = orgs.findById(id).orElseThrow(() -> ApiException.notFound("기관 신청을 찾을 수 없어요"));
        o.status = OrgStatus.REJECTED;
        return o;
    }

    @GetMapping("/managers")
    public List<Map<String, Object>> managers() {
        return users.findByRole(Role.ORG).stream().map(m -> Views.map("id", m.id, "loginId", m.loginId, "name", m.name, "status", m.status,
                "banned", Boolean.TRUE.equals(m.banned), "orgId", m.orgId, "orgName", m.orgId == null ? null : orgs.findById(m.orgId).map(o -> o.name).orElse(null),
                "elders", elders.findByManagerId(m.id).stream().map(e -> Views.map("id", e.id, "alias", e.alias, "status", e.status)).toList())).toList();
    }

    private User userOf(Long id) { return users.findById(id).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요")); }

    @PostMapping("/managers/{id}/suspend")
    public Map<String, Object> suspendManager(@PathVariable Long id) { return setStatus(id, UserStatus.SUSPENDED); }

    @PostMapping("/managers/{id}/unsuspend")
    public Map<String, Object> unsuspendManager(@PathVariable Long id) { return setStatus(id, UserStatus.ACTIVE); }

    /**
     * D-02 퇴출: 정지와 달리 되돌릴 수 없다. 다시 로그인할 수 없고(로그인 토큰 폐기), 진행 중인 요청은 취소·보류 해제, 열린 공고는 마감한다.
     * 담당자 계정은 담당 대상·요청을 그대로 두므로(운영자가 이관) 계정만 막는다.
     */
    private void ban(User u) {
        u.status = UserStatus.SUSPENDED;
        u.banned = true;
        auth.revokeAll(u.id);
        if (u.role == Role.USER) reqSvc.closeAllFor(u, "운영 정책 위반으로 상대 계정의 이용이 끝났어요");
    }

    private Map<String, Object> setStatus(Long id, UserStatus s) {
        User u = userOf(id);
        if (u.role == Role.ADMIN) throw ApiException.forbidden("운영자 계정은 정지할 수 없어요");
        if (Boolean.TRUE.equals(u.banned)) throw ApiException.conflict("BANNED", "퇴출된 계정은 정지를 풀 수 없어요");
        u.status = s;
        if (s != UserStatus.ACTIVE) auth.revokeAll(u.id);
        return Views.map("id", u.id, "status", u.status);
    }

    @PostMapping("/elders/{id}/transfer")
    public Map<String, Object> transferElder(@PathVariable Long id, @RequestBody TransferBody b) {
        Elder e = elders.findById(id).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
        User to = userOf(b.toManagerId() == null ? -1 : b.toManagerId());
        if (to.role != Role.ORG || to.status != UserStatus.ACTIVE) throw ApiException.bad("INVALID_MANAGER", "활동 중인 담당자에게만 이관할 수 있어요");
        ElderTransfer t = new ElderTransfer();
        t.elderId = e.id; t.fromManagerId = e.managerId; t.toManagerId = to.id; t.status = TransferStatus.ADMIN;
        transfers.save(t);
        transfers.findAll().stream().filter(x -> x.elderId.equals(id) && x.status == TransferStatus.PENDING).forEach(x -> x.status = TransferStatus.REJECTED);
        e.managerId = to.id;
        e.orgId = to.orgId;
        noti.send(to.id, "TRANSFER", "운영자가 도움 대상을 이관했어요", e.alias + " 님의 담당이 되었어요", "/org/elders/" + e.id);
        return Views.map("elderId", e.id, "managerId", to.id, "status", "ADMIN");
    }

    // ======================= 신원 승인 (D-03, B-01) =======================
    @GetMapping("/verifications")
    public List<Map<String, Object>> verifications() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users.findAll()) {
            if (u.idStatus == VerifyStatus.PENDING) out.add(Views.map("userId", u.id, "name", u.name, "kind", "id", "fileUrl", u.idFileUrl, "ageGroup", u.ageGroup, "createdAt", u.createdAt));
            if (u.crimeStatus == VerifyStatus.PENDING) out.add(Views.map("userId", u.id, "name", u.name, "kind", "crime", "fileUrl", u.crimeFileUrl, "ageGroup", u.ageGroup, "createdAt", u.createdAt));
        }
        return out;
    }

    @PostMapping("/verifications/{userId}/{kind}/{decision}")
    public Map<String, Object> verify(@PathVariable Long userId, @PathVariable String kind, @PathVariable String decision) {
        User u = userOf(userId);
        boolean ok = switch (decision) { case "approve" -> true; case "reject" -> false; default -> throw ApiException.bad("INVALID", "approve 또는 reject"); };
        VerifyStatus s = ok ? VerifyStatus.APPROVED : VerifyStatus.REJECTED;
        if ("id".equals(kind)) u.idStatus = s; else if ("crime".equals(kind)) u.crimeStatus = s; else throw ApiException.bad("INVALID", "id 또는 crime");
        noti.send(u.id, "VERIFY", ("id".equals(kind) ? "신분증 인증" : "범죄경력 회보서 확인") + "이 " + (ok ? "승인" : "반려") + "됐어요",
                ok ? ("crime".equals(kind) ? "프로필에 신뢰 배지가 붙었어요" : "이제 봉사 활동을 할 수 있어요") : "서류를 다시 올려 주세요", "/my");
        return Views.map("userId", u.id, "idStatus", u.idStatus, "crimeStatus", u.crimeStatus);
    }

    // ======================= 난이도 이의 (B-28) =======================
    @GetMapping("/appeals")
    public List<Map<String, Object>> appeals() {
        return appeals.findAll().stream().sorted(Comparator.comparing((DifficultyAppeal a) -> a.status).thenComparing(a -> -a.id)).map(a -> {
            Request r = requests.findById(a.requestId).orElse(null);
            return Views.map("id", a.id, "requestId", a.requestId, "user", views.userBrief(users.findById(a.userId).orElse(null)),
                    "reason", a.reason, "requestedLevel", a.requestedLevel, "status", a.status, "resultLevel", a.resultLevel, "adminNote", a.adminNote,
                    "currentLevel", r == null ? null : r.difficulty, "category", r == null ? null : r.category, "hours", r == null ? null : r.hours,
                    "earned", r == null ? null : r.earned, "content", r == null ? null : r.content,
                    "recipient", r == null ? null : noti.recipientLabel(r, false), "createdAt", a.createdAt);
        }).toList();
    }

    @PostMapping("/appeals/{id}/answer")
    public Map<String, Object> answerAppeal(@PathVariable Long id, @RequestBody AppealAnswer b) {
        DifficultyAppeal a = appeals.findById(id).orElseThrow(() -> ApiException.notFound("이의 신청을 찾을 수 없어요"));
        if (a.status != AppealStatus.RECEIVED) throw ApiException.conflict("INVALID_STATE", "이미 처리했어요");
        Request r = requests.findById(a.requestId).orElseThrow();
        a.adminNote = b.note();
        double diff = 0;
        if (Boolean.TRUE.equals(b.accept())) {
            Level asked = b.resultLevel() != null ? b.resultLevel() : a.requestedLevel;
            Level lv = Rules.current(asked); // 7.21: 책임 등급(1.5)으로는 더 올리지 않는다. 예전 신청은 수고까지만
            if (asked == Level.RESPONSIBLE && lv.ordinal() <= r.difficulty.ordinal())
                throw ApiException.bad("INVALID_LEVEL", "책임 등급(1.5)은 2026-11-13부터 쓰지 않아요. 지금 등급보다 올릴 수 없으니 반려로 답해 주세요");
            double hours = "ACCEPTED".equals(r.adjustStatus) && r.adjustHours != null ? r.adjustHours : r.hours;
            double newEarn = Rules.earn(hours, lv);
            diff = Rules.round05(newEarn - (r.earned == null ? 0 : r.earned));
            if (diff != 0) bank.add(r.volunteerId, LedgerType.ADJUST, diff, hours, Rules.weight(lv), r.id,
                    "난이도 조정 " + Rules.levelKo(r.difficulty) + " → " + Rules.levelKo(lv));
            r.difficulty = lv;
            r.weight = Rules.weight(lv);
            r.earned = newEarn;
            a.status = AppealStatus.ACCEPTED;
            a.resultLevel = lv;
        } else {
            a.status = AppealStatus.REJECTED;
        }
        noti.send(a.userId, "APPEAL", "난이도 이의 신청이 " + (a.status == AppealStatus.ACCEPTED ? "받아들여졌어요" : "반려됐어요"),
                a.status == AppealStatus.ACCEPTED ? "+" + diff + "시간이 조정 적립됐어요" : Objects.toString(b.note(), ""), "/bank");
        return Views.map("id", a.id, "status", a.status, "resultLevel", a.resultLevel, "adjust", diff);
    }

    // ======================= 신고·제재 (D-02) =======================
    private Long targetUser(Report r) {
        if (r.targetId == null) return null;
        return switch (r.targetType) {
            case USER -> r.targetId;
            case MESSAGE -> messages.findById(r.targetId).map(m -> m.senderId).orElse(null);
            case POST -> posts.findById(r.targetId).map(p -> p.ownerId).orElse(null);
            case REQUEST -> requests.findById(r.targetId).map(q -> r.reporterId.equals(q.volunteerId) ? q.recipientId : q.volunteerId).orElse(null);
        };
    }

    @GetMapping("/reports")
    public List<Map<String, Object>> reports() {
        return reports.findAll().stream().sorted(Comparator.comparing((Report r) -> r.status == ReportStatus.DONE).thenComparing(r -> -r.id)).map(r -> {
            Long t = targetUser(r);
            Map<String, Object> m = Views.map("id", r.id, "reporter", views.userBrief(users.findById(r.reporterId).orElse(null)),
                    "targetType", r.targetType, "targetId", r.targetId, "targetLabel", r.targetLabel,
                    "targetUser", t == null ? null : views.userBrief(users.findById(t).orElse(null)),
                    "type", r.type, "content", r.content, "status", r.status, "action", r.action, "result", r.result,
                    "createdAt", r.createdAt, "handledAt", r.handledAt);
            if (r.targetType == ReportTarget.MESSAGE && r.targetId != null) messages.findById(r.targetId).ifPresent(x -> m.put("messageText", x.text));
            // D-02: 독거노인 이상 징후(안부 확인)는 제재 없는 접수 기록으로 구분해 보여 준다
            m.put("kind", r.kind);
            m.put("welfare", r.welfare());
            if (r.welfare()) {
                m.put("targetUser", null); // 제재 대상이 없다
                if (r.elderId != null) elders.findById(r.elderId).ifPresent(e -> {
                    m.put("elder", Views.map("id", e.id, "displayName", Rules.maskAlias(e.alias), "dong", e.dong));
                    m.put("manager", e.managerId == null ? null : views.userBrief(users.findById(e.managerId).orElse(null)));
                });
            }
            return m;
        }).toList();
    }

    @PostMapping("/reports/{id}/handle")
    public Map<String, Object> handle(@PathVariable Long id, @RequestBody HandleBody b) {
        Report r = reports.findById(id).orElseThrow(() -> ApiException.notFound("신고를 찾을 수 없어요"));
        if (b.action() == null) {
            r.status = ReportStatus.REVIEWING;
            return Views.map("id", r.id, "status", r.status);
        }
        if (r.welfare()) {
            // D-02: 이상 징후 접수는 제재하지 않는다. 확인했다는 기록만 남기고 접수한 사람에게 알린다.
            if (b.action() != ReportAction.NONE) throw ApiException.bad("NO_SANCTION", "이상 징후(안부 확인) 접수는 제재 없이 확인만 해요");
            r.action = ReportAction.NONE;
            r.result = b.result() == null || b.result().isBlank() ? "접수 기록 확인" : b.result();
            r.status = ReportStatus.DONE;
            r.handledAt = LocalDateTime.now();
            noti.send(r.reporterId, "REPORT_RESULT", "안부 확인 접수를 운영자가 확인했어요", r.result, "/my/reports");
            return Views.map("id", r.id, "status", r.status, "action", r.action, "result", r.result);
        }
        r.action = b.action();
        r.result = b.result() == null || b.result().isBlank() ? switch (b.action()) {
            case NONE -> "조치 없음"; case WARNING -> "경고 1회"; case SUSPEND -> "이용 정지"; case BAN -> "퇴출";
        } : b.result();
        r.status = ReportStatus.DONE;
        r.handledAt = LocalDateTime.now();
        Long t = targetUser(r);
        if (t != null) {
            User u = userOf(t);
            switch (b.action()) {
                case WARNING -> { u.warningCount++; noti.send(u.id, "WARNING", "운영 정책 위반으로 경고를 받았어요", r.type + " · 반복되면 이용이 제한돼요", "/my"); }
                case SUSPEND -> { if (u.role != Role.ADMIN) { u.status = UserStatus.SUSPENDED; auth.revokeAll(u.id); } }
                case BAN -> { if (u.role != Role.ADMIN) ban(u); }
                default -> { }
            }
        }
        noti.send(r.reporterId, "REPORT_RESULT", "신고 처리 결과가 나왔어요", r.type + " · " + r.result, "/my/reports");
        return Views.map("id", r.id, "status", r.status, "action", r.action, "result", r.result);
    }

    // ======================= 이상 적립 점검 (D-06) =======================
    private List<Map<String, Object>> anomalyGroups() {
        LocalDate since = LocalDate.now().minusDays(30);
        Map<String, List<Request>> g = requests.findAll().stream()
                .filter(r -> r.status == ReqStatus.DONE && r.hours <= 1.0 && !r.date.isBefore(since))
                .collect(Collectors.groupingBy(r -> r.volunteerId + ":" + (r.elderId != null ? "E" + r.elderId : "U" + r.recipientId)));
        List<Map<String, Object>> out = new ArrayList<>();
        g.forEach((k, list) -> {
            if (list.size() < 3) return;
            Request f = list.get(0);
            out.add(Views.map("volunteer", views.userBrief(users.findById(f.volunteerId).orElse(null)),
                    "recipient", noti.recipientLabel(f, true), "count", list.size(),
                    "totalEarned", list.stream().mapToDouble(r -> r.earned == null ? 0 : r.earned).sum(),
                    "reason", "최근 30일 같은 쌍의 1시간 이하 짧은 활동 " + list.size() + "회",
                    "requests", list.stream().sorted(Comparator.comparing((Request r) -> r.date)).map(r -> Views.map("id", r.id, "date", r.date.toString(),
                            "hours", r.hours, "earned", r.earned, "category", r.category, "logText", r.logText, "held", r.anomalyHold)).toList()));
        });
        return out;
    }

    @GetMapping("/anomalies")
    public List<Map<String, Object>> anomalies() { return anomalyGroups(); }

    @PostMapping("/anomalies/{requestId}/{op}")
    public Map<String, Object> hold(@PathVariable Long requestId, @PathVariable String op) {
        Request r = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("요청을 찾을 수 없어요"));
        boolean hold = switch (op) { case "hold" -> true; case "release" -> false; default -> throw ApiException.bad("INVALID", "hold 또는 release"); };
        if (hold == r.anomalyHold) return Views.map("id", r.id, "anomalyHold", r.anomalyHold);
        boolean done = r.status == ReqStatus.DONE && r.earned != null;
        if (hold) {
            if (done) bank.add(r.volunteerId, LedgerType.ADJUST, -r.earned, r.hours, r.weight, r.id, "이상 패턴 점검으로 적립 보류");
            noti.send(r.volunteerId, "ANOMALY", "활동 적립이 재확인을 위해 보류됐어요", r.category + " · " + Rules.md(r.date), "/bank");
        } else if (done) {
            bank.add(r.volunteerId, LedgerType.ADJUST, r.earned, r.hours, r.weight, r.id, "점검 완료 · 보류 해제");
            noti.send(r.volunteerId, "ANOMALY", "보류됐던 적립이 풀렸어요", "+" + r.earned + "시간", "/bank");
        }
        r.anomalyHold = hold;
        return Views.map("id", r.id, "anomalyHold", r.anomalyHold);
    }

    // ======================= 회원 (D-07) =======================
    private Map<String, Object> userRow(User u, List<Request> all) {
        BankService.Summary s = bank.summary(u.id);
        return Views.map("id", u.id, "loginId", u.loginId, "name", u.name, "role", u.role, "status", u.status, "area", u.area,
                "ageGroup", u.ageGroup, "phone", u.phone, "idStatus", u.idStatus, "crimeStatus", u.crimeStatus, "banned", Boolean.TRUE.equals(u.banned),
                "volunteerCount", all.stream().filter(r -> u.id.equals(r.volunteerId) && r.status == ReqStatus.DONE).count(),
                "recipientCount", all.stream().filter(r -> u.id.equals(r.recipientId) && r.status == ReqStatus.DONE).count(),
                "balance", s.balance(), "penaltyCount", u.penaltyCount, "warningCount", u.warningCount, "createdAt", u.createdAt);
    }

    @GetMapping("/users")
    public List<Map<String, Object>> usersList(@RequestParam(required = false) String q, @RequestParam(required = false) Role role) {
        List<Request> all = requests.findAll();
        return users.findAll().stream()
                .filter(u -> role == null || u.role == role)
                .filter(u -> q == null || q.isBlank() || u.name.contains(q) || u.loginId.contains(q))
                .map(u -> userRow(u, all)).toList();
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> userDetail(@PathVariable Long id) {
        User u = userOf(id);
        List<Request> all = requests.findAll();
        Map<String, Object> m = userRow(u, all);
        m.put("intro", u.intro);
        m.put("categories", u.categories);
        double[] r = views.ratingRaw(u.id); // 운영자는 건수가 적어도 그대로 본다
        m.put("ratingAvg", r[0]);
        m.put("ratingCount", (int) r[1]);
        m.put("recentRequests", all.stream().filter(x -> u.id.equals(x.volunteerId) || u.id.equals(x.recipientId))
                .sorted(Comparator.comparing((Request x) -> x.id).reversed()).limit(10)
                .map(x -> Views.map("id", x.id, "status", x.status, "date", x.date.toString(), "category", x.category,
                        "role", u.id.equals(x.volunteerId) ? "VOLUNTEER" : "RECIPIENT")).toList());
        m.put("reportsAgainst", reports.findAll().stream().filter(x -> u.id.equals(targetUser(x))).map(x -> Views.map("id", x.id, "type", x.type, "status", x.status, "action", x.action)).toList());
        return m;
    }

    @PostMapping("/users/{id}/suspend")
    public Map<String, Object> suspend(@PathVariable Long id) { return setStatus(id, UserStatus.SUSPENDED); }

    @PostMapping("/users/{id}/unsuspend")
    public Map<String, Object> unsuspend(@PathVariable Long id) { return setStatus(id, UserStatus.ACTIVE); }

    // ======================= 문의 (D-08) =======================
    @GetMapping("/inquiries")
    public List<Map<String, Object>> inquiries() {
        return inquiries.findAll().stream().sorted(Comparator.comparing((Inquiry q) -> INQUIRY_STAGES.indexOf(stageOf(q))).thenComparing(q -> -q.id))
                .map(this::inquiryView).toList();
    }

    private Map<String, Object> inquiryView(Inquiry q) {
        return Views.map("id", q.id, "user", views.userBrief(users.findById(q.userId).orElse(null)), "type", q.type, "title", q.title,
                "content", q.content, "status", q.status, "stage", stageOf(q), "answer", q.answer, "answeredAt", q.answeredAt, "createdAt", q.createdAt);
    }

    /** D-08 처리 상태만 따로 바꾸기. 답변 완료(ANSWERED)는 답변이 있어야 한다. 바뀌면 문의한 사람에게 알린다. */
    @PutMapping("/inquiries/{id}/status")
    public Map<String, Object> inquiryStatus(@PathVariable Long id, @RequestBody InquiryStatusBody b) {
        Inquiry q = inquiries.findById(id).orElseThrow(() -> ApiException.notFound("문의를 찾을 수 없어요"));
        String s = b == null || b.status() == null ? "" : b.status().trim().toUpperCase();
        if (!INQUIRY_STAGES.contains(s)) throw ApiException.bad("INVALID_STATUS", "처리 상태는 접수·확인 중·답변 완료·처리 완료 중에서 골라 주세요");
        boolean hasAnswer = q.answer != null && !q.answer.isBlank();
        if (s.equals("ANSWERED") && !hasAnswer) throw ApiException.bad("ANSWER_REQUIRED", "답변을 먼저 등록해 주세요");
        if (s.equals(stageOf(q))) return inquiryView(q);
        q.stage = s;
        // status(접수/답변 완료)는 답변이 있는지에 맞춘다: 다시 접수·확인 중으로 돌리면 접수로 보인다
        q.status = hasAnswer && (s.equals("ANSWERED") || s.equals("CLOSED")) ? InquiryStatus.ANSWERED : InquiryStatus.RECEIVED;
        String label = switch (s) { case "CHECKING" -> "확인 중"; case "ANSWERED" -> "답변 완료"; case "CLOSED" -> "처리 완료"; default -> "접수"; };
        noti.send(q.userId, "INQUIRY", "1:1 문의 처리 상태가 '" + label + "'" + Rules.ro(label).substring(label.length()) + " 바뀌었어요", q.title, "/my/inquiries");
        return inquiryView(q);
    }

    @PostMapping("/inquiries/{id}/answer")
    public Inquiry answer(@PathVariable Long id, @RequestBody AnswerBody b) {
        if (b.answer() == null || b.answer().isBlank()) throw ApiException.bad("ANSWER_REQUIRED", "답변을 적어 주세요");
        Inquiry q = inquiries.findById(id).orElseThrow(() -> ApiException.notFound("문의를 찾을 수 없어요"));
        q.answer = b.answer().trim();
        q.status = InquiryStatus.ANSWERED;
        q.stage = "ANSWERED";
        q.answeredAt = LocalDateTime.now();
        noti.send(q.userId, "INQUIRY", "1:1 문의에 답변이 등록됐어요", q.title, "/my/inquiries");
        return q;
    }

    // ======================= 공지 (D-09) =======================
    @PostMapping("/notices")
    public Notice addNotice(@RequestBody NoticeBody b) {
        if (b.title() == null || b.title().isBlank()) throw ApiException.bad("TITLE_REQUIRED", "제목을 적어 주세요");
        Notice n = new Notice();
        n.title = b.title().trim();
        n.content = b.content();
        return notices.save(n);
    }

    @PutMapping("/notices/{id}")
    public Notice putNotice(@PathVariable Long id, @RequestBody NoticeBody b) {
        Notice n = notices.findById(id).orElseThrow(() -> ApiException.notFound("공지를 찾을 수 없어요"));
        if (b.title() != null) n.title = b.title();
        if (b.content() != null) n.content = b.content();
        return n;
    }

    @DeleteMapping("/notices/{id}")
    public Map<String, Object> delNotice(@PathVariable Long id) { notices.deleteById(id); return Views.map("ok", true); }

    // ======================= 공공 서비스 (P-02) =======================
    @GetMapping("/public-services")
    public List<PublicService> services() { return services.findAll(); }

    private PublicService applySvc(PublicService s, ServiceBody b) {
        if (b.name() != null) s.name = b.name();
        if (b.target() != null) s.target = b.target();
        if (b.content() != null) s.content = b.content();
        if (b.applyHow() != null) s.applyHow = b.applyHow();
        if (b.contact() != null) s.contact = b.contact();
        if (b.region() != null) s.region = b.region();
        if (b.keywords() != null) s.keywords = new ArrayList<>(b.keywords());
        if (s.name == null || s.name.isBlank()) throw ApiException.bad("NAME_REQUIRED", "서비스 이름을 적어 주세요");
        return services.save(s);
    }

    @PostMapping("/public-services")
    public PublicService addSvc(@RequestBody ServiceBody b) { PublicService s = new PublicService(); s.region = "전국"; return applySvc(s, b); }

    @PutMapping("/public-services/{id}")
    public PublicService putSvc(@PathVariable Long id, @RequestBody ServiceBody b) {
        return applySvc(services.findById(id).orElseThrow(() -> ApiException.notFound("서비스를 찾을 수 없어요")), b);
    }

    @DeleteMapping("/public-services/{id}")
    public Map<String, Object> delSvc(@PathVariable Long id) { services.deleteById(id); return Views.map("ok", true); }

    // ======================= 가상 상대 =======================
    @GetMapping("/personas")
    public List<Map<String, Object>> personasList() { return personas.findAll().stream().map(p -> eduSvc.personaView(p, true)).toList(); }

    private Map<String, Object> applyPersona(RoleplayPersona p, PersonaBody b) {
        if (b.name() != null) p.name = b.name();
        if (b.initial() != null) p.initial = b.initial();
        if (b.ageLabel() != null) p.ageLabel = b.ageLabel();
        if (b.fitCategory() != null) p.fitCategory = b.fitCategory();
        if (b.trait() != null) p.trait = b.trait();
        if (b.goal() != null) p.goal = b.goal();
        if (b.opening() != null) p.opening = b.opening();
        if (b.turns() != null) p.turnsJson = Views.json(b.turns());
        if (b.active() != null) p.active = b.active();
        if (p.name == null || p.name.isBlank()) throw ApiException.bad("NAME_REQUIRED", "이름을 적어 주세요");
        return eduSvc.personaView(personas.save(p), true);
    }

    @PostMapping("/personas")
    public Map<String, Object> addPersona(@RequestBody PersonaBody b) { RoleplayPersona p = new RoleplayPersona(); p.turnsJson = "[]"; return applyPersona(p, b); }

    @PutMapping("/personas/{id}")
    public Map<String, Object> putPersona(@PathVariable Long id, @RequestBody PersonaBody b) { return applyPersona(eduSvc.persona(id), b); }

    @DeleteMapping("/personas/{id}")
    public Map<String, Object> delPersona(@PathVariable Long id) { personas.deleteById(id); return Views.map("ok", true); }

    // ======================= 리포트 즉시 생성 (P-07) · 스케줄러 =======================
    @PostMapping("/reports/generate")
    public Map<String, Object> generate(@RequestBody(required = false) MonthBody b) {
        YearMonth m = b == null || b.month() == null || b.month().isBlank() ? YearMonth.now().minusMonths(1) : YearMonth.parse(b.month());
        int n = lifeReports.generate(m);
        return Views.map("month", m.toString(), "generated", n);
    }

    @PostMapping("/scheduler/run")
    public Map<String, Object> run() { return scheduler.runAll(LocalDateTime.now()); }
}
