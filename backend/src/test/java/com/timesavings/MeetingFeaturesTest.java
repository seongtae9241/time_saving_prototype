package com.timesavings;

import com.timesavings.ai.InsightAi;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import com.timesavings.service.PersonalInsightService.PlanItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 회의반영(2026-11-09) U1·O1·4~6장: 통장 합계, 봉사자 거부·차단, 전달 메모 열람 권한, 최근 리포트, 봉사 플랜, 수요·공급 집계. 규칙 모드. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MeetingFeaturesTest {
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired NotificationRepo notis;
    @Autowired FamilyLinkRepo family;
    @Autowired RequestService reqs;
    @Autowired PostService postSvc;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;

    User vol, vol2, rec, mgr, mgr2;
    Elder elder;
    final LocalDate day = LocalDate.now().plusDays(3);

    User user(String id, Role role, double earned) {
        User u = new User();
        u.loginId = id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "60대";
        u.idStatus = VerifyStatus.APPROVED; u.phone = "010-0000-" + (2000 + id.length());
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, "초기 적립");
        return u;
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    @BeforeEach
    void setUp() {
        vol = user("mvol", Role.USER, 0);
        vol2 = user("mvol2", Role.USER, 0);
        rec = user("mrec", Role.USER, 20);
        mgr = user("mmgr", Role.ORG, 0);
        mgr2 = user("mmgr2", Role.ORG, 0);
        elder = new Elder();
        elder.alias = "이순례"; elder.dong = "행복동"; elder.managerId = mgr.id; elder.needs = List.of("동행"); elder.ageGroup = "80대";
        elders.save(elder);
    }

    Post elderNeed() {
        return postSvc.create(mgr, new PostService.PostCmd(PostType.NEED, day, 10.0, 16.0, "행복동", List.of("말동무·정서"),
                "말벗", "이야기 나눠요", null, 1.0, false, null, elder.id, null, null)).get(0);
    }

    Request propose(User v, Post p) {
        return reqs.create(v, new RequestService.CreateCmd(Kind.PROPOSAL, p.id, null, null, day, 11.0, 1.0, null, "말벗 해 드릴게요", null));
    }

    /** 확정 → 시작 → 종료 → 일지(전달 메모)까지 */
    Request runToLog(User v, Request r, User recipientSide, String memo) {
        if (r.status == ReqStatus.SENT) reqs.accept(recipientSide, r.id);
        reqs.checkIn(v, r.id, r.checkCode, null);
        reqs.checkOut(v, r.id, r.checkCode, null);
        return reqs.submitLog(v, r.id, "이야기 나눔", "이야기를 나눴어요.", null, false, memo);
    }

    @Test
    void U1_통장_전체_적립_사용_합계() throws Exception {
        bank.add(rec.id, LedgerType.USE, -3.0, 3.0, 1.0, null, "사용");
        bank.add(rec.id, LedgerType.HOLD, 2.0, 2.0, 1.0, null, "보류"); // 보류는 합계에서 빠진다
        bank.add(rec.id, LedgerType.GIFT_IN, 1.5, null, null, null, "선물");
        mvc.perform(get("/api/bank").header("Authorization", tok(rec))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned").value(21.5)).andExpect(jsonPath("$.totalUsed").value(3.0))
                .andExpect(jsonPath("$.balance").value(18.5));
    }

    @Test
    void O1_담당자_거부와_받지_않기_후_다시_제안_불가() throws Exception {
        Post p = elderNeed();
        Request r = propose(vol, p);
        reqs.accept(mgr, r.id); // 확정
        assertEquals(ReqStatus.CONFIRMED, r.status);
        String url = "/api/org/requests/" + r.id + "/reject-volunteer";
        mvc.perform(post(url).header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \",\"block\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        mvc.perform(post(url).header("Authorization", tok(mgr2)).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"약속 시간을 자주 바꿔요\",\"block\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.blocked").value(true));
        assertTrue(notis.findAll().stream().anyMatch(n -> vol.id.equals(n.userId) && "MANAGER_REJECTED".equals(n.type)));
        assertEquals(PostStatus.OPEN, p.status); // 날짜가 남은 대상 공고는 다시 열린다

        ApiException e = assertThrows(ApiException.class, () -> propose(vol, p));
        assertEquals("VOLUNTEER_BLOCKED", e.code);
        assertEquals(403, e.status.value());
        assertEquals(ReqStatus.SENT, propose(vol2, p).status); // 다른 봉사자는 그대로

        // 받지 않기 목록·해제
        String list = mvc.perform(get("/api/org/elders/" + elder.id + "/blocks").header("Authorization", tok(mgr)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].reason").value("약속 시간을 자주 바꿔요")).andReturn().getResponse().getContentAsString();
        long blockId = Long.parseLong(list.replaceAll("^\\[\\{\"id\":(\\d+).*$", "$1"));
        mvc.perform(delete("/api/org/blocks/" + blockId).header("Authorization", tok(mgr))).andExpect(status().isOk());
    }

    @Test
    void O1_요청_단계_제안은_거부되고_진행중은_거부_불가() throws Exception {
        Post p = elderNeed();
        Request r = propose(vol, p);
        mvc.perform(post("/api/org/requests/" + r.id + "/reject-volunteer").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"대상이 원하지 않아요\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        Request r2 = propose(vol2, p); // 차단 안 했으니 다른 봉사자·같은 봉사자 모두 가능
        reqs.accept(mgr, r2.id);
        reqs.checkIn(vol2, r2.id, r2.checkCode, null);
        mvc.perform(post("/api/org/requests/" + r2.id + "/reject-volunteer").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isConflict());
    }

    @Test
    void 전달_메모_열람_권한_담당자_받은사람_가족만() throws Exception {
        // 독거노인 건: 메모는 담당자에게 보이고 봉사자는 대상 리포트를 못 본다
        Post p = elderNeed();
        Request r = runToLog(vol, propose(vol, p), mgr, "병원에서 다음 주 재진 안내를 받았어요. 우산을 챙겨 드리기");
        assertEquals("병원에서 다음 주 재진 안내를 받았어요. 우산을 챙겨 드리기", r.careMemo);
        mvc.perform(get("/api/requests/" + r.id).header("Authorization", tok(mgr))).andExpect(jsonPath("$.careMemo").exists());
        mvc.perform(get("/api/requests/" + r.id).header("Authorization", tok(vol))).andExpect(jsonPath("$.careMemo").exists()); // 쓴 사람 본인
        mvc.perform(get("/api/reports/recent?subject=elder:" + elder.id).header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].careMemo").value(r.careMemo))
                .andExpect(jsonPath("$.handover.source").value("rule"))
                .andExpect(jsonPath("$.handover.points[0]").value(org.hamcrest.Matchers.startsWith("병원 안내 사항 있음")));
        mvc.perform(get("/api/reports/recent?subject=elder:" + elder.id).header("Authorization", tok(vol))).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/recent?subject=elder:" + elder.id).header("Authorization", tok(mgr2))).andExpect(status().isForbidden());

        // 일반 사용자 건: 받은 사람·공유 동의한 가족만
        Post offer = postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day, 13.0, 17.0, "행복동", List.of("말동무·정서"),
                "말벗", null, null, null, false, null, null, null, null)).get(0);
        Request ur = reqs.create(rec, new RequestService.CreateCmd(Kind.REQUEST, offer.id, null, null, day, 14.0, 1.0, "말동무·정서", "말벗", null));
        reqs.accept(vol, ur.id);
        reqs.accept(rec, ur.id);
        runToLog(vol, ur, rec, "다음엔 공원 둘레길로 가기로 했어요");
        mvc.perform(get("/api/reports/recent?subject=me").header("Authorization", tok(rec))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].careMemo").value("다음엔 공원 둘레길로 가기로 했어요"));
        User kid = user("mkid", Role.USER, 0);
        FamilyLink f = new FamilyLink();
        f.requesterId = rec.id; f.targetId = kid.id; f.status = LinkStatus.ACCEPTED;
        family.save(f);
        mvc.perform(get("/api/reports/recent?subject=family:" + rec.id).header("Authorization", tok(kid))).andExpect(status().isForbidden());
        f.shareReport = true; // rec 가 kid 에게 공유 동의
        mvc.perform(get("/api/reports/recent?subject=family:" + rec.id).header("Authorization", tok(kid))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].careMemo").value("다음엔 공원 둘레길로 가기로 했어요"));
        mvc.perform(get("/api/reports/recent?subject=family:" + rec.id).header("Authorization", tok(vol2))).andExpect(status().isForbidden());

        // 500자 넘는 메모는 막는다
        Request r3 = propose(vol2, elderNeed());
        reqs.accept(mgr, r3.id);
        reqs.checkIn(vol2, r3.id, r3.checkCode, null);
        reqs.checkOut(vol2, r3.id, r3.checkCode, null);
        assertEquals("MEMO_TOO_LONG", assertThrows(ApiException.class, () -> reqs.submitLog(vol2, r3.id, "x", null, null, false, "가".repeat(501))).code);
    }

    @Test
    void 봉사_플랜_기간은_가중치를_반영해_코드가_계산() {
        // 동행 월 2회 2시간: 1회 3.0(2×1.5), 주 1.5 → 10시간이면 6주
        Map<String, Object> a = PersonalInsightService.compute(10.0, List.of(new PlanItem("동행", 0.5, 2.0)));
        assertEquals(1.5, a.get("weeklyCost"));
        assertEquals(6, a.get("weeks"));
        // 말동무 주 2회 1시간: 주 2.0 → 5주
        assertEquals(5, PersonalInsightService.compute(10.0, List.of(new PlanItem("말동무·정서", 2.0, 1.0))).get("weeks"));
        // 조합: 주 3.5 → 20시간이면 5주
        Map<String, Object> c = PersonalInsightService.compute(20.0, List.of(new PlanItem("동행", 0.5, 2.0), new PlanItem("말동무·정서", 2.0, 1.0)));
        assertEquals(3.5, c.get("weeklyCost"));
        assertEquals(5, c.get("weeks"));
        assertEquals(1.2, PersonalInsightService.compute(48.0, List.of(new PlanItem("장보기·심부름", 1.0, 2.5))).get("plans") instanceof List<?> l
                ? ((Map<?, ?>) l.get(0)).get("weight") : null);
        // 32주 이상이면 개월로 표시
        assertEquals("약 7.4개월(32주)", PersonalInsightService.spanText(32));
    }

    @Test
    void 봉사_플랜_HTTP_본인_사용_조건_반영() throws Exception {
        User newbie = user("mnew", Role.USER, 4); // 직접 적립 4시간 → 아직 내 시간 못 씀
        mvc.perform(post("/api/me/care-plan").header("Authorization", tok(newbie)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plans\":[{\"category\":\"말동무·정서\",\"perWeek\":1,\"hours\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.canUseSelf").value(false))
                .andExpect(jsonPath("$.summary.needToUnlock").value(6.0)).andExpect(jsonPath("$.result.weeks").value(0))
                .andExpect(jsonPath("$.ifUnlocked.weeks").value(4)).andExpect(jsonPath("$.source").value("rule"))
                .andExpect(jsonPath("$.candidates.length()").value(3));
        mvc.perform(post("/api/me/care-plan").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plans\":[{\"category\":\"동행\",\"perWeek\":0.5,\"hours\":2}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.weeks").value(13)).andExpect(jsonPath("$.text").isNotEmpty());
        mvc.perform(post("/api/me/care-plan").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plans\":[{\"category\":\"기타\",\"perWeek\":1,\"hours\":1}]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/me/care-plan").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plans\":[{\"category\":\"동행\",\"perWeek\":0.3,\"hours\":1}]}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/insights").header("Authorization", tok(rec))).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("rule")).andExpect(jsonPath("$.activityText").isNotEmpty());
        mvc.perform(get("/api/me/insights").header("Authorization", tok(mgr))).andExpect(status().isForbidden());
    }

    @Test
    void 수요_공급_집계() {
        LocalDateTime now = LocalDateTime.now();
        List<Post> ps = new ArrayList<>();
        for (int i = 0; i < 3; i++) ps.add(mkPost(PostType.NEED, "동행", now.minusDays(5)));
        ps.add(mkPost(PostType.OFFER, "동행", now.minusDays(5)));
        ps.add(mkPost(PostType.NEED, "동행", now.minusDays(400))); // 기간 밖
        ps.add(mkPost(PostType.OFFER, "디지털 도움", now.minusDays(2)));
        List<Request> rs = new ArrayList<>();
        rs.add(req(Kind.REQUEST, "동행", ReqStatus.DONE, now.minusDays(3)));     // 수요+1, 공급(수락)+1
        rs.add(req(Kind.REQUEST, "동행", ReqStatus.EXPIRED, now.minusDays(3)));  // 수요+1
        rs.add(req(Kind.PROPOSAL, "디지털 도움", ReqStatus.CONFIRMED, now.minusDays(1))); // 공급(수락)+1
        List<Map<String, Object>> sd = AnalyticsService.supplyDemand(ps, rs, now.minusDays(180));
        Map<String, Object> dong = sd.stream().filter(m -> "동행".equals(m.get("category"))).findFirst().orElseThrow();
        assertEquals(5, dong.get("demand"));
        assertEquals(2, dong.get("supply"));
        assertEquals(3, dong.get("gap"));
        assertEquals("동행", sd.get(0).get("category")); // 가장 부족한 것이 맨 앞
        Map<String, Object> dig = sd.stream().filter(m -> "디지털 도움".equals(m.get("category"))).findFirst().orElseThrow();
        assertEquals(0, dig.get("demand"));
        assertEquals(2, dig.get("supply"));
        Map<String, Object> rule = AnalyticsService.insightRule(sd, List.of("거실 형광등이 나가서 갈아 주실 분", "고양이 모래 갈기"));
        assertEquals("동행", ((List<Map<String, Object>>) rule.get("shortages")).get(0).get("category"));
        assertEquals(2, ((List<?>) rule.get("newCategories")).size());
        assertFalse(AnalyticsService.fitsCategory("거실 형광등이 나가서 갈아 주실 분"));
        assertTrue(AnalyticsService.fitsCategory("병원 같이 가 주세요"));
    }

    @Test
    void 운영자_인사이트는_캐시되고_다시_분석하면_새로() throws Exception {
        User admin = user("madmin", Role.ADMIN, 0);
        mvc.perform(get("/api/admin/analytics").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.supplyDemand.length()").value(6)).andExpect(jsonPath("$.monthlyCategory.length()").value(12));
        mvc.perform(get("/api/admin/insights?refresh=true").header("Authorization", tok(admin))).andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.source").value("rule"));
        mvc.perform(get("/api/admin/insights").header("Authorization", tok(admin))).andExpect(jsonPath("$.cached").value(true));
        mvc.perform(get("/api/admin/insights").header("Authorization", tok(rec))).andExpect(status().isForbidden());
    }

    @Test
    void 인수인계_규칙_요약은_건강_메모를_그대로_옮기지_않는다() {
        List<Map<String, Object>> items = List.of(
                Views.map("date", "2026-11-05", "category", "동행", "careMemo", "내과에서 혈압약 처방이 바뀌었어요"),
                Views.map("date", "2026-11-01", "category", "말동무·정서", "careMemo", "사진첩 같이 보기"));
        Map<String, Object> h = InsightAi.handoverRule("이순례 님", items);
        String all = h.toString();
        assertFalse(all.contains("혈압약"));
        assertTrue(all.contains("병원 안내 사항 있음 (11/5 메모 확인)"));
        assertTrue(all.contains("사진첩 같이 보기"));
        assertTrue(((String) h.get("text")).contains("최근 2번"));
    }

    static Post mkPost(PostType t, String cat, LocalDateTime created) {
        Post p = new Post();
        p.type = t; p.categories = new ArrayList<>(List.of(cat)); p.status = PostStatus.OPEN; p.createdAt = created;
        return p;
    }

    static Request req(Kind k, String cat, ReqStatus st, LocalDateTime created) {
        Request r = new Request();
        r.kind = k; r.category = cat; r.status = st; r.createdAt = created;
        return r;
    }
}
