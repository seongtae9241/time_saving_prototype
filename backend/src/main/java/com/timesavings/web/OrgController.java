package com.timesavings.web;

import com.timesavings.ai.VoiceSafety;
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

/** 담당자(C-02~C-10, P-05, P-06). /api/org/** 는 ORG 역할만(인터셉터). */
@RestController
@RequestMapping("/api/org")
@Transactional
public class OrgController {
    private final ElderRepo elders;
    private final ElderMemoRepo memos;
    private final ElderTransferRepo transfers;
    private final UserRepo users;
    private final RequestRepo requests;
    private final PostRepo posts;
    private final NotificationRepo notis;
    private final LifeReportRepo reports;
    private final LifeReportService reportSvc;
    private final NotificationService noti;
    private final Views views;
    /** 41판 C-13 메모 정리 · C-16 변화 리포트(생성자 인자를 늘리지 않으려고 필드 주입) */
    @org.springframework.beans.factory.annotation.Autowired private com.timesavings.ai.ManagerMemoAi memoAi;
    @org.springframework.beans.factory.annotation.Autowired private ElderChangeService changeSvc;

    public OrgController(ElderRepo elders, ElderMemoRepo memos, ElderTransferRepo transfers, UserRepo users, RequestRepo requests,
                         PostRepo posts, NotificationRepo notis, LifeReportRepo reports,
                         LifeReportService reportSvc, NotificationService noti, Views views) {
        this.elders = elders; this.memos = memos; this.transfers = transfers; this.users = users; this.requests = requests;
        this.posts = posts; this.notis = notis; this.reports = reports; this.reportSvc = reportSvc;
        this.noti = noti; this.views = views;
    }

    public record ElderBody(String alias, String dong, String ageGroup, List<String> needs, String caution, String memo, Boolean consent) {}
    public record MemoBody(String text) {}
    public record TransferBody(Long toManagerId) {}
    public record AnswerBody(Boolean accept) {}
    public record ConsentBody(Boolean reportConsent) {}

    private Elder myElder(User u, Long id) {
        Elder e = elders.findById(id).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
        if (!u.id.equals(e.managerId)) throw ApiException.forbidden("내가 담당하는 대상이 아니에요");
        return e;
    }

    private Set<Long> myElderIds(User u) {
        Set<Long> s = new HashSet<>();
        elders.findByManagerId(u.id).forEach(e -> s.add(e.id));
        return s;
    }

    private List<Request> myRequests(User u) {
        Set<Long> ids = myElderIds(u);
        return requests.findAll().stream().filter(r -> r.elderId != null && ids.contains(r.elderId))
                .sorted(Comparator.comparing((Request r) -> r.id).reversed()).toList();
    }

    private static boolean needsMe(Request r) {
        return r.status == ReqStatus.VOL_ACCEPTED || (r.status == ReqStatus.SENT && r.kind == Kind.PROPOSAL);
    }

    @GetMapping("/home")
    public Map<String, Object> home(@CurrentUser User u) {
        List<Request> list = myRequests(u);
        List<Map<String, Object>> todo = new ArrayList<>();
        for (Request r : list) {
            String what = needsMe(r) ? (r.kind == Kind.PROPOSAL ? "제안 수락/거절" : "최종 수락/거부 입력")
                    : r.status == ReqStatus.LOG_SUBMITTED ? "활동 일지 대신 확인" : "PENDING".equals(r.adjustStatus) ? "시간 조정 답하기" : null;
            if (what == null) continue;
            todo.add(Views.map("requestId", r.id, "type", what, "elder", noti.recipientLabel(r, true), "volunteer", noti.name(r.volunteerId),
                    "date", r.date.toString(), "startHour", r.startHour, "category", r.category, "leftText", Views.leftText(r.deadlineAt)));
        }
        List<Notification> recent = notis.findByUserIdOrderByCreatedAtDescIdDesc(u.id).stream().filter(n -> !n.isRead).limit(5).toList();
        Set<Long> ids = myElderIds(u);
        String last = YearMonth.now().minusMonths(1).toString();
        long flagged = reports.findByMonth(last).stream().filter(r -> r.subjectType == SubjectType.ELDER && ids.contains(r.subjectId) && r.bigChange).count();
        YearMonth now = YearMonth.now();
        return Views.map("todo", todo,
                "counts", Views.map("monthDone", list.stream().filter(r -> r.status == ReqStatus.DONE && YearMonth.from(r.date).equals(now)).count(),
                        "elders", elders.findByManagerId(u.id).stream().filter(e -> e.status == ElderStatus.ACTIVE).count(),
                        "pendingFinal", list.stream().filter(OrgController::needsMe).count(),
                        "logsToConfirm", list.stream().filter(r -> r.status == ReqStatus.LOG_SUBMITTED).count(),
                        "active", list.stream().filter(r -> r.status.active()).count(),
                        "flaggedReports", flagged,
                        "transfersIn", transfers.findAll().stream().filter(t -> t.status == TransferStatus.PENDING && u.id.equals(t.toManagerId)).count()),
                "alerts", recent);
    }

