package com.timesavings.service;

import com.timesavings.ai.VoiceSafety;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 권한 위임(2026-11-16 담당자 추가 기능, 프로토타입 O-DLG-01~03).
 * - 관리 대상은 별칭·나이대·동만 쓴다. 동의 방법은 종류 코드(APP·PAPER·GUARDIAN)와 날짜만 저장하고 서류 사본·서명·연락처는 저장하지 않는다.
 * - 보호자는 본인 앱 계정과 가족으로 연결된 계정이 있을 때만 그 계정 id 로 둔다.
 * - 위임·수락·회수는 모두 사람이 눌러야 바뀐다. 만료는 날짜로 계산만 한다(자동으로 연장하지 않는다).
 * - 기간은 최대 1년. 끝나기 30일 전부터 "만료 임박"으로 보여 준다.
 */
@Service
@Transactional
public class DelegationService {
    /** 맡긴 일 범위: 코드 → 화면 이름(프로토타입 ALLSCOPE 순서) */
    public static final Map<String, String> SCOPES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("POST_PROXY", "대신 공고");
        m.put("ACCEPT_PROXY", "대신 수락");
        m.put("LOG_CONFIRM", "일지 확인");
        m.put("PUBLIC_SERVICE", "공공 서비스");
        m.put("CHECK_IN", "안부 확인");
        SCOPES = Collections.unmodifiableMap(m);
    }
    public static final Map<String, String> METHODS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("APP", "앱 동의");
        m.put("PAPER", "서면 동의");
        m.put("GUARDIAN", "보호자 동의");
        METHODS = Collections.unmodifiableMap(m);
    }
    public static final int SOON_DAYS = 30;
    public static final String NEW_LINK = "NEW_LINK", SCOPE_CHANGE = "SCOPE_CHANGE";
    private static final String LINK_ORG = "/o/delegations", LINK_USER = "/my/delegations";

    private final DelegationRepo delegations;
    private final DelegationRequestRepo reqs;
    private final DelegationLogRepo logs;
    private final ElderRepo elders;
    private final UserRepo users;
    private final OrganizationRepo orgs;
    private final FamilyLinkRepo family;
    private final PostRepo posts;
    private final RequestRepo requests;
    private final NotificationService noti;

    public DelegationService(DelegationRepo delegations, DelegationRequestRepo reqs, DelegationLogRepo logs, ElderRepo elders, UserRepo users,
                             OrganizationRepo orgs, FamilyLinkRepo family, PostRepo posts, RequestRepo requests, NotificationService noti) {
        this.delegations = delegations; this.reqs = reqs; this.logs = logs; this.elders = elders; this.users = users;
        this.orgs = orgs; this.family = family; this.posts = posts; this.requests = requests; this.noti = noti;
    }

    public record CreateCmd(Long elderId, String consentMethod, List<String> scopes, LocalDate startDate, LocalDate endDate,
                            LocalDate consentDate, Boolean consentConfirmed, Long guardianUserId) {}
    public record UpdateCmd(List<String> scopes, LocalDate endDate, LocalDate consentDate, Boolean consentConfirmed) {}
    public record AnswerCmd(Boolean accept, String alias, String dong, String reason) {}
    public record RequestCmd(String kind, Long orgId, Long delegationId, List<String> scopes, String message) {}
    public record ConsentCmd(Boolean accept, List<String> scopes) {}
    public record ExtendCmd(LocalDate endDate) {}

    // ---------------- 계산 ----------------
    /** 보여 주는 상태: 저장된 ACTIVE 가 끝나는 날을 지났으면 EXPIRED. */
    public static String statusOf(Delegation d, LocalDate today) {
        if ("ACTIVE".equals(d.status) && d.endDate != null && d.endDate.isBefore(today)) return "EXPIRED";
        return d.status;
    }

    private static boolean open(Delegation d, LocalDate today) {
        String s = statusOf(d, today);
        return "ACTIVE".equals(s) || "PENDING".equals(s);
    }

    static String statusLabel(String s, boolean soon) {
        return switch (s) {
            case "ACTIVE" -> soon ? "만료 임박" : "위임 중";
            case "PENDING" -> "동의 대기";
            case "EXPIRED" -> "만료";
            case "REVOKED" -> "회수됨";
            case "REJECTED" -> "동의하지 않음";
            default -> "위임 없음";
        };
    }

    /** 범위 코드 검사: 정해진 코드만, 중복 없이 정해진 순서로. 하나 이상. */
    static List<String> cleanScopes(List<String> in) {
        if (in == null || in.isEmpty()) throw ApiException.bad("SCOPES_REQUIRED", "맡길 일을 하나 이상 골라 주세요");
        for (String s : in) if (!SCOPES.containsKey(s)) throw ApiException.bad("INVALID_SCOPE", "맡길 일은 정해진 항목에서만 고를 수 있어요");
        return SCOPES.keySet().stream().filter(in::contains).toList();
    }

    private static String scopeNames(List<String> codes) {
        return String.join(" · ", codes.stream().map(c -> SCOPES.getOrDefault(c, c)).toList());
    }

    /** 이 대상의 지금 위임: 동의 대기·유효한 것이 있으면 그것, 없으면 가장 최근 것. 없으면 null. */
    public Delegation current(Long elderId) {
        LocalDate today = LocalDate.now();
        List<Delegation> all = delegations.findByElderId(elderId).stream().sorted(Comparator.comparing((Delegation d) -> d.id).reversed()).toList();
        return all.stream().filter(d -> open(d, today)).findFirst().orElse(all.isEmpty() ? null : all.get(0));
    }

    /** 유효한 위임에 그 범위가 들어 있는지. (기존 대리 공고·대신 수락 흐름은 아직 이 검사를 쓰지 않는다 — 문서 "확인 못 한 것" 참고) */
    @Transactional(readOnly = true)
    public boolean allows(Long elderId, String scope) {
        Delegation d = current(elderId);
        return d != null && "ACTIVE".equals(statusOf(d, LocalDate.now())) && d.scopes.contains(scope);
    }

    /** 관리 대상 본인의 앱 계정(앱에서 연결을 요청해 수락된 적이 있을 때만). */
    private Long linkedUserId(Long elderId) {
        return delegations.findByElderId(elderId).stream().filter(d -> d.userId != null)
                .sorted(Comparator.comparing((Delegation d) -> d.id).reversed()).map(d -> d.userId)
                .filter(id -> users.findById(id).map(u -> u.status == UserStatus.ACTIVE).orElse(false)).findFirst().orElse(null);
    }

    /** 본인 계정과 가족으로 연결(수락됨)된 계정들. */
    private List<Map<String, Object>> guardians(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (userId == null) return out;
        for (FamilyLink l : family.findAll()) {
            if (l.status != LinkStatus.ACCEPTED) continue;
            Long other = userId.equals(l.requesterId) ? l.targetId : userId.equals(l.targetId) ? l.requesterId : null;
            if (other == null) continue;
            users.findById(other).filter(u -> u.status == UserStatus.ACTIVE)
                    .ifPresent(u -> out.add(Views.map("userId", u.id, "name", u.name, "relation", l.relation)));
        }
        return out;
    }

    private Elder myElder(User mgr, Long id) {
        Elder e = elders.findById(id == null ? -1 : id).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
        if (!mgr.id.equals(e.managerId)) throw ApiException.forbidden("내가 담당하는 대상이 아니에요");
        return e;
    }

    private Delegation myDelegation(User mgr, Long id) {
        Delegation d = delegations.findById(id).orElseThrow(() -> ApiException.notFound("위임을 찾을 수 없어요"));
        myElder(mgr, d.elderId);
        return d;
    }

    private void log(Delegation d, Long elderId, User actor, String role, String action, String detail) {
        DelegationLog l = new DelegationLog();
        l.delegationId = d == null ? null : d.id;
        l.elderId = elderId;
        l.actorId = actor == null ? null : actor.id;
        l.actorRole = role;
        l.action = action;
        l.detail = detail == null ? null : (detail.length() > 300 ? detail.substring(0, 300) : detail);
        logs.save(l);
    }

    private void tellOwners(Delegation d, Long except, String title, String body) {
        for (Long uid : new LinkedHashSet<>(Arrays.asList(d.userId, d.guardianUserId)))
            if (uid != null && !uid.equals(except)) noti.send(uid, "DELEGATION", title, body, LINK_USER);
    }

    private void tellManager(Long elderId, String title, String body) {
        elders.findById(elderId).ifPresent(e -> noti.send(e.managerId, "DELEGATION", title, body, LINK_ORG));
    }

    // ---------------- 보기 ----------------
    private Map<String, Object> view(Delegation d, Elder e, boolean forManager) {
        LocalDate today = LocalDate.now();
        String st = statusOf(d, today);
        Long daysLeft = d.endDate == null || !"ACTIVE".equals(st) ? null : ChronoUnit.DAYS.between(today, d.endDate);
        boolean soon = daysLeft != null && daysLeft <= SOON_DAYS;
        List<Map<String, Object>> sc = new ArrayList<>();
        SCOPES.forEach((code, label) -> sc.add(Views.map("code", code, "label", label, "on", d.scopes.contains(code))));
        Map<String, Object> m = Views.map("id", d.id, "elderId", d.elderId,
                "consentMethod", d.consentMethod, "consentMethodLabel", METHODS.getOrDefault(d.consentMethod, d.consentMethod),
                "consentDate", d.consentDate == null ? null : d.consentDate.toString(),
                "scopes", d.scopes, "scopeItems", sc, "scopeCount", d.scopes.size(),
                "startDate", d.startDate == null ? null : d.startDate.toString(), "endDate", d.endDate == null ? null : d.endDate.toString(),
                "status", st, "statusLabel", statusLabel(st, soon), "expiringSoon", soon, "daysLeft", daysLeft,
                "appLinked", d.userId != null, "hasGuardianAccount", d.guardianUserId != null,
                "revokedAt", d.revokedAt, "createdAt", d.createdAt);
        if (forManager) {
            m.put("displayName", e == null ? null : e.alias + " 님");
            m.put("ageGroup", e == null ? null : e.ageGroup);
            m.put("dong", e == null ? null : e.dong);
            // 앱으로 동의한 위임은 본인이 앱에서만 바꾼다. 서면·보호자 동의는 다시 동의를 받은 담당자가 바꾼다.
            m.put("editableByManager", !"APP".equals(d.consentMethod) && "ACTIVE".equals(d.status));
            m.put("canRevoke", "ACTIVE".equals(st) || "PENDING".equals(st));
            m.put("canAskExtend", "ACTIVE".equals(d.status) && (d.userId != null || d.guardianUserId != null));
            m.put("guardian", d.guardianUserId == null ? null : users.findById(d.guardianUserId).map(u -> Views.map("userId", u.id, "name", u.name)).orElse(null));
        } else {
            m.put("orgName", d.orgId == null ? null : orgs.findById(d.orgId).map(o -> o.name).orElse(null));
            m.put("managerName", e == null || e.managerId == null ? null : users.findById(e.managerId).map(u -> u.name).orElse(null));
        }
        return m;
    }

    private List<Map<String, Object>> history(Long elderId) {
        return logs.findByElderIdOrderByIdDesc(elderId).stream().limit(50).map(l -> Views.map("id", l.id, "delegationId", l.delegationId,
                "action", l.action, "actionLabel", actionLabel(l.action), "actorRole", l.actorRole,
                "actorName", l.actorId == null ? null : users.findById(l.actorId).map(u -> u.name).orElse(null),
                "detail", l.detail, "at", l.createdAt)).toList();
    }

    static String actionLabel(String a) {
        return switch (a) {
            case "CREATED" -> "위임 등록";
            case "CONSENTED" -> "동의";
            case "CONSENT_REJECTED" -> "동의하지 않음";
            case "SCOPE_CHANGED" -> "맡긴 일 변경";
            case "EXTENDED" -> "기간 연장";
            case "EXTEND_ASKED" -> "연장 동의 요청";
            case "REVOKED" -> "회수";
            case "LINK_ACCEPTED" -> "연결 요청 수락";
            case "REQUEST_REJECTED" -> "요청 거절";
            default -> a;
        };
    }

    /**
     * "위임으로 한 일"(프로토타입 O-DLG-02 아래 카드): 따로 저장하지 않고 기존 자료에서 읽는다.
     * 대리 공고는 올린 담당자를 알 수 있고, 일정 확정·일지 확인은 누른 사람을 저장하지 않아 by 가 null 이다.
     */
    private List<Map<String, Object>> acts(Long elderId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Post p : posts.findByElderId(elderId)) {
            if (p.status == PostStatus.DELETED || p.createdAt == null) continue;
            out.add(Views.map("at", p.createdAt, "scope", "POST_PROXY", "by", p.createdBy == null ? null : users.findById(p.createdBy).map(u -> u.name).orElse(null),
                    "text", (p.title == null ? "도움 요청" : p.title) + " 공고를 대신 올림", "link", "/post/" + p.id));
        }
        for (Request r : requests.findByElderId(elderId)) {
            if (r.confirmedAt != null) out.add(Views.map("at", r.confirmedAt, "scope", "ACCEPT_PROXY", "by", null,
                    "text", Rules.md(r.date) + " " + (r.category == null ? "도움" : r.category) + " 일정을 확정", "link", "/requests/" + r.id));
            if (r.status == ReqStatus.DONE && r.doneAt != null) out.add(Views.map("at", r.doneAt, "scope", "LOG_CONFIRM", "by", null,
                    "text", Rules.md(r.date) + " " + (r.category == null ? "도움" : r.category) + " 활동 일지 확인", "link", "/requests/" + r.id));
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDateTime) m.get("at")).reversed());
        return out.size() > 10 ? new ArrayList<>(out.subList(0, 10)) : out;
    }

    /** 범위·동의 방법 목록(화면 칩용) */
    public static Map<String, Object> meta() {
        List<Map<String, Object>> sc = new ArrayList<>(), me = new ArrayList<>();
        SCOPES.forEach((c, l) -> sc.add(Views.map("code", c, "label", l)));
        METHODS.forEach((c, l) -> me.add(Views.map("code", c, "label", l)));
        return Views.map("scopes", sc, "consentMethods", me, "maxDays", 365, "soonDays", SOON_DAYS);
    }

    // ---------------- 담당자: 목록·상세 ----------------
    @Transactional(readOnly = true)
    public Map<String, Object> listForManager(User mgr) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> items = new ArrayList<>();
        int active = 0, soon = 0, expired = 0, pending = 0, none = 0;
        for (Elder e : elders.findByManagerId(mgr.id).stream().filter(x -> x.status == ElderStatus.ACTIVE).sorted(Comparator.comparing((Elder x) -> x.id)).toList()) {
            Delegation d = current(e.id);
            Map<String, Object> row;
            if (d == null) {
                none++;
                row = Views.map("id", null, "elderId", e.id, "displayName", e.alias + " 님", "ageGroup", e.ageGroup, "dong", e.dong,
                        "status", "NONE", "statusLabel", statusLabel("NONE", false), "scopes", List.of(), "scopeCount", 0,
                        "appLinked", linkedUserId(e.id) != null, "expiringSoon", false);
            } else {
                row = view(d, e, true);
                String st = (String) row.get("status");
                if ("ACTIVE".equals(st)) { active++; if (Boolean.TRUE.equals(row.get("expiringSoon"))) soon++; }
                else if ("PENDING".equals(st)) pending++;
                else if ("EXPIRED".equals(st)) expired++;
                else none++;
            }
            items.add(row);
        }
        long newReq = pendingRequests(mgr).size();
        Map<String, Object> out = Views.map("items", items,
                "counts", Views.map("total", items.size(), "active", active, "expiringSoon", soon, "expired", expired, "pending", pending, "none", none, "requests", newReq),
                "today", today.toString(), "notice", "위임 기간이 끝나기 " + SOON_DAYS + "일 전부터 '만료 임박'으로 보여요. 본인(또는 보호자)에게 다시 동의를 받아 주세요.");
        out.putAll(meta());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(User mgr, Long id) {
        Delegation d = myDelegation(mgr, id);
        return detailOf(d, elders.findById(d.elderId).orElse(null));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ofElder(User mgr, Long elderId) {
        Elder e = myElder(mgr, elderId);
        Delegation d = current(e.id);
        Map<String, Object> m = d == null ? Views.map("id", null, "elderId", e.id, "displayName", e.alias + " 님", "ageGroup", e.ageGroup, "dong", e.dong,
                "status", "NONE", "statusLabel", statusLabel("NONE", false), "scopes", List.of(), "history", history(e.id), "acts", acts(e.id)) : detailOf(d, e);
        Long uid = linkedUserId(e.id);
        m.put("appLinked", uid != null);
        m.put("guardianCandidates", guardians(uid));
        m.put("canCreate", e.status == ElderStatus.ACTIVE && (d == null || !open(d, LocalDate.now())));
        return m;
    }

    private Map<String, Object> detailOf(Delegation d, Elder e) {
        Map<String, Object> m = view(d, e, true);
        m.put("history", history(d.elderId));
        m.put("acts", acts(d.elderId));
        m.put("actsNote", "기록은 본인·가족도 볼 수 있어요. 일정 확정과 일지 확인은 누른 사람을 따로 저장하지 않아요.");
        return m;
    }

    // ---------------- 담당자: 새 위임 등록 (O-DLG-03) ----------------
    public Map<String, Object> create(User mgr, CreateCmd c) {
        if (c == null) throw ApiException.bad("INVALID_INPUT", "내용을 채워 주세요");
        Elder e = myElder(mgr, c.elderId());
        if (e.status != ElderStatus.ACTIVE) throw ApiException.bad("ELDER_ENDED", "지원이 종료된 대상이에요");
        LocalDate today = LocalDate.now();
        Delegation cur = current(e.id);
        if (cur != null && open(cur, today))
            throw ApiException.conflict("ALREADY_EXISTS", "이미 " + statusLabel(statusOf(cur, today), false) + "인 위임이 있어요. 바꾸려면 그 위임을 고치거나 회수한 뒤 등록해 주세요");
        String method = c.consentMethod() == null ? "" : c.consentMethod();
        if (!METHODS.containsKey(method)) throw ApiException.bad("INVALID_METHOD", "동의 방법을 골라 주세요 (앱 · 서면 · 보호자)");
        List<String> scopes = cleanScopes(c.scopes());
        LocalDate start = c.startDate() == null ? today : c.startDate();
        LocalDate end = c.endDate() == null ? start.plusYears(1).minusDays(1) : c.endDate();
        checkPeriod(start, end, today);
        Long subject = linkedUserId(e.id);

        Delegation d = new Delegation();
        d.elderId = e.id; d.orgId = e.orgId; d.createdBy = mgr.id; d.userId = subject;
        d.consentMethod = method; d.scopes = new ArrayList<>(scopes); d.startDate = start; d.endDate = end;
        boolean confirmed = Boolean.TRUE.equals(c.consentConfirmed());
        if ("APP".equals(method)) {
            if (subject == null) throw ApiException.bad("NO_APP_ACCOUNT", "앱으로 연결된 분만 앱 동의를 요청할 수 있어요. 서면 동의나 보호자 동의로 등록해 주세요");
            d.status = "PENDING";
        } else if ("GUARDIAN".equals(method) && c.guardianUserId() != null) {
            if (guardians(subject).stream().noneMatch(g -> c.guardianUserId().equals(g.get("userId"))))
                throw ApiException.bad("GUARDIAN_NOT_LINKED", "본인 앱 계정과 가족으로 연결된 계정만 보호자로 고를 수 있어요");
            d.guardianUserId = c.guardianUserId();
            d.status = "PENDING";
        } else {
            // 서면 동의, 또는 앱 계정이 없는 보호자에게 직접 받은 동의: 담당자가 "동의를 받았음"을 확인해야 등록된다(사본은 저장하지 않는다)
            if (!confirmed) throw ApiException.bad("CONSENT_REQUIRED", "본인(또는 보호자)에게 설명하고 동의를 받았는지 확인(체크)해 주세요");
            d.consentDate = consentDate(c.consentDate(), today);
            d.status = "ACTIVE";
        }
        delegations.save(d);
        log(d, e.id, mgr, "MANAGER", "CREATED", METHODS.get(method) + " · " + scopeNames(scopes) + " · " + start + " ~ " + end);
        if ("PENDING".equals(d.status)) {
            Long to = d.guardianUserId != null ? d.guardianUserId : d.userId;
            noti.send(to, "DELEGATION", mgr.name + " 담당자가 권한 위임 동의를 요청했어요", scopeNames(scopes) + " · " + end + "까지 · 맡길 일을 확인하고 동의해 주세요", LINK_USER);
        } else tellOwners(d, null, "권한 위임이 등록됐어요", scopeNames(scopes) + " · " + end + "까지");
        return detailOf(d, e);
    }

    private static void checkPeriod(LocalDate start, LocalDate end, LocalDate today) {
        if (end.isBefore(start) || end.isBefore(today)) throw ApiException.bad("INVALID_PERIOD", "끝나는 날은 시작일과 오늘보다 뒤여야 해요");
        if (start.isAfter(today.plusDays(30))) throw ApiException.bad("INVALID_PERIOD", "시작일은 오늘부터 30일 안으로 정해 주세요");
        if (end.isAfter(start.plusYears(1))) throw ApiException.bad("PERIOD_TOO_LONG", "위임 기간은 최대 1년이에요");
    }

    private static LocalDate consentDate(LocalDate given, LocalDate today) {
        LocalDate d = given == null ? today : given;
        if (d.isAfter(today)) throw ApiException.bad("INVALID_CONSENT_DATE", "동의받은 날은 오늘이나 그 전이어야 해요");
        if (d.isBefore(today.minusDays(90))) throw ApiException.bad("INVALID_CONSENT_DATE", "동의받은 지 90일이 넘었어요. 다시 동의를 받아 주세요");
        return d;
    }

    // ---------------- 담당자: 범위·기간 바꾸기(서면·보호자 동의만) ----------------
    public Map<String, Object> update(User mgr, Long id, UpdateCmd c) {
        Delegation d = myDelegation(mgr, id);
        if (!"ACTIVE".equals(d.status)) throw ApiException.conflict("INVALID_STATE", "유효한 위임만 바꿀 수 있어요");
        if ("APP".equals(d.consentMethod)) throw ApiException.conflict("APP_OWNED", "앱으로 동의한 분은 본인이 앱에서만 바꿀 수 있어요");
        if (c == null || (c.scopes() == null && c.endDate() == null)) throw ApiException.bad("INVALID_INPUT", "바꿀 내용을 골라 주세요");
        if (!Boolean.TRUE.equals(c.consentConfirmed()))
            throw ApiException.bad("CONSENT_REQUIRED", "바뀐 내용으로 본인(또는 보호자)에게 다시 동의를 받았는지 확인(체크)해 주세요");
        LocalDate today = LocalDate.now();
        List<String> changes = new ArrayList<>();
        if (c.scopes() != null) {
            List<String> sc = cleanScopes(c.scopes());
            if (!sc.equals(d.scopes)) { changes.add(scopeNames(d.scopes) + " → " + scopeNames(sc)); d.scopes = new ArrayList<>(sc); }
        }
        if (c.endDate() != null && !c.endDate().equals(d.endDate)) {
            if (c.endDate().isBefore(today)) throw ApiException.bad("INVALID_PERIOD", "끝나는 날은 오늘이나 그 뒤여야 해요");
            if (c.endDate().isAfter(today.plusYears(1))) throw ApiException.bad("PERIOD_TOO_LONG", "위임 기간은 오늘부터 최대 1년이에요");
            changes.add("기간 " + d.endDate + " → " + c.endDate());
            d.endDate = c.endDate();
        }
        if (changes.isEmpty()) throw ApiException.bad("NO_CHANGE", "바뀐 내용이 없어요");
        d.consentDate = consentDate(c.consentDate(), today);
        log(d, d.elderId, mgr, "MANAGER", "SCOPE_CHANGED", String.join(" / ", changes));
        tellOwners(d, null, "권한 위임 내용이 바뀌었어요", String.join(" / ", changes));
        return detailOf(d, elders.findById(d.elderId).orElse(null));
    }

    public Map<String, Object> revokeByManager(User mgr, Long id) {
        Delegation d = myDelegation(mgr, id);
        revoke(d, mgr, "MANAGER");
        tellOwners(d, null, "권한 위임이 해지됐어요", mgr.name + " 담당자가 해지했어요");
        return detailOf(d, elders.findById(d.elderId).orElse(null));
    }

    private void revoke(Delegation d, User actor, String role) {
        if (!open(d, LocalDate.now())) throw ApiException.conflict("INVALID_STATE", "이미 끝났거나 회수된 위임이에요");
        d.status = "REVOKED";
        d.revokedAt = LocalDateTime.now();
        d.revokedBy = actor.id;
        log(d, d.elderId, actor, role, "REVOKED", null);
    }

    /** 기간 연장 요청: 앱 계정(본인·보호자)에게 알림만 보낸다. 연장은 그 사람이 앱에서 눌러야 된다. */
    public Map<String, Object> askExtend(User mgr, Long id) {
        Delegation d = myDelegation(mgr, id);
        if (!"ACTIVE".equals(d.status)) throw ApiException.conflict("INVALID_STATE", "유효했던 위임만 연장을 요청할 수 있어요");
        if (d.userId == null && d.guardianUserId == null)
            throw ApiException.conflict("NO_APP_ACCOUNT", "앱 계정이 없는 분이에요. 다시 동의를 받은 뒤 기간을 직접 바꿔 주세요");
        log(d, d.elderId, mgr, "MANAGER", "EXTEND_ASKED", "끝나는 날 " + d.endDate);
        tellOwners(d, null, mgr.name + " 담당자가 위임 기간 연장을 요청했어요", "지금 기간은 " + d.endDate + "까지예요. 연장하려면 눌러서 동의해 주세요");
        return Views.map("ok", true, "id", d.id, "message", "연장 동의 요청을 보냈어요");
    }

    // ---------------- 담당자: 받은 요청 ----------------
    private List<DelegationRequest> pendingRequests(User mgr) {
        if (mgr.orgId == null) return List.of();
        return reqs.findByOrgId(mgr.orgId).stream().filter(r -> "PENDING".equals(r.status) && visibleTo(r, mgr)).toList();
    }

    /** 새 연결은 그 기관 담당자 누구나, 범위 변경은 그 대상의 담당자만 본다. */
    private boolean visibleTo(DelegationRequest r, User mgr) {
        if (!Objects.equals(r.orgId, mgr.orgId)) return false;
        if (r.elderId == null) return true;
        return elders.findById(r.elderId).map(e -> mgr.id.equals(e.managerId)).orElse(false);
    }

    private Map<String, Object> requestView(DelegationRequest r, boolean forManager) {
        User u = users.findById(r.userId).orElse(null);
        Elder e = r.elderId == null ? null : elders.findById(r.elderId).orElse(null);
        List<String> before = r.delegationId == null ? List.of() : delegations.findById(r.delegationId).map(d -> d.scopes).orElse(List.of());
        Map<String, Object> m = Views.map("id", r.id, "kind", r.kind, "kindLabel", NEW_LINK.equals(r.kind) ? "새 연결" : "범위 변경",
                "status", r.status, "scopes", r.scopes, "scopeLabels", r.scopes.stream().map(s -> SCOPES.getOrDefault(s, s)).toList(),
                "applied", Boolean.TRUE.equals(r.applied), "message", r.message, "rejectReason", r.rejectReason,
                "delegationId", r.delegationId, "elderId", r.elderId, "createdAt", r.createdAt, "answeredAt", r.answeredAt);
        if (forManager) {
            m.put("displayName", e != null ? e.alias + " 님" : u == null ? "탈퇴한 사용자" : u.name + " 님");
            m.put("ageGroup", e != null ? e.ageGroup : u == null ? null : u.ageGroup);
            m.put("dong", e != null ? e.dong : u == null ? null : u.area);
            m.put("currentScopes", before);
            m.put("canReject", "PENDING".equals(r.status) && !Boolean.TRUE.equals(r.applied));
            m.put("acceptLabel", NEW_LINK.equals(r.kind) ? "수락하고 연결" : "확인");
        } else {
            m.put("orgName", orgs.findById(r.orgId).map(o -> o.name).orElse(null));
        }
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> requestsForManager(User mgr, String status) {
        List<Map<String, Object>> list = mgr.orgId == null ? List.of() : reqs.findByOrgId(mgr.orgId).stream()
                .filter(r -> visibleTo(r, mgr) && (status == null || status.isBlank() || "all".equalsIgnoreCase(status) ? true : status.equalsIgnoreCase(r.status)))
                .sorted(Comparator.comparing((DelegationRequest r) -> r.id).reversed()).map(r -> requestView(r, true)).toList();
        return Views.map("items", list, "pendingCount", pendingRequests(mgr).size(),
                "notice", "앱을 쓰는 분이 '담당 기관 연결'이나 '맡긴 일 바꾸기'를 하면 여기로 와요.");
    }

    public Map<String, Object> answer(User mgr, Long id, AnswerCmd c) {
        DelegationRequest r = reqs.findById(id).orElseThrow(() -> ApiException.notFound("위임 요청을 찾을 수 없어요"));
        if (!visibleTo(r, mgr)) throw ApiException.forbidden("우리 기관·내 담당 대상의 요청이 아니에요");
        if (!"PENDING".equals(r.status)) throw ApiException.conflict("INVALID_STATE", "이미 처리한 요청이에요");
        boolean ok = c != null && Boolean.TRUE.equals(c.accept());
        User u = users.findById(r.userId).orElse(null);
        LocalDate today = LocalDate.now();
        if (!ok) {
            if (Boolean.TRUE.equals(r.applied)) throw ApiException.conflict("ALREADY_APPLIED", "맡긴 일을 줄이는 변경은 본인 뜻이라 이미 반영됐어요. 확인만 눌러 주세요");
            String reason = c == null || c.reason() == null || c.reason().isBlank() ? null : c.reason().trim();
            if (reason != null && reason.length() > 200) throw ApiException.bad("TOO_LONG", "사유는 200자 안으로 적어 주세요");
            if (reason != null && !VoiceSafety.scrub(reason).removed().isEmpty()) throw ApiException.bad("PII_NOT_ALLOWED", "연락처·주소 같은 개인정보는 적지 않아요");
            r.status = "REJECTED";
            r.rejectReason = reason;
            if (r.elderId != null) log(null, r.elderId, mgr, "MANAGER", "REQUEST_REJECTED", reason);
            noti.send(r.userId, "DELEGATION", NEW_LINK.equals(r.kind) ? "담당 기관 연결 요청이 거절됐어요" : "맡긴 일 바꾸기 요청이 거절됐어요", reason, LINK_USER);
        } else if (NEW_LINK.equals(r.kind)) {
            if (u == null || u.status != UserStatus.ACTIVE) throw ApiException.conflict("USER_UNAVAILABLE", "요청한 분의 계정을 쓸 수 없어요");
            String alias = c.alias() == null ? "" : c.alias().trim();
            if (alias.isEmpty() || alias.length() > 10) throw ApiException.bad("INVALID_ALIAS", "관리 대상으로 부를 별칭을 1~10자로 적어 주세요");
            String dong = c.dong() != null && !c.dong().isBlank() ? c.dong().trim() : u.area;
            if (dong == null || !dong.endsWith("동")) throw ApiException.bad("INVALID_DONG", "지역은 동 단위로만 적어 주세요 (예: 행복동)");
            if (!VoiceSafety.scrub(alias + " " + dong).removed().isEmpty()) throw ApiException.bad("PII_NOT_ALLOWED", "연락처·상세 주소 같은 개인정보는 적지 않아요");
            Elder e = new Elder();
            e.alias = alias; e.dong = dong; e.ageGroup = u.ageGroup; e.orgId = mgr.orgId; e.managerId = mgr.id;
            // 본인이 앱에서 직접 연결을 요청했으므로 서비스 이용 동의가 있는 것으로 기록한다
            e.serviceConsentAt = LocalDateTime.now(); e.serviceConsentBy = mgr.id;
            elders.save(e);
            Delegation d = new Delegation();
            d.elderId = e.id; d.orgId = mgr.orgId; d.createdBy = mgr.id; d.userId = u.id; d.consentMethod = "APP";
            d.consentDate = r.createdAt == null ? today : r.createdAt.toLocalDate();
            d.scopes = new ArrayList<>(cleanScopes(r.scopes)); d.startDate = today; d.endDate = today.plusYears(1).minusDays(1); d.status = "ACTIVE";
            delegations.save(d);
            r.status = "ACCEPTED"; r.elderId = e.id; r.delegationId = d.id;
            log(d, e.id, mgr, "MANAGER", "LINK_ACCEPTED", scopeNames(d.scopes) + " · " + d.endDate + "까지");
            noti.send(u.id, "DELEGATION", "담당 기관과 연결됐어요", mgr.name + " 담당자 · " + scopeNames(d.scopes) + " · " + d.endDate + "까지", LINK_USER);
        } else {
            Delegation d = r.delegationId == null ? null : delegations.findById(r.delegationId).orElse(null);
            if (d == null || !"ACTIVE".equals(statusOf(d, today))) throw ApiException.conflict("INVALID_STATE", "유효한 위임이 없어 반영할 수 없어요");
            if (!Boolean.TRUE.equals(r.applied)) {
                List<String> sc = cleanScopes(r.scopes);
                log(d, d.elderId, mgr, "MANAGER", "SCOPE_CHANGED", scopeNames(d.scopes) + " → " + scopeNames(sc) + " (본인 요청 수락)");
                d.scopes = new ArrayList<>(sc);
                noti.send(r.userId, "DELEGATION", "맡긴 일이 바뀌었어요", scopeNames(sc), LINK_USER);
            }
            r.status = "ACCEPTED";
        }
        r.answeredBy = mgr.id;
        r.answeredAt = LocalDateTime.now();
        return requestView(r, true);
    }

    // ---------------- 사용자(본인·보호자) ----------------
    private static void userOnly(User me) {
        if (me.role != Role.USER) throw ApiException.forbidden("사용자 계정만 쓸 수 있어요");
        if (me.status != UserStatus.ACTIVE) throw ApiException.forbidden("지금은 쓸 수 없는 계정이에요");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> mine(User me) {
        userOnly(me);
        List<Map<String, Object>> mine = new ArrayList<>(), guard = new ArrayList<>();
        for (Delegation d : delegations.findByUserId(me.id).stream().sorted(Comparator.comparing((Delegation d) -> d.id).reversed()).toList())
            mine.add(userView(d, me));
        for (Delegation d : delegations.findByGuardianUserId(me.id).stream().sorted(Comparator.comparing((Delegation d) -> d.id).reversed()).toList()) {
            Map<String, Object> v = userView(d, me);
            v.put("subjectName", d.userId == null ? null : users.findById(d.userId).map(u -> u.name + " 님").orElse(null));
            guard.add(v);
        }
        Map<String, Object> out = Views.map("mine", mine, "asGuardian", guard,
                "requests", reqs.findByUserId(me.id).stream().sorted(Comparator.comparing((DelegationRequest r) -> r.id).reversed()).map(r -> requestView(r, false)).toList());
        out.putAll(meta());
        return out;
    }

    private Map<String, Object> userView(Delegation d, User me) {
        Map<String, Object> v = view(d, elders.findById(d.elderId).orElse(null), false);
        boolean decider = "GUARDIAN".equals(d.consentMethod) ? me.id.equals(d.guardianUserId) : "APP".equals(d.consentMethod) && me.id.equals(d.userId);
        v.put("canConsent", "PENDING".equals(d.status) && decider);
        v.put("canRevoke", open(d, LocalDate.now()));
        v.put("canExtend", "ACTIVE".equals(d.status) && decider);
        v.put("canChangeScopes", "ACTIVE".equals(v.get("status")) && "APP".equals(d.consentMethod) && me.id.equals(d.userId));
        v.put("history", history(d.elderId));
        v.put("acts", acts(d.elderId));
        return v;
    }

    private Delegation ownDelegation(User me, Long id) {
        userOnly(me);
        Delegation d = delegations.findById(id).orElseThrow(() -> ApiException.notFound("위임을 찾을 수 없어요"));
        if (!me.id.equals(d.userId) && !me.id.equals(d.guardianUserId)) throw ApiException.forbidden("내 위임이 아니에요");
        return d;
    }

    private static String roleOf(Delegation d, User me) { return me.id.equals(d.userId) ? "USER" : "GUARDIAN"; }

    public Map<String, Object> request(User me, RequestCmd c) {
        userOnly(me);
        if (c == null || c.kind() == null) throw ApiException.bad("INVALID_INPUT", "요청 종류를 골라 주세요");
        List<String> scopes = cleanScopes(c.scopes());
        String msg = c.message() == null || c.message().isBlank() ? null : c.message().trim();
        if (msg != null && msg.length() > 300) throw ApiException.bad("TOO_LONG", "남길 말은 300자 안으로 적어 주세요");
        if (msg != null && !VoiceSafety.scrub(msg).removed().isEmpty()) throw ApiException.bad("PII_NOT_ALLOWED", "연락처·주소 같은 개인정보는 적지 않아요");
        LocalDate today = LocalDate.now();
        DelegationRequest r = new DelegationRequest();
        r.userId = me.id; r.scopes = new ArrayList<>(scopes); r.message = msg;
        if (NEW_LINK.equals(c.kind())) {
            Organization o = orgs.findById(c.orgId() == null ? -1 : c.orgId()).orElseThrow(() -> ApiException.notFound("기관을 찾을 수 없어요"));
            if (o.status != OrgStatus.APPROVED) throw ApiException.bad("ORG_NOT_APPROVED", "승인된 기관에만 연결을 요청할 수 있어요");
            if (delegations.findByUserId(me.id).stream().anyMatch(d -> o.id.equals(d.orgId) && open(d, today)))
                throw ApiException.conflict("ALREADY_LINKED", "이미 이 기관과 연결돼 있어요");
            if (reqs.findByUserId(me.id).stream().anyMatch(x -> "PENDING".equals(x.status) && NEW_LINK.equals(x.kind) && o.id.equals(x.orgId)))
                throw ApiException.conflict("DUPLICATE", "이미 연결을 요청했어요. 담당자의 답을 기다려 주세요");
            r.kind = NEW_LINK; r.orgId = o.id;
            reqs.save(r);
            users.findByOrgId(o.id).stream().filter(m -> m.role == Role.ORG && m.status == UserStatus.ACTIVE)
                    .forEach(m -> noti.send(m.id, "DELEGATION", me.name + " 님이 담당 기관 연결을 요청했어요", scopeNames(scopes), LINK_ORG + "?tab=requests"));
        } else if (SCOPE_CHANGE.equals(c.kind())) {
            Delegation d = ownDelegation(me, c.delegationId() == null ? -1 : c.delegationId());
            if (!me.id.equals(d.userId) || !"APP".equals(d.consentMethod)) throw ApiException.forbidden("앱으로 동의한 본인만 맡긴 일을 바꿀 수 있어요");
            if (!"ACTIVE".equals(statusOf(d, today))) throw ApiException.conflict("INVALID_STATE", "유효한 위임만 바꿀 수 있어요");
            if (scopes.equals(d.scopes)) throw ApiException.bad("NO_CHANGE", "바뀐 내용이 없어요");
            if (reqs.findByUserId(me.id).stream().anyMatch(x -> "PENDING".equals(x.status) && d.id.equals(x.delegationId)))
                throw ApiException.conflict("DUPLICATE", "아직 확인되지 않은 변경 요청이 있어요");
            r.kind = SCOPE_CHANGE; r.orgId = d.orgId; r.delegationId = d.id; r.elderId = d.elderId;
            boolean narrowing = d.scopes.containsAll(scopes);
            if (narrowing) {
                // 맡긴 일을 줄이는 것은 본인 뜻이므로 바로 반영한다. 담당자는 확인만 한다.
                log(d, d.elderId, me, "USER", "SCOPE_CHANGED", scopeNames(d.scopes) + " → " + scopeNames(scopes));
                d.scopes = new ArrayList<>(scopes);
                r.applied = true;
            }
            reqs.save(r);
            tellManager(d.elderId, me.name + " 님이 맡긴 일을 바꿨어요", scopeNames(scopes) + (narrowing ? " · 바로 반영됐어요" : " · 확인해 주세요"));
        } else throw ApiException.bad("INVALID_KIND", "요청 종류는 NEW_LINK 또는 SCOPE_CHANGE 예요");
        return requestView(r, false);
    }

    public Map<String, Object> cancelRequest(User me, Long id) {
        userOnly(me);
        DelegationRequest r = reqs.findById(id).orElseThrow(() -> ApiException.notFound("위임 요청을 찾을 수 없어요"));
        if (!me.id.equals(r.userId)) throw ApiException.forbidden("내 요청이 아니에요");
        if (!"PENDING".equals(r.status) || Boolean.TRUE.equals(r.applied)) throw ApiException.conflict("INVALID_STATE", "취소할 수 있는 요청이 아니에요");
        r.status = "CANCELLED";
        return requestView(r, false);
    }

    /** 앱 동의·보호자 동의 답하기. 본인은 맡길 일을 직접 고를 수 있다. */
    public Map<String, Object> consent(User me, Long id, ConsentCmd c) {
        Delegation d = ownDelegation(me, id);
        boolean decider = "GUARDIAN".equals(d.consentMethod) ? me.id.equals(d.guardianUserId) : me.id.equals(d.userId);
        if (!decider) throw ApiException.forbidden("동의를 요청받은 사람만 답할 수 있어요");
        if (!"PENDING".equals(d.status)) throw ApiException.conflict("INVALID_STATE", "동의를 기다리는 위임이 아니에요");
        LocalDate today = LocalDate.now();
        if (c != null && Boolean.TRUE.equals(c.accept())) {
            if (d.endDate != null && d.endDate.isBefore(today)) throw ApiException.conflict("EXPIRED", "기간이 지난 요청이에요. 담당자에게 다시 요청해 달라고 해 주세요");
            if (c.scopes() != null) d.scopes = new ArrayList<>(cleanScopes(c.scopes()));
            d.status = "ACTIVE";
            d.consentDate = today;
            if (d.startDate == null || d.startDate.isBefore(today)) d.startDate = today;
            log(d, d.elderId, me, roleOf(d, me), "CONSENTED", scopeNames(d.scopes) + " · " + d.endDate + "까지");
            tellManager(d.elderId, me.name + " 님이 권한 위임에 동의했어요", scopeNames(d.scopes));
        } else {
            d.status = "REJECTED";
            log(d, d.elderId, me, roleOf(d, me), "CONSENT_REJECTED", null);
            tellManager(d.elderId, me.name + " 님이 권한 위임에 동의하지 않았어요", null);
        }
        return userView(d, me);
    }

    public Map<String, Object> revokeByUser(User me, Long id) {
        Delegation d = ownDelegation(me, id);
        revoke(d, me, roleOf(d, me));
        tellManager(d.elderId, me.name + " 님이 권한 위임을 회수했어요", "이제 이 분 대신 처리할 수 없어요");
        tellOwners(d, me.id, "권한 위임이 회수됐어요", me.name + " 님이 회수했어요");
        return userView(d, me);
    }

    public Map<String, Object> extend(User me, Long id, ExtendCmd c) {
        Delegation d = ownDelegation(me, id);
        boolean decider = "GUARDIAN".equals(d.consentMethod) ? me.id.equals(d.guardianUserId) : "APP".equals(d.consentMethod) && me.id.equals(d.userId);
        if (!decider) throw ApiException.forbidden("동의한 사람만 기간을 연장할 수 있어요");
        if (!"ACTIVE".equals(d.status)) throw ApiException.conflict("INVALID_STATE", "유효했던 위임만 연장할 수 있어요");
        LocalDate today = LocalDate.now();
        LocalDate end = c == null || c.endDate() == null ? today.plusYears(1).minusDays(1) : c.endDate();
        if (end.isBefore(today) || (d.endDate != null && !end.isAfter(d.endDate))) throw ApiException.bad("INVALID_PERIOD", "지금 끝나는 날보다 뒤로 정해 주세요");
        if (end.isAfter(today.plusYears(1))) throw ApiException.bad("PERIOD_TOO_LONG", "위임 기간은 오늘부터 최대 1년이에요");
        log(d, d.elderId, me, roleOf(d, me), "EXTENDED", d.endDate + " → " + end);
        d.endDate = end;
        d.consentDate = today;
        tellManager(d.elderId, me.name + " 님이 위임 기간을 연장했어요", end + "까지");
        return userView(d, me);
    }
}
