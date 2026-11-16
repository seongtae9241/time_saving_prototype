package com.timesavings.service;

import com.timesavings.ai.RuleEngine;
import com.timesavings.ai.VoiceSafety;
import com.timesavings.config.ApiException;
import com.timesavings.domain.Enums.*;
import com.timesavings.domain.GroupParticipant;
import com.timesavings.domain.GroupPost;
import com.timesavings.domain.User;
import com.timesavings.repo.GroupParticipantRepo;
import com.timesavings.repo.GroupPostRepo;
import com.timesavings.repo.OrganizationRepo;
import com.timesavings.repo.UserRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 기관 공고 3종(2026-11-16 담당자 추가 기능, 프로토타입 O-POST-05·07·08·09): 봉사자 모집 · 단체 봉사 · 해 드려요.
 * 새 표를 만들지 않고 단체 봉사 표(group_posts, group_participants)를 넓혀 쓴다(org_kind 가 있으면 기관 공고).
 * - 신청(APPLIED) → 담당자가 수락(JOINED)·거절(REJECTED). 선착순 자동 확정이 아니다(결정은 사람이).
 * - 봉사자 모집·단체 봉사: 활동한 날 담당자가 참석을 확인하면 참석한 사람마다 활동 시간 × 1.0 이 적립된다(GroupService.complete 그대로). 차감 없음.
 * - 해 드려요: 기관 직원이 해 주는 도움이라 적립·차감이 없다. 담당자가 방문 날짜를 정해 확정하고, 다녀온 뒤 방문 완료(SERVED)만 기록한다.
 * 기관 행사(org_events)는 시간과 상관없는 안내라 따로 둔다(프로토타입도 따로).
 */
@Service
@Transactional
public class OrgNoticeService {
    public static final String VOLUNTEER = "VOLUNTEER", GROUP = "GROUP", SERVICE = "SERVICE";
    public static final List<String> KINDS = List.of(VOLUNTEER, GROUP, SERVICE);
    public static final List<String> TARGETS = List.of("ANYONE", "SENIOR65", "ALONE");
    public static final int MAX_HEADS = 10;
    private static final String LINK_ORG = "/o/notices", LINK_USER = "/org-notices/";

    private final GroupPostRepo posts;
    private final GroupParticipantRepo parts;
    private final UserRepo users;
    private final OrganizationRepo orgs;
    private final GroupService groups;
    private final VolunteerGate gate;
    private final NotificationService noti;

    public OrgNoticeService(GroupPostRepo posts, GroupParticipantRepo parts, UserRepo users, OrganizationRepo orgs, GroupService groups,
                            VolunteerGate gate, NotificationService noti) {
        this.posts = posts; this.parts = parts; this.users = users; this.orgs = orgs; this.groups = groups; this.gate = gate; this.noti = noti;
    }

    public record Cmd(String kind, String title, String detail, String category, LocalDate date, Double startHour, Double hours, String area,
                      String place, Integer capacity, String whenText, String target, String supplies, Boolean teamAllowed) {}
    public record ApplyCmd(String message, Integer headCount) {}
    public record DecideCmd(LocalDate visitDate, Double visitStartHour, Double visitHours, String reason) {}

    public static String kindLabel(String k) {
        return VOLUNTEER.equals(k) ? "봉사자 모집" : GROUP.equals(k) ? "단체 봉사" : SERVICE.equals(k) ? "해 드려요" : k;
    }

    static String targetLabel(String t) { return "SENIOR65".equals(t) ? "65세 이상" : "ALONE".equals(t) ? "혼자 사는 분" : "누구나"; }

    /** 종류 안내(프로토타입 O-POST-07 고르기 창) */
    public static List<Map<String, Object>> kindsMeta() {
        return List.of(
                Views.map("code", VOLUNTEER, "label", "봉사자 모집", "title", "봉사자 1~2명이 필요해요", "desc", "산책·배식처럼 한두 명이 와서 도와주면 되는 일",
                        "timeRule", "봉사자는 활동 시간만큼 적립 · 기관 공고라 차감 없음", "minCapacity", 1, "maxCapacity", 2, "earns", true),
                Views.map("code", GROUP, "label", "단체 봉사", "title", "여러 명이 함께해요", "desc", "김장·생신잔치처럼 여러 명이 같이 하는 일",
                        "timeRule", "참여자 각자 활동 시간만큼 적립 · 차감 없음", "minCapacity", GroupService.MIN_PEOPLE, "maxCapacity", GroupService.MAX_PEOPLE, "earns", true),
                Views.map("code", SERVICE, "label", "해 드려요", "title", "우리 기관이 해 드려요", "desc", "기관이 동네 분들께 해 줄 수 있는 도움 · 필요한 분이 신청",
                        "timeRule", "기관 직원이 하는 일이라 적립·차감 없음", "minCapacity", 1, "maxCapacity", GroupService.MAX_PEOPLE, "earns", false));
    }