    private Map<String, Object> elderView(Elder e) {
        List<Request> rs = requests.findByElderId(e.id);
        YearMonth now = YearMonth.now();
        LifeReport latest = reports.findBySubjectTypeAndSubjectIdOrderByMonthDesc(SubjectType.ELDER, e.id).stream().findFirst().orElse(null);
        return Views.map("id", e.id, "alias", e.alias, "displayName", e.alias + " 님", "maskedName", Rules.maskAlias(e.alias),
                "dong", e.dong, "ageGroup", e.ageGroup, "needs", e.needs, "caution", e.caution, "status", e.status,
                "reportConsent", e.reportConsent, "serviceConsentAt", e.serviceConsentAt, "managerId", e.managerId, "createdAt", e.createdAt,
                "qrIssuedAt", e.qrIssuedAt,
                "regularCount", views.regularsOf(null, e.id).size(),
                "activeCount", rs.stream().filter(r -> !r.status.terminal()).count(),
                "monthDone", rs.stream().filter(r -> r.status == ReqStatus.DONE && YearMonth.from(r.date).equals(now)).count(),
                "doneTotal", rs.stream().filter(r -> r.status == ReqStatus.DONE).count(),
                "flag", latest != null && latest.bigChange);
    }

    @GetMapping("/elders")
    public List<Map<String, Object>> list(@CurrentUser User u) {
        return elders.findByManagerId(u.id).stream().sorted(Comparator.comparing((Elder e) -> e.status).thenComparing(e -> e.id)).map(this::elderView).toList();
    }

    /** C-02: 이름·주소·연락처는 받지 않는다. 연락처·상세 주소가 보이면 막는다. */
    private static void noPii(String... fields) {
        for (String f : fields) {
            if (f == null) continue;
            if (!VoiceSafety.scrub(f).removed().isEmpty())
                throw ApiException.bad("PII_NOT_ALLOWED", "연락처·상세 주소 같은 개인정보는 적지 않아요. 별칭·동·주의사항만 기록해 주세요");
        }
    }

    private void apply(Elder e, ElderBody b) {
        noPii(b.alias(), b.caution(), b.memo(), b.dong());
        if (b.alias() != null) {
            if (b.alias().isBlank() || b.alias().length() > 10) throw ApiException.bad("INVALID_ALIAS", "별칭은 1~10자로 적어 주세요");
            e.alias = b.alias().trim();
        }
        if (b.dong() != null) {
            if (!b.dong().trim().endsWith("동")) throw ApiException.bad("INVALID_DONG", "지역은 동 단위로만 적어 주세요 (예: 행복동)");
            e.dong = b.dong().trim();
        }
        if (b.ageGroup() != null) e.ageGroup = b.ageGroup();
        if (b.needs() != null) e.needs = AuthController.clean(b.needs());
        if (b.caution() != null) e.caution = b.caution();
    }

