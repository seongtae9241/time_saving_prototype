package com.timesavings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.domain.Enums.*;
import com.timesavings.domain.GroupPost;
import com.timesavings.domain.Organization;
import com.timesavings.domain.User;
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
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기능명세서 — 단체 봉사: 기능 U-10(단체 공고 목록·참여), 기능 U-11(사용자 단체 공고 올리기), 기능 O-07(담당자 단체 공고).
 * AI 를 부르지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GroupTest {
    @Autowired UserRepo users;
    @Autowired OrganizationRepo orgs;
    @Autowired GroupPostRepo groupPosts;
    @Autowired PostRepo postRepo;
    @Autowired RequestRepo requestRepo;
    @Autowired NotificationRepo notis;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    User leader, a, b, c, noId, mgr, mgr2, otherMgr, admin;
    Organization org, org2;
    final LocalDate day = LocalDate.now().plusDays(3);

    User user(String id, Role role, Long orgId, VerifyStatus idStatus) {
        User u = new User();
        u.loginId = "grp" + id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "30대";
        u.idStatus = idStatus; u.orgId = orgId; u.phone = "010-0000-" + (5000 + users.count());
        return users.save(u);
    }

    Organization org(String name) {
        Organization o = new Organization();
        o.name = name; o.status = OrgStatus.APPROVED;
        return orgs.save(o);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    ResultActions send(User u, String method, String path, String body) throws Exception {
        var req = (method.equals("POST") ? post(path) : method.equals("DELETE") ? delete(path) : get(path)).header("Authorization", tok(u));
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    String body(String kind, LocalDate date, int capacity, String extra) {
        return "{\"kind\":\"" + kind + "\",\"date\":\"" + date + "\",\"startHour\":10,\"hours\":2,\"area\":\"행복동\",\"category\":\"가벼운 집안일\","
                + "\"title\":\"경로당 청소\",\"detail\":\"창문 닦기와 바닥 청소\",\"capacity\":" + capacity + extra + "}";
    }

    long create(User u, String kind, LocalDate date, int capacity, String extra) throws Exception {
        return json(send(u, "POST", "/api/group-posts", body(kind, date, capacity, extra)).andExpect(status().isOk())).path("id").asLong();
    }

    @BeforeEach
    void setUp() {
        org = org("행복동 주민센터");
        org2 = org("희망동 복지관");
        leader = user("leader", Role.USER, null, VerifyStatus.APPROVED);
        a = user("a", Role.USER, null, VerifyStatus.APPROVED);
        b = user("b", Role.USER, null, VerifyStatus.APPROVED);
        c = user("c", Role.USER, null, VerifyStatus.APPROVED);
        noId = user("noid", Role.USER, null, VerifyStatus.NONE);
        mgr = user("mgr", Role.ORG, org.id, VerifyStatus.NONE);
        mgr2 = user("mgr2", Role.ORG, org.id, VerifyStatus.NONE);
        otherMgr = user("other", Role.ORG, org2.id, VerifyStatus.NONE);
        admin = user("admin", Role.ADMIN, null, VerifyStatus.NONE);
    }

    // ---------------- 기능 O-07: 담당자의 단체 모집 공고 ----------------
    @Test
    void 담당자가_단체_모집_공고를_올리면_단체_목록에만_보인다() throws Exception {
        long posts = postRepo.count();
        JsonNode g = json(send(mgr, "POST", "/api/group-posts", body("RECRUIT", day, 3, ",\"place\":\"행복동 경로당\"")).andExpect(status().isOk()));
        assertEquals("RECRUIT", g.path("kind").asText());
        assertEquals("단체 모집", g.path("kindLabel").asText());
        assertEquals(3, g.path("capacity").asInt());
        assertEquals(0, g.path("joined").asInt());
        assertEquals("행복동 주민센터", g.path("ownerName").asText());
        assertTrue(g.path("byOrg").asBoolean());
        assertEquals(2.0, g.path("estEarn").asDouble()); // 2시간 × 가중치 1.0
        assertEquals(posts, postRepo.count(), "1:1 공고 표에는 들어가지 않는다");
        JsonNode list = json(send(a, "GET", "/api/group-posts", null).andExpect(status().isOk()));
        assertEquals(1, list.size());
        assertTrue(list.get(0).path("canJoin").asBoolean());
        assertEquals(0, json(send(a, "GET", "/api/group-posts?kind=OFFER", null)).size());
        assertEquals(0, json(send(a, "GET", "/api/group-posts?area=희망동", null)).size());
        assertEquals(1, json(send(a, "GET", "/api/group-posts?cat=가벼운 집안일", null)).size());
        // 1:1 공고 목록에는 섞이지 않는다
        assertEquals(0, json(send(a, "GET", "/api/posts", null)).size());
        // 같은 기관의 다른 담당자도 우리 기관 공고로 본다
        assertEquals(1, json(send(mgr2, "GET", "/api/group-posts/mine", null)).size());
        assertEquals(0, json(send(otherMgr, "GET", "/api/group-posts/mine", null)).size());
    }

    @Test
    void 인원은_2명_이상이고_규칙을_지켜야_올릴_수_있다() throws Exception {
        send(mgr, "POST", "/api/group-posts", body("RECRUIT", day, 1, "")).andExpect(status().isBadRequest());
        send(mgr, "POST", "/api/group-posts", body("RECRUIT", day, 51, "")).andExpect(status().isBadRequest());
        send(mgr, "POST", "/api/group-posts", body("RECRUIT", LocalDate.now().minusDays(1), 3, "")).andExpect(status().isBadRequest());
        send(mgr, "POST", "/api/group-posts", body("TEAM", day, 3, "")).andExpect(status().isBadRequest());
        // 가벼운 봉사 6종만
        send(mgr, "POST", "/api/group-posts", body("RECRUIT", day, 3, "").replace("가벼운 집안일", "이삿짐 나르기")).andExpect(status().isBadRequest());
        // 활동 시간은 30분~4시간
        send(mgr, "POST", "/api/group-posts", body("RECRUIT", day, 3, "").replace("\"hours\":2", "\"hours\":5")).andExpect(status().isBadRequest());
        // 금지 요청(신체 돌봄·위험 작업)은 단체 공고에도 적용
        JsonNode e = json(send(mgr, "POST", "/api/group-posts", body("RECRUIT", day, 3, "").replace("창문 닦기와 바닥 청소", "어르신 목욕 시켜 드리기")).andExpect(status().isBadRequest()));
        assertEquals("FORBIDDEN_REQUEST", e.path("code").asText());
        e = json(send(mgr, "POST", "/api/group-posts", body("RECRUIT", day, 3, "").replace("창문 닦기와 바닥 청소", "사다리 타고 지붕 고치기")).andExpect(status().isBadRequest()));
        assertEquals("FORBIDDEN_REQUEST", e.path("code").asText());
        // 연락처·상세 주소는 적지 않는다
        e = json(send(mgr, "POST", "/api/group-posts", body("RECRUIT", day, 3, ",\"place\":\"행복아파트 101동 202호\"")).andExpect(status().isBadRequest()));
        assertEquals("PII_NOT_ALLOWED", e.path("code").asText());
        send(admin, "POST", "/api/group-posts", body("RECRUIT", day, 3, "")).andExpect(status().isForbidden());
        assertEquals(0, groupPosts.count());
    }

    // ---------------- 기능 U-11: 사용자의 단체 봉사 공고 ----------------
    @Test
    void 사용자는_단체_봉사_가능_공고만_올리고_올린_사람도_한_명으로_센다() throws Exception {
        // 모집형은 기관 담당자만
        send(leader, "POST", "/api/group-posts", body("RECRUIT", day, 3, "")).andExpect(status().isForbidden());
        // 신분증 인증이 안 됐으면 못 올린다
        JsonNode e = json(send(noId, "POST", "/api/group-posts", body("OFFER", day, 3, ",\"groupName\":\"우리 동아리\"")).andExpect(status().isBadRequest()));
        assertEquals("ID_NOT_VERIFIED", e.path("code").asText());
        // 단체 이름이 있어야 한다
        e = json(send(leader, "POST", "/api/group-posts", body("OFFER", day, 3, "")).andExpect(status().isBadRequest()));
        assertEquals("GROUP_NAME_REQUIRED", e.path("code").asText());
        // 1명이면 단체 공고가 아니다
        e = json(send(leader, "POST", "/api/group-posts", body("OFFER", day, 1, ",\"groupName\":\"우리 동아리\"")).andExpect(status().isBadRequest()));
        assertEquals("INVALID_CAPACITY", e.path("code").asText());

        JsonNode g = json(send(leader, "POST", "/api/group-posts", body("OFFER", day, 3, ",\"groupName\":\"행복 동아리\"")).andExpect(status().isOk()));
        assertEquals("OFFER", g.path("kind").asText());
        assertEquals("행복 동아리", g.path("groupName").asText());
        assertEquals(1, g.path("joined").asInt());
        assertEquals("JOINED", g.path("myStatus").asText());
        assertTrue(g.path("mine").asBoolean());
        assertFalse(g.path("canLeave").asBoolean());
        assertEquals(1, g.path("participants").size());
        // 올린 사람은 참여 취소가 아니라 공고 내리기
        send(leader, "POST", "/api/group-posts/" + g.path("id").asLong() + "/leave", null).andExpect(status().isConflict());
    }

    // ---------------- 기능 U-10: 참여 신청 ----------------
    @Test
    void 참여_신청은_신분증_인증된_사용자만_정원까지_선착순() throws Exception {
        long id = create(mgr, "RECRUIT", day, 2, "");
        JsonNode e = json(send(noId, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isBadRequest()));
        assertEquals("ID_NOT_VERIFIED", e.path("code").asText());
        send(mgr, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isConflict()); // 담당자는 참여하지 않는다
        long before = notis.count();
        JsonNode g = json(send(a, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk()));
        assertEquals(1, g.path("joined").asInt());
        assertEquals(1, g.path("left").asInt());
        assertEquals("JOINED", g.path("myStatus").asText());
        assertTrue(g.path("canLeave").asBoolean());
        assertFalse(g.has("participants"), "참여자 이름은 올린 쪽에만 보인다");
        assertEquals(before + 1, notis.count()); // 올린 담당자에게 알림
        send(a, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isConflict()); // 두 번 신청 안 됨
        send(b, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());
        e = json(send(c, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isConflict()));
        assertEquals("CANNOT_JOIN", e.path("code").asText());
        g = json(send(c, "GET", "/api/group-posts/" + id, null));
        assertTrue(g.path("full").asBoolean());
        assertFalse(g.path("canJoin").asBoolean());
        // 취소하면 자리가 나고 다른 사람이 들어온다. 취소한 사람도 다시 신청할 수 있다
        send(a, "POST", "/api/group-posts/" + id + "/leave", null).andExpect(status().isOk());
        send(c, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());
        send(a, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isConflict());
        // 담당자는 참여자 이름을 본다
        g = json(send(mgr, "GET", "/api/group-posts/" + id, null));
        assertEquals(2, g.path("participants").size());
        // 통장·1:1 요청은 그대로
        assertEquals(0, requestRepo.count());
        assertEquals(0.0, bank.summary(a.id).balance());
    }

    @Test
    void 같은_시간의_다른_단체_봉사에는_겹쳐_참여할_수_없다() throws Exception {
        long one = create(mgr, "RECRUIT", day, 3, "");
        long two = create(otherMgr, "RECRUIT", day, 3, "");
        long later = json(send(otherMgr, "POST", "/api/group-posts", body("RECRUIT", day, 3, "").replace("\"startHour\":10", "\"startHour\":12"))).path("id").asLong();
        send(a, "POST", "/api/group-posts/" + one + "/join", null).andExpect(status().isOk());
        JsonNode e = json(send(a, "POST", "/api/group-posts/" + two + "/join", null).andExpect(status().isConflict()));
        assertEquals("TIME_OVERLAP", e.path("code").asText());
        send(a, "POST", "/api/group-posts/" + later + "/join", null).andExpect(status().isOk()); // 10~12 와 12~14 는 안 겹친다
    }

    // ---------------- 참석 확인·완료 → 개인별 적립 ----------------
    @Test
    void 담당자가_참석을_확인하면_참석한_사람마다_자기_통장에_적립된다() throws Exception {
        long id = create(mgr, "RECRUIT", LocalDate.now(), 3, "");
        for (User u : new User[]{a, b, c}) send(u, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());
        String att = "{\"attended\":[" + a.id + "," + b.id + "]}";
        send(a, "POST", "/api/group-posts/" + id + "/complete", att).andExpect(status().isForbidden());        // 참여자는 스스로 확인 못 함
        send(otherMgr, "POST", "/api/group-posts/" + id + "/complete", att).andExpect(status().isForbidden()); // 다른 기관도 안 됨
        send(mgr, "POST", "/api/group-posts/" + id + "/complete", "{}").andExpect(status().isBadRequest());
        send(mgr, "POST", "/api/group-posts/" + id + "/complete", "{\"attended\":[" + leader.id + "]}").andExpect(status().isBadRequest());
        JsonNode g = json(send(mgr2, "POST", "/api/group-posts/" + id + "/complete", att).andExpect(status().isOk())); // 같은 기관 담당자
        assertEquals("DONE", g.path("status").asText());
        assertEquals(2, g.path("result").path("attended").asInt());
        assertEquals(1, g.path("result").path("absent").asInt());
        assertEquals(2.0, g.path("result").path("earnedEach").asDouble());
        // 1시간 = 1시간: 2시간 활동 → 각자 2.0시간(가중치 1.0). 불참자는 0. 기관 쪽 차감 없음
        assertEquals(2.0, bank.summary(a.id).balance());
        assertEquals(2.0, bank.summary(b.id).balance());
        assertEquals(2.0, bank.summary(a.id).earnedSelf());
        assertEquals(0.0, bank.summary(c.id).balance());
        assertEquals(0.0, bank.summary(mgr.id).balance());
        // 통장 내역에 단체 봉사 적립이 보인다
        JsonNode bk = json(send(a, "GET", "/api/bank", null).andExpect(status().isOk()));
        assertEquals(1, bk.path("entries").size());
        assertEquals("EARN", bk.path("entries").get(0).path("type").asText());
        assertTrue(bk.path("entries").get(0).path("label").asText().startsWith("단체 봉사 · 경로당 청소"));
        assertEquals(2.0, bk.path("entries").get(0).path("amount").asDouble());
        assertEquals("ATTENDED", json(send(a, "GET", "/api/group-posts/" + id, null)).path("myStatus").asText());
        assertEquals("ABSENT", json(send(c, "GET", "/api/group-posts/" + id, null)).path("myStatus").asText());
        // 두 번 완료할 수 없고(이중 적립 방지), 끝난 공고는 목록에서 빠진다
        send(mgr, "POST", "/api/group-posts/" + id + "/complete", att).andExpect(status().isConflict());
        assertEquals(2.0, bank.summary(a.id).balance());
        assertEquals(0, json(send(a, "GET", "/api/group-posts", null)).size());
        assertEquals(1, json(send(a, "GET", "/api/group-posts/mine", null)).size());
    }

    @Test
    void 활동하는_날_전에는_참석을_확인할_수_없다() throws Exception {
        long id = create(mgr, "RECRUIT", day, 3, "");
        send(a, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());
        JsonNode e = json(send(mgr, "POST", "/api/group-posts/" + id + "/complete", "{\"attended\":[" + a.id + "]}").andExpect(status().isConflict()));
        assertEquals("TOO_EARLY", e.path("code").asText());
        assertEquals(0.0, bank.summary(a.id).balance());
    }

    // ---------------- 제공형: 기관이 받아야 진행되고, 받은 기관 담당자가 확인 ----------------
    @Test
    void 제공형은_기관_담당자가_받은_뒤에_그_기관이_참석을_확인한다() throws Exception {
        long id = create(leader, "OFFER", LocalDate.now(), 3, ",\"groupName\":\"행복 동아리\"");
        send(a, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());
        String att = "{\"attended\":[" + leader.id + "," + a.id + "]}";
        // 올린 사용자가 스스로 완료해 시간을 만들 수 없다
        send(leader, "POST", "/api/group-posts/" + id + "/complete", att).andExpect(status().is4xxClientError());
        JsonNode e = json(send(mgr, "POST", "/api/group-posts/" + id + "/complete", att).andExpect(status().isConflict()));
        assertEquals("NOT_HOSTED", e.path("code").asText());
        send(a, "POST", "/api/group-posts/" + id + "/host", null).andExpect(status().isForbidden()); // 받기는 담당자만
        JsonNode g = json(send(mgr, "POST", "/api/group-posts/" + id + "/host", null).andExpect(status().isOk()));
        assertTrue(g.path("hosted").asBoolean());
        assertEquals("행복동 주민센터", g.path("hostOrgName").asText());
        assertTrue(g.path("canComplete").asBoolean());
        assertEquals(2, g.path("participants").size());
        send(otherMgr, "POST", "/api/group-posts/" + id + "/host", null).andExpect(status().isConflict()); // 한 기관만
        send(otherMgr, "POST", "/api/group-posts/" + id + "/complete", att).andExpect(status().isForbidden());
        send(mgr, "POST", "/api/group-posts/" + id + "/complete", att).andExpect(status().isOk());
        assertEquals(2.0, bank.summary(leader.id).balance());
        assertEquals(2.0, bank.summary(a.id).balance());
    }

    @Test
    void 담당자도_기관_봉사단의_제공형_공고를_올리고_받기를_취소할_수_있다() throws Exception {
        long id = create(mgr, "OFFER", day, 4, ",\"groupName\":\"주민센터 청소년 봉사단\"");
        JsonNode g = json(send(a, "GET", "/api/group-posts/" + id, null));
        assertEquals(0, g.path("joined").asInt()); // 담당자는 인원에 들어가지 않는다
        assertEquals("주민센터 청소년 봉사단", g.path("groupName").asText());
        send(otherMgr, "POST", "/api/group-posts/" + id + "/host", null).andExpect(status().isOk());
        assertEquals(1, json(send(otherMgr, "GET", "/api/group-posts/mine", null)).size());
        send(mgr, "POST", "/api/group-posts/" + id + "/unhost", null).andExpect(status().isForbidden());
        g = json(send(otherMgr, "POST", "/api/group-posts/" + id + "/unhost", null).andExpect(status().isOk()));
        assertFalse(g.path("hosted").asBoolean());
        assertTrue(g.path("canHost").asBoolean());
    }

    @Test
    void 올린_쪽만_공고를_내릴_수_있고_내리면_참여할_수_없다() throws Exception {
        long id = create(mgr, "RECRUIT", day, 3, "");
        send(a, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());
        send(a, "DELETE", "/api/group-posts/" + id, null).andExpect(status().isForbidden());
        send(otherMgr, "DELETE", "/api/group-posts/" + id, null).andExpect(status().isForbidden());
        long before = notis.count();
        JsonNode g = json(send(mgr2, "DELETE", "/api/group-posts/" + id, null).andExpect(status().isOk()));
        assertEquals("CANCELLED", g.path("status").asText());
        assertEquals(before + 1, notis.count()); // 참여자에게 알림
        send(b, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isConflict());
        assertEquals(0, json(send(b, "GET", "/api/group-posts", null)).size());
        assertEquals(GroupPost.class, groupPosts.findById(id).orElseThrow().getClass()); // 기록은 남는다
    }

    // ---------------- 플래너 달력·내 활동 ----------------
    @Test
    void 참여한_단체_봉사는_플래너_달력과_내_일정에_보인다() throws Exception {
        long id = create(mgr, "RECRUIT", LocalDate.now(), 3, "");
        send(a, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());
        String month = YearMonth.now().toString();
        JsonNode sch = json(send(a, "GET", "/api/group-posts/schedule?month=" + month, null).andExpect(status().isOk()));
        assertEquals(1, sch.size());
        assertEquals("give", sch.get(0).path("kind").asText());
        assertEquals(id, sch.get(0).path("groupPostId").asLong());
        assertEquals("[단체] 경로당 청소", sch.get(0).path("title").asText());
        assertEquals(2.0, sch.get(0).path("amount").asDouble());
        send(a, "GET", "/api/group-posts/schedule?month=11월", null).andExpect(status().isBadRequest());
        JsonNode pl = json(send(a, "GET", "/api/me/planner?month=" + month, null).andExpect(status().isOk()));
        long n = 0;
        for (JsonNode x : pl.path("items")) if (x.path("groupPostId").asLong() == id) n++;
        assertEquals(1, n);
        // 참여하지 않은 사람 달력에는 없다
        pl = json(send(b, "GET", "/api/me/planner?month=" + month, null).andExpect(status().isOk()));
        for (JsonNode x : pl.path("items")) assertFalse(x.has("groupPostId"));
        // 취소하면 달력에서 빠진다
        send(a, "POST", "/api/group-posts/" + id + "/leave", null).andExpect(status().isOk());
        assertEquals(0, json(send(a, "GET", "/api/group-posts/schedule", null)).size());
    }
}