    // ---------------- 권한 ----------------
    private static void requireOrg(User u) {
        if (u == null || u.role != Role.ORG || u.orgId == null) throw ApiException.forbidden("기관 담당자만 쓸 수 있어요");
    }

    private GroupPost notice(Long id) {
        GroupPost g = posts.findById(id).orElseThrow(() -> ApiException.notFound("기관 공고를 찾을 수 없어요"));
        if (g.orgKind == null) throw ApiException.notFound("기관 공고를 찾을 수 없어요");
        return g;
    }

    /** 우리 기관이 올린 기관 공고만(같은 기관 담당자는 함께 본다 — 단체 봉사와 같은 기준). */
    private GroupPost myNotice(User mgr, Long id) {
        requireOrg(mgr);
        GroupPost g = notice(id);
        if (!mgr.orgId.equals(g.orgId)) throw ApiException.forbidden("우리 기관이 올린 공고가 아니에요");
        return g;
    }

    private List<GroupParticipant> apps(GroupPost g) {
        return parts.findByGroupPostId(g.id).stream().filter(p -> !"LEFT".equals(p.status)).sorted(Comparator.comparing((GroupParticipant p) -> p.id)).toList();
    }

    private static boolean confirmed(GroupParticipant p) { return "JOINED".equals(p.status) || "ATTENDED".equals(p.status) || "ABSENT".equals(p.status) || "SERVED".equals(p.status); }

    private static int heads(List<GroupParticipant> list) { return list.stream().filter(OrgNoticeService::confirmed).mapToInt(GroupParticipant::heads).sum(); }

    private static boolean past(GroupPost g) { return g.date.isBefore(LocalDate.now()); }

    /** 신청을 받는 중인지: 열려 있고, 마감을 누르지 않았고, 날짜가 지나지 않았다. */
    private static boolean accepting(GroupPost g) { return "OPEN".equals(g.status) && g.closedAt == null && !past(g); }