    @PostMapping("/elders")
    public Map<String, Object> create(@CurrentUser User u, @RequestBody ElderBody b) {
        if (b.alias() == null || b.dong() == null) throw ApiException.bad("INVALID_INPUT", "별칭과 동을 적어 주세요");
        // C-02: 대상에게 서비스 이용을 설명하고 동의를 받았음을 확인해야 등록된다(화면 체크만으로 끝내지 않고 서버가 확인·기록)
        if (!Boolean.TRUE.equals(b.consent()))
            throw ApiException.bad("CONSENT_REQUIRED", "대상에게 서비스 이용을 설명하고 동의를 받았는지 확인(체크)해 주세요");
        Elder e = new Elder();
        apply(e, b);
        e.serviceConsentAt = LocalDateTime.now();
        e.serviceConsentBy = u.id;
        e.orgId = u.orgId;
        e.managerId = u.id;
        elders.save(e);
        if (b.memo() != null && !b.memo().isBlank()) addMemo(e.id, b.memo());
        return elderView(e);
    }

    private void addMemo(Long elderId, String text) {
        ElderMemo m = new ElderMemo();
        m.elderId = elderId;
        m.text = text.trim();
        // C-13: 원문은 그대로 두고, 읽기 쉬운 정리본을 따로 저장한다(키 없으면 규칙 정리)
        Map<String, Object> t = memoAi.tidy(m.text);
        String tidy = (String) t.get("tidy");
        m.tidyText = tidy == null || tidy.isBlank() ? null : (tidy.length() > 1000 ? tidy.substring(0, 1000) : tidy);
        m.source = (String) t.get("source");
        memos.save(m);
    }

    @GetMapping("/elders/{id}")
    public Map<String, Object> get(@CurrentUser User u, @PathVariable Long id) {
        Elder e = myElder(u, id);
        Map<String, Object> v = elderView(e);
        v.put("memos", memoViews(id));
        v.put("regulars", views.regularsOf(null, id));
        v.put("requests", requests.findByElderId(id).stream() // 최근 날짜순(같은 날은 늦은 시각 먼저)
                .sorted(Comparator.comparing((Request r) -> r.date).thenComparing(r -> r.startHour).thenComparing(r -> r.id).reversed()).limit(20)
                .map(r -> views.requestView(r, u)).toList());
        v.put("posts", posts.findByElderId(id).stream().filter(p -> p.status != PostStatus.DELETED).map(p -> views.postCard(p, u)).toList());
        v.put("reports", reports.findBySubjectTypeAndSubjectIdOrderByMonthDesc(SubjectType.ELDER, id).stream().map(r -> reportSvc.view(r, e.alias + " 님")).toList());
        return v;
    }

    @PutMapping("/elders/{id}")
    public Map<String, Object> update(@CurrentUser User u, @PathVariable Long id, @RequestBody ElderBody b) {
        Elder e = myElder(u, id);
        apply(e, b);
        return elderView(e);
    }

    // ---------------- 현관 QR 카드 (C-11) ----------------
    @org.springframework.beans.factory.annotation.Autowired private RequestService requestSvc;

    private Map<String, Object> cardView(Elder e) {
        return Views.map("elderId", e.id, "displayName", e.alias + " 님", "code", e.qrCode,
                "qrText", Rules.qrText("ELD", e.id, e.qrCode), "issuedAt", e.qrIssuedAt,
                "activeCount", requests.findByElderId(e.id).stream().filter(r -> r.status == ReqStatus.CONFIRMED || r.status == ReqStatus.IN_PROGRESS).count());
    }

    /** 카드 보기(없으면 처음 한 번 만든다). 카드에는 이름·주소가 없고 번호만 담긴다. */
    @GetMapping("/elders/{id}/qr-card")
    public Map<String, Object> qrCard(@CurrentUser User u, @PathVariable Long id) {
        return cardView(requestSvc.ensureCard(myElder(u, id)));
    }

    /** 다시 발급: 번호가 바뀌어 이전 카드로는 시작·종료를 기록할 수 없다. */
    @PostMapping("/elders/{id}/qr-card/reissue")
    public Map<String, Object> qrCardReissue(@CurrentUser User u, @PathVariable Long id) {
        Elder e = myElder(u, id);
        if (e.status != ElderStatus.ACTIVE) throw ApiException.bad("ELDER_ENDED", "지원이 종료된 대상이에요");
        return cardView(requestSvc.reissueCard(e));
    }

