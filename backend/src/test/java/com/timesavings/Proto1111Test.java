package com.timesavings;

import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import com.timesavings.web.PlannerController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 2026-11-11 역할별 정리판(7.16): 카테고리 건의 권한·묶음, AI 추천 2섹션, 생애 플랜 계산, 플래너, 관리 대상 이슈, 행사 지역, 인증 항목. 규칙 모드. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Proto1111Test {
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired OrganizationRepo orgs;
    @Autowired OrgEventRepo eventRepo;
    @Autowired RegularMarkRepo marks;
    @Autowired NotificationRepo notis;
    @Autowired CategorySuggestionRepo suggestions;
    @Autowired PostService postSvc;
    @Autowired RequestService reqs;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;

    User me, regularVol, nearVol, farVol, mgr, admin;
    Elder elder;
    Organization org;
    final LocalDate day = LocalDate.now().plusDays(3);

    User user(String id, Role role, String area) {
        User u = new User();
        u.loginId = id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = area; u.ageGroup = "50대";
        u.idStatus = VerifyStatus.APPROVED; u.eduDone = true; u.phone = "010-0000-" + (3000 + users.count());
        return users.save(u);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    Post offer(User v, String cat) {
        return postSvc.create(v, new PostService.PostCmd(PostType.OFFER, day, 13.0, 17.0, v.area, List.of(cat),
                null, null, "도와드려요", null, false, null, null, null, null)).get(0);
    }

    Post need(User owner, LocalDate d, double s, double e, String cat) {
        return postSvc.create(owner, new PostService.PostCmd(PostType.NEED, d, s, e, owner.area, List.of(cat),
                "도와주세요", "자세한 내용", null, 1.0, false, null, null, null, null)).get(0);
    }

    @BeforeEach
    void setUp() {
        me = user("p11me", Role.USER, "행복동");
        regularVol = user("p11reg", Role.USER, "희망동");
        nearVol = user("p11near", Role.USER, "행복동");
        farVol = user("p11far", Role.USER, "미래동");
        admin = user("p11admin", Role.ADMIN, null);
        org = new Organization();
        org.name = "행복동 주민센터"; org.status = OrgStatus.APPROVED;
        orgs.save(org);
        mgr = user("p11mgr", Role.ORG, "행복동");
        mgr.orgId = org.id; users.save(mgr);
        elder = new Elder();
        elder.alias = "이순례"; elder.dong = "행복동"; elder.managerId = mgr.id; elder.orgId = org.id; elder.needs = List.of("동행"); elder.ageGroup = "80대";
        elder.caution = "계단이 있어요";
        elders.save(elder);
        bank.add(me.id, LedgerType.EARN, 23.5, 23.5, 1.0, null, "초기 적립");
    }

    // ---------- 카테고리 건의 ----------
    String sug(String name, String reason) { return "{\"name\":\"" + name + "\",\"reason\":\"" + reason + "\"}"; }

    @Test
    void 카테고리_건의_권한과_같은_이름_묶음_답변_알림() throws Exception {
        mvc.perform(post("/api/category-suggestions").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                .content(sug("반려동물 돌봄", "입원할 때 강아지 산책을 부탁하고 싶어요"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
        // 담당자도 건의할 수 있고, 띄어쓰기만 다른 이름은 한 묶음
        mvc.perform(post("/api/category-suggestions").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                .content(sug("반려동물돌봄", "대상 어르신이 고양이 밥 주기를 자주 부탁하세요"))).andExpect(status().isOk());
        mvc.perform(post("/api/category-suggestions").header("Authorization", tok(nearVol)).contentType(MediaType.APPLICATION_JSON)
                .content(sug("악기 가르치기", "기타를 가르쳐 드리고 싶어요"))).andExpect(status().isOk());
        // 같은 사람이 같은 이름을 다시 건의 / 이미 있는 카테고리 / 운영자 건의 / 개인정보
        mvc.perform(post("/api/category-suggestions").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                .content(sug("반려동물 돌봄", "한 번 더 건의합니다"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ALREADY_SUGGESTED"));
        mvc.perform(post("/api/category-suggestions").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                .content(sug("말동무 정서", "이미 있는 종류입니다"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_EXISTS"));
        mvc.perform(post("/api/category-suggestions").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(sug("새 종류", "운영자는 건의하지 않아요"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/category-suggestions").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                .content(sug("김장 돕기", "010-1234-5678 로 연락 주세요"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PII_NOT_ALLOWED"));
        // 내 건의는 내 것만
        mvc.perform(get("/api/category-suggestions/mine").header("Authorization", tok(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].name").value("반려동물 돌봄"));
        // 운영자 목록은 운영자만, 같은 이름 묶음 건수(많은 순)
        mvc.perform(get("/api/admin/category-suggestions").header("Authorization", tok(me))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/category-suggestions").header("Authorization", tok(mgr))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/category-suggestions").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.waiting").value(3))
                .andExpect(jsonPath("$.groups", hasSize(2)))
                .andExpect(jsonPath("$.groups[0].count").value(2)).andExpect(jsonPath("$.groups[0].key").value("반려동물돌봄"))
                .andExpect(jsonPath("$.groups[1].count").value(1));
        // 답변: 사용자·담당자는 못 하고, 운영자가 답하면 묶음 전체에 적용 + 건의자 둘 다 알림
        Long id = suggestions.findByUserIdOrderByIdDesc(me.id).get(0).id;
        String body = "{\"status\":\"HELD\",\"answer\":\"금지 규칙을 먼저 정해야 해서 보류해요\"}";
        mvc.perform(post("/api/admin/category-suggestions/" + id + "/answer").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/category-suggestions/" + id + "/answer").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ADOPTED\",\"answer\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/category-suggestions/" + id + "/answer").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));
        assertTrue(suggestions.findByNameKey("반려동물돌봄").stream().allMatch(s -> s.status.equals("HELD") && s.answer != null));
        assertEquals("RECEIVED", suggestions.findByNameKey(PlannerController.nameKey("악기 가르치기")).get(0).status);
        assertTrue(notis.findAll().stream().anyMatch(n -> n.userId.equals(me.id) && "CATEGORY_SUGGESTION".equals(n.type)));
        assertTrue(notis.findAll().stream().anyMatch(n -> n.userId.equals(mgr.id) && "CATEGORY_SUGGESTION".equals(n.type)));
        // 운영자 인사이트에 건의 건수
        mvc.perform(get("/api/admin/insights").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.categorySuggestions[0].name").value("반려동물 돌봄")).andExpect(jsonPath("$.categorySuggestions[0].count").value(2));
    }

    // ---------- AI 도움 추천 2섹션 ----------
    @Test
    void 추천은_단골_섹션과_가까운_사람_섹션으로_나뉜다() throws Exception {
        Post pr = offer(regularVol, "장보기·심부름");
        Post pn = offer(nearVol, "장보기·심부름");
        Post pf = offer(farVol, "장보기·심부름");
        offer(nearVol, "디지털 도움"); // 카테고리가 달라 빠진다
        RegularMark m = new RegularMark(); m.userId = me.id; m.volunteerId = regularVol.id; marks.save(m);
        mvc.perform(post("/api/ai/recommend").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"무릎이 아파서 장보기가 힘들어요\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.regularPosts", hasSize(1))).andExpect(jsonPath("$.regularPosts[0].id").value(pr.id.intValue()))
                .andExpect(jsonPath("$.regularPosts[0].fit.regular").value(true))
                .andExpect(jsonPath("$.nearbyPosts[*].id", hasItems(pn.id.intValue(), pf.id.intValue())))
                .andExpect(jsonPath("$.nearbyPosts[*].id", not(hasItem(pr.id.intValue()))))
                // 가까운 사람 섹션은 같은 동이 먼저
                .andExpect(jsonPath("$.nearbyPosts[0].id").value(pn.id.intValue())).andExpect(jsonPath("$.nearbyPosts[0].fit.area").value(true))
                .andExpect(jsonPath("$.help.categories[0].category").value("장보기·심부름")).andExpect(jsonPath("$.help.blocked").value(false));
        // 금지 요청이면 받을 수 없다고 알려 준다
        mvc.perform(post("/api/ai/recommend").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"목욕을 시켜 주세요\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.help.blocked").value(true));
    }

    // ---------- 생애 플랜 ----------
    @Test
    void 생애_플랜_계산은_가중치를_넣고_상속은_없다() {
        Map<String, Double> needs = new LinkedHashMap<>();
        needs.put("동행", 2.0); needs.put("장보기·심부름", 2.0); needs.put("말동무·정서", 1.0);
        Map<String, Object> p = PlannerService.lifeCompute(23.5, 56, 72, 4, needs);
        assertEquals(791.5, (double) p.get("saved"), 1e-9);           // 23.5 + 4×12×16
        assertEquals(6.4, (double) p.get("useMonthly"), 1e-9);        // 2×1.5 + 2×1.2 + 1×1.0
        assertEquals(76.8, (double) p.get("useYearly"), 1e-9);
        assertEquals(10.3, (double) p.get("lastYears"), 1e-9);
        assertEquals(82, p.get("endAge"));
        assertEquals(false, p.get("okTo90"));
        assertEquals(591.0, (double) p.get("need90"), 1e-9);          // 18×76.8 − 791.5 = 590.9 → 0.5 단위
        assertEquals(false, p.get("inherit"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> pts = (List<Map<String, Object>>) p.get("points");
        assertEquals(40, pts.size());                                  // 56 ~ 95세
        assertEquals(791.5, (double) pts.get(16).get("hours"), 1e-9); // 72세에 가장 많다
        assertEquals(0.0, (double) pts.get(39).get("hours"), 1e-9);
        // need90Monthly 로 올리면 90세까지 간다
        Map<String, Object> q = PlannerService.lifeCompute(23.5, 56, 72, (double) p.get("need90Monthly"), needs);
        assertEquals(true, q.get("okTo90"));
        // 받고 싶은 도움이 없으면 끝 나이는 상한(95)
        assertEquals(95, PlannerService.lifeCompute(0, 40, 70, 2, Map.of()).get("endAge"));
    }

    @Test
    void 생애_플랜_API_저장과_검증() throws Exception {
        mvc.perform(get("/api/me/life-plan").header("Authorization", tok(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false)).andExpect(jsonPath("$.plan.balance").value(23.5)).andExpect(jsonPath("$.source").value("rule"));
        mvc.perform(post("/api/me/life-plan").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"age\":56,\"startAge\":72,\"monthlyHours\":4,\"needs\":{\"동행\":2,\"장보기·심부름\":2,\"말동무·정서\":1},\"save\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plan.endAge").value(82)).andExpect(jsonPath("$.saved").value(true));
        mvc.perform(get("/api/me/life-plan").header("Authorization", tok(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.plan.startAge").value(72)).andExpect(jsonPath("$.plan.saved").value(791.5));
        mvc.perform(post("/api/me/life-plan").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"age\":60,\"startAge\":55}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/life-plan").header("Authorization", tok(mgr))).andExpect(status().isForbidden());
    }

    // ---------- 플래너 ----------
    @Test
    void 플래너_추천_봉사는_내_일정과_겹치지_않고_고르면_달력에_들어간다() throws Exception {
        LocalDate d = LocalDate.now().plusDays(2);
        Post free = need(nearVol, d, 10.0, 12.0, "말동무·정서");
        Post clash = need(farVol, d, 14.0, 15.0, "말동무·정서");
        Post fit = need(farVol, d, 14.0, 16.0, "동행"); // 14~15시는 막혀 있지만 15~16시에 들어간다
        Post mine = need(me, d, 18.0, 19.0, "말동무·정서");
        // 내가 14~15시에 봉사하기로 한 요청(진행 중) → clash 공고는 추천에서 빠진다
        Post other = need(regularVol, d, 14.0, 15.0, "디지털 도움");
        reqs.create(me, new RequestService.CreateCmd(Kind.PROPOSAL, other.id, null, null, d, 14.0, 1.0, null, "도와드릴게요", null));
        String month = java.time.YearMonth.from(d).toString();
        mvc.perform(get("/api/me/planner?month=" + month).header("Authorization", tok(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommends[*].id", hasItem(free.id.intValue())))
                .andExpect(jsonPath("$.recommends[*].id", not(hasItem(clash.id.intValue()))))
                .andExpect(jsonPath("$.recommends[*].id", not(hasItem(mine.id.intValue()))))
                .andExpect(jsonPath("$.recommends[*].id", not(hasItem(other.id.intValue()))))
                .andExpect(jsonPath("$.goal.target").value(4.0));
        mvc.perform(post("/api/me/planner/picks").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"postIds\":[" + free.id + "]}")).andExpect(status().isOk());
        mvc.perform(get("/api/me/planner?month=" + month).header("Authorization", tok(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.kind=='plan')].postId", hasItem(free.id.intValue())))
                .andExpect(jsonPath("$.recommends[*].id", not(hasItem(free.id.intValue()))))
                .andExpect(jsonPath("$.goal.planned").value(1.0));
        // 41판 B-61 계획 만들기: 공고 범위(14~16시) 안에서 내 일정(14~15시)과 겹치지 않는 15시로 정해진다(키 없음 → 규칙)
        mvc.perform(post("/api/me/planner/picks").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postIds\":[" + fit.id + "," + clash.id + "]}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.made", hasSize(1))).andExpect(jsonPath("$.made[0].postId").value(fit.id.intValue()))
                .andExpect(jsonPath("$.made[0].startHour").value(15.0)).andExpect(jsonPath("$.made[0].endHour").value(16.0))
                .andExpect(jsonPath("$.made[0].source").value("rule")).andExpect(jsonPath("$.madeSource").value("rule"))
                .andExpect(jsonPath("$.skipped", hasSize(1))).andExpect(jsonPath("$.skipped[0].postId").value(clash.id.intValue()))
                .andExpect(jsonPath("$.items[?(@.postId==" + fit.id + ")].startHour", hasItem(15.0)));
        // B-60 홈 추천: 이미 계획에 넣은 것·겹치는 것은 빠진다(AI 호출 없음)
        mvc.perform(get("/api/me/recommends").header("Authorization", tok(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommends[*].id", not(hasItem(fit.id.intValue()))))
                .andExpect(jsonPath("$.recommends[*].id", not(hasItem(clash.id.intValue()))))
                .andExpect(jsonPath("$.planCount").value(2));
        mvc.perform(delete("/api/me/planner/picks/" + fit.id).header("Authorization", tok(me))).andExpect(status().isOk());
        // 내 공고·도움 가능 공고는 계획에 못 넣는다
        mvc.perform(post("/api/me/planner/picks").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"postIds\":[" + mine.id + "]}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/me/planner/picks/" + free.id).header("Authorization", tok(me))).andExpect(status().isOk());
        mvc.perform(get("/api/me/planner?month=" + month).header("Authorization", tok(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.plans", hasSize(0)));
    }

    // ---------- 담당자 대리 공고의 관리 대상 이슈 ----------
    @Test
    void 대리_공고에_관리_대상_이슈를_적으면_그_공고의_주의사항이_된다() throws Exception {
        String base = "{\"type\":\"NEED\",\"date\":\"" + day + "\",\"startHour\":9,\"endHour\":13,\"area\":\"행복동\",\"categories\":[\"동행\"],\"title\":\"병원 동행\",\"detail\":\"진료 후 약국\",\"estHours\":2,\"elderId\":" + elder.id;
        mvc.perform(post("/api/posts").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content(base + ",\"elderIssue\":\"오전에는 기운이 없으셔서 천천히 걸어 주세요\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.elderIssue").value("오전에는 기운이 없으셔서 천천히 걸어 주세요"))
                .andExpect(jsonPath("$.elderCaution").value("오전에는 기운이 없으셔서 천천히 걸어 주세요"));
        // 안 적으면 대상 등록 때의 주의사항
        mvc.perform(post("/api/posts").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content(base + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.elderCaution").value("계단이 있어요"));
        // 연락처는 막고, 병명은 걸러 낸다
        mvc.perform(post("/api/posts").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                .content(base + ",\"elderIssue\":\"급하면 010-2222-3333 으로 전화\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PII_NOT_ALLOWED"));
        mvc.perform(post("/api/posts").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content(base + ",\"elderIssue\":\"치매가 있으시고 계단이 있어요\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.elderCaution", not(containsString("치매"))));
        // 사용자는 못 적는다
        mvc.perform(post("/api/posts").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NEED\",\"date\":\"" + day + "\",\"startHour\":9,\"endHour\":13,\"area\":\"행복동\",\"categories\":[\"동행\"],\"title\":\"병원 동행\",\"detail\":\"진료\",\"estHours\":2,\"elderIssue\":\"메모\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 동네 기관 행사: 근처 지역 ----------
    @Test
    void 기관_행사는_내_동네가_먼저이고_지역으로_거를_수_있다() throws Exception {
        Organization far = new Organization();
        far.name = "미래동 노인복지관"; far.status = OrgStatus.APPROVED;
        orgs.save(far);
        OrgEvent a = new OrgEvent(); a.orgId = far.id; a.title = "미래동 산책 모임"; a.eventDate = LocalDate.now().plusDays(1); eventRepo.save(a);
        OrgEvent b = new OrgEvent(); b.orgId = org.id; b.title = "행복동 사진 교실"; b.eventDate = LocalDate.now().plusDays(5); eventRepo.save(b);
        var res = mvc.perform(get("/api/events").header("Authorization", tok(me))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(res.indexOf("행복동 사진 교실") < res.indexOf("미래동 산책 모임"), "내 동네 행사가 먼저");
        mvc.perform(get("/api/events?area=미래동").header("Authorization", tok(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", hasItem("미래동 산책 모임"))).andExpect(jsonPath("$[*].title", not(hasItem("행복동 사진 교실"))))
                .andExpect(jsonPath("$[0].area").value("미래동")).andExpect(jsonPath("$[0].near").value(false));
    }

    // ---------- 봉사 인증 항목 ----------
    @Test
    void 인증_항목_설정은_운영자만_바꾸고_휴대폰_인증은_항상_필수() throws Exception {
        mvc.perform(get("/api/verify-items").header("Authorization", tok(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(4))).andExpect(jsonPath("$.items[3].required").value(false)); // 기능 U-34: 첫 활동 교육(edu) 항목이 더해져 4개
        String body = "{\"items\":[{\"key\":\"phone\",\"on\":false,\"required\":false},{\"key\":\"id\",\"on\":true,\"required\":true},{\"key\":\"crime\",\"on\":false,\"required\":true}]}";
        mvc.perform(put("/api/admin/verify-items").header("Authorization", tok(me)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/verify-items").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].on").value(true)).andExpect(jsonPath("$.items[0].required").value(true))
                .andExpect(jsonPath("$.items[1].on").value(true)).andExpect(jsonPath("$.items[1].required").value(true))
                .andExpect(jsonPath("$.items[3].on").value(false)).andExpect(jsonPath("$.items[3].required").value(false));
    }
}