    private static String clean(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static void noPii(String text) {
        if (!VoiceSafety.scrub(text).removed().isEmpty())
            throw ApiException.bad("PII_NOT_ALLOWED", "연락처·상세 주소 같은 개인정보는 적지 않아요. 장소는 기관·공공장소 이름만 적어 주세요");
    }

    // ---------------- 담당자: 올리기 (O-POST-07·08) ----------------
    public GroupPost create(User mgr, Cmd c) {
        requireOrg(mgr);
        if (c == null) throw ApiException.bad("INVALID_INPUT", "내용을 채워 주세요");
        String kind = c.kind() == null ? "" : c.kind();
        if (!KINDS.contains(kind)) throw ApiException.bad("INVALID_KIND", "공고 종류를 골라 주세요 (봉사자 모집 · 단체 봉사 · 해 드려요)");
        String title = clean(c.title()), detail = clean(c.detail()), place = clean(c.place()), when = clean(c.whenText()), supplies = clean(c.supplies());
        if (title == null) throw ApiException.bad("TITLE_REQUIRED", "제목을 적어 주세요");
        if (title.length() > 100) throw ApiException.bad("TOO_LONG", "제목은 100자 안으로 적어 주세요");
        if (detail != null && detail.length() > 1000) throw ApiException.bad("TOO_LONG", "내용은 1000자 안으로 적어 주세요");
        if (place != null && place.length() > 100) throw ApiException.bad("TOO_LONG", "장소는 100자 안으로 적어 주세요");
        if (when != null && when.length() > 100) throw ApiException.bad("TOO_LONG", "가능한 때는 100자 안으로 적어 주세요");
        if (supplies != null && supplies.length() > 100) throw ApiException.bad("TOO_LONG", "준비물은 100자 안으로 적어 주세요");
        if (!Rules.isCategory(c.category())) throw ApiException.bad("INVALID_CATEGORY", "도움 종류를 골라 주세요");
        String area = clean(c.area()) != null ? clean(c.area()) : clean(mgr.area);
        if (area == null) throw ApiException.bad("AREA_REQUIRED", "지역(동)을 골라 주세요");
        int cap = c.capacity() == null ? 0 : c.capacity();
        int min = GROUP.equals(kind) ? GroupService.MIN_PEOPLE : 1, max = VOLUNTEER.equals(kind) ? 2 : GroupService.MAX_PEOPLE;
        if (cap < min || cap > max) throw ApiException.bad("INVALID_CAPACITY", VOLUNTEER.equals(kind) ? "봉사자 모집은 1~2명이에요. 더 많이 필요하면 단체 봉사로 올려 주세요"
                : GROUP.equals(kind) ? "단체 봉사는 " + min + "~" + max + "명이에요. 한두 명이면 봉사자 모집으로 올려 주세요" : "받을 수 있는 인원은 1~" + max + "명으로 적어 주세요");
        LocalDate today = LocalDate.now();
        boolean service = SERVICE.equals(kind);
        LocalDate date = c.date() != null ? c.date() : service ? today.plusDays(30) : null;
        if (date == null || date.isBefore(today)) throw ApiException.bad("INVALID_DATE", service ? "신청을 받는 마지막 날은 오늘 이후로 정해 주세요" : "오늘 이후 날짜를 골라 주세요");
        double start = 0, hours = 0;
        if (!service || (c.startHour() != null && c.hours() != null)) {
            if (c.startHour() == null || c.hours() == null) throw ApiException.bad("INVALID_TIME", "시작 시각과 활동 시간을 정해 주세요");
            Rules.checkHours(c.startHour(), c.hours());
            start = c.startHour(); hours = c.hours();
        } else if (when == null) throw ApiException.bad("WHEN_REQUIRED", "가능한 때를 적어 주세요 (예: 평일 오후 2~5시)");
        String target = service ? (c.target() == null ? "ANYONE" : c.target()) : null;
        if (target != null && !TARGETS.contains(target)) throw ApiException.bad("INVALID_TARGET", "대상은 누구나 · 65세 이상 · 혼자 사는 분 중에서 골라 주세요");
        String text = String.join(" ", title, Objects.toString(detail, ""), Objects.toString(place, ""), Objects.toString(when, ""), Objects.toString(supplies, ""));
        // 봉사자가 하는 일에는 금지 요청(신체 돌봄·의료·금전·위험 작업) 필터를 똑같이 건다. 해 드려요는 기관 직원이 하는 일이라 걸지 않는다.
        if (!service) {
            RuleEngine.FilterResult f = RuleEngine.filter(text);
            if (f.level() == FilterLevel.BLOCK) throw ApiException.bad("FORBIDDEN_REQUEST", f.reason());
        }
        noPii(text);
        GroupPost g = new GroupPost();
        g.kind = service ? SERVICE : GroupService.RECRUIT;
        g.orgKind = kind;
        g.ownerId = mgr.id; g.orgId = mgr.orgId;
        g.title = title; g.detail = detail; g.category = c.category(); g.date = date; g.startHour = start; g.hours = hours;
        g.area = area; g.place = place; g.capacity = cap; g.whenText = when; g.target = target; g.supplies = supplies;
        g.teamAllowed = GROUP.equals(kind) ? Boolean.TRUE.equals(c.teamAllowed()) : null;
        return posts.save(g);
    }

    /** 신청 마감: 새 신청만 막는다. 확정된 사람의 활동·방문은 그대로 진행한다. 답하지 않은 신청은 그대로 남아 담당자가 답한다. */
    public GroupPost close(User mgr, Long id) {
        GroupPost g = myNotice(mgr, id);
        if (!"OPEN".equals(g.status)) throw ApiException.conflict("INVALID_STATE", "이미 끝났거나 내린 공고예요");
        if (g.closedAt != null) throw ApiException.conflict("ALREADY_CLOSED", "이미 신청을 마감한 공고예요");
        g.closedAt = LocalDateTime.now();
        g.closedBy = mgr.id;
        return g;
    }

    public GroupPost reopen(User mgr, Long id) {
        GroupPost g = myNotice(mgr, id);
        if (!"OPEN".equals(g.status) || g.closedAt == null || past(g)) throw ApiException.conflict("INVALID_STATE", "다시 열 수 있는 공고가 아니에요");
        g.closedAt = null; g.closedBy = null;
        return g;
    }

    /** 공고 내리기: 신청·확정한 사람 모두에게 알린다. */
    public GroupPost cancel(User mgr, Long id) {
        GroupPost g = myNotice(mgr, id);
        if (!"OPEN".equals(g.status)) throw ApiException.conflict("INVALID_STATE", "이미 끝났거나 내린 공고예요");
        g.status = "CANCELLED";
        for (GroupParticipant p : apps(g)) {
            if (!"APPLIED".equals(p.status) && !"JOINED".equals(p.status)) continue;
            noti.send(p.userId, "ORG_NOTICE", "기관 공고가 취소됐어요", g.title + " · " + orgName(g.orgId), LINK_USER + g.id);
        }
        return g;
    }

    // ---------------- 담당자: 신청 수락·거절 (O-POST-09) ----------------
    private GroupParticipant app(Long appId) {
        return parts.findById(appId).orElseThrow(() -> ApiException.notFound("신청을 찾을 수 없어요"));
    }

    public Map<String, Object> approve(User mgr, Long appId, DecideCmd c) {
        GroupParticipant p = app(appId);
        myNotice(mgr, p.groupPostId);
        GroupPost g = posts.findForUpdate(p.groupPostId).orElseThrow(); // 정원을 세고 바꾸는 동안 공고 행을 잠근다
        if (!"APPLIED".equals(p.status)) throw ApiException.conflict("INVALID_STATE", "답을 기다리는 신청이 아니에요");
        if (!"OPEN".equals(g.status)) throw ApiException.conflict("INVALID_STATE", "이미 끝났거나 내린 공고예요");
        boolean service = SERVICE.equals(g.orgKind);
        if (!service && past(g)) throw ApiException.conflict("INVALID_STATE", "날짜가 지난 공고예요");
        int now = heads(apps(g));
        if (now + p.heads() > g.capacity) throw ApiException.conflict("FULL", "정원(" + g.capacity + "명)을 넘어요. 지금 확정 " + now + "명이에요");
        User applicant = users.findById(p.userId).orElse(null);
        if (applicant == null || applicant.status != UserStatus.ACTIVE) throw ApiException.conflict("USER_UNAVAILABLE", "신청한 분의 계정을 쓸 수 없어요");
        if (service) {
            if (c == null || c.visitDate() == null || c.visitStartHour() == null) throw ApiException.bad("VISIT_REQUIRED", "방문 날짜와 시각을 정해 주세요");
            if (c.visitDate().isBefore(LocalDate.now())) throw ApiException.bad("INVALID_DATE", "오늘 이후 날짜를 골라 주세요");
            double h = c.visitHours() == null ? 1.0 : c.visitHours();
            Rules.checkHours(c.visitStartHour(), h);
            p.visitDate = c.visitDate(); p.visitStartHour = c.visitStartHour(); p.visitHours = h;
        } else {
            String[] b = gate.block(applicant, false);
            if (b != null) throw ApiException.bad(b[0], b[1]);
        }
        p.status = "JOINED";
        p.decidedBy = mgr.id; p.decidedAt = LocalDateTime.now();
        String when = service ? Rules.md(p.visitDate) + " " + hhmm(p.visitStartHour) : Rules.md(g.date) + " " + hhmm(g.startHour);
        noti.send(p.userId, "ORG_NOTICE", service ? "신청이 확정됐어요 · 방문 일정을 확인해 주세요" : "봉사 신청이 확정됐어요",
                g.title + " · " + when + " · " + orgName(g.orgId), LINK_USER + g.id);
        return appView(p, g, true);
    }

    public Map<String, Object> reject(User mgr, Long appId, DecideCmd c) {
        GroupParticipant p = app(appId);
        GroupPost g = myNotice(mgr, p.groupPostId);
        if (!"APPLIED".equals(p.status)) throw ApiException.conflict("INVALID_STATE", "답을 기다리는 신청이 아니에요");
        String reason = c == null ? null : clean(c.reason());
        if (reason != null && reason.length() > 200) throw ApiException.bad("TOO_LONG", "사유는 200자 안으로 적어 주세요");
        if (reason != null) noPii(reason);
        p.status = "REJECTED";
        p.rejectReason = reason;
        p.decidedBy = mgr.id; p.decidedAt = LocalDateTime.now();
        noti.send(p.userId, "ORG_NOTICE", "이번에는 함께하기 어려워요", g.title + (reason == null ? "" : " · " + reason), LINK_USER + g.id);
        return appView(p, g, true);
    }

    /** 해 드려요: 방문을 마쳤음을 기록한다. 시간은 적립·차감되지 않는다. */
    public Map<String, Object> served(User mgr, Long appId) {
        GroupParticipant p = app(appId);
        GroupPost g = myNotice(mgr, p.groupPostId);
        if (!SERVICE.equals(g.orgKind)) throw ApiException.bad("INVALID_KIND", "해 드려요 공고의 신청만 방문 완료로 기록해요. 봉사자 모집·단체 봉사는 참석 확인을 눌러 주세요");
        if (!"JOINED".equals(p.status)) throw ApiException.conflict("INVALID_STATE", "확정된 신청만 방문 완료로 기록할 수 있어요");
        if (p.visitDate != null && p.visitDate.isAfter(LocalDate.now())) throw ApiException.conflict("TOO_EARLY", "방문하는 날(" + Rules.md(p.visitDate) + ")부터 기록할 수 있어요");
        p.status = "SERVED";
        p.decidedBy = mgr.id; p.decidedAt = LocalDateTime.now();
        return appView(p, g, true);
    }

    /** 봉사자 모집·단체 봉사: 참석 확인·완료(적립은 GroupService.complete 한 곳에서). 답하지 않은 신청은 거절로 닫는다. */
    public Map<String, Object> complete(User mgr, Long id, List<Long> attended) {
        GroupPost g = myNotice(mgr, id);
        if (SERVICE.equals(g.orgKind)) throw ApiException.bad("INVALID_KIND", "해 드려요 공고는 적립이 없어요. 신청별로 방문 완료를 눌러 주세요");
        Map<String, Object> r = groups.complete(mgr, id, attended);
        for (GroupParticipant p : apps(g)) {
            if (!"APPLIED".equals(p.status)) continue;
            p.status = "REJECTED";
            p.rejectReason = "활동이 끝났어요";
            p.decidedBy = mgr.id; p.decidedAt = LocalDateTime.now();
            noti.send(p.userId, "ORG_NOTICE", "활동이 끝나 신청이 닫혔어요", g.title, LINK_USER + g.id);
        }
        Map<String, Object> out = detail(g, mgr);
        out.put("result", r);
        return out;
    }

    // ---------------- 사용자: 신청·취소 ----------------
    /** 신청할 수 없는 까닭(없으면 null). */
    String applyBlock(GroupPost g, User u) {
        if (u == null || u.role != Role.USER) return "사용자 계정만 신청할 수 있어요";
        if (u.status != UserStatus.ACTIVE) return "지금은 신청할 수 없는 계정이에요";
        if (!"OPEN".equals(g.status)) return "끝났거나 내린 공고예요";
        if (g.closedAt != null) return "신청을 마감한 공고예요";
        if (past(g)) return "날짜가 지난 공고예요";
        if (apps(g).stream().anyMatch(p -> p.userId.equals(u.id) && ("APPLIED".equals(p.status) || "JOINED".equals(p.status)))) return "이미 신청했어요";
        if (heads(apps(g)) >= g.capacity) return "정원이 다 찼어요";
        return null;
    }

    public Map<String, Object> apply(User me, Long id, ApplyCmd c) {
        GroupPost g = posts.findForUpdate(id).filter(x -> x.orgKind != null).orElseThrow(() -> ApiException.notFound("기관 공고를 찾을 수 없어요"));
        String why = applyBlock(g, me);
        if (why != null) throw ApiException.conflict("CANNOT_APPLY", why);
        // 봉사하러 오는 신청은 봉사 자격(신분증 인증·첫 활동 교육)을 1:1·단체와 같은 곳에서 검사한다. 해 드려요는 도움을 받는 신청이라 검사하지 않는다.
        if (!SERVICE.equals(g.orgKind)) gate.require(me, true);
        String msg = c == null ? null : clean(c.message());
        if (msg != null && msg.length() > 300) throw ApiException.bad("TOO_LONG", "신청 메시지는 300자 안으로 적어 주세요");
        if (msg != null && !VoiceSafety.scrub(msg).removed().isEmpty()) throw ApiException.bad("PII_NOT_ALLOWED", "연락처·주소 같은 개인정보는 적지 않아요. 확정되면 담당자가 안내해요");
        int n = c == null || c.headCount() == null ? 1 : c.headCount();
        if (n < 1 || n > MAX_HEADS) throw ApiException.bad("INVALID_HEAD_COUNT", "함께 오는 인원은 1~" + MAX_HEADS + "명으로 적어 주세요");
        if (n > 1 && !(GROUP.equals(g.orgKind) && Boolean.TRUE.equals(g.teamAllowed)))
            throw ApiException.bad("TEAM_NOT_ALLOWED", "이 공고는 한 명씩만 신청할 수 있어요");
        // 거절·취소했던 기록이 있으면 그 줄을 다시 쓴다(한 사람 한 줄)
        GroupParticipant p = parts.findByGroupPostId(g.id).stream().filter(x -> x.userId.equals(me.id)).findFirst().orElseGet(GroupParticipant::new);
        p.groupPostId = g.id; p.userId = me.id; p.status = "APPLIED"; p.message = msg; p.headCount = n;
        p.earned = null; p.rejectReason = null; p.decidedBy = null; p.decidedAt = null; p.visitDate = null; p.visitStartHour = null; p.visitHours = null;
        p.createdAt = LocalDateTime.now();
        parts.save(p);
        Set<Long> to = new LinkedHashSet<>();
        to.add(g.ownerId);
        for (Long uid : to)
            noti.send(uid, "ORG_NOTICE", me.name + " 님이 " + kindLabel(g.orgKind) + " 공고에 신청했어요", g.title + (n > 1 ? " · " + n + "명" : ""), LINK_ORG + "?tab=apps&app=" + p.id);
        return detail(g, me);
    }

    public Map<String, Object> withdraw(User me, Long id) {
        GroupPost g = notice(id);
        GroupParticipant p = apps(g).stream().filter(x -> x.userId.equals(me.id) && ("APPLIED".equals(x.status) || "JOINED".equals(x.status))).findFirst()
                .orElseThrow(() -> ApiException.conflict("INVALID_STATE", "취소할 수 있는 신청이 없어요"));
        if (!"OPEN".equals(g.status)) throw ApiException.conflict("INVALID_STATE", "이미 끝난 공고예요");
        boolean wasConfirmed = "JOINED".equals(p.status);
        p.status = "LEFT";
        noti.send(g.ownerId, "ORG_NOTICE", me.name + " 님이 " + (wasConfirmed ? "확정된 신청을 취소했어요" : "신청을 취소했어요"), g.title, LINK_ORG + "?tab=apps");
        return detail(g, me);
    }

    // ---------------- 보기 ----------------
    private String orgName(Long orgId) { return orgId == null ? null : orgs.findById(orgId).map(o -> o.name).orElse(null); }

    private static String hhmm(Double h) {
        if (h == null) return "";
        int H = (int) Math.floor(h);
        return String.format("%02d:%s", H, h - H >= 0.5 ? "30" : "00");
    }

    /** 공고 상태 글자: 모집 중(해 드려요는 신청 받는 중) / 마감 / 완료 / 취소 */
    static String stateOf(GroupPost g) {
        if ("DONE".equals(g.status)) return "DONE";
        if ("CANCELLED".equals(g.status)) return "CANCELLED";
        return accepting(g) ? "OPEN" : "CLOSED";
    }

    static String stateLabel(GroupPost g) {
        return switch (stateOf(g)) {
            case "OPEN" -> SERVICE.equals(g.orgKind) ? "신청 받는 중" : "모집 중";
            case "CLOSED" -> "마감";
            case "DONE" -> "완료";
            default -> "취소";
        };
    }

    public Map<String, Object> card(GroupPost g, User viewer) {
        List<GroupParticipant> list = apps(g);
        boolean service = SERVICE.equals(g.orgKind), mine = viewer != null && viewer.role == Role.ORG && viewer.orgId != null && viewer.orgId.equals(g.orgId);
        int confirmedHeads = heads(list);
        long pending = list.stream().filter(p -> "APPLIED".equals(p.status)).count();
        long applied = list.stream().filter(p -> !"REJECTED".equals(p.status)).mapToInt(GroupParticipant::heads).sum();
        GroupParticipant my = viewer == null ? null : list.stream().filter(p -> p.userId.equals(viewer.id)).findFirst().orElse(null);
        Map<String, Object> m = Views.map("id", g.id, "kind", g.orgKind, "kindLabel", kindLabel(g.orgKind), "title", g.title, "detail", g.detail,
                "category", g.category, "date", g.date.toString(), "startHour", service && g.hours == 0 ? null : g.startHour, "hours", service && g.hours == 0 ? null : g.hours,
                "endHour", service && g.hours == 0 ? null : g.startHour + g.hours, "whenText", g.whenText,
                "area", g.area, "place", g.place, "supplies", g.supplies, "teamAllowed", Boolean.TRUE.equals(g.teamAllowed),
                "target", g.target, "targetLabel", g.target == null ? null : targetLabel(g.target),
                "capacity", g.capacity, "applied", applied, "confirmed", confirmedHeads, "pending", pending, "left", Math.max(0, g.capacity - confirmedHeads),
                "full", confirmedHeads >= g.capacity, "percent", g.capacity <= 0 ? 0 : Math.min(100, (int) Math.round(applied * 100.0 / g.capacity)),
                "status", g.status, "state", stateOf(g), "stateLabel", stateLabel(g), "closed", g.closedAt != null, "past", past(g),
                "orgId", g.orgId, "orgName", orgName(g.orgId), "mine", mine,
                "earns", !service, "weight", service ? null : GroupService.WEIGHT, "estEarn", service ? null : Rules.earn(g.hours, GroupService.WEIGHT),
                "timeRule", service ? "기관 직원이 하는 일이라 시간이 적립·차감되지 않아요" : "봉사자는 활동 시간만큼 적립돼요 · 기관 공고라 받는 쪽 차감은 없어요",
                "createdAt", g.createdAt == null ? null : g.createdAt.toString());
        if (viewer != null && viewer.role == Role.USER) {
            String block = applyBlock(g, viewer);
            if (block == null && !service) { String[] b = gate.block(viewer, true); if (b != null) block = b[1]; }
            m.put("myStatus", my == null ? null : my.status);
            m.put("myStatusLabel", my == null ? null : appLabel(my.status, service));
            m.put("myEarned", my == null ? null : my.earned);
            m.put("myVisit", my == null || my.visitDate == null ? null : Views.map("date", my.visitDate.toString(), "startHour", my.visitStartHour, "hours", my.visitHours));
            m.put("myRejectReason", my == null ? null : my.rejectReason);
            m.put("canApply", block == null);
            m.put("applyBlock", block);
            m.put("canWithdraw", my != null && ("APPLIED".equals(my.status) || "JOINED".equals(my.status)) && "OPEN".equals(g.status));
        }
        if (mine) {
            boolean open = "OPEN".equals(g.status);
            m.put("canClose", open && g.closedAt == null);
            m.put("canReopen", open && g.closedAt != null && !past(g));
            m.put("canCancel", open);
            m.put("canComplete", open && !service && !g.date.isAfter(LocalDate.now()));
        }
        return m;
    }

    static String appLabel(String st, boolean service) {
        return switch (st) {
            case "APPLIED" -> "답 기다리는 중";
            case "JOINED" -> "확정";
            case "REJECTED" -> "거절";
            case "ATTENDED" -> "참석 · 적립";
            case "ABSENT" -> "불참";
            case "SERVED" -> "방문 완료";
            default -> st;
        };
    }

    /** 신청 묶음(프로토타입 AGRP): 처리할 일 / 확정 / 완료 */
    static String boxOf(String st) { return "APPLIED".equals(st) ? "todo" : "JOINED".equals(st) ? "confirmed" : "done"; }

    private Map<String, Object> appView(GroupParticipant p, GroupPost g, boolean withPost) {
        User u = users.findById(p.userId).orElse(null);
        boolean service = SERVICE.equals(g.orgKind);
        Map<String, Object> m = Views.map("id", p.id, "noticeId", g.id, "userId", p.userId, "name", u == null ? "탈퇴한 사용자" : u.name,
                "area", u == null ? null : u.area, "ageGroup", u == null ? null : u.ageGroup,
                "headCount", p.heads(), "message", p.message, "status", p.status, "statusLabel", appLabel(p.status, service), "box", boxOf(p.status),
                "appliedAt", p.createdAt, "decidedAt", p.decidedAt, "rejectReason", p.rejectReason, "earned", p.earned,
                "visit", p.visitDate == null ? null : Views.map("date", p.visitDate.toString(), "startHour", p.visitStartHour, "hours", p.visitHours),
                "canDecide", "APPLIED".equals(p.status) && "OPEN".equals(g.status), "needVisit", service,
                "canServe", service && "JOINED".equals(p.status) && (p.visitDate == null || !p.visitDate.isAfter(LocalDate.now())));
        if (!service && u != null) {
            m.put("idVerified", u.idStatus == VerifyStatus.APPROVED);
            m.put("eduDone", u.eduDone);
            m.put("doneCount", groups.attendedBy(u.id).size());
        }
        if (withPost) m.put("notice", Views.map("id", g.id, "kind", g.orgKind, "kindLabel", kindLabel(g.orgKind), "title", g.title, "date", g.date.toString(),
                "startHour", service && g.hours == 0 ? null : g.startHour, "hours", service && g.hours == 0 ? null : g.hours, "whenText", g.whenText,
                "orgName", orgName(g.orgId), "state", stateOf(g)));
        return m;
    }

    /** 상세: 신청자 목록은 올린 기관 담당자에게만. 다른 사람에게는 인원 수만. */
    public Map<String, Object> detail(GroupPost g, User viewer) {
        Map<String, Object> m = card(g, viewer);
        if (Boolean.TRUE.equals(m.get("mine"))) m.put("applications", apps(g).stream().map(p -> appView(p, g, false)).toList());
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(User viewer, Long id) { return detail(notice(id), viewer); }

    /** 담당자 화면용 상세: 우리 기관 공고만. */
    @Transactional(readOnly = true)
    public Map<String, Object> getMine(User mgr, Long id) { return detail(myNotice(mgr, id), mgr); }

    private List<GroupPost> orgNotices(User mgr) {
        requireOrg(mgr);
        return posts.findAll().stream().filter(g -> g.orgKind != null && mgr.orgId.equals(g.orgId))
                .sorted(Comparator.comparing((GroupPost g) -> stateOf(g).equals("OPEN") ? 0 : stateOf(g).equals("CLOSED") ? 1 : 2)
                        .thenComparing(g -> g.date).thenComparing(g -> g.id)).toList();
    }

    /** 담당자: 우리 기관 공고 목록 + 종류별 개수(O-POST-05, 홈 "기관 공고 진행"). */
    @Transactional(readOnly = true)
    public Map<String, Object> listForOrg(User mgr, String kind, String state) {
        List<GroupPost> all = orgNotices(mgr);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("ALL", all.size());
        for (String k : KINDS) counts.put(k, all.stream().filter(g -> k.equals(g.orgKind)).count());
        long pending = all.stream().filter(g -> "OPEN".equals(g.status)).mapToLong(g -> apps(g).stream().filter(p -> "APPLIED".equals(p.status)).count()).sum();
        List<Map<String, Object>> items = all.stream()
                .filter(g -> kind == null || kind.isBlank() || "ALL".equalsIgnoreCase(kind) || kind.equals(g.orgKind))
                .filter(g -> state == null || state.isBlank() || state.equalsIgnoreCase(stateOf(g)))
                .map(g -> card(g, mgr)).toList();
        return Views.map("items", items, "counts", counts, "pendingApplications", pending, "kinds", kindsMeta(),
                "notice", "특정 관리 대상 분을 위한 요청은 요청 관리의 대리 요청에서 올려요. 여기는 기관 이름으로 동네 전체에 올리는 공고예요.");
    }

    /** 담당자: 우리 기관 공고에 들어온 신청(O-POST-09). box: todo | confirmed | done | all. */
    @Transactional(readOnly = true)
    public Map<String, Object> applicationsForOrg(User mgr, String box, String kind) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (GroupPost g : orgNotices(mgr)) {
            if ("CANCELLED".equals(g.status)) continue;
            for (GroupParticipant p : apps(g)) all.add(appView(p, g, true));
        }
        all.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDateTime) m.get("appliedAt"), Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, Object> counts = Views.map("todo", all.stream().filter(m -> "todo".equals(m.get("box"))).count(),
                "confirmed", all.stream().filter(m -> "confirmed".equals(m.get("box"))).count(),
                "done", all.stream().filter(m -> "done".equals(m.get("box"))).count());
        String bx = box == null || box.isBlank() ? "todo" : box;
        List<Map<String, Object>> items = all.stream().filter(m -> "all".equals(bx) || bx.equals(m.get("box")))
                .filter(m -> kind == null || kind.isBlank() || "ALL".equalsIgnoreCase(kind) || kind.equals(((Map<?, ?>) m.get("notice")).get("kind"))).toList();
        return Views.map("items", items, "counts", counts, "box", bx,
                "notice", "우리 기관이 올린 공고(봉사자 모집 · 단체 봉사 · 해 드려요)에 들어온 신청이에요.");
    }

