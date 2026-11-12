package com.timesavings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.ai.ForecastAi;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import com.timesavings.service.FinanceModel.Adj;
import com.timesavings.service.FinanceModel.Inputs;
import org.junit.jupiter.api.AfterEach;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 기능명세서 운영자 범위(기능 A-02·A-04·A-05·A-06·A-07·A-08·A-09·A-13·A-15·A-16).
 * AI 키 없이(규칙 모드) 돈다. 외부 네트워크(기상청 포함)를 부르지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FuncAdminTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired OrganizationRepo orgs;
    @Autowired ElderRepo elders;
    @Autowired RequestRepo requests;
    @Autowired PostRepo posts;
    @Autowired NoticeRepo notices;
    @Autowired NotificationRepo notis;
    @Autowired ReportRepo reports;
    @Autowired CategorySuggestionRepo suggestions;
    @Autowired AiResultRepo results;
    @Autowired AuthService auth;
    @Autowired WeatherProvider weatherProvider;
    final ObjectMapper om = new ObjectMapper();
    static final String AREA = "시험동";

    Organization org;
    User admin, mgr, vol, usr;

    User user(String id, Role role, String age) {
        User u = new User();
        u.loginId = id; u.name = "이름" + id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = role == Role.USER ? AREA : null; u.ageGroup = age;
        u.idStatus = VerifyStatus.APPROVED;
        if (role == Role.ORG) u.orgId = org.id;
        return users.save(u);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    Elder elder(String alias, String age) {
        Elder e = new Elder();
        e.alias = alias; e.dong = AREA; e.managerId = mgr.id; e.orgId = org.id; e.ageGroup = age; e.needs = List.of("동행");
        return elders.save(e);
    }

    Post need(String cat, int daysAgo) {
        Post p = new Post();
        p.type = PostType.NEED; p.ownerId = usr.id; p.date = LocalDate.now().minusDays(daysAgo).plusDays(3); p.startHour = 10; p.endHour = 12;
        p.area = AREA; p.categories = new ArrayList<>(List.of(cat)); p.title = cat + " 도와주세요"; p.createdAt = LocalDateTime.now().minusDays(daysAgo);
        return posts.save(p);
    }

    Request done(User v, Long recipientId, Long elderId, String cat, int daysAgo) {
        Request r = new Request();
        r.kind = Kind.REQUEST; r.senderId = recipientId != null ? recipientId : mgr.id; r.volunteerId = v.id; r.recipientId = recipientId; r.elderId = elderId;
        r.date = LocalDate.now().minusDays(daysAgo); r.startHour = 10; r.hours = 2; r.category = cat; r.content = cat; r.weight = 1; r.status = ReqStatus.DONE;
        r.createdAt = r.date.atTime(9, 0); r.doneAt = r.date.atTime(13, 0);
        return requests.save(r);
    }

    JsonNode json(org.springframework.test.web.servlet.ResultActions a) throws Exception {
        return om.readTree(a.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.name = "시험동 복지관"; org.status = OrgStatus.APPROVED;
        orgs.save(org);
        admin = user("fadmin", Role.ADMIN, null);
        mgr = user("fmgr", Role.ORG, null);
        vol = user("fvol", Role.USER, "20대");
        usr = user("fusr", Role.USER, "70대");
    }

    @AfterEach
    void tearDown() { Rules.clearExtraCategories(); }

    // ================= 기능 A-05 모형 =================
    Inputs simple() {
        Inputs in = new Inputs();
        in.areas = List.of("가동", "나동");
        in.users = new double[][]{{0, 10, 0, 0, 0, 10, 0, 0}, {0, 0, 0, 0, 0, 5, 10, 0}};
        in.elders = new double[][]{{0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 10}};
        in.joins = new double[2][8];
        in.churnYear = 0;
        in.baseYear = 2026;
        return in;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> out, String area) {
        return ((List<Map<String, Object>>) out.get("areas")).stream().filter(a -> area.equals(a.get("area"))).map(a -> (List<Map<String, Object>>) a.get("rows")).findFirst().orElseThrow();
    }

    @Test
    void A05_나이대_읽기() {
        assertEquals(1, FinanceModel.band("20대"));
        assertEquals(7, FinanceModel.band("80대 이상"));
        assertEquals(7, FinanceModel.band("90대"));
        assertEquals(5, FinanceModel.band("65세"));
        assertEquals(-1, FinanceModel.band(null));
        assertEquals(-1, FinanceModel.band("미상"));
    }

    @Test
    void A05_코호트_모형은_해마다_나이대를_올리고_연도_동네별_수요_공급을_낸다() {
        Inputs in = simple();
        Map<String, Object> out = FinanceModel.project(in, List.of());
        List<Map<String, Object>> ga = rows(out, "가동"), na = rows(out, "나동");
        assertEquals(List.of(0, 1, 3, 5, 10), ga.stream().map(r -> r.get("offset")).toList());
        assertEquals(2036, ga.get(4).get("year"));
        // 지금: 가동 공급 = (10×2.0 + 10×3.0)×12 = 600, 수요 = (10×0.2 + 10×1.0)×12 = 144
        assertEquals(600.0, ga.get(0).get("supply"));
        assertEquals(144.0, ga.get(0).get("demand"));
        // 이탈·가입이 없으면 회원 수는 그대로이고 나이만 올라가 70대 이상 비율이 커진다
        assertEquals(20.0, ga.get(4).get("members"));
        assertTrue(((Number) ga.get(4).get("seniorShare")).longValue() > ((Number) ga.get(0).get("seniorShare")).longValue());
        // 나동: 관리 대상 10명(80대 이상)은 도움만 받는다 → 공급이 수요에 못 미쳐 위험
        assertEquals(10.0, na.get(0).get("elders"));
        assertTrue((Double) na.get(0).get("ratio") < 80);
        @SuppressWarnings("unchecked") Map<String, Object> first = ((List<Map<String, Object>>) out.get("areas")).get(0);
        assertEquals("나동", first.get("area")); // 위험한 동네가 위
        assertEquals("위험", first.get("risk"));
        assertFalse(((List<?>) first.get("reasons")).isEmpty());
        // 전체 = 동네 합
        @SuppressWarnings("unchecked") List<Map<String, Object>> total = (List<Map<String, Object>>) out.get("total");
        assertEquals((Double) ga.get(0).get("supply") + (Double) na.get(0).get("supply"), (Double) total.get(0).get("supply"), 0.01);
    }

    @Test
    void A06_조정_값을_넣으면_같은_모형으로_다시_계산한다() {
        Inputs in = simple();
        Map<String, Object> base = FinanceModel.project(in, List.of());
        // 20대 가입 월 30명 → 공급이 늘어난다
        Map<String, Object> join = FinanceModel.project(in, List.of(new Adj("JOIN_ADD", "20대", null, 30)));
        assertTrue((Double) rows(join, "가동").get(4).get("supply") > (Double) rows(base, "가동").get(4).get("supply"));
        assertEquals(rows(base, "가동").get(0).get("supply"), rows(join, "가동").get(0).get("supply")); // 지금 값은 그대로
        // 나동 60대 봉사 시간 월 +2시간 → 나동만 바뀐다
        Map<String, Object> sup = FinanceModel.project(in, List.of(new Adj("SUPPLY_ADD", "60대", "나동", 2)));
        assertEquals((Double) rows(base, "나동").get(0).get("supply") + 5 * 2 * 12, (Double) rows(sup, "나동").get(0).get("supply"), 0.01);
        assertEquals(rows(base, "가동").get(0).get("supply"), rows(sup, "가동").get(0).get("supply"));
        // 이탈률 절반 → 10년 뒤 회원이 더 많이 남는다
        in.churnYear = 0.2;
        double m1 = (Double) rows(FinanceModel.project(in, List.of()), "가동").get(4).get("members");
        double m2 = (Double) rows(FinanceModel.project(in, List.of(new Adj("CHURN_MULT", null, null, 0.5))), "가동").get(4).get("members");
        assertTrue(m2 > m1);
    }

    @Test
    void A06_규칙_해석은_흔한_문장을_조정_값으로_바꾸고_LLM_응답은_형식을_검사한다() throws Exception {
        List<String> areas = List.of("행복동", "희망동");
        assertEquals(List.of(new Adj("JOIN_ADD", "20대", null, 30)), ForecastAi.parseRule("20대 가입이 매달 30명 늘면", areas));
        assertEquals(List.of(new Adj("CHURN_MULT", null, null, 0.5)), ForecastAi.parseRule("이탈률이 절반으로 줄면", areas));
        assertEquals(List.of(new Adj("SUPPLY_ADD", "60대", "희망동", 2)), ForecastAi.parseRule("희망동 60대 봉사 시간이 월 2시간 늘면", areas));
        assertEquals(List.of(new Adj("CHURN_MULT", null, null, 0.8)), ForecastAi.parseRule("이탈률이 20% 감소하면", areas));
        assertEquals(List.of(new Adj("JOIN_ADD", "30대", null, -5), new Adj("DEMAND_ADD", "70대", null, 1)),
                ForecastAi.parseRule("30대 가입이 5명 줄고, 70대 도움 받는 시간이 1시간 늘면", areas));
        assertTrue(ForecastAi.parseRule("날씨가 좋으면", areas).isEmpty());
        // LLM 이 준 JSON: 모르는 종류·모르는 동네는 버리고, 터무니없는 값은 자른다
        JsonNode j = om.readTree("{\"adjustments\":[{\"type\":\"JOIN_ADD\",\"ageBand\":\"20대\",\"area\":null,\"value\":999999},"
                + "{\"type\":\"DELETE_ALL\",\"value\":1},{\"type\":\"SUPPLY_ADD\",\"area\":\"없는동\",\"value\":2},{\"type\":\"CHURN_MULT\",\"value\":\"절반\"}]}");
        assertEquals(List.of(new Adj("JOIN_ADD", "20대", null, 1000)), ForecastAi.fromJson(j, areas));
    }

    @Test
    void A05_추계_API는_동네별_예측과_가정_기록_구분을_주고_운영자만_본다() throws Exception {
        elder("가", "80대"); elder("나", "80대"); elder("다", "80대");
        done(vol, null, elders.findAll().stream().filter(e -> AREA.equals(e.dong)).findFirst().orElseThrow().id, "동행", 10);
        mvc.perform(get("/api/admin/forecast").header("Authorization", tok(usr))).andExpect(status().isForbidden());
        JsonNode f = json(mvc.perform(get("/api/admin/forecast").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.areas[?(@.area=='" + AREA + "')]").exists())
                .andExpect(jsonPath("$.total", hasSize(5)))
                .andExpect(jsonPath("$.assumptions.bands", hasSize(8))));
        JsonNode area = null;
        for (JsonNode a : f.path("areas")) if (AREA.equals(a.path("area").asText())) area = a;
        assertNotNull(area);
        assertEquals(5, area.path("rows").size());
        assertEquals(3, area.path("rows").get(0).path("elders").asInt());
        assertTrue(area.path("rows").get(0).path("demand").asDouble() > 0);
        assertTrue(List.of("위험", "주의", "안정").contains(area.path("risk").asText()));
        for (JsonNode b : f.path("assumptions").path("bands")) {
            assertTrue(List.of("가정", "기록").contains(b.path("supplySource").asText()));
            assertTrue(List.of("가정", "기록").contains(b.path("demandSource").asText()));
        }
        // 80대 이상은 관리 대상이 3명 이상이라 수요 시간이 기록에서 나온다
        assertEquals("기록", f.path("assumptions").path("bands").get(7).path("demandSource").asText());
    }

    @Test
    void A05_월간_요약은_저장해_두고_다시_본다() throws Exception {
        elder("가", "80대"); elder("나", "80대"); elder("다", "80대");
        String month = YearMonth.now().toString();
        JsonNode s1 = json(mvc.perform(post("/api/admin/forecast/summary").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(month)).andExpect(jsonPath("$.source").value("rule")).andExpect(jsonPath("$.headline").isNotEmpty()));
        assertTrue(results.findByKindAndRefKey(FinanceForecastService.KIND, month).isPresent());
        // 다시 부르면 저장된 것을 그대로 준다
        JsonNode s2 = json(mvc.perform(post("/api/admin/forecast/summary").header("Authorization", tok(admin))).andExpect(status().isOk()));
        assertEquals(s1.path("savedAt").asText(), s2.path("savedAt").asText());
        assertEquals(s1.path("headline").asText(), s2.path("headline").asText());
        mvc.perform(get("/api/admin/forecast/summaries").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].month").value(month));
        // 위험·주의 동네에는 코드가 뽑은 이유가 붙는다
        for (JsonNode a : s1.path("areas")) { assertFalse(a.path("reasons").isEmpty()); assertFalse(a.path("text").asText().isBlank()); }
        mvc.perform(post("/api/admin/forecast/summary?refresh=true").header("Authorization", tok(admin))).andExpect(status().isOk());
        assertEquals(1, results.findByKind(FinanceForecastService.KIND).stream().filter(r -> month.equals(r.refKey)).count());
    }

    @Test
    void A06_시뮬레이션_API는_문장을_해석해_전후를_주고_못_알아들으면_직접_넣게_한다() throws Exception {
        JsonNode r = json(mvc.perform(post("/api/admin/forecast/simulate").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"20대 가입이 매달 30명 늘면\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.understood").value(true)).andExpect(jsonPath("$.source").value("rule"))
                .andExpect(jsonPath("$.adjustments[0].type").value("JOIN_ADD")).andExpect(jsonPath("$.adjustments[0].value").value(30.0))
                .andExpect(jsonPath("$.adjustments[0].label", containsString("+30명"))));
        JsonNode b = r.path("before").path("total"), a = r.path("after").path("total");
        assertEquals(b.get(0).path("supply").asDouble(), a.get(0).path("supply").asDouble(), 0.01);
        assertTrue(a.get(4).path("supply").asDouble() > b.get(4).path("supply").asDouble());
        // 못 알아들은 문장 → after 없음, 화면이 직접 넣는 칸을 보여 준다
        mvc.perform(post("/api/admin/forecast/simulate").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"날씨가 좋으면\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.understood").value(false)).andExpect(jsonPath("$.after").doesNotExist()).andExpect(jsonPath("$.bands", hasSize(8)));
        // 직접 넣은 조정 값
        mvc.perform(post("/api/admin/forecast/simulate").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adjustments\":[{\"type\":\"CHURN_MULT\",\"ageBand\":null,\"area\":null,\"value\":0.5}]}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.understood").value(true)).andExpect(jsonPath("$.source").value("manual")).andExpect(jsonPath("$.after.total", hasSize(5)));
        mvc.perform(post("/api/admin/forecast/simulate").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    // ================= 기능 A-02 · A-04 · A-07 =================
    @Test
    void A02_봉사_유형_리포트는_주는_쪽_받는_쪽_비율과_사용자_유형_분포와_해석을_준다() throws Exception {
        User v2 = user("fvol2", Role.USER, "30대");
        Elder e = elder("가", "80대");
        done(vol, usr.id, null, "동행", 5);
        done(v2, usr.id, null, "동행", 6);
        done(vol, null, e.id, "동행", 7);
        done(usr, vol.id, null, "말동무·정서", 8); // usr 는 주고받는 회원
        JsonNode r = json(mvc.perform(get("/api/admin/stats/types").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.interpretation.source").value("rule")).andExpect(jsonPath("$.interpretation.text").isNotEmpty())
                .andExpect(jsonPath("$.userTypes", hasSize(4))));
        JsonNode c = null;
        for (JsonNode x : r.path("categories")) if ("동행".equals(x.path("category").asText())) c = x;
        assertNotNull(c);
        assertTrue(c.path("givers").asInt() >= 2);
        assertTrue(c.path("receivers").asInt() >= 2);
        assertEquals(100, c.path("giverPct").asInt() + c.path("receiverPct").asInt());
        int sum = 0;
        for (JsonNode t : r.path("userTypes")) sum += t.path("count").asInt();
        assertEquals(r.path("members").asInt(), sum);
        int both = 0;
        for (JsonNode t : r.path("userTypes")) if ("BOTH".equals(t.path("key").asText())) both = t.path("count").asInt();
        assertTrue(both >= 2); // usr, vol
    }

    @Test
    void A04_다음_계절_예상_수요는_코드가_계산한다() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 10, 0); // 가을 → 다음 계절 겨울
        List<Post> ps = new ArrayList<>();
        // 지난 겨울(1월) 동행 4건, 말동무 2건 / 최근 90일 6건, 그 앞 90일 3건 → 추세 2.0
        for (int i = 0; i < 4; i++) ps.add(np("동행", LocalDateTime.of(2026, 1, 10, 9, 0)));
        for (int i = 0; i < 2; i++) ps.add(np("말동무·정서", LocalDateTime.of(2026, 1, 11, 9, 0)));
        for (int i = 0; i < 6; i++) ps.add(np("디지털 도움", now.minusDays(10)));
        for (int i = 0; i < 3; i++) ps.add(np("디지털 도움", now.minusDays(100)));
        Map<String, Object> n = AdminReportService.nextSeason(ps, List.of(), now);
        assertEquals("겨울", n.get("season"));
        assertEquals(2.0, n.get("growth"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) n.get("categories");
        assertEquals("동행", rows.get(0).get("category"));
        assertEquals(8L, rows.get(0).get("expected"));
        assertEquals(12L, n.get("total"));
        // API: 월별 12개월 · 계절 4개 · 다음 계절 · 해석
        need("동행", 3);
        mvc.perform(get("/api/admin/stats/season").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.monthly", hasSize(12))).andExpect(jsonPath("$.seasons", hasSize(4)))
                .andExpect(jsonPath("$.nextSeason.season").isNotEmpty()).andExpect(jsonPath("$.nextSeason.categories", hasSize(6)))
                .andExpect(jsonPath("$.interpretation.source").value("rule")).andExpect(jsonPath("$.interpretation.text").isNotEmpty());
    }

    Post np(String cat, LocalDateTime at) {
        Post p = new Post();
        p.type = PostType.NEED; p.categories = new ArrayList<>(List.of(cat)); p.createdAt = at; p.date = at.toLocalDate();
        return p;
    }

    @Test
    void A07_공급이_부족한_동네_시간대_도움_종류에_맞춘_모집_문구_초안을_준다() throws Exception {
        for (int i = 0; i < 4; i++) need("동행", 2 + i);
        JsonNode r = json(mvc.perform(get("/api/admin/recruit-drafts").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("rule")));
        JsonNode d = null;
        for (JsonNode x : r.path("drafts")) if (AREA.equals(x.path("area").asText()) && "동행".equals(x.path("category").asText())) d = x;
        assertNotNull(d, r.toString());
        assertEquals(4, d.path("gap").asInt());
        assertTrue(d.path("timeSlot").asText().matches("(평일|주말) (오전|오후|저녁)"));
        assertTrue(d.path("title").asText().contains(AREA) && d.path("title").asText().contains("동행"));
        assertTrue(d.path("body").asText().contains("4건"));
        assertFalse(d.path("body").asText().matches(".*(원|상품권|사례금).*")); // 돈 약속 없음
    }

    // ================= 기능 A-08 · A-09 =================
    @Test
    void A08_특보를_넣으면_위험_지역의_대상과_담당_기관을_센_리포트가_나온다() throws Exception {
        Elder e1 = elder("가", "80대"); elder("나", "70대");
        Request up = new Request();
        up.kind = Kind.PROPOSAL; up.senderId = vol.id; up.volunteerId = vol.id; up.elderId = e1.id; up.date = LocalDate.now().plusDays(1);
        up.startHour = 14; up.hours = 1; up.category = "동행"; up.weight = 1.5; up.status = ReqStatus.CONFIRMED;
        requests.save(up);
        assertFalse(weatherProvider.live()); // 테스트에는 기상청 키가 없다
        mvc.perform(post("/api/admin/weather/fetch").header("Authorization", tok(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.live").value(false));
        mvc.perform(post("/api/admin/weather/alerts").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"우박\",\"level\":\"경보\",\"areas\":[\"" + AREA + "\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/weather/alerts").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"폭염\",\"level\":\"경보\",\"areas\":[\"없는동\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/weather/alerts").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"폭염\",\"level\":\"경보\",\"areas\":[\"" + AREA + "\"]}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.facts.totalElders").value(2)).andExpect(jsonPath("$.facts.totalElders80").value(1))
                .andExpect(jsonPath("$.facts.totalActivities").value(1)).andExpect(jsonPath("$.facts.managerCount").value(1))
                .andExpect(jsonPath("$.facts.areas[0].managers[0].orgName").value("시험동 복지관"))
                .andExpect(jsonPath("$.report.source").value("rule")).andExpect(jsonPath("$.report.summary", containsString("2명")))
                .andExpect(jsonPath("$.report.actions", not(empty())))
                // 관리 대상의 별칭은 리포트에 나오지 않는다
                .andExpect(content().string(not(containsString("\"alias\""))));
        mvc.perform(get("/api/admin/weather/alerts").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(false)).andExpect(jsonPath("$.alerts[0].type").value("폭염")).andExpect(jsonPath("$.knownAreas", hasItem(AREA)));
        mvc.perform(get("/api/admin/weather/alerts").header("Authorization", tok(mgr))).andExpect(status().isForbidden());
        // 시연용 표본
        mvc.perform(post("/api/admin/weather/alerts/sample").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("SAMPLE")).andExpect(jsonPath("$.areas", not(empty())));
    }

    @Test
    void A08_기상청_응답_읽기는_우리_구역의_특보만_뽑는다() {
        String body = "# REG_UP, REG_KO, TM_FC, WRN, LVL\nL1100000, 서울, 202607011000, 폭염, 경보\nL1100000, 부산, 202607011000, 호우, 주의\nL1100000, 서울, 202607011000, 강풍, 주의\n";
        assertEquals(List.of(new WeatherProvider.Alert("폭염", "경보", "서울"), new WeatherProvider.Alert("강풍", "주의보", "서울")), KmaWeatherProvider.parse(body, "서울"));
        assertTrue(KmaWeatherProvider.parse("", "서울").isEmpty());
    }

    @Test
    void A09_전달은_운영자가_눌러야_공지로_게시되고_해당_지역_담당자에게_알림이_간다() throws Exception {
        elder("가", "80대");
        long noticesBefore = notices.count();
        JsonNode a = json(mvc.perform(post("/api/admin/weather/alerts").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"한파\",\"level\":\"주의보\",\"areas\":[\"" + AREA + "\"]}")).andExpect(status().isOk()));
        long id = a.path("id").asLong();
        // 만들기만 해서는 아무것도 나가지 않는다
        assertEquals(noticesBefore, notices.count());
        assertTrue(notis.findAll().stream().noneMatch(n -> "WEATHER".equals(n.type)));
        JsonNode s = json(mvc.perform(post("/api/admin/weather/alerts/" + id + "/send").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sentManagers").value(1)).andExpect(jsonPath("$.sentAt").isNotEmpty()));
        Notice n = notices.findById(s.path("noticeId").asLong()).orElseThrow();
        assertTrue(n.title.startsWith("[특보]") && n.title.contains(AREA) && n.title.contains("한파"));
        assertTrue(n.content.contains("안부"));
        Notification nt = notis.findAll().stream().filter(x -> "WEATHER".equals(x.type)).findFirst().orElseThrow();
        assertEquals(mgr.id, nt.userId);
        assertEquals("/notice/" + n.id, nt.link);
        assertTrue(nt.body.contains("1명"));
        // 두 번 보내지 않고, 보낸 특보는 지우지 못한다
        mvc.perform(post("/api/admin/weather/alerts/" + id + "/send").header("Authorization", tok(admin))).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/weather/alerts/" + id).header("Authorization", tok(admin))).andExpect(status().isConflict());
    }

    // ================= 기능 A-13 · A-15 · A-16 =================
    @Test
    void A13_신고_관련_기록을_본다() throws Exception {
        vol.warningCount = 1;
        Request q = done(vol, usr.id, null, "동행", 3);
        Report old = new Report();
        old.reporterId = usr.id; old.targetType = ReportTarget.USER; old.targetId = vol.id; old.type = "불친절"; old.status = ReportStatus.DONE; old.action = ReportAction.WARNING;
        reports.save(old);
        Report r = new Report();
        r.reporterId = usr.id; r.targetType = ReportTarget.REQUEST; r.targetId = q.id; r.type = "연락처 요구"; r.content = "번호를 계속 물어봐요";
        reports.save(r);
        mvc.perform(get("/api/admin/report-context/" + r.id).header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.target.id").value(vol.id)).andExpect(jsonPath("$.target.warningCount").value(1))
                .andExpect(jsonPath("$.pastReports", hasSize(1))).andExpect(jsonPath("$.pastReports[0].action").value("WARNING"))
                .andExpect(jsonPath("$.request.category").value("동행")).andExpect(jsonPath("$.betweenDone").value(1))
                .andExpect(jsonPath("$.reporterReportCount").value(2));
        mvc.perform(get("/api/admin/report-context/" + r.id).header("Authorization", tok(usr))).andExpect(status().isForbidden());
    }

    @Test
    void A15_건의를_승인하면_공고_카테고리에_더해진다() throws Exception {
        CategorySuggestion s = new CategorySuggestion();
        s.userId = usr.id; s.name = "간단한 수리"; s.nameKey = "간단한수리"; s.reason = "형광등 갈기 같은 일이 많아요";
        suggestions.save(s);
        assertFalse(Rules.isCategory("간단한 수리"));
        mvc.perform(post("/api/admin/category-suggestions/" + s.id + "/adopt").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"EFFORT\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADOPTED")).andExpect(jsonPath("$.category.weight").value(1.2));
        assertTrue(Rules.isCategory("간단한 수리"));
        assertEquals(Level.EFFORT, Rules.categoryLevel("간단한 수리"));
        assertEquals("ADOPTED", suggestions.findById(s.id).orElseThrow().status);
        assertTrue(notis.findAll().stream().anyMatch(n -> n.userId.equals(usr.id) && "CATEGORY_SUGGESTION".equals(n.type)));
        // 누구나 보는 카테고리 목록에 나온다(기본 6종 + 더한 것)
        mvc.perform(get("/api/categories").header("Authorization", tok(usr))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7))).andExpect(jsonPath("$[6].name").value("간단한 수리")).andExpect(jsonPath("$[6].extra").value(true));
        // 그 카테고리로 공고를 올릴 수 있다
        mvc.perform(post("/api/posts").header("Authorization", tok(usr)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"NEED\",\"date\":\"" + LocalDate.now().plusDays(3) + "\",\"startHour\":10,\"endHour\":11,\"area\":\"" + AREA
                                + "\",\"categories\":[\"간단한 수리\"],\"title\":\"형광등 갈아 주세요\",\"detail\":\"거실 형광등이 나갔어요\"}"))
                .andExpect(status().isOk());
        // 쓰이는 카테고리는 뺄 수 없다
        mvc.perform(delete("/api/admin/categories/간단한 수리").header("Authorization", tok(admin))).andExpect(status().isConflict());
    }

    @Test
    void A16_공지를_고정하면_목록_맨_위에_온다() throws Exception {
        Notice old = new Notice();
        old.title = "오래된 고정 공지"; old.content = "내용"; old.createdAt = LocalDateTime.now().minusDays(30);
        notices.save(old);
        Notice fresh = new Notice();
        fresh.title = "방금 올린 공지"; fresh.content = "내용"; fresh.createdAt = LocalDateTime.now().plusMinutes(1);
        notices.save(fresh);
        mvc.perform(get("/api/notices")).andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("방금 올린 공지"));
        mvc.perform(post("/api/admin/notices/" + old.id + "/pin").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pinned").value(true));
        mvc.perform(get("/api/notices")).andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("오래된 고정 공지")).andExpect(jsonPath("$[0].pinned").value(true));
        mvc.perform(post("/api/admin/notices/" + old.id + "/pin").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/notices")).andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("방금 올린 공지"));
        mvc.perform(post("/api/admin/notices/" + old.id + "/pin").header("Authorization", tok(usr))).andExpect(status().isForbidden());
    }
}
