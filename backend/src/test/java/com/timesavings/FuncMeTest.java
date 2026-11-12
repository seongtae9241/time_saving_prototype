package com.timesavings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 기능명세서(73개 기능) — 공통·플래너·AI 안내·마이 범위:
 * 기능 C-04 담당자 계정 신청, U-34 봉사 인증(첫 활동 교육), U-28 추천 봉사, U-30 공공 서비스 안내 AI, U-31 동네 기관 소식, C-08 공지 고정.
 * AI 키 없이 규칙 모드로 돈다(외부 호출 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FuncMeTest {
    static final ObjectMapper OM = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired OrganizationRepo orgs;
    @Autowired ManagerApplicationRepo apps;
    @Autowired NotificationRepo notis;
    @Autowired NoticeRepo notices;
    @Autowired RequestRepo requests;
    @Autowired PostRepo posts;
    @Autowired OrgEventRepo events;
    @Autowired PublicServiceRepo services;
    @Autowired AuthService auth;
    @Autowired PostService postSvc;
    @Autowired RequestService reqs;
    @Autowired PlannerService planner;
    @Autowired EventService eventSvc;
    @Autowired VolunteerGate gate;
    @Autowired RateLimiter limiter;

    User vol, rec, admin, mgr;
    Organization org, pending;

    User user(String id, Role role) {
        User u = new User();
        u.loginId = id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "30대";
        u.idStatus = VerifyStatus.APPROVED;
        return users.save(u);
    }

    Organization org(String name, OrgStatus st) {
        Organization o = new Organization();
        o.name = name; o.orgType = "주민센터"; o.address = "행복구 " + name; o.status = st;
        return orgs.save(o);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    @BeforeEach
    void setUp() {
        limiter.reset();
        vol = user("fvol", Role.USER);
        rec = user("frec", Role.USER);
        admin = user("fadmin", Role.ADMIN);
        org = org("행복동 주민센터", OrgStatus.APPROVED);
        pending = org("희망동 복지관", OrgStatus.PENDING);
        mgr = user("fmgr", Role.ORG);
        mgr.orgId = org.id; mgr.phone = "010-1111-2222";
    }

    String verifyToken(String phone) throws Exception {
        String body = mvc.perform(post("/api/auth/phone/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"code\":\"123456\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return OM.readTree(body).get("verifyToken").asText();
    }

    MockMultipartFile proof() { return new MockMultipartFile("proof", "cert.png", "image/png", new byte[]{1, 2, 3}); }

    // ======================= 기능 C-04 담당자 계정 신청 =======================
    @Test
    void 담당자_신청_기관_목록은_승인된_기관만() throws Exception {
        mvc.perform(get("/api/auth/manager-apply/orgs")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("행복동 주민센터")))
                .andExpect(jsonPath("$[*].name", not(hasItem("희망동 복지관"))));
    }

    @Test
    void 담당자_신청은_본인인증이_되면_자동_승인되고_계정이_생긴다() throws Exception {
        String vt = verifyToken("010-3333-4444");
        String body = mvc.perform(multipart("/api/auth/manager-apply").file(proof())
                        .param("orgId", String.valueOf(org.id)).param("name", "새담당").param("phone", "010-3333-4444")
                        .param("verifyToken", vt).param("loginId", "newmgr1").param("password", "pass1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.user.role").value("ORG"))
                .andExpect(jsonPath("$.user.orgName").value("행복동 주민센터"))
                .andReturn().getResponse().getContentAsString();
        JsonNode j = OM.readTree(body);
        User created = users.findByLoginId("newmgr1").orElseThrow();
        assertEquals(Role.ORG, created.role);
        assertEquals(org.id, created.orgId);
        assertEquals(UserStatus.ACTIVE, created.status);
        // 받은 토큰으로 바로 담당자 화면을 쓸 수 있다
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + j.get("token").asText())).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG"));
        // 자동 승인이 위험하므로: 운영자와 같은 기관의 기존 담당자에게 알림
        assertTrue(notis.findByUserIdOrderByCreatedAtDescIdDesc(admin.id).stream().anyMatch(n -> "MANAGER_APPLY".equals(n.type)));
        assertTrue(notis.findByUserIdOrderByCreatedAtDescIdDesc(mgr.id).stream().anyMatch(n -> "MANAGER_APPLY".equals(n.type) && n.body.contains("새담당")));
        // 운영자는 승인 내역과 재직 증빙을 볼 수 있다
        String list = mvc.perform(get("/api/admin/manager-applications").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].loginId").value("newmgr1"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].orgName").value("행복동 주민센터"))
                .andExpect(jsonPath("$[0].existingManagers").value(1))
                .andReturn().getResponse().getContentAsString();
        String url = OM.readTree(list).get(0).get("proofFileUrl").asText();
        assertTrue(url.startsWith("/api/files/"));
        mvc.perform(get(url).header("Authorization", tok(admin))).andExpect(status().isOk());
        mvc.perform(get(url).header("Authorization", tok(mgr))).andExpect(status().isForbidden());
        // 운영자가 아니면 내역을 볼 수 없다
        mvc.perform(get("/api/admin/manager-applications").header("Authorization", tok(mgr))).andExpect(status().isForbidden());
        // 취소하면 계정이 정지되고 로그인이 끊긴다
        long id = OM.readTree(list).get(0).get("id").asLong();
        mvc.perform(post("/api/admin/manager-applications/" + id + "/revoke").header("Authorization", tok(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"재직 확인이 안 됨\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED")).andExpect(jsonPath("$.userStatus").value("SUSPENDED"));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + j.get("token").asText())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/manager-applications/" + id + "/revoke").header("Authorization", tok(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
    }

    @Test
    void 담당자_신청은_본인인증_승인된_기관_재직_증빙이_없으면_안_된다() throws Exception {
        // 본인인증 없음
        mvc.perform(multipart("/api/auth/manager-apply").file(proof()).param("orgId", String.valueOf(org.id)).param("name", "가")
                        .param("phone", "010-5555-6666").param("verifyToken", "nope").param("loginId", "nomgr1").param("password", "pass1234"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHONE_NOT_VERIFIED"));
        String vt = verifyToken("010-5555-6666");
        // 승인 전 기관
        mvc.perform(multipart("/api/auth/manager-apply").file(proof()).param("orgId", String.valueOf(pending.id)).param("name", "가")
                        .param("phone", "010-5555-6666").param("verifyToken", vt).param("loginId", "nomgr1").param("password", "pass1234"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ORG_NOT_APPROVED"));
        // 재직 증빙 없음
        mvc.perform(multipart("/api/auth/manager-apply").param("orgId", String.valueOf(org.id)).param("name", "가")
                        .param("phone", "010-5555-6666").param("verifyToken", vt).param("loginId", "nomgr1").param("password", "pass1234"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROOF_REQUIRED"));
        // 이미 쓰는 아이디
        mvc.perform(multipart("/api/auth/manager-apply").file(proof()).param("orgId", String.valueOf(org.id)).param("name", "가")
                        .param("phone", "010-5555-6666").param("verifyToken", vt).param("loginId", "fmgr").param("password", "pass1234"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_LOGIN_ID"));
        // 같은 번호로 이미 그 기관 담당자
        String vt2 = verifyToken("010-1111-2222");
        mvc.perform(multipart("/api/auth/manager-apply").file(proof()).param("orgId", String.valueOf(org.id)).param("name", "가")
                        .param("phone", "010-1111-2222").param("verifyToken", vt2).param("loginId", "nomgr2").param("password", "pass1234"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_MANAGER"));
        assertTrue(users.findByLoginId("nomgr1").isEmpty());
        assertEquals(0, apps.count());
    }

    // ======================= 기능 U-34 봉사 인증: 첫 활동 교육 =======================
    void eduRequired(boolean required) throws Exception {
        mvc.perform(put("/api/admin/verify-items").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"key\":\"id\",\"on\":true,\"required\":true},{\"key\":\"edu\",\"on\":true,\"required\":" + required + "},{\"key\":\"crime\",\"on\":true,\"required\":false}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.key=='edu')].required").value(hasItem(required)));
    }

    PostService.PostCmd offerCmd() {
        return new PostService.PostCmd(PostType.OFFER, LocalDate.now().plusDays(3), 13.0, 17.0, "행복동", List.of("말동무·정서"),
                "도와드려요", null, null, null, false, null, null, null, null);
    }

    Post need(User owner, String cat, String area, LocalDate d, double s, double e) {
        return postSvc.create(owner, new PostService.PostCmd(PostType.NEED, d, s, e, area, List.of(cat), cat + " 부탁드려요", null, null, 1.0,
                false, null, null, null, null)).get(0);
    }

    @Test
    void 봉사_인증_항목에_첫_활동_교육이_있다() throws Exception {
        mvc.perform(get("/api/verify-items").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].key", contains("phone", "id", "edu", "crime")))
                .andExpect(jsonPath("$.items[?(@.key=='edu')].title").value(hasItem("첫 활동 교육(안전 교육)")));
    }

    @Test
    void 교육이_필수면_미이수자는_봉사_제안과_봉사_가능_공고가_막힌다() throws Exception {
        eduRequired(true);
        assertTrue(gate.eduRequired());
        assertEquals("EDU_REQUIRED", assertThrows(ApiException.class, () -> postSvc.create(vol, offerCmd())).code);
        Post n = need(rec, "말동무·정서", "행복동", LocalDate.now().plusDays(2), 10, 12);
        RequestService.CreateCmd proposal = new RequestService.CreateCmd(Kind.PROPOSAL, n.id, null, null, n.date, 10.0, 1.0, null, "말벗 해 드릴게요", null);
        ApiException e = assertThrows(ApiException.class, () -> reqs.create(vol, proposal));
        assertEquals("EDU_REQUIRED", e.code);
        assertTrue(e.getMessage().contains("첫 활동 교육"));
        mvc.perform(get("/api/me").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.eduDone").value(false)).andExpect(jsonPath("$.verify.eduRequired").value(true))
                .andExpect(jsonPath("$.canVolunteer").value(false)).andExpect(jsonPath("$.volunteerBlock.code").value("EDU_REQUIRED"));
        // 도움 받기(봉사 요청 공고)는 교육 없이도 된다
        assertNotNull(need(vol, "장보기·심부름", "행복동", LocalDate.now().plusDays(4), 10, 12).id);
        // 필수가 아니면(운영자 설정) 막지 않는다
        eduRequired(false);
        assertNotNull(postSvc.create(vol, offerCmd()).get(0).id);
    }

    @Test
    void 첫_활동_연습_상황을_모두_마치면_교육_이수로_기록된다() throws Exception {
        eduRequired(true);
        List<Map<String, Object>> sits = EduService.DEFAULT_SITUATIONS;
        assertEquals(4, sits.size());
        // 안전 규칙에 어긋난 답은 끝낸 것으로 치지 않는다
        mvc.perform(post("/api/edu/practice").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content(OM.writeValueAsString(Map.of("key", sits.get(0).get("key"), "text", sits.get(0).get("bad")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.education.doneCount").value(0)).andExpect(jsonPath("$.education.done").value(false));
        for (int i = 0; i < sits.size(); i++) {
            mvc.perform(post("/api/edu/practice").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                            .content(OM.writeValueAsString(Map.of("key", sits.get(i).get("key"), "text", sits.get(i).get("good")))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.education.doneCount").value(i + 1))
                    .andExpect(jsonPath("$.education.done").value(i == sits.size() - 1));
        }
        assertTrue(users.findById(vol.id).orElseThrow().eduDone);
        mvc.perform(get("/api/edu").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.education.done").value(true)).andExpect(jsonPath("$.education.total").value(4))
                .andExpect(jsonPath("$.education.completedAt").isNotEmpty()).andExpect(jsonPath("$.optional").value(false));
        mvc.perform(get("/api/me").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.eduDone").value(true)).andExpect(jsonPath("$.canVolunteer").value(true));
        // 프로필에도 표시된다
        mvc.perform(get("/api/users/" + vol.id + "/profile").header("Authorization", tok(rec))).andExpect(status().isOk())
                .andExpect(jsonPath("$.eduDone").value(true));
        // 이제 봉사 가능 공고를 올릴 수 있다
        assertNotNull(postSvc.create(users.findById(vol.id).orElseThrow(), offerCmd()).get(0).id);
    }

    // ======================= 기능 U-28 추천 봉사: 많이 한 카테고리·지역·시간대와 이유 =======================
    Request done(User volunteer, User recipient, Post p, String cat, LocalDate date, double start) {
        Request r = new Request();
        r.kind = Kind.PROPOSAL; r.senderId = volunteer.id; r.recipientId = recipient.id; r.volunteerId = volunteer.id; r.postId = p == null ? null : p.id;
        r.date = date; r.startHour = start; r.hours = 1; r.category = cat; r.content = "도움";
        r.difficulty = Level.BASIC; r.weight = 1.0; r.status = ReqStatus.DONE; r.earned = 1.0; r.doneAt = LocalDateTime.now();
        return requests.save(r);
    }

    /** 다음 평일(월~금) 가운데 n일 뒤부터 가장 가까운 날 */
    static LocalDate weekday(int plus) {
        LocalDate d = LocalDate.now().plusDays(plus);
        while (d.getDayOfWeek().getValue() >= 6) d = d.plusDays(1);
        return d;
    }

    static LocalDate pastWeekday(int minus) {
        LocalDate d = LocalDate.now().minusDays(minus);
        while (d.getDayOfWeek().getValue() >= 6) d = d.minusDays(1);
        return d;
    }

    @Test
    @SuppressWarnings("unchecked")
    void 추천_봉사는_많이_한_카테고리_지역_시간대를_바탕으로_이유를_준다() throws Exception {
        rec.area = "희망동";
        // 기록: 희망동에서 평일 오후 디지털 도움 3회, 행복동 말동무 1회
        for (int i = 0; i < 3; i++) done(vol, rec, null, "디지털 도움", pastWeekday(7 + i * 7), 14);
        User other = user("fother", Role.USER);
        done(vol, other, null, "말동무·정서", pastWeekday(3), 10);
        Map<String, Object> basis = planner.historyBasis(vol, requests.findAll());
        assertEquals(4, basis.get("doneCount"));
        assertEquals("디지털 도움", basis.get("topCategory"));
        assertEquals(3, basis.get("topCategoryCount"));
        assertEquals("희망동", basis.get("topArea"));
        assertEquals("평일 오후", basis.get("topSlot"));
        // 열린 봉사 요청: 기록과 모두 맞는 것 하나, 아무것도 안 맞는 것 하나
        User owner = user("fowner", Role.USER);
        Post fit = need(owner, "디지털 도움", "희망동", weekday(3), 14, 16);
        Post etc = need(owner, "생활 정리", "소망동", weekday(4), 8, 10);
        String body = mvc.perform(get("/api/me/recommends").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.basis.topCategory").value("디지털 도움"))
                .andExpect(jsonPath("$.basisText", containsString("디지털 도움 3회")))
                .andReturn().getResponse().getContentAsString();
        JsonNode recs = OM.readTree(body).get("recommends");
        assertEquals(fit.id.longValue(), recs.get(0).get("id").asLong()); // 기록과 맞는 공고가 맨 위
        String why = recs.get(0).get("why").asText();
        assertTrue(why.contains("가장 많이 하신 디지털 도움(3회)"), why);
        assertTrue(why.contains("자주 활동하신 희망동(3회)"), why);
        assertTrue(why.contains("자주 활동하신 시간대(평일 오후)"), why);
        assertTrue(recs.get(0).get("score").asInt() > recs.get(1).get("score").asInt());
        assertEquals(etc.id.longValue(), recs.get(1).get("id").asLong());
        // 플래너에도 같은 바탕과 설명 문장(규칙 모드)이 온다
        mvc.perform(get("/api/me/planner").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.basis.topArea").value("희망동"))
                .andExpect(jsonPath("$.text", containsString("많이 하신 봉사")))
                .andExpect(jsonPath("$.source").value("rule"));
    }

    @Test
    void 봉사_기록이_없으면_프로필_기준으로_추천한다() throws Exception {
        mvc.perform(get("/api/me/recommends").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.basis.doneCount").value(0))
                .andExpect(jsonPath("$.basisText", containsString("프로필")));
    }

    // ======================= 기능 U-30 공공 서비스 안내: 봉사로 가능한 일인지와 봉사 기준 =======================
    @Test
    void 공공_서비스_안내는_봉사로_가능한지와_봉사_기준을_함께_알려_준다() throws Exception {
        PublicService s = new PublicService();
        s.name = "노인맞춤돌봄서비스"; s.target = "65세 이상"; s.content = "안부 확인, 장보기 동행"; s.applyHow = "주민센터 방문 신청"; s.contact = "129";
        s.keywords = List.of("장보기", "안부", "돌봄"); s.region = "전국";
        services.save(s);
        mvc.perform(post("/api/ai/public-services").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"무릎이 아파서 장보기가 힘들어요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volunteer.possible").value("YES"))
                .andExpect(jsonPath("$.volunteer.categories[0].category").value("장보기·심부름"))
                .andExpect(jsonPath("$.volunteer.categories[0].weight").value(1.2))
                .andExpect(jsonPath("$.volunteer.criteria", hasSize(greaterThanOrEqualTo(4))))
                .andExpect(jsonPath("$.volunteer.writeLink", startsWith("/write/need?prefill=")))
                .andExpect(jsonPath("$.volunteer.source").value("rule"))
                .andExpect(jsonPath("$.source").value("rule"));
        // 금지 요청(신체 돌봄)은 봉사로 불가 → 공고 작성 버튼 없음
        mvc.perform(post("/api/ai/public-services").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"목욕을 도와줄 사람이 필요해요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volunteer.possible").value("NO"))
                .andExpect(jsonPath("$.volunteer.reason", not(emptyOrNullString())))
                .andExpect(jsonPath("$.volunteer.writeLink").value(nullValue()));
        // 주의가 필요한 일
        mvc.perform(post("/api/ai/public-services/nearby").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"병원 갈 때 부축해 줄 사람이 필요해요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volunteer.possible").value("CAUTION"))
                .andExpect(jsonPath("$.volunteer.categories[*].category", hasItem("동행")));
        // 6가지에 들지 않는 말
        mvc.perform(post("/api/ai/public-services").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"기초연금은 어떻게 받나요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.volunteer.possible").value("UNKNOWN"));
    }

    // ======================= 기능 U-31 동네 기관 소식: 내 주소 근처만 =======================
    @Test
    void 홈의_기관_소식은_내_동네_근처_행사만_준다() throws Exception {
        Organization far = org("소망동 복지관", OrgStatus.APPROVED), next = org("희망동 주민센터", OrgStatus.APPROVED);
        OrgEvent a = new OrgEvent(); a.orgId = org.id; a.title = "행복동 사진 교실"; a.eventDate = LocalDate.now().plusDays(5); a.url = "https://example.org/a"; events.save(a);
        OrgEvent b = new OrgEvent(); b.orgId = next.id; b.title = "희망동 걷기 모임"; b.eventDate = LocalDate.now().plusDays(2); b.url = "https://example.org/b"; events.save(b);
        OrgEvent c = new OrgEvent(); c.orgId = far.id; c.title = "소망동 김장 나눔"; c.eventDate = LocalDate.now().plusDays(1); c.url = "https://example.org/c"; events.save(c);
        assertTrue(EventService.nearDong("행복동", "희망동"));
        assertFalse(EventService.nearDong("행복동", "소망동"));
        assertFalse(EventService.nearDong("행복동", "모르는동"));
        mvc.perform(get("/api/events?nearby=true").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", contains("행복동 사진 교실", "희망동 걷기 모임")))
                .andExpect(jsonPath("$[0].url").value("https://example.org/a")); // 신청은 기관 페이지 링크로
        // 전체 목록에는 먼 동네 행사도 있다
        mvc.perform(get("/api/events").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", hasItem("소망동 김장 나눔")));
    }

    // ======================= 기능 C-08 공지사항: 고정 공지가 위 =======================
    @Test
    void 공지_목록은_고정_공지가_맨_위에_온다() throws Exception {
        Notice old = new Notice(); old.title = "오래된 고정 공지"; old.content = "내용"; old.pinned = true; old.createdAt = LocalDateTime.now().minusDays(30); notices.save(old);
        Notice n1 = new Notice(); n1.title = "어제 공지"; n1.content = "내용"; n1.createdAt = LocalDateTime.now().minusDays(1); notices.save(n1);
        Notice n2 = new Notice(); n2.title = "오늘 공지"; n2.content = "내용"; notices.save(n2);
        mvc.perform(get("/api/notices")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", contains("오래된 고정 공지", "오늘 공지", "어제 공지")))
                .andExpect(jsonPath("$[0].pinned").value(true));
        mvc.perform(get("/api/notices/" + old.id)).andExpect(status().isOk()).andExpect(jsonPath("$.content").value("내용"));
    }
}