    @PostMapping("/elders/{id}/memos")
    public List<Map<String, Object>> memo(@CurrentUser User u, @PathVariable Long id, @RequestBody MemoBody b) {
        myElder(u, id);
        if (b.text() == null || b.text().isBlank()) throw ApiException.bad("TEXT_REQUIRED", "메모를 적어 주세요");
        if (b.text().trim().length() > 1000) throw ApiException.bad("TEXT_TOO_LONG", "메모는 1000자 안으로 적어 주세요");
        noPii(b.text());
        addMemo(id, b.text());
        return memoViews(id);
    }

    /** C-13 메모 목록: 원문(text)과 정리본(tidy, 줄 목록 lines). 담당자만 본다 — 봉사자에게 가는 주의사항(Elder.caution)과 따로 관리. */
    private List<Map<String, Object>> memoViews(Long elderId) {
        return memos.findByElderIdOrderByCreatedAtDesc(elderId).stream().map(m -> {
            List<String> lines = m.tidyText == null || m.tidyText.isBlank() ? List.of()
                    : Arrays.stream(m.tidyText.split("\n")).map(String::trim).filter(x -> !x.isEmpty()).toList();
            return Views.map("id", m.id, "elderId", m.elderId, "text", m.text, "tidy", m.tidyText, "lines", lines,
                    "source", m.source, "createdAt", m.createdAt);
        }).toList();
    }

    @PostMapping("/elders/{id}/end")
    public Map<String, Object> end(@CurrentUser User u, @PathVariable Long id) {
        Elder e = myElder(u, id);
        long active = requests.findByElderId(id).stream().filter(r -> !r.status.terminal()).count();
        if (active > 0) throw ApiException.conflict("ACTIVE_REQUESTS", "진행 중인 요청 " + active + "건을 먼저 정리해 주세요 (C-03)");
        e.status = ElderStatus.ENDED;
        posts.findByElderId(id).forEach(p -> { if (p.status == PostStatus.OPEN) p.status = PostStatus.CLOSED; });
        return elderView(e);
    }

    @PutMapping("/elders/{id}/consent")
    public Map<String, Object> consent(@CurrentUser User u, @PathVariable Long id, @RequestBody ConsentBody b) {
        Elder e = myElder(u, id);
        e.reportConsent = Boolean.TRUE.equals(b.reportConsent());
        return elderView(e);
    }

    @GetMapping("/managers")
    public List<Map<String, Object>> managers(@CurrentUser User u) {
        return users.findByOrgId(u.orgId).stream().filter(m -> m.role == Role.ORG && !m.id.equals(u.id))
                .map(m -> Views.map("id", m.id, "name", m.name, "status", m.status, "elderCount", elders.findByManagerId(m.id).size())).toList();
    }

    @PostMapping("/elders/{id}/transfer")
    public Map<String, Object> transfer(@CurrentUser User u, @PathVariable Long id, @RequestBody TransferBody b) {
        Elder e = myElder(u, id);
        User to = users.findById(b.toManagerId() == null ? -1 : b.toManagerId()).orElseThrow(() -> ApiException.notFound("받을 담당자를 찾을 수 없어요"));
        if (to.role != Role.ORG || !Objects.equals(to.orgId, u.orgId) || to.id.equals(u.id) || to.status != UserStatus.ACTIVE)
            throw ApiException.bad("INVALID_MANAGER", "같은 기관의 다른 담당자에게만 이관할 수 있어요");
        if (transfers.findAll().stream().anyMatch(t -> t.elderId.equals(id) && t.status == TransferStatus.PENDING))
            throw ApiException.conflict("DUPLICATE", "이미 이관 신청 중이에요");
        ElderTransfer t = new ElderTransfer();
        t.elderId = e.id; t.fromManagerId = u.id; t.toManagerId = to.id;
        transfers.save(t);
        noti.send(to.id, "TRANSFER", u.name + " 님이 도움 대상 이관을 신청했어요", e.alias + " 님 · 수락하면 담당이 넘어와요", "/org/transfers");
        return transferView(t);
    }

    private Map<String, Object> transferView(ElderTransfer t) {
        Elder e = elders.findById(t.elderId).orElse(null);
        return Views.map("id", t.id, "elderId", t.elderId, "elderAlias", e == null ? null : e.alias,
                "from", t.fromManagerId == null ? null : views.userBrief(users.findById(t.fromManagerId).orElse(null)),
                "to", views.userBrief(users.findById(t.toManagerId).orElse(null)), "status", t.status, "createdAt", t.createdAt);
    }

