package com.timesavings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.domain.Enums.*;
import com.timesavings.domain.Elder;
import com.timesavings.domain.Organization;
import com.timesavings.domain.Rating;
import com.timesavings.domain.Request;
import com.timesavings.domain.Post;
import com.timesavings.domain.User;
import com.timesavings.repo.*;
import com.timesavings.service.AdminReportService;
import com.timesavings.service.AnalyticsService;
import com.timesavings.service.AuthService;
import com.timesavings.service.Rules;
import org.junit.jupiter.api.AfterEach;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기능명세서 구역 사이 연결(통합):
 * - 단체 봉사 참여·올리기에도 봉사 자격 검사(기능 U-34 첫 활동 교육 필수 설정)를 적용한다.
 * - 참석이 확인된 단체 봉사를 봉사 확인서·월별 리포트(기능 U-25)·월별 횟수 요약·플래너 목표·주 보기·운영자 통계에 넣는다.
 * - 운영자가 더한 카테고리(기능 A-15)가 통계에서 깨지거나 빠지지 않는다.
 * AI 를 부르지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FuncLinkTest {
    @Autowired UserRepo users;
    @Autowired OrganizationRepo orgs;
    @Autowired AuthService auth;
    @Autowired ElderRepo elders;
    @Autowired PostRepo postRepo;
    @Autowired RequestRepo requests;
    @Autowired RatingRepo ratings;
    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    User a, b, mgr, admin;
    Organization org;

    User user(String id, Role role, Long orgId, VerifyStatus idStatus) {
        User u = new User();
        u.loginId = "lnk" + id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "30대";
        u.idStatus = idStatus; u.orgId = orgId; u.phone = "010-0000-" + (7000 + users.count());
        return users.save(u);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    ResultActions send(User u, String method, String path, String body) throws Exception {
        var req = (method.equals("POST") ? post(path) : method.equals("PUT") ? put(path) : get(path)).header("Authorization", tok(u));
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    String body(String kind, LocalDate date, int capacity, String extra) {
        return "{\"kind\":\"" + kind + "\",\"date\":\"" + date + "\",\"startHour\":10,\"hours\":2,\"area\":\"행복동\",\"category\":\"가벼운 집안일\","
                + "\"title\":\"경로당 청소\",\"capacity\":" + capacity + extra + "}";
    }

    long create(User u, String kind, LocalDate date, int capacity, String extra) throws Exception {
        return json(send(u, "POST", "/api/group-posts", body(kind, date, capacity, extra)).andExpect(status().isOk())).path("id").asLong();
    }

    void eduRequired(boolean required) throws Exception {
        send(admin, "PUT", "/api/admin/verify-items",
                "{\"items\":[{\"key\":\"id\",\"on\":true,\"required\":true},{\"key\":\"edu\",\"on\":true,\"required\":" + required + "},{\"key\":\"crime\",\"on\":true,\"required\":false}]}")
                .andExpect(status().isOk());
    }

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.name = "연결동 주민센터"; o.status = OrgStatus.APPROVED;
        org = orgs.save(o);
        a = user("a", Role.USER, null, VerifyStatus.APPROVED);
        b = user("b", Role.USER, null, VerifyStatus.APPROVED);
        mgr = user("mgr", Role.ORG, org.id, VerifyStatus.NONE);
        admin = user("admin", Role.ADMIN, null, VerifyStatus.NONE);
    }

    @AfterEach
    void tearDown() { Rules.clearExtraCategories(); }

    // ---------------- 단체 봉사 + 봉사 자격 검사(기능 U-34) ----------------
    @Test
    void 교육이_필수면_교육을_마치지_않은_사용자는_단체_봉사에_참여하거나_올릴_수_없다() throws Exception {
        long id = create(mgr, "RECRUIT", LocalDate.now().plusDays(2), 3, "");
        eduRequired(true);
        // 미이수자: 참여도, 단체 봉사 가능 공고 올리기도 막힌다. 화면에는 까닭이 내려간다
        send(a, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EDU_REQUIRED"));
        JsonNode card = json(send(a, "GET", "/api/group-posts/" + id, null).andExpect(status().isOk()));
        assertFalse(card.path("canJoin").asBoolean());
        assertTrue(card.path("joinBlock").asText().contains("첫 활동 교육"));
        send(a, "POST", "/api/group-posts", body("OFFER", LocalDate.now().plusDays(3), 4, ",\"groupName\":\"연결동 청년 봉사단\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EDU_REQUIRED"));
        // 이수하면 된다
        a.eduDone = true;
        users.save(a);
        send(a, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());
        // 교육을 선택으로 바꾸면 미이수자도 참여할 수 있다
        eduRequired(false);
        send(b, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());
    }

    // ---------------- 단체 봉사를 확인서·리포트·요약·목표·통계에 ----------------
    @Test
    void 참석이_확인된_단체_봉사는_확인서와_월별_리포트와_횟수_요약과_운영자_통계에_들어간다() throws Exception {
        String month = YearMonth.now().toString();
        long before = json(send(admin, "GET", "/api/admin/dashboard", null).andExpect(status().isOk())).path("kpi").path("activities").asLong();
        long typesBefore = json(send(admin, "GET", "/api/admin/stats/types", null).andExpect(status().isOk())).path("doneCount").asLong();
        long id = create(mgr, "RECRUIT", LocalDate.now(), 3, "");
        send(a, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());
        send(b, "POST", "/api/group-posts/" + id + "/join", null).andExpect(status().isOk());

        // 참여 신청만 한 상태: 플래너 목표의 확정 시간과 주 보기에 들어가고, 확인서·요약에는 아직 없다
        JsonNode pl = json(send(a, "GET", "/api/me/planner?month=" + month, null).andExpect(status().isOk()));
        assertEquals(2.0, pl.path("goal").path("confirmed").asDouble());
        assertEquals(2.0, pl.path("goal").path("left").asDouble()); // 기본 목표 4시간 − 단체 봉사 2시간
        JsonNode wk = json(send(a, "GET", "/api/me/planner/week?mode=give", null).andExpect(status().isOk()));
        long inWeek = 0;
        for (JsonNode x : wk.path("items")) if (x.path("groupPostId").asLong() == id) inWeek++;
        assertEquals(1, inWeek);
        assertEquals(0, json(send(a, "GET", "/api/me/planner/week?mode=recv", null)).path("items").size());
        assertEquals(0, json(send(a, "GET", "/api/certificate", null).andExpect(status().isOk())).path("count").asInt());

        // 담당자가 a 만 참석 확인 → a 에게만 2시간
        send(mgr, "POST", "/api/group-posts/" + id + "/complete", "{\"attended\":[" + a.id + "]}").andExpect(status().isOk());

        JsonNode cert = json(send(a, "GET", "/api/certificate", null).andExpect(status().isOk()));
        assertEquals(1, cert.path("count").asInt());
        assertEquals(1, cert.path("groupCount").asInt());
        assertEquals(2.0, cert.path("totalHours").asDouble());
        assertEquals(2.0, cert.path("totalEarned").asDouble());
        assertEquals("가벼운 집안일", cert.path("byCategory").get(0).path("category").asText());
        assertTrue(cert.path("items").get(0).path("group").asBoolean());
        assertEquals("연결동 주민센터 (단체 봉사)", cert.path("items").get(0).path("recipient").asText());
        assertTrue(cert.path("summary").asText().contains("1회"));
        // 불참으로 기록된 b 의 확인서에는 없다
        assertEquals(0, json(send(b, "GET", "/api/certificate", null)).path("count").asInt());
        // 기간 밖이면 빠진다
        assertEquals(0, json(send(a, "GET", "/api/certificate?to=" + LocalDate.now().minusDays(1), null)).path("count").asInt());

        JsonNode sum = json(send(a, "GET", "/api/my/activity-summary?months=3", null).andExpect(status().isOk()));
        assertEquals(1, sum.path("totalHelpCount").asInt());
        assertEquals(1, sum.path("groupHelpCount").asInt());
        assertEquals(1, sum.path("months").get(2).path("helpCount").asInt());
        assertEquals(2.0, sum.path("months").get(2).path("helpHours").asDouble());
        assertEquals(0, json(send(b, "GET", "/api/my/activity-summary", null)).path("totalHelpCount").asInt());

        JsonNode rep = json(send(a, "GET", "/api/me/month-report", null).andExpect(status().isOk()));
        assertEquals(1, rep.path("give").path("count").asInt());
        assertEquals(2.0, rep.path("give").path("hours").asDouble());
        assertEquals("가벼운 집안일", rep.path("give").path("top").asText());
        assertEquals(1, rep.path("change").path("giveCount").asInt());

        // 운영자: 이번 달 활동 수와 봉사 유형 리포트에 한 사람 한 번 = 한 건
        JsonNode dash = json(send(admin, "GET", "/api/admin/dashboard", null).andExpect(status().isOk()));
        assertEquals(before + 1, dash.path("kpi").path("activities").asLong());
        assertEquals(1, dash.path("kpi").path("groupActivities").asLong());
        JsonNode types = json(send(admin, "GET", "/api/admin/stats/types", null).andExpect(status().isOk()));
        assertEquals(typesBefore + 1, types.path("doneCount").asLong());
        assertEquals(1, types.path("groupDoneCount").asLong());
        send(admin, "GET", "/api/admin/analytics", null).andExpect(status().isOk());
    }

    // ---------------- 더한 카테고리(기능 A-15)와 통계 ----------------
    @Test
    void 운영자가_더한_카테고리는_통계에서_깨지지_않고_자기_이름으로_집계된다() {
        Rules.putExtraCategory("반려동물 산책", Level.BASIC);
        LocalDateTime now = LocalDateTime.now();
        Post p = new Post();
        p.type = PostType.NEED; p.status = PostStatus.OPEN; p.area = "행복동"; p.date = LocalDate.now().plusDays(2);
        p.categories = new ArrayList<>(List.of("반려동물 산책"));
        p.createdAt = now.minusDays(1);
        Post unknown = new Post();
        unknown.type = PostType.NEED; unknown.status = PostStatus.OPEN; unknown.area = "행복동"; unknown.date = LocalDate.now().plusDays(2);
        unknown.categories = new ArrayList<>(List.of("없는 종류"));
        unknown.createdAt = now.minusDays(1);
        List<Post> posts = List.of(p, unknown);

        List<Map<String, Object>> sd = AnalyticsService.supplyDemand(posts, List.of(), now.minusDays(30));
        Map<String, Object> row = sd.stream().filter(m -> "반려동물 산책".equals(m.get("category"))).findFirst().orElseThrow();
        assertEquals(1, row.get("demand"));
        assertTrue(sd.stream().noneMatch(m -> "없는 종류".equals(m.get("category")))); // 카테고리가 아닌 값은 세지 않는다
        assertEquals(Rules.CATEGORIES.size() + 1, sd.size());

        // 예전에는 여기서 NullPointerException 이 났다(지역·계절별 수요·공급)
        List<Map<String, Object>> area = AnalyticsService.areaShortage(posts, List.of(), now.minusDays(30), r -> null);
        assertEquals(1, area.get(0).get("demand"));
        assertEquals(1, AnalyticsService.seasonSupplyDemand(posts, List.of(), now.minusDays(30)).stream().mapToInt(m -> (Integer) m.get("demand")).sum());

        // 다음 계절 예상 수요에도 줄이 생긴다
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> next = (List<Map<String, Object>>) AdminReportService.nextSeason(posts, List.of(), now).get("categories");
        assertTrue(next.stream().anyMatch(m -> "반려동물 산책".equals(m.get("category"))));
        assertEquals(Rules.CATEGORIES.size() + 1, next.size());
    }

    // ======================= 재대조에서 나온 문제 고침 =======================
    Elder elder(String alias) {
        Elder e = new Elder();
        e.alias = alias; e.dong = "행복동"; e.managerId = mgr.id; e.orgId = org.id; e.needs = List.of("말동무·정서"); e.ageGroup = "80대";
        e.caution = "계단이 가팔라요";
        return elders.save(e);
    }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8); }

    // ---------------- 기능 O-04: 대리 정기 요청의 주의사항은 공개 칸으로 새지 않는다 + 브리핑에 이슈 메모 ----------------
    @Test
    void 대리_정기_요청의_방문_주의는_이슈_칸으로_가고_확정된_봉사자의_브리핑에만_나온다() throws Exception {
        Elder e = elder("이순례");
        LocalDate start = LocalDate.now().plusDays(2);
        // 예전 화면처럼 공개 칸(note)에 "방문 시 주의: …"를 넣어 보내도 서버가 이슈 칸으로 옮긴다
        JsonNode rec = json(send(mgr, "POST", "/api/recurring", "{\"startDate\":\"" + start + "\",\"weeks\":4,\"startHour\":10,\"endHour\":12,\"estHours\":1,"
                + "\"area\":\"행복동\",\"categories\":[\"말동무·정서\"],\"title\":\"매주 말벗\",\"elderId\":" + e.id
                + ",\"note\":\"엘리베이터 있어요 방문 시 주의: 귀가 어두우셔서 크게 말해 주세요\"}").andExpect(status().isOk()));
        assertEquals(1, rec.path("createdCount").asInt());
        long postId = rec.path("createdPostIds").get(0).asLong();
        Post p = postRepo.findById(postId).orElseThrow();
        assertEquals("엘리베이터 있어요", p.note);
        assertTrue(p.issueNote.contains("귀가 어두우셔서"));
        // 확정 전 봉사자(공고를 보는 누구나)에게는 주의사항이 가지 않는다
        String seen = body(send(a, "GET", "/api/posts/" + postId, null).andExpect(status().isOk()));
        assertFalse(seen.contains("귀가 어두우셔서"), seen);
        assertFalse(seen.contains("방문 시 주의"), seen);
        assertFalse(body(send(a, "GET", "/api/posts?type=NEED", null).andExpect(status().isOk())).contains("귀가 어두우셔서"));

        // 화면이 이슈 칸(elderIssue)으로 보내면 그대로 이슈로 들어간다
        JsonNode rec2 = json(send(mgr, "POST", "/api/recurring", "{\"startDate\":\"" + start.plusDays(1) + "\",\"weeks\":4,\"startHour\":14,\"endHour\":16,\"estHours\":1,"
                + "\"area\":\"행복동\",\"categories\":[\"말동무·정서\"],\"title\":\"매주 산책\",\"elderId\":" + e.id
                + ",\"note\":\"\",\"elderIssue\":\"천천히 걸어 주세요\"}").andExpect(status().isOk()));
        Post p2 = postRepo.findById(rec2.path("createdPostIds").get(0).asLong()).orElseThrow();
        assertEquals("천천히 걸어 주세요", p2.issueNote);
        assertTrue(p2.note == null || p2.note.isBlank());

        // 확정된 봉사자의 방문 전 브리핑에는 대상 주의사항과 공고의 이슈 메모가 함께 나온다. 다른 사람은 볼 수 없다
        Request r = new Request();
        r.kind = Kind.PROPOSAL; r.senderId = a.id; r.volunteerId = a.id; r.elderId = e.id; r.postId = postId; r.date = p.date;
        r.startHour = 10; r.hours = 1; r.category = "말동무·정서"; r.content = "말벗"; r.weight = 1; r.status = ReqStatus.CONFIRMED;
        requests.save(r);
        String brief = body(send(a, "GET", "/api/requests/" + r.id + "/briefing", null).andExpect(status().isOk()));
        assertTrue(brief.contains("귀가 어두우셔서"), brief);
        assertTrue(brief.contains("계단이 가팔라요"), brief);
        send(b, "GET", "/api/requests/" + r.id + "/briefing", null).andExpect(status().isForbidden());
    }

    // ---------------- 기능 C-03: 기관 주소는 서버에서도 필수 ----------------
    @Test
    void 기관_신청은_주소가_없으면_서버에서도_막힌다() throws Exception {
        long before = orgs.count();
        mvc.perform(post("/api/orgs/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"연결동 복지관\",\"managerName\":\"김담당\",\"proofFileUrl\":\"/api/files/x.pdf\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADDRESS_REQUIRED"));
        mvc.perform(multipart("/api/orgs/apply").file(new org.springframework.mock.web.MockMultipartFile("proof", "p.pdf", "application/pdf", new byte[]{1}))
                        .param("name", "연결동 복지관").param("managerName", "김담당").param("address", "  "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADDRESS_REQUIRED"));
        assertEquals(before, orgs.count());
    }

    // ---------------- 기능 U-18: 평가가 3건 미만이면 평균 별점·칭찬을 보여 주지 않는다 ----------------
    Rating rate(User target, User rater, int stars, String tag) {
        Rating r = new Rating();
        r.requestId = 900000L + ratings.count(); r.raterId = rater.id; r.targetUserId = target.id; r.stars = stars; r.tags = new ArrayList<>(List.of(tag));
        return ratings.save(r);
    }

    @Test
    void 평가가_3건_미만이면_평균_별점과_칭찬이_프로필에_나오지_않는다() throws Exception {
        rate(a, b, 2, "꼼꼼해요");
        JsonNode pf = json(send(b, "GET", "/api/users/" + a.id + "/profile", null).andExpect(status().isOk()));
        assertEquals(0.0, pf.path("ratingAvg").asDouble());   // 한 건뿐이면 그 한 건의 별점이 그대로 드러나므로 가린다
        assertEquals(0, pf.path("ratingCount").asInt());
        assertEquals(0, pf.path("tags").size());
        assertTrue(pf.path("ratingPending").asBoolean());
        JsonNode me = json(send(a, "GET", "/api/me", null).andExpect(status().isOk())); // 평가받은 본인에게도 보이지 않는다
        assertEquals(0.0, me.path("ratingAvg").asDouble());
        rate(a, b, 5, "꼼꼼해요");
        assertEquals(0.0, json(send(b, "GET", "/api/users/" + a.id + "/profile", null)).path("ratingAvg").asDouble());
        rate(a, mgr, 5, "편안해요");
        pf = json(send(b, "GET", "/api/users/" + a.id + "/profile", null).andExpect(status().isOk()));
        assertEquals(4.0, pf.path("ratingAvg").asDouble());   // 3건이 모이면 평균(2+5+5)/3
        assertEquals(3, pf.path("ratingCount").asInt());
        assertEquals("꼼꼼해요", pf.path("tags").get(0).asText());
        assertFalse(pf.path("ratingPending").asBoolean());
        // 평가가 없는 사람은 '모으는 중' 표시가 아니다
        assertFalse(json(send(a, "GET", "/api/users/" + b.id + "/profile", null)).path("ratingPending").asBoolean());
    }

    // ---------------- 기능 U-34: 교육 필수가 켜진 상태에서 세 군데(봉사 제안·봉사 가능 공고·단체 참여) 모두 ----------------
    @Test
    void 교육_필수가_켜져_있으면_미이수자는_세_군데_모두_막히고_이수자는_모두_통과한다() throws Exception {
        eduRequired(true);
        LocalDate d = LocalDate.now().plusDays(3);
        b.eduDone = true; // b = 이수자, a = 미이수자
        users.save(b);
        User rec = user("rec", Role.USER, null, VerifyStatus.APPROVED);
        // 도움 요청 공고 두 건(공고 1개에는 제안 1건만 받으므로 따로)
        String need = "{\"type\":\"NEED\",\"date\":\"" + d + "\",\"startHour\":10,\"endHour\":12,\"area\":\"행복동\",\"categories\":[\"말동무·정서\"],\"title\":\"말벗 부탁드려요\",\"estHours\":1}";
        JsonNode n1 = json(send(rec, "POST", "/api/posts", need).andExpect(status().isOk()));
        long needId = (n1.isArray() ? n1.get(0) : n1).path("id").asLong();
        String proposal = "{\"kind\":\"PROPOSAL\",\"postId\":" + needId + ",\"date\":\"" + d + "\",\"startHour\":10,\"hours\":1,\"category\":\"말동무·정서\",\"content\":\"말벗 해 드릴게요\"}";
        String offer = "{\"type\":\"OFFER\",\"date\":\"" + d + "\",\"startHour\":13,\"endHour\":17,\"area\":\"행복동\",\"categories\":[\"말동무·정서\"],\"title\":\"도와드려요\"}";
        long gid = create(mgr, "RECRUIT", d, 3, "");

        // 미이수자 a: 봉사 제안 · 봉사 가능 공고 등록 · 단체 봉사 참여 모두 EDU_REQUIRED
        send(a, "POST", "/api/requests", proposal).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EDU_REQUIRED"));
        send(a, "POST", "/api/posts", offer).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EDU_REQUIRED"));
        send(a, "POST", "/api/group-posts/" + gid + "/join", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EDU_REQUIRED"));
        // 이수자 b: 세 군데 모두 통과
        send(b, "POST", "/api/requests", proposal).andExpect(status().isOk());
        send(b, "POST", "/api/posts", offer).andExpect(status().isOk());
        send(b, "POST", "/api/group-posts/" + gid + "/join", null).andExpect(status().isOk());
        // 도움 받기(요청 공고)는 교육과 관계없다
        send(a, "POST", "/api/posts", need).andExpect(status().isOk());
    }
}
