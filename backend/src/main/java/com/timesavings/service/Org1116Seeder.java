package com.timesavings.service;

import com.timesavings.domain.*;
import com.timesavings.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 데모 데이터(2026-11-16 담당자 추가 기능): 권한 위임 몇 건과 기관 공고 3종. 담당자 org1 기준.
 * 데모 계정이 있고 위임·기관 공고가 하나도 없을 때만 넣는다. 관리 대상은 이미 있는 가상의 별칭만 쓴다(연락처·주소 없음).
 */
@Component
@Order(120)
public class Org1116Seeder implements ApplicationRunner {
    private final UserRepo users;
    private final ElderRepo elders;
    private final DelegationRepo delegations;
    private final DelegationRequestRepo dlgReqs;
    private final DelegationLogRepo dlgLogs;
    private final GroupPostRepo posts;
    private final GroupParticipantRepo parts;
    private final boolean enabled;

    public Org1116Seeder(UserRepo users, ElderRepo elders, DelegationRepo delegations, DelegationRequestRepo dlgReqs, DelegationLogRepo dlgLogs,
                         GroupPostRepo posts, GroupParticipantRepo parts, @Value("${app.seed.enabled:true}") boolean enabled) {
        this.users = users; this.elders = elders; this.delegations = delegations; this.dlgReqs = dlgReqs; this.dlgLogs = dlgLogs;
        this.posts = posts; this.parts = parts; this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        User org1 = u("org1");
        if (org1 == null || org1.orgId == null) return;
        if (delegations.count() == 0 && dlgReqs.count() == 0) seedDelegations(org1);
        if (posts.findAll().stream().noneMatch(g -> g.orgKind != null)) seedNotices(org1);
    }

    private User u(String loginId) { return users.findByLoginId(loginId).orElse(null); }

    private void seedDelegations(User org1) {
        LocalDate today = LocalDate.now();
        List<Elder> mine = elders.findByManagerId(org1.id).stream().sorted((a, b) -> a.id.compareTo(b.id)).toList();
        // 첫째: 서면 동의(넉넉히 남음) / 둘째: 보호자 동의 / 셋째: 서면 동의(만료 임박) / 넷째: 위임 없음(새 위임 등록을 해 볼 수 있게)
        if (mine.size() > 0) dlg(org1, mine.get(0), "PAPER", List.of("POST_PROXY", "ACCEPT_PROXY", "LOG_CONFIRM", "CHECK_IN"), today.minusDays(230), today.plusDays(134));
        if (mine.size() > 1) dlg(org1, mine.get(1), "GUARDIAN", List.of("POST_PROXY", "ACCEPT_PROXY", "LOG_CONFIRM", "CHECK_IN"), today.minusDays(300), today.plusDays(64));
        if (mine.size() > 2) dlg(org1, mine.get(2), "PAPER", List.of("POST_PROXY", "LOG_CONFIRM", "PUBLIC_SERVICE"), today.minusDays(345), today.plusDays(19));
        // 받은 요청: 앱을 쓰는 분의 "담당 기관 연결"
        User bok = u("bok");
        if (bok != null) {
            DelegationRequest r = new DelegationRequest();
            r.kind = DelegationService.NEW_LINK; r.userId = bok.id; r.orgId = org1.orgId;
            r.scopes = new ArrayList<>(List.of("POST_PROXY", "LOG_CONFIRM", "PUBLIC_SERVICE", "CHECK_IN"));
            r.message = "혼자 지내서 담당자님이 챙겨 주시면 좋겠어요.";
            r.createdAt = LocalDateTime.now().minusHours(5);
            dlgReqs.save(r);
        }
    }

    private void dlg(User mgr, Elder e, String method, List<String> scopes, LocalDate start, LocalDate end) {
        Delegation d = new Delegation();
        d.elderId = e.id; d.orgId = e.orgId; d.createdBy = mgr.id; d.consentMethod = method; d.scopes = new ArrayList<>(scopes);
        d.consentDate = start; d.startDate = start; d.endDate = end; d.status = "ACTIVE"; d.createdAt = start.atTime(10, 0);
        delegations.save(d);
        DelegationLog l = new DelegationLog();
        l.delegationId = d.id; l.elderId = e.id; l.actorId = mgr.id; l.actorRole = "MANAGER"; l.action = "CREATED";
        l.detail = DelegationService.METHODS.get(method) + " · " + start + " ~ " + end; l.createdAt = d.createdAt;
        dlgLogs.save(l);
    }

    private void seedNotices(User org1) {
        LocalDate today = LocalDate.now();
        GroupPost walk = notice(org1, OrgNoticeService.VOLUNTEER, "어르신 산책 동행", "기관 앞 공원까지 천천히 산책을 함께해요.", "말동무·정서",
                today.plusDays(2), 10, 2, "행복동 주민센터 앞", 1);
        app(walk, "sky", "APPLIED", "오전 수업이 없어서 갈 수 있어요. 산책 동행을 여러 번 해 봤어요.", 1, null);
        app(walk, "yujin", "APPLIED", "그날 오전에 시간이 돼요.", 1, null);
        GroupPost party = notice(org1, OrgNoticeService.GROUP, "어르신 생신잔치 보조", "상차림, 어르신 이동 돕기, 사진 촬영", "말동무·정서",
                today.plusDays(6), 13, 3, "행복동 주민센터 2층 강당", 8);
        party.teamAllowed = true; party.supplies = "편한 신발";
        app(party, "mikyung", "APPLIED", "친구 1명과 같이 갈게요.", 2, null);
        app(party, "doyun", "JOINED", null, 1, null);
        GroupPost visit = notice(org1, OrgNoticeService.SERVICE, "생활 상담 방문 (무료)", "혼자 지내기 힘든 일이 있는 분 댁에 기관 직원이 찾아가 이야기를 듣고, 받을 수 있는 공공 서비스 신청을 도와드려요.",
                "생활 정리", today.plusDays(30), 0, 0, null, 6);
        visit.kind = OrgNoticeService.SERVICE; visit.whenText = "평일 오후 2~5시"; visit.target = "SENIOR65";
        app(visit, "bok", "APPLIED", "장기요양 등급 신청이 궁금해요.", 1, null);
        app(visit, "mom", "JOINED", "어머니 일로 상담받고 싶어요.", 1, today.plusDays(3));
    }

    private GroupPost notice(User owner, String orgKind, String title, String detail, String category, LocalDate date, double start, double hours, String place, int capacity) {
        GroupPost g = new GroupPost();
        g.kind = GroupService.RECRUIT; g.orgKind = orgKind; g.ownerId = owner.id; g.orgId = owner.orgId; g.title = title; g.detail = detail; g.category = category;
        g.date = date; g.startHour = start; g.hours = hours; g.area = "행복동"; g.place = place; g.capacity = capacity;
        g.createdAt = LocalDateTime.now().minusDays(2);
        return posts.save(g);
    }

    private void app(GroupPost g, String loginId, String status, String message, int heads, LocalDate visit) {
        User v = u(loginId);
        if (v == null) return;
        GroupParticipant p = new GroupParticipant();
        p.groupPostId = g.id; p.userId = v.id; p.status = status; p.message = message; p.headCount = heads;
        p.createdAt = LocalDateTime.now().minusHours(3L * (parts.count() % 7 + 1));
        if (visit != null) { p.visitDate = visit; p.visitStartHour = 14.0; p.visitHours = 1.0; }
        if ("JOINED".equals(status)) p.decidedAt = LocalDateTime.now().minusHours(1);
        parts.save(p);
    }
}