    /** 사용자: 열려 있는 기관 공고(가까운 날짜순). kind, area, cat 로 거른다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOpen(User viewer, String kind, String area, String cat) {
        return posts.findByStatus("OPEN").stream().filter(g -> g.orgKind != null && accepting(g))
                .filter(g -> kind == null || kind.isBlank() || kind.equals(g.orgKind))
                .filter(g -> area == null || area.isBlank() || area.equals(g.area))
                .filter(g -> cat == null || cat.isBlank() || cat.equals(g.category))
                .sorted(Comparator.comparing((GroupPost g) -> g.date).thenComparingDouble(g -> g.startHour).thenComparing(g -> g.id))
                .map(g -> card(g, viewer)).toList();
    }

    /** 사용자: 내가 신청한 기관 공고(최근 신청순). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(User me) {
        List<Map<String, Object>> out = new ArrayList<>();
        parts.findByUserId(me.id).stream().filter(p -> !"LEFT".equals(p.status)).sorted(Comparator.comparing((GroupParticipant p) -> p.id).reversed())
                .forEach(p -> posts.findById(p.groupPostId).filter(g -> g.orgKind != null).ifPresent(g -> out.add(card(g, me))));
        return out;
    }

    /** 홈·AI 도우미가 쓰는 숫자: 답을 기다리는 신청 수. */
    @Transactional(readOnly = true)
    public long pendingCount(User mgr) {
        if (mgr.orgId == null) return 0;
        return orgNotices(mgr).stream().filter(g -> "OPEN".equals(g.status)).mapToLong(g -> apps(g).stream().filter(p -> "APPLIED".equals(p.status)).count()).sum();
    }
}