    @GetMapping("/transfers")
    public List<Map<String, Object>> transfersList(@CurrentUser User u) {
        return transfers.findAll().stream().filter(t -> u.id.equals(t.toManagerId) || u.id.equals(t.fromManagerId))
                .sorted(Comparator.comparing((ElderTransfer t) -> t.id).reversed())
                .map(t -> { Map<String, Object> m = transferView(t); m.put("direction", u.id.equals(t.toManagerId) ? "in" : "out"); return m; }).toList();
    }

    @PostMapping("/transfers/{id}/answer")
    public Map<String, Object> answer(@CurrentUser User u, @PathVariable Long id, @RequestBody AnswerBody b) {
        ElderTransfer t = transfers.findById(id).orElseThrow(() -> ApiException.notFound("이관 신청을 찾을 수 없어요"));
        if (!u.id.equals(t.toManagerId) || t.status != TransferStatus.PENDING) throw ApiException.conflict("INVALID_STATE", "응답할 수 있는 이관 신청이 아니에요");
        boolean ok = Boolean.TRUE.equals(b.accept());
        t.status = ok ? TransferStatus.ACCEPTED : TransferStatus.REJECTED;
        if (ok) elders.findById(t.elderId).ifPresent(e -> e.managerId = u.id);
        noti.send(t.fromManagerId, "TRANSFER", "이관 신청이 " + (ok ? "수락" : "거절") + "됐어요", null, "/org/transfers");
        return transferView(t);
    }

    @GetMapping("/requests")
    public List<Map<String, Object>> requests(@CurrentUser User u, @RequestParam(required = false) String box) {
        String bx = box == null ? "all" : box;
        return myRequests(u).stream().filter(r -> switch (bx) {
            case "received" -> needsMe(r) || r.status == ReqStatus.LOG_SUBMITTED;
            case "sent" -> u.id.equals(r.senderId) && r.status.pending();
            case "active" -> r.status.active();
            case "done" -> r.status.terminal();
            default -> true;
        }).map(r -> views.requestView(r, u)).toList();
    }

    @GetMapping("/reports")
    public List<Map<String, Object>> reports(@CurrentUser User u, @RequestParam(required = false) String month) {
        String m = month == null || month.isBlank() ? YearMonth.now().minusMonths(1).toString() : month;
        Map<Long, Elder> mine = new HashMap<>();
        elders.findByManagerId(u.id).forEach(e -> mine.put(e.id, e));
        return reports.findByMonth(m).stream().filter(r -> r.subjectType == SubjectType.ELDER && mine.containsKey(r.subjectId))
                .sorted(Comparator.comparing((LifeReport r) -> !r.bigChange))
                .map(r -> { Map<String, Object> v = reportSvc.view(r, mine.get(r.subjectId).alias + " 님"); v.put("reportConsent", mine.get(r.subjectId).reportConsent); return v; })
                .toList();
    }

    /**
     * C-16 대상 변화 리포트: 대상별 최근 활동 일지를 이전 기록과 비교한 특이사항·변화와, 먼저 살펴볼 순서.
     * range=today 면 오늘 일지가 들어온 대상만, 아니면 담당 대상 전체(최근 30일 기준).
     */
    @GetMapping("/change-report")
    public Map<String, Object> changeReport(@CurrentUser User u, @RequestParam(required = false) String range) {
        return changeSvc.report(u, "today".equalsIgnoreCase(range));
    }

    @PostMapping("/reports/{id}/flag")
    public Map<String, Object> flag(@CurrentUser User u, @PathVariable Long id) {
        LifeReport r = reports.findById(id).orElseThrow(() -> ApiException.notFound("리포트를 찾을 수 없어요"));
        if (r.subjectType != SubjectType.ELDER) throw ApiException.forbidden("도움 대상 리포트만 표시할 수 있어요");
        myElder(u, r.subjectId);
        r.bigChange = !r.bigChange;
        return Views.map("id", r.id, "bigChange", r.bigChange);
    }
}
