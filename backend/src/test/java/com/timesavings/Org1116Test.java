package com.timesavings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.AuthService;
import com.timesavings.service.BankService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-11-16 담당자 추가 기능(권한 위임 · 기관 공고 3종 · 홈 봉사자 현황 · AI 도우미)의 권한 검사·상태 전이·규칙 답변.
 * AI 키 없이(규칙 모드) 돈다. 외부 네트워크를 부르지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Org1116Test {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired OrganizationRepo orgs;
    @Autowired ElderRepo elders;
    @Autowired RequestRepo requests;
    @Autowired PostRepo posts;
    @Autowired DelegationRepo delegations;
    @Autowired FamilyLinkRepo family;
    @Autowired GroupPostRepo groupPosts;
    @Autowired GroupParticipantRepo groupParts;
    @Autowired NotificationRepo notis;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    final ObjectMapper om = new ObjectMapper();
    final LocalDate today = LocalDate.now();

    Organization org, org2;
    User mgr, mgr2, other, senior, child, v1, v2, v3, noId;
    Elder e1, e2;

    User user(String id, Role role, Long orgId, VerifyStatus idStatus) {
        User u = new User();
        u.loginId = "x16" + id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "70대";
        u.idStatus = idStatus; u.orgId = orgId; u.eduDone = true;
        return users.save(u);
    }

    Organization org(String name) {
        Organization o = new Organization();
        o.name = name; o.status = OrgStatus.APPROVED;
        return orgs.save(o);
    }

    Elder elder(String alias, User m) {
        Elder e = new Elder();
        e.alias = alias; e.dong = "행복동"; e.ageGroup = "80대"; e.managerId = m.id; e.orgId = m.orgId; e.needs = new ArrayList<>(List.of("동행"));
        return elders.save(e);
    }

    Request req(Elder e, User vol, LocalDate date, double start, ReqStatus st) {
        Request r = new Request();
        r.kind = Kind.REQUEST; r.senderId = mgr.id; r.volunteerId = vol.id; r.elderId = e.id; r.date = date;
        r.startHour = start; r.hours = 1; r.category = "동행"; r.content = "병원 동행"; r.weight = 1; r.status = st;
        if (st == ReqStatus.DONE) { r.logText = "잘 다녀왔어요"; r.doneAt = date.atTime(13, 0); }
        return requests.save(r);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    ResultActions send(User u, String method, String path, String body) throws Exception {
        var rq = (switch (method) { case "POST" -> post(path); case "PUT" -> put(path); case "DELETE" -> delete(path); default -> get(path); })
                .header("Authorization", tok(u));
        if (body != null) rq = rq.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(rq);
    }

    JsonNode json(ResultActions r) throws Exception { return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    JsonNode ok(User u, String method, String path, String body) throws Exception { return json(send(u, method, path, body).andExpect(status().isOk())); }

    void fail(User u, String method, String path, String body, int http, String code) throws Exception {
        JsonNode j = json(send(u, method, path, body).andExpect(status().is(http)));
        if (code != null) assertEquals(code, j.path("code").asText(), j.toString());
    }

    @BeforeEach
    void setUp() {
        org = org("행복동 주민센터"); org2 = org("희망 복지관");
        mgr = user("mgr", Role.ORG, org.id, VerifyStatus.NONE);
        mgr2 = user("mgr2", Role.ORG, org.id, VerifyStatus.NONE);
        other = user("other", Role.ORG, org2.id, VerifyStatus.NONE);
        senior = user("senior", Role.USER, null, VerifyStatus.NONE);
        child = user("child", Role.USER, null, VerifyStatus.APPROVED);
        v1 = user("v1", Role.USER, null, VerifyStatus.APPROVED);
        v2 = user("v2", Role.USER, null, VerifyStatus.APPROVED);
        v3 = user("v3", Role.USER, null, VerifyStatus.APPROVED);
        noId = user("noid", Role.USER, null, VerifyStatus.NONE);
        e1 = elder("이순례", mgr);
        e2 = elder("오덕수", mgr);
    }

    String paper(Long elderId, String extra) {
        return "{\"elderId\":" + elderId + ",\"consentMethod\":\"PAPER\",\"scopes\":[\"POST_PROXY\",\"LOG_CONFIRM\"]" + extra + "}";
    }

    // ===================== 1. 권한 위임 =====================
    @Test
    void 위임_등록은_동의_확인이_있어야_하고_소유와_역할을_검사한다() throws Exception {
        fail(v1, "GET", "/api/org/delegations", null, 403, "FORBIDDEN"); // ORG 만
        fail(mgr, "POST", "/api/org/delegations", paper(e1.id, ""), 400, "CONSENT_REQUIRED");
        fail(mgr2, "POST", "/api/org/delegations", paper(e1.id, ",\"consentConfirmed\":true"), 403, "FORBIDDEN"); // 같은 기관이어도 내 담당이 아니면 안 됨
        fail(other, "POST", "/api/org/delegations", paper(e1.id, ",\"consentConfirmed\":true"), 403, "FORBIDDEN");
        fail(mgr, "POST", "/api/org/delegations", "{\"elderId\":" + e1.id + ",\"consentMethod\":\"PAPER\",\"scopes\":[\"MONEY\"],\"consentConfirmed\":true}", 400, "INVALID_SCOPE");
        fail(mgr, "POST", "/api/org/delegations", paper(e1.id, ",\"consentConfirmed\":true,\"endDate\":\"" + today.plusYears(1).plusDays(2) + "\""), 400, "PERIOD_TOO_LONG");
        fail(mgr, "POST", "/api/org/delegations", "{\"elderId\":" + e1.id + ",\"consentMethod\":\"APP\",\"scopes\":[\"CHECK_IN\"]}", 400, "NO_APP_ACCOUNT");

        JsonNode d = ok(mgr, "POST", "/api/org/delegations", paper(e1.id, ",\"consentConfirmed\":true"));
        assertEquals("ACTIVE", d.path("status").asText());
        assertEquals("서면 동의", d.path("consentMethodLabel").asText());
        assertEquals(today.toString(), d.path("consentDate").asText());
        assertEquals(today.plusYears(1).minusDays(1).toString(), d.path("endDate").asText());
        assertEquals(2, d.path("scopeCount").asInt());
        assertEquals("이순례 님", d.path("displayName").asText());
        assertTrue(d.path("editableByManager").asBoolean());
        assertEquals("CREATED", d.path("history").get(0).path("action").asText());
        // 저장하는 칸에 서류·연락처가 없다
        for (String f : List.of("phone", "address", "fileUrl", "signature", "realName")) assertTrue(d.path(f).isMissingNode(), f);

        fail(mgr, "POST", "/api/org/delegations", paper(e1.id, ",\"consentConfirmed\":true"), 409, "ALREADY_EXISTS");
        long id = d.path("id").asLong();
        fail(mgr2, "GET", "/api/org/delegations/" + id, null, 403, "FORBIDDEN");
        fail(mgr2, "POST", "/api/org/delegations/" + id + "/revoke", null, 403, "FORBIDDEN");

        // 범위 바꾸기: 다시 동의 확인이 있어야 한다
        fail(mgr, "PUT", "/api/org/delegations/" + id, "{\"scopes\":[\"CHECK_IN\"]}", 400, "CONSENT_REQUIRED");
        JsonNode up = ok(mgr, "PUT", "/api/org/delegations/" + id, "{\"scopes\":[\"CHECK_IN\",\"POST_PROXY\"],\"consentConfirmed\":true}");
        assertEquals("[\"POST_PROXY\",\"CHECK_IN\"]", up.path("scopes").toString()); // 정해진 순서
        assertEquals("SCOPE_CHANGED", up.path("history").get(0).path("action").asText());
        fail(mgr, "POST", "/api/org/delegations/" + id + "/extend-request", null, 409, "NO_APP_ACCOUNT");

        JsonNode list = ok(mgr, "GET", "/api/org/delegations", null);
        assertEquals(2, list.path("items").size());
        assertEquals(1, list.path("counts").path("active").asInt());
        assertEquals(1, list.path("counts").path("none").asInt());
        assertEquals(5, list.path("scopes").size());
        assertEquals("NONE", list.path("items").get(1).path("status").asText());

        // 회수 → 다시 등록할 수 있다
        assertEquals("REVOKED", ok(mgr, "POST", "/api/org/delegations/" + id + "/revoke", null).path("status").asText());
        fail(mgr, "POST", "/api/org/delegations/" + id + "/revoke", null, 409, "INVALID_STATE");
        fail(mgr, "PUT", "/api/org/delegations/" + id, "{\"scopes\":[\"CHECK_IN\"],\"consentConfirmed\":true}", 409, "INVALID_STATE");
        assertTrue(ok(mgr, "GET", "/api/org/elders/" + e1.id + "/delegation", null).path("canCreate").asBoolean());
        ok(mgr, "POST", "/api/org/delegations", paper(e1.id, ",\"consentConfirmed\":true"));
    }

    @Test
    void 위임_만료와_만료_임박은_날짜로_계산한다() throws Exception {
        Delegation soon = new Delegation();
        soon.elderId = e1.id; soon.orgId = org.id; soon.consentMethod = "PAPER"; soon.scopes = new ArrayList<>(List.of("CHECK_IN"));
        soon.startDate = today.minusDays(300); soon.endDate = today.plusDays(10); soon.consentDate = soon.startDate;
        delegations.save(soon);
        Delegation gone = new Delegation();
        gone.elderId = e2.id; gone.orgId = org.id; gone.consentMethod = "GUARDIAN"; gone.scopes = new ArrayList<>(List.of("CHECK_IN"));
        gone.startDate = today.minusDays(400); gone.endDate = today.minusDays(1); gone.consentDate = gone.startDate;
        delegations.save(gone);
        JsonNode list = ok(mgr, "GET", "/api/org/delegations", null);
        assertEquals("ACTIVE", list.path("items").get(0).path("status").asText());
        assertTrue(list.path("items").get(0).path("expiringSoon").asBoolean());
        assertEquals("만료 임박", list.path("items").get(0).path("statusLabel").asText());
        assertEquals(10, list.path("items").get(0).path("daysLeft").asInt());
        assertEquals("EXPIRED", list.path("items").get(1).path("status").asText());
        assertEquals(1, list.path("counts").path("expiringSoon").asInt());
        assertEquals(1, list.path("counts").path("expired").asInt());
        // 만료된 대상은 새로 등록할 수 있다(자동 연장은 없다)
        assertEquals("ACTIVE", ok(mgr, "POST", "/api/org/delegations", paper(e2.id, ",\"consentConfirmed\":true")).path("status").asText());
    }

    @Test
    void 앱_연결_요청을_수락하면_별칭으로_등록되고_범위_변경은_줄이면_바로_넓히면_수락_뒤_반영된다() throws Exception {
        String link = "{\"kind\":\"NEW_LINK\",\"orgId\":" + org.id + ",\"scopes\":[\"POST_PROXY\",\"LOG_CONFIRM\",\"CHECK_IN\"],\"message\":\"혼자 지내서 챙겨 주시면 좋겠어요.\"}";
        fail(mgr, "POST", "/api/delegations/requests", link, 403, "FORBIDDEN"); // 사용자만
        fail(senior, "POST", "/api/delegations/requests", link.replace("챙겨 주시면 좋겠어요.", "010-1234-5678 로 전화 주세요"), 400, "PII_NOT_ALLOWED");
        JsonNode r = ok(senior, "POST", "/api/delegations/requests", link);
        assertEquals("PENDING", r.path("status").asText());
        fail(senior, "POST", "/api/delegations/requests", link, 409, "DUPLICATE");
        long rid = r.path("id").asLong();

        assertEquals(0, ok(other, "GET", "/api/org/delegation-requests", null).path("items").size()); // 다른 기관에는 안 보인다
        fail(other, "POST", "/api/org/delegation-requests/" + rid + "/answer", "{\"accept\":true,\"alias\":\"김영자\"}", 403, "FORBIDDEN");
        JsonNode in = ok(mgr, "GET", "/api/org/delegation-requests?status=PENDING", null);
        assertEquals(1, in.path("pendingCount").asInt());
        assertEquals("새 연결", in.path("items").get(0).path("kindLabel").asText());
        assertEquals("수락하고 연결", in.path("items").get(0).path("acceptLabel").asText());

        fail(mgr, "POST", "/api/org/delegation-requests/" + rid + "/answer", "{\"accept\":true}", 400, "INVALID_ALIAS");
        long eldersBefore = elders.count();
        JsonNode acc = ok(mgr, "POST", "/api/org/delegation-requests/" + rid + "/answer", "{\"accept\":true,\"alias\":\"김영자\"}");
        assertEquals("ACCEPTED", acc.path("status").asText());
        assertEquals(eldersBefore + 1, elders.count());
        Elder ne = elders.findById(acc.path("elderId").asLong()).orElseThrow();
        assertEquals("김영자", ne.alias); assertEquals("행복동", ne.dong); assertEquals("70대", ne.ageGroup); assertEquals(mgr.id, ne.managerId);
        fail(mgr, "POST", "/api/org/delegation-requests/" + rid + "/answer", "{\"accept\":false}", 409, "INVALID_STATE");

        JsonNode my = ok(senior, "GET", "/api/delegations/mine", null);
        assertEquals(1, my.path("mine").size());
        JsonNode d = my.path("mine").get(0);
        long did = d.path("id").asLong();
        assertEquals("ACTIVE", d.path("status").asText()); assertEquals("APP", d.path("consentMethod").asText());
        assertEquals("행복동 주민센터", d.path("orgName").asText());
        assertTrue(d.path("canChangeScopes").asBoolean());
        // 앱으로 동의한 위임은 담당자가 범위를 바꿀 수 없다
        fail(mgr, "PUT", "/api/org/delegations/" + did, "{\"scopes\":[\"CHECK_IN\"],\"consentConfirmed\":true}", 409, "APP_OWNED");

        // 줄이기: 바로 반영, 담당자는 확인만(거절 불가)
        JsonNode narrow = ok(senior, "POST", "/api/delegations/requests", "{\"kind\":\"SCOPE_CHANGE\",\"delegationId\":" + did + ",\"scopes\":[\"POST_PROXY\",\"CHECK_IN\"]}");
        assertTrue(narrow.path("applied").asBoolean());
        assertEquals(List.of("POST_PROXY", "CHECK_IN"), delegations.findById(did).orElseThrow().scopes);
        fail(mgr, "POST", "/api/org/delegation-requests/" + narrow.path("id").asLong() + "/answer", "{\"accept\":false}", 409, "ALREADY_APPLIED");
        fail(mgr2, "POST", "/api/org/delegation-requests/" + narrow.path("id").asLong() + "/answer", "{\"accept\":true}", 403, "FORBIDDEN"); // 그 대상 담당자만
        ok(mgr, "POST", "/api/org/delegation-requests/" + narrow.path("id").asLong() + "/answer", "{\"accept\":true}");

        // 넓히기: 담당자가 수락해야 반영
        JsonNode wide = ok(senior, "POST", "/api/delegations/requests", "{\"kind\":\"SCOPE_CHANGE\",\"delegationId\":" + did + ",\"scopes\":[\"POST_PROXY\",\"CHECK_IN\",\"PUBLIC_SERVICE\"]}");
        assertFalse(wide.path("applied").asBoolean());
        assertEquals(2, delegations.findById(did).orElseThrow().scopes.size());
        ok(mgr, "POST", "/api/org/delegation-requests/" + wide.path("id").asLong() + "/answer", "{\"accept\":true}");
        assertEquals(List.of("POST_PROXY", "PUBLIC_SERVICE", "CHECK_IN"), delegations.findById(did).orElseThrow().scopes);

        // 남의 위임은 못 건드린다. 본인은 언제든 회수.
        fail(v1, "POST", "/api/delegations/" + did + "/revoke", null, 403, "FORBIDDEN");
        assertEquals("REVOKED", ok(senior, "POST", "/api/delegations/" + did + "/revoke", null).path("status").asText());
        assertTrue(notis.findByUserIdOrderByCreatedAtDescIdDesc(mgr.id).stream().anyMatch(n -> "DELEGATION".equals(n.type) && n.title.contains("회수")));

        // 앱 동의 요청: 본인이 눌러야 유효(맡길 일을 직접 고른다)
        JsonNode pend = ok(mgr, "POST", "/api/org/delegations", "{\"elderId\":" + ne.id + ",\"consentMethod\":\"APP\",\"scopes\":[\"POST_PROXY\",\"ACCEPT_PROXY\"]}");
        assertEquals("PENDING", pend.path("status").asText());
        long pid = pend.path("id").asLong();
        fail(v1, "POST", "/api/delegations/" + pid + "/consent", "{\"accept\":true}", 403, "FORBIDDEN");
        JsonNode agreed = ok(senior, "POST", "/api/delegations/" + pid + "/consent", "{\"accept\":true,\"scopes\":[\"POST_PROXY\"]}");
        assertEquals("ACTIVE", agreed.path("status").asText());
        assertEquals(1, agreed.path("scopeCount").asInt());
        assertEquals(today.toString(), agreed.path("consentDate").asText());
        // 연장 요청은 알림만, 연장은 본인이
        ok(mgr, "POST", "/api/org/delegations/" + pid + "/extend-request", null);
        Delegation dd = delegations.findById(pid).orElseThrow();
        dd.endDate = today.plusDays(5);
        assertEquals(today.plusYears(1).minusDays(1).toString(), ok(senior, "POST", "/api/delegations/" + pid + "/extend", "{}").path("endDate").asText());
        fail(senior, "POST", "/api/delegations/" + pid + "/extend", "{\"endDate\":\"" + today.plusYears(2) + "\"}", 400, "PERIOD_TOO_LONG");
    }

    @Test
    void 보호자는_가족으로_연결된_계정만_고를_수_있고_보호자가_눌러야_유효해진다() throws Exception {
        // 본인 계정이 연결된 대상을 만든다(연결 요청 수락)
        long rid = ok(senior, "POST", "/api/delegations/requests", "{\"kind\":\"NEW_LINK\",\"orgId\":" + org.id + ",\"scopes\":[\"CHECK_IN\"]}").path("id").asLong();
        long elderId = ok(mgr, "POST", "/api/org/delegation-requests/" + rid + "/answer", "{\"accept\":true,\"alias\":\"김영자\"}").path("elderId").asLong();
        long did = ok(senior, "GET", "/api/delegations/mine", null).path("mine").get(0).path("id").asLong();
        ok(senior, "POST", "/api/delegations/" + did + "/revoke", null);

        String body = "{\"elderId\":" + elderId + ",\"consentMethod\":\"GUARDIAN\",\"scopes\":[\"CHECK_IN\",\"LOG_CONFIRM\"],\"guardianUserId\":" + child.id + "}";
        fail(mgr, "POST", "/api/org/delegations", body, 400, "GUARDIAN_NOT_LINKED"); // 가족 연결이 없다
        FamilyLink l = new FamilyLink();
        l.requesterId = child.id; l.targetId = senior.id; l.relation = "자녀"; l.status = LinkStatus.ACCEPTED;
        family.save(l);
        JsonNode cand = ok(mgr, "GET", "/api/org/elders/" + elderId + "/delegation", null);
        assertTrue(cand.path("appLinked").asBoolean());
        assertEquals(child.id.longValue(), cand.path("guardianCandidates").get(0).path("userId").asLong());

        JsonNode d = ok(mgr, "POST", "/api/org/delegations", body);
        assertEquals("PENDING", d.path("status").asText());
        long id = d.path("id").asLong();
        fail(senior, "POST", "/api/delegations/" + id + "/consent", "{\"accept\":true}", 403, "FORBIDDEN"); // 보호자 동의는 보호자가
        JsonNode g = ok(child, "GET", "/api/delegations/mine", null);
        assertEquals(1, g.path("asGuardian").size());
        assertTrue(g.path("asGuardian").get(0).path("canConsent").asBoolean());
        assertEquals("ACTIVE", ok(child, "POST", "/api/delegations/" + id + "/consent", "{\"accept\":true}").path("status").asText());

        // 앱 계정이 없는 대상의 보호자 동의는 담당자가 받았음을 확인해야 등록된다
        fail(mgr, "POST", "/api/org/delegations", "{\"elderId\":" + e1.id + ",\"consentMethod\":\"GUARDIAN\",\"scopes\":[\"CHECK_IN\"]}", 400, "CONSENT_REQUIRED");
        fail(mgr, "POST", "/api/org/delegations", "{\"elderId\":" + e1.id + ",\"consentMethod\":\"GUARDIAN\",\"scopes\":[\"CHECK_IN\"],\"guardianUserId\":" + child.id + "}", 400, "GUARDIAN_NOT_LINKED");
        assertEquals("ACTIVE", ok(mgr, "POST", "/api/org/delegations", "{\"elderId\":" + e1.id + ",\"consentMethod\":\"GUARDIAN\",\"scopes\":[\"CHECK_IN\"],\"consentConfirmed\":true}").path("status").asText());
    }

    // ===================== 2. 기관 공고 3종 =====================
    String notice(String kind, int cap, LocalDate date, String extra) {
        return "{\"kind\":\"" + kind + "\",\"title\":\"어르신 산책 동행\",\"detail\":\"공원까지 천천히 산책을 함께해요\",\"category\":\"말동무·정서\",\"area\":\"행복동\","
                + "\"place\":\"행복동 주민센터 앞\",\"date\":\"" + date + "\",\"startHour\":10,\"hours\":2,\"capacity\":" + cap + extra + "}";
    }

    @Test
    void 기관_공고는_종류별_인원_규칙이_있고_신청은_담당자가_수락해야_확정된다() throws Exception {
        fail(v1, "POST", "/api/org/notices", notice("VOLUNTEER", 1, today, ""), 403, "FORBIDDEN");
        fail(mgr, "POST", "/api/org/notices", notice("VOLUNTEER", 3, today, ""), 400, "INVALID_CAPACITY");
        fail(mgr, "POST", "/api/org/notices", notice("GROUP", 1, today, ""), 400, "INVALID_CAPACITY");
        fail(mgr, "POST", "/api/org/notices", notice("EVENT", 2, today, ""), 400, "INVALID_KIND");
        fail(mgr, "POST", "/api/org/notices", notice("VOLUNTEER", 1, today, "").replace("공원까지 천천히 산책을 함께해요", "목욕을 시켜 주세요"), 400, "FORBIDDEN_REQUEST");

        JsonNode n = ok(mgr, "POST", "/api/org/notices", notice("VOLUNTEER", 1, today, ""));
        long id = n.path("id").asLong();
        assertEquals("VOLUNTEER", n.path("kind").asText()); assertEquals("봉사자 모집", n.path("kindLabel").asText());
        assertEquals("모집 중", n.path("stateLabel").asText()); assertTrue(n.path("earns").asBoolean()); assertEquals(2.0, n.path("estEarn").asDouble());
        GroupPost g = groupPosts.findById(id).orElseThrow();
        assertEquals("RECRUIT", g.kind); assertEquals("VOLUNTEER", g.orgKind); // 단체 봉사 표를 넓혀 쓴다

        // 예전 단체 봉사 목록에는 섞이지 않고, 예전 선착순 참여로는 들어올 수 없다
        assertEquals(0, ok(v1, "GET", "/api/group-posts", null).size());
        assertEquals(0, ok(mgr, "GET", "/api/group-posts/mine", null).size());
        fail(v1, "POST", "/api/group-posts/" + id + "/join", null, 409, "CANNOT_JOIN");
        assertEquals(1, ok(v1, "GET", "/api/org-notices?kind=VOLUNTEER", null).size());

        fail(noId, "POST", "/api/org-notices/" + id + "/apply", "{}", 400, "ID_NOT_VERIFIED");
        fail(mgr, "POST", "/api/org-notices/" + id + "/apply", "{}", 403, "FORBIDDEN");
        fail(v1, "POST", "/api/org-notices/" + id + "/apply", "{\"headCount\":2}", 400, "TEAM_NOT_ALLOWED");
        fail(v1, "POST", "/api/org-notices/" + id + "/apply", "{\"message\":\"010-1234-5678 로 연락 주세요\"}", 400, "PII_NOT_ALLOWED");
        JsonNode a1 = ok(v1, "POST", "/api/org-notices/" + id + "/apply", "{\"message\":\"오전에 갈 수 있어요\"}");
        assertEquals("APPLIED", a1.path("myStatus").asText());
        assertTrue(a1.path("applications").isMissingNode()); // 신청자 목록은 담당자만
        fail(v1, "POST", "/api/org-notices/" + id + "/apply", "{}", 409, "CANNOT_APPLY");
        ok(v2, "POST", "/api/org-notices/" + id + "/apply", "{}");

        JsonNode box = ok(mgr, "GET", "/api/org/notice-applications", null);
        assertEquals(2, box.path("counts").path("todo").asInt()); assertEquals(2, box.path("items").size());
        assertEquals(0, ok(other, "GET", "/api/org/notice-applications", null).path("items").size());
        fail(other, "GET", "/api/org/notices/" + id, null, 403, "FORBIDDEN");
        JsonNode det = ok(mgr2, "GET", "/api/org/notices/" + id, null); // 같은 기관 담당자는 함께 본다
        assertEquals(2, det.path("applications").size()); assertEquals(2, det.path("pending").asInt()); assertEquals(0, det.path("confirmed").asInt());
        long app1 = det.path("applications").get(0).path("id").asLong(), app2 = det.path("applications").get(1).path("id").asLong();

        fail(other, "POST", "/api/org/notice-applications/" + app1 + "/approve", null, 403, "FORBIDDEN");
        fail(v1, "POST", "/api/org/notice-applications/" + app1 + "/approve", null, 403, "FORBIDDEN");
        assertEquals("JOINED", ok(mgr, "POST", "/api/org/notice-applications/" + app1 + "/approve", null).path("status").asText());
        fail(mgr, "POST", "/api/org/notice-applications/" + app1 + "/approve", null, 409, "INVALID_STATE");
        fail(mgr, "POST", "/api/org/notice-applications/" + app2 + "/approve", null, 409, "FULL"); // 정원 1명
        assertEquals("REJECTED", ok(mgr, "POST", "/api/org/notice-applications/" + app2 + "/reject", "{\"reason\":\"이번에는 인원이 찼어요\"}").path("status").asText());
        assertTrue(notis.findByUserIdOrderByCreatedAtDescIdDesc(v1.id).stream().anyMatch(x -> "ORG_NOTICE".equals(x.type) && x.title.contains("확정")));
        assertEquals("JOINED", ok(v1, "GET", "/api/org-notices/mine", null).get(0).path("myStatus").asText());

        // 마감: 새 신청만 막는다
        assertEquals("마감", ok(mgr, "POST", "/api/org/notices/" + id + "/close", null).path("stateLabel").asText());
        fail(v3, "POST", "/api/org-notices/" + id + "/apply", "{}", 409, "CANNOT_APPLY");
        fail(mgr, "POST", "/api/org/notices/" + id + "/close", null, 409, "ALREADY_CLOSED");

        // 참석 확인 → 활동 시간 × 1.0 적립(받는 쪽 차감 없음)
        fail(other, "POST", "/api/org/notices/" + id + "/complete", "{\"attended\":[" + v1.id + "]}", 403, "FORBIDDEN");
        JsonNode done = ok(mgr, "POST", "/api/org/notices/" + id + "/complete", "{\"attended\":[" + v1.id + "]}");
        assertEquals(2.0, done.path("result").path("earnedEach").asDouble());
        assertEquals("완료", done.path("stateLabel").asText());
        assertEquals(2.0, bank.summary(v1.id).balance());
        assertEquals(0.0, bank.summary(v2.id).balance());
        JsonNode fin = ok(mgr, "GET", "/api/org/notice-applications?box=done", null);
        assertEquals(2, fin.path("items").size());
    }

    @Test
    void 단체_봉사_공고는_함께_오는_인원을_정원에_세고_내리면_신청자에게_알린다() throws Exception {
        long id = ok(mgr, "POST", "/api/org/notices", notice("GROUP", 3, today.plusDays(3), ",\"teamAllowed\":true,\"supplies\":\"편한 신발\"")).path("id").asLong();
        ok(v1, "POST", "/api/org-notices/" + id + "/apply", "{\"headCount\":2,\"message\":\"친구 1명과 같이 갈게요\"}");
        ok(v2, "POST", "/api/org-notices/" + id + "/apply", "{\"headCount\":2}");
        JsonNode det = ok(mgr, "GET", "/api/org/notices/" + id, null);
        assertEquals(4, det.path("applied").asInt());
        ok(mgr, "POST", "/api/org/notice-applications/" + det.path("applications").get(0).path("id").asLong() + "/approve", null);
        fail(mgr, "POST", "/api/org/notice-applications/" + det.path("applications").get(1).path("id").asLong() + "/approve", null, 409, "FULL"); // 2 + 2 > 3
        assertEquals(2, ok(mgr, "GET", "/api/org/notices/" + id, null).path("confirmed").asInt());
        fail(mgr, "POST", "/api/org/notices/" + id + "/complete", "{\"attended\":[" + v1.id + "]}", 409, "TOO_EARLY");
        // 확정된 사람이 취소
        assertNull(ok(v1, "POST", "/api/org-notices/" + id + "/withdraw", null).path("myStatus").textValue());
        assertEquals("LEFT", groupParts.findByUserId(v1.id).get(0).status);
        JsonNode list = ok(mgr, "GET", "/api/org/notices?kind=GROUP", null);
        assertEquals(1, list.path("items").size()); assertEquals(1, list.path("counts").path("GROUP").asInt()); assertEquals(1, list.path("pendingApplications").asInt());
        assertEquals(3, list.path("kinds").size());
        assertEquals("CANCELLED", ok(mgr, "DELETE", "/api/org/notices/" + id, null).path("status").asText());
        assertTrue(notis.findByUserIdOrderByCreatedAtDescIdDesc(v2.id).stream().anyMatch(x -> x.title.contains("취소")));
        assertEquals(0, ok(v3, "GET", "/api/org-notices", null).size());
    }

    @Test
    void 해_드려요는_방문_날짜를_정해_확정하고_시간이_적립되지_않는다() throws Exception {
        String body = "{\"kind\":\"SERVICE\",\"title\":\"생활 상담 방문 (무료)\",\"detail\":\"기관 직원이 찾아가 이야기를 듣고 공공 서비스 신청을 도와드려요\",\"category\":\"생활 정리\","
                + "\"area\":\"행복동\",\"whenText\":\"평일 오후 2~5시\",\"capacity\":2,\"target\":\"SENIOR65\"}";
        fail(mgr, "POST", "/api/org/notices", body.replace(",\"whenText\":\"평일 오후 2~5시\"", ""), 400, "WHEN_REQUIRED");
        JsonNode n = ok(mgr, "POST", "/api/org/notices", body);
        long id = n.path("id").asLong();
        assertEquals("해 드려요", n.path("kindLabel").asText()); assertEquals("신청 받는 중", n.path("stateLabel").asText());
        assertFalse(n.path("earns").asBoolean()); assertTrue(n.path("estEarn").isNull()); assertEquals("65세 이상", n.path("targetLabel").asText());
        // 도움을 받는 신청이라 신분증 인증이 없어도 된다
        ok(noId, "POST", "/api/org-notices/" + id + "/apply", "{\"message\":\"상담 받고 싶어요\"}");
        long app = ok(mgr, "GET", "/api/org/notices/" + id, null).path("applications").get(0).path("id").asLong();
        fail(mgr, "POST", "/api/org/notice-applications/" + app + "/approve", "{}", 400, "VISIT_REQUIRED");
        fail(mgr, "POST", "/api/org/notice-applications/" + app + "/served", null, 409, "INVALID_STATE");
        JsonNode okd = ok(mgr, "POST", "/api/org/notice-applications/" + app + "/approve", "{\"visitDate\":\"" + today + "\",\"visitStartHour\":14}");
        assertEquals("JOINED", okd.path("status").asText()); assertEquals(today.toString(), okd.path("visit").path("date").asText());
        assertEquals(today.toString(), ok(noId, "GET", "/api/org-notices/" + id, null).path("myVisit").path("date").asText());
        fail(mgr, "POST", "/api/org/notices/" + id + "/complete", "{\"attended\":[" + noId.id + "]}", 400, "INVALID_KIND");
        fail(mgr, "POST", "/api/group-posts/" + id + "/complete", "{\"attended\":[" + noId.id + "]}", 400, "INVALID_KIND"); // 예전 주소로도 적립되지 않는다
        assertEquals("SERVED", ok(mgr, "POST", "/api/org/notice-applications/" + app + "/served", null).path("status").asText());
        assertEquals(0.0, bank.summary(noId.id).balance());
        assertTrue(bank.entries(noId.id).isEmpty());
        assertEquals(0, ok(noId, "GET", "/api/group-posts/schedule", null).size()); // 봉사 일정으로 잡히지 않는다
    }

    // ===================== 3. 홈 봉사자 현황 =====================
    @Test
    void 봉사자_현황은_내_대상과_엮인_봉사자만_상태별로_센다() throws Exception {
        Elder theirs = elder("남의대상", mgr2);
        req(e1, v1, today, 14.5, ReqStatus.IN_PROGRESS);
        req(e1, v1, today.plusDays(2), 10, ReqStatus.CONFIRMED);
        req(e2, v2, today.plusDays(1), 10, ReqStatus.CONFIRMED);
        for (int i = 1; i <= 3; i++) req(e1, v3, today.minusDays(7L * i), 10, ReqStatus.DONE); // 단골(3회)
        req(e2, child, today.plusDays(4), 9, ReqStatus.SENT);
        req(e1, noId, today.minusDays(200), 9, ReqStatus.DONE); // 오래전 한 번 → 안 보인다
        req(theirs, senior, today, 9, ReqStatus.IN_PROGRESS); // 남의 대상
        fail(v1, "GET", "/api/org/volunteers", null, 403, "FORBIDDEN");
        JsonNode j = ok(mgr, "GET", "/api/org/volunteers", null);
        JsonNode c = j.path("counts");
        assertEquals(4, c.path("total").asInt()); assertEquals(1, c.path("activeNow").asInt()); assertEquals(1, c.path("scheduled").asInt());
        assertEquals(1, c.path("pending").asInt()); assertEquals(1, c.path("idle").asInt()); assertEquals(1, c.path("regulars").asInt()); assertEquals(0, c.path("today").asInt());
        JsonNode first = j.path("items").get(0);
        assertEquals(v1.id.longValue(), first.path("volunteerId").asLong()); assertEquals("활동 중", first.path("stateLabel").asText());
        assertEquals("오늘 14:30 이순례 님", first.path("sub").asText());
        JsonNode last = j.path("items").get(3);
        assertEquals(v3.id.longValue(), last.path("volunteerId").asLong()); assertEquals("대기", last.path("stateLabel").asText());
        assertEquals("단골 1명", last.path("sub").asText()); assertEquals(3, last.path("doneCount").asInt());
        assertFalse(j.toString().contains("남의대상"));
        JsonNode lim = ok(mgr, "GET", "/api/org/volunteers?limit=2", null);
        assertEquals(2, lim.path("items").size()); assertEquals(4, lim.path("counts").path("total").asInt());
        assertEquals(0, ok(mgr2, "GET", "/api/org/volunteers", null).path("counts").path("regulars").asInt());
    }

    // ===================== 4. AI 도우미(규칙 모드) =====================
    JsonNode ask(User u, String q) throws Exception { return ok(u, "POST", "/api/org/assistant", om.writeValueAsString(java.util.Map.of("question", q, "screen", "O-HOME-01"))); }

    @Test
    void AI_도우미는_내_자료로_계산한_숫자로_답하고_아무것도_실행하지_않는다() throws Exception {
        fail(v1, "POST", "/api/org/assistant", "{\"question\":\"안녕\"}", 403, "FORBIDDEN");
        fail(mgr, "POST", "/api/org/assistant", "{\"question\":\"  \"}", 400, "QUESTION_REQUIRED");
        JsonNode intro = ok(mgr, "GET", "/api/org/assistant", null);
        assertTrue(intro.path("suggestions").size() >= 4);

        // 프로토타입 예시 질문 4개
        for (int w : new int[]{7, 14, 21}) req(e1, v1, today.plusDays(1).minusDays(w), 10, ReqStatus.DONE); // 내일 요일에 방문 흐름 → 빈칸
        JsonNode gaps = ask(mgr, "이번 주 빈칸 정리해줘");
        assertEquals("GAPS", gaps.path("intent").asText()); assertEquals("rule", gaps.path("source").asText()); assertFalse(gaps.path("executed").asBoolean());
        int g = gaps.path("facts").path("gaps").asInt();
        assertTrue(gaps.path("text").asText().contains(g == 0 ? "없어요" : g + "개"));
        assertEquals("/o/schedule", gaps.path("card").path("link").asText());

        JsonNode chk = ask(mgr, "먼저 안부 물어볼 분 알려줘");
        assertEquals("CHECK_FIRST", chk.path("intent").asText());
        assertEquals(2, chk.path("facts").path("total").asInt());
        assertTrue(chk.path("text").asText().contains("진단이 아니라") || chk.path("text").asText().contains("없어요"));

        JsonNode wx = ask(mgr, "한파 대비 할 일 정리해줘");
        assertEquals("WEATHER", wx.path("intent").asText());
        assertEquals(0, wx.path("facts").path("alerts").asInt());
        assertTrue(wx.path("text").asText().contains("특보가 없어요"));

        JsonNode dr = ask(mgr, "오덕수 님 다음 주 동행 공고 올려줘");
        assertEquals("DRAFT", dr.path("intent").asText());
        assertEquals(e2.id.longValue(), dr.path("card").path("draft").path("elderId").asLong());
        assertEquals("동행", dr.path("card").path("draft").path("category").asText());
        assertTrue(dr.path("text").asText().contains("아직 올린 것은 아니에요"));
        assertTrue(dr.path("text").asText().contains("대신하지 않아요")); // "올려줘" → 결정은 사람이
        assertTrue(dr.path("card").path("link").asText().startsWith("/write/need?elderId=" + e2.id));
        assertEquals(0, posts.count()); // 초안만 보여 주고 공고는 만들지 않는다
        // 남의 대상 이름으로는 초안이 만들어지지 않는다
        elder("강만수", mgr2);
        JsonNode no = ask(mgr, "강만수 님 장보기 요청 올려줘");
        assertTrue(no.path("card").path("draft").isMissingNode());
        assertFalse(no.path("card").toString().contains("강만수"));

        // 숫자는 실제 자료
        req(e1, v2, today.plusDays(2), 10, ReqStatus.VOL_ACCEPTED);
        req(e2, v2, today.minusDays(1), 10, ReqStatus.LOG_SUBMITTED);
        long nid = ok(mgr, "POST", "/api/org/notices", notice("VOLUNTEER", 1, today.plusDays(1), "")).path("id").asLong();
        ok(v1, "POST", "/api/org-notices/" + nid + "/apply", "{}");
        JsonNode todo = ask(mgr, "오늘 처리할 일 알려줘");
        assertEquals("TODO", todo.path("intent").asText());
        assertEquals(1, todo.path("facts").path("finalAccept").asInt()); assertEquals(1, todo.path("facts").path("logs").asInt());
        assertEquals(1, todo.path("facts").path("applications").asInt()); assertEquals(3, todo.path("facts").path("total").asInt());
        assertTrue(todo.path("text").asText().contains("모두 3건"));
        assertEquals(0, ask(mgr2, "오늘 처리할 일 알려줘").path("facts").path("finalAccept").asInt()); // 남의 대상 건은 세지 않는다

        JsonNode apps = ask(mgr, "기관 공고 신청 몇 건이야?");
        assertEquals("APPLICATIONS", apps.path("intent").asText()); assertEquals(1, apps.path("facts").path("todo").asInt());
        ok(mgr, "POST", "/api/org/delegations", paper(e1.id, ",\"consentConfirmed\":true"));
        JsonNode dl = ask(mgr, "만료되는 위임 알려줘");
        assertEquals("DELEGATION", dl.path("intent").asText()); assertEquals(1, dl.path("facts").path("active").asInt()); assertEquals(1, dl.path("facts").path("none").asInt());
        assertEquals("VOLUNTEERS", ask(mgr, "봉사자 현황 알려줘").path("intent").asText());
        assertEquals("HELP", ask(mgr, "안녕하세요").path("intent").asText());

        // 진단하지 않는다
        JsonNode dx = ask(mgr, "이순례 님 치매인가요?");
        assertEquals("NO_DIAGNOSIS", dx.path("intent").asText());
        assertTrue(dx.path("text").asText().contains("판단하는 말은 하지 않아요"));
        // 질문에 섞인 연락처는 되돌려주지 않는다
        assertFalse(ask(mgr, "빈칸 알려줘 010-1234-5678").path("question").asText().contains("5678"));
    }
}
