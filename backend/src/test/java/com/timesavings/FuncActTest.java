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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 기능명세서 — 진행·일지·평가·단골·시간 통장(기능 U-16, U-18, U-19, U-21, U-22, U-24, U-25).
 * AI 없이 규칙 모드, 메모리 H2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FuncActTest {
    @Autowired UserRepo users;
    @Autowired RequestRepo requests;
    @Autowired NotificationRepo notis;
    @Autowired LedgerRepo ledger;
    @Autowired RequestService reqs;
    @Autowired PostService postSvc;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired Views views;
    @Autowired TimePlanService plans;
    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    User vol, rec, stranger;
    final LocalDate day = LocalDate.now().plusDays(3);
    int seq = 0;

    User user(String id, String ageGroup, double earned) {
        User u = new User();
        u.loginId = "fa" + id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = Role.USER; u.area = "행복동"; u.ageGroup = ageGroup;
        u.idStatus = VerifyStatus.APPROVED; u.phone = "010-0000-" + (3000 + seq++);
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, "초기 적립");
        return u;
    }

    @BeforeEach
    void setUp() {
        vol = user("vol", "20대", 0);
        rec = user("rec", "70대", 30);
        stranger = user("stranger", "30대", 0);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 종료 체크까지 끝난(일지 쓰기 직전) 요청. */
    Request checkedOut(User v, User r, String cat, double start) {
        Post p = postSvc.create(v, new PostService.PostCmd(PostType.OFFER, day, start, start + 2, "행복동",
                List.of(cat), "도와드려요", null, null, null, false, null, null, null, null)).get(0);
        Request x = reqs.create(r, new RequestService.CreateCmd(Kind.REQUEST, p.id, null, null, day, start, 1.0, cat, cat + " 부탁", null));
        reqs.accept(v, x.id);
        reqs.accept(r, x.id);
        reqs.checkIn(v, x.id, reqs.codeFor(x), "code");
        reqs.checkOut(v, x.id, reqs.codeFor(x), "code");
        return x;
    }

    Request done(User v, User r, String cat, double start) {
        Request x = checkedOut(v, r, cat, start);
        reqs.submitLog(v, x.id, "함께함", "함께했어요.", "/api/files/a.png", false, null, "/api/files/b.png");
        reqs.confirm(r, x.id);
        return x;
    }

    String upload(User u, String name) throws Exception {
        return json(mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", name, "image/png", new byte[]{1, 2, 3}))
                .header("Authorization", tok(u))).andExpect(status().isOk())).path("url").asText();
    }

    int open(String url, User u) throws Exception {
        return mvc.perform(get(url).header("Authorization", tok(u))).andReturn().getResponse().getStatus();
    }

    // ---------------- 기능 U-16 사진 인증 ----------------
    @Test
    void 장보기는_영수증_사진이_있어야_일지를_낼_수_있다() {
        assertEquals("RECEIPT", RequestService.photoRule("장보기·심부름"));
        assertEquals("BEFORE_AFTER", RequestService.photoRule("가벼운 집안일"));
        assertEquals("NONE", RequestService.photoRule("동행"));
        Request r = checkedOut(vol, rec, "장보기·심부름", 10);
        ApiException e = assertThrows(ApiException.class, () -> reqs.submitLog(vol, r.id, "장 봄", null, null, false));
        assertEquals("PHOTO_REQUIRED", e.code);
        assertEquals(ReqStatus.CHECKED_OUT, r.status);
        // 촬영 동의 칸과 상관없이 영수증 사진은 저장된다
        reqs.submitLog(vol, r.id, "장 봄", null, "/api/files/receipt.png", false, null, null);
        assertEquals(ReqStatus.LOG_SUBMITTED, r.status);
        assertEquals("/api/files/receipt.png", r.logPhotoUrl);
        assertNull(r.logPhotoUrl2);
        // 고쳐서 다시 낼 때 사진을 다시 올리지 않아도 된다
        reqs.submitLog(vol, r.id, "장 보고 정리함", null, null, false);
        assertEquals("/api/files/receipt.png", r.logPhotoUrl);
    }

    @Test
    void 집안일은_전후_사진_두_장이_있어야_하고_다른_카테고리는_선택이다() {
        Request r = checkedOut(vol, rec, "가벼운 집안일", 10);
        assertEquals("PHOTO_REQUIRED", assertThrows(ApiException.class,
                () -> reqs.submitLog(vol, r.id, "청소", null, "/api/files/before.png", true, null, null)).code);
        reqs.submitLog(vol, r.id, "청소", null, "/api/files/before.png", true, null, "/api/files/after.png");
        assertEquals("/api/files/before.png", r.logPhotoUrl);
        assertEquals("/api/files/after.png", r.logPhotoUrl2);

        Request talk = checkedOut(vol, rec, "말동무·정서", 14);
        reqs.submitLog(vol, talk.id, "이야기 나눔", null, null, false);
        assertEquals(ReqStatus.LOG_SUBMITTED, talk.status);
        assertNull(talk.logPhotoUrl);
    }

    @Test
    void 인증_사진은_직접_올린_파일만_붙이고_당사자만_연다() throws Exception {
        Request r = checkedOut(vol, rec, "가벼운 집안일", 10);
        String before = upload(vol, "before.png"), after = upload(vol, "after.png"), others = upload(stranger, "x.png");
        mvc.perform(get("/api/requests/" + r.id).header("Authorization", tok(vol))).andExpect(jsonPath("$.photoRule").value("BEFORE_AFTER"));
        mvc.perform(post("/api/requests/" + r.id + "/log").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"raw\":\"청소\",\"photoUrl\":\"" + before + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTO_REQUIRED"));
        mvc.perform(post("/api/requests/" + r.id + "/log").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"raw\":\"청소\",\"photoUrl\":\"" + before + "\",\"photoUrl2\":\"" + others + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_NOT_MINE"));
        mvc.perform(post("/api/requests/" + r.id + "/log").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"raw\":\"청소\",\"photoUrl\":\"" + before + "\",\"photoUrl2\":\"" + after + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.logPhotoUrl2").value(after));
        for (String url : List.of(before, after)) {
            assertEquals(200, open(url, vol));
            assertEquals(200, open(url, rec));
            assertEquals(403, open(url, stranger));
        }
    }

    // ---------------- 기능 U-18 평가 비공개 ----------------
    @Test
    void 개별_평가는_상대에게_알리지_않는다() throws Exception {
        Request r = done(vol, rec, "말동무·정서", 10);
        reqs.rate(rec, r.id, 2, List.of("편안해요"), "조금 아쉬웠어요");
        List<Notification> rated = notis.findByUserIdOrderByCreatedAtDescIdDesc(vol.id).stream().filter(n -> "RATED".equals(n.type)).toList();
        assertEquals(1, rated.size());
        String all = rated.get(0).title + " " + rated.get(0).body;
        assertFalse(all.contains("★"));
        assertFalse(all.contains("2"), all); // 별점 숫자가 없다
        assertFalse(all.contains("아쉬웠"));
        // 봉사자가 보는 요청 상세에도 상대가 준 별점·한마디가 없다
        String body = mvc.perform(get("/api/requests/" + r.id).header("Authorization", tok(vol))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("아쉬웠"));
        assertFalse(body.contains("\"stars\""));
        // 봉사자 → 받는 분 평가는 비공개 점수이고 알림도 가지 않는다
        long before = notis.findByUserIdOrderByCreatedAtDescIdDesc(rec.id).size();
        Rating x = reqs.rate(vol, r.id, 5, List.of("친절해요"), null);
        assertTrue(x.isPrivate);
        assertEquals(before, notis.findByUserIdOrderByCreatedAtDescIdDesc(rec.id).size());
        assertEquals(0, views.rating(rec.id)[1]);
    }

    // ---------------- 기능 U-19 단골 자동 지정(양쪽) ----------------
    @Test
    void 완료_3회째에_양쪽_모두_단골이_되고_알림을_받는다() {
        done(vol, rec, "말동무·정서", 9);
        done(vol, rec, "말동무·정서", 12);
        assertFalse(views.isRegular(vol.id, rec.id, null));
        assertEquals(0, notis.findByUserIdOrderByCreatedAtDescIdDesc(vol.id).stream().filter(n -> "REGULAR".equals(n.type)).count());
        done(vol, rec, "말동무·정서", 15);
        assertTrue(views.isRegular(vol.id, rec.id, null));
        // 받는 쪽: 내 단골 봉사자 목록 / 봉사자 쪽: 나를 단골로 둔 분 목록
        assertTrue(views.regularsOf(rec.id, null).stream().anyMatch(m -> vol.id.equals(m.get("id"))));
        assertTrue(views.regularBadgeList(vol.id, vol.id).stream().anyMatch(m -> rec.id.equals(m.get("userId"))));
        for (User u : List.of(vol, rec))
            assertEquals(1, notis.findByUserIdOrderByCreatedAtDescIdDesc(u.id).stream().filter(n -> "REGULAR".equals(n.type)).count(), u.name);
        done(vol, rec, "말동무·정서", 18); // 4회째에는 다시 알리지 않는다
        assertEquals(1, notis.findByUserIdOrderByCreatedAtDescIdDesc(vol.id).stream().filter(n -> "REGULAR".equals(n.type)).count());
    }

    // ---------------- 기능 U-21 잔고 · U-22 사용 내역 ----------------
    @Test
    void 통장은_쓸_수_있는_시간_묶인_시간_누적과_월별_합계_계산식을_준다() throws Exception {
        done(vol, rec, "장보기·심부름", 9); // 1시간 × 1.2 = 1.0
        Post p = postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day, 13.0, 16.0, "행복동",
                List.of("동행"), "도와드려요", null, null, null, false, null, null, null, null)).get(0);
        Request held = reqs.create(rec, new RequestService.CreateCmd(Kind.REQUEST, p.id, null, null, day, 13.0, 2.0, "동행", "병원 동행", null));
        reqs.accept(vol, held.id);
        reqs.accept(rec, held.id); // 확정 → 2시간 × 1.5 = 3시간 묶임
        JsonNode b = json(mvc.perform(get("/api/bank").header("Authorization", tok(rec))).andExpect(status().isOk()));
        assertEquals(3.0, b.path("hold").asDouble());
        assertEquals(29.0, b.path("balance").asDouble());
        assertEquals(26.0, b.path("available").asDouble());
        assertEquals(30.0, b.path("totalEarned").asDouble());
        assertEquals(1.0, b.path("totalUsed").asDouble());
        JsonNode m = b.path("months");
        assertEquals(1, m.size());
        assertEquals(YearMonth.now().toString(), m.get(0).path("month").asText());
        assertEquals(30.0, m.get(0).path("in").asDouble());
        assertEquals(1.0, m.get(0).path("out").asDouble());
        // 건별 계산식 재료: 시간 × 난이도 가중치
        JsonNode use = null;
        for (JsonNode e : b.path("entries")) if ("USE".equals(e.path("type").asText())) use = e;
        assertNotNull(use);
        assertEquals(1.0, use.path("baseHours").asDouble());
        assertEquals(1.2, use.path("weight").asDouble());
        assertEquals(-1.0, use.path("amount").asDouble());
    }

    // ---------------- 기능 U-24 시간 플랜 ----------------
    @Test
    void 시간_플랜_계산은_가중치를_넣어_몇_년_받을_수_있는지와_적립_목표를_낸다() {
        Map<String, Double> pat = new LinkedHashMap<>();
        pat.put("동행", 2.0); pat.put("말동무·정서", 1.0); // 한 달 2×1.5 + 1×1.0 = 4시간, 한 해 48시간
        Map<String, Object> p = TimePlanService.compute(50, 70, 24, 2, pat);
        assertEquals(4.0, p.get("useMonthly"));
        assertEquals(48.0, p.get("useYearly"));
        assertEquals(504.0, p.get("saved")); // 24 + 2×12×20
        assertEquals(0.5, p.get("nowYears"));
        assertEquals(10.5, p.get("years"));
        assertEquals(80, p.get("endAge"));
        assertEquals(90, p.get("targetAge"));
        assertEquals(960.0, p.get("needTotal")); // 20년 × 48
        assertEquals(456.0, p.get("shortfall"));
        assertEquals(4.0, p.get("goalMonthly")); // (960−24) ÷ 240 = 3.9 → 4.0
        assertEquals(false, p.get("onTrack"));
        // 이미 받는 나이: 지금부터 받는 것으로 계산
        Map<String, Object> now = TimePlanService.compute(75, 75, 96, 0, pat);
        assertEquals(0, now.get("saveYears"));
        assertEquals(2.0, now.get("years"));
        assertEquals(77, now.get("endAge"));
        // 충분히 모으는 사람
        assertEquals(true, TimePlanService.compute(50, 70, 0, 5, pat).get("onTrack"));
    }

    Request received(User r, User v, String cat, double hours, LocalDate date) {
        Request x = new Request();
        x.kind = Kind.REGULAR; x.senderId = r.id; x.recipientId = r.id; x.volunteerId = v.id; x.date = date; x.startHour = 10; x.hours = hours;
        x.category = cat; x.content = cat; x.status = ReqStatus.DONE; x.difficulty = Rules.categoryLevel(cat); x.weight = Rules.weight(x.difficulty);
        x.estEarn = Rules.earn(hours, x.weight); x.earned = x.estEarn;
        return requests.save(x);
    }

    @Test
    void 비슷한_사람이_5명_미만이면_일반적인_가정으로_계산했다고_밝힌다() throws Exception {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 4; i++) received(user("p" + i, "70대", 0), vol, "동행", 2, today.minusDays(2));
        TimePlanService.Pattern pat = plans.peerPattern(rec, today);
        assertTrue(pat.usedDefault());
        assertEquals(TimePlanService.defaultPattern(), pat.monthlyHours());
        JsonNode d = json(mvc.perform(get("/api/me/time-plan").header("Authorization", tok(rec))).andExpect(status().isOk()));
        assertEquals(TimePlanService.DEFAULT_NOTICE, d.path("notice").asText());
        assertTrue(d.path("pattern").path("usedDefault").asBoolean());
        assertEquals("rule", d.path("source").asText());
        assertEquals(75, d.path("age").asInt()); // 70대 → 75세로 어림
        assertEquals(30.0, d.path("balance").asDouble());
        assertFalse(d.path("text").asText().isBlank());
        assertFalse(d.path("goalText").asText().isBlank());
    }

    @Test
    void 같은_나이대_5명_이상이면_그분들의_실제_사용_기록으로_계산한다() throws Exception {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 5; i++) {
            User p = user("q" + i, "70대", 0);
            received(p, vol, "동행", 2, today.minusDays(1));          // 다섯 명 모두 이번 달 동행 2시간
            if (i < 2) received(p, vol, "말동무·정서", 1, today.minusDays(1)); // 두 명만 말동무 1시간 → 평균 0.4
        }
        received(rec, vol, "가벼운 집안일", 4, today.minusDays(1)); // 내 기록은 "비슷한 사람들"에 넣지 않는다
        TimePlanService.Pattern pat = plans.peerPattern(rec, today);
        assertEquals("SAME_AGE", pat.basis());
        assertEquals(5, pat.peers());
        assertEquals(Map.of("동행", 2.0, "말동무·정서", 0.4), pat.monthlyHours());
        JsonNode d = json(mvc.perform(get("/api/me/time-plan").header("Authorization", tok(rec))).andExpect(status().isOk()));
        assertTrue(d.path("notice").isNull());
        assertEquals(3.4, d.path("useMonthly").asDouble()); // 2×1.5 + 0.4×1.0
        assertEquals(2, d.path("rows").size());
        // 이름 같은 개인 정보는 응답에 없다
        assertFalse(d.toString().contains("q0"));
        // 20대(같은 나이대 기록 없음)는 60대 이상 이웃의 기록을 쓴다
        assertEquals("SENIOR", plans.peerPattern(stranger, today).basis());
        // 나이를 바꿔 다시 계산
        JsonNode e = json(mvc.perform(get("/api/me/time-plan?age=40&startAge=70").header("Authorization", tok(vol))).andExpect(status().isOk()));
        assertEquals(30, e.path("saveYears").asInt());
        mvc.perform(get("/api/me/time-plan?age=80&startAge=70").header("Authorization", tok(vol)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_START_AGE"));
    }

    @Test
    void 적립_속도는_최근_여섯_달에_직접_번_시간의_한_달_평균이다() {
        LocalDate today = LocalDate.now();
        assertEquals(0.0, plans.earnRate(vol.id, today)[0]);
        Ledger a = bank.add(vol.id, LedgerType.EARN, 6, 6.0, 1.0, null, "적립");
        a.createdAt = today.minusMonths(2).atTime(10, 0);
        bank.add(vol.id, LedgerType.EARN, 3, 2.0, 1.5, null, "적립");
        bank.add(vol.id, LedgerType.GIFT_IN, 10, null, null, null, "선물"); // 선물은 적립 속도에 넣지 않는다
        double[] r = plans.earnRate(vol.id, today);
        assertEquals(3.0, r[0]); // 9시간 ÷ 3달
        assertEquals(3.0, r[1]);
    }

    // ---------------- 기능 U-25 월별 리포트 ----------------
    @Test
    void 월별_리포트는_횟수와_많이_한_도움_지난달_대비_변화를_센다() throws Exception {
        LocalDate today = LocalDate.now(), last = today.minusMonths(1).withDayOfMonth(1);
        received(rec, vol, "동행", 2, today);
        received(rec, vol, "동행", 1, today);
        received(rec, vol, "말동무·정서", 1, today);
        received(stranger, vol, "동행", 1, last);
        received(vol, stranger, "디지털 도움", 1, today);
        JsonNode d = json(mvc.perform(get("/api/me/month-report").header("Authorization", tok(vol))).andExpect(status().isOk()));
        assertEquals(YearMonth.now().toString(), d.path("month").asText());
        assertEquals(3, d.path("give").path("count").asInt());
        assertEquals(4.0, d.path("give").path("hours").asDouble());
        assertEquals("동행", d.path("give").path("top").asText());
        assertEquals(2, d.path("give").path("topCount").asInt());
        assertEquals(1, d.path("receive").path("count").asInt());
        assertEquals(1, d.path("prev").path("give").path("count").asInt());
        assertEquals(2, d.path("change").path("giveCount").asInt());
        assertEquals(1, d.path("change").path("receiveCount").asInt());
        assertEquals("rule", d.path("source").asText());
        String text = d.path("text").asText();
        assertTrue(text.contains("봉사를 3회"), text);
        assertTrue(text.contains("동행(2회)"), text);
        assertTrue(text.contains("지난달보다 2회 늘었어요"), text);
        // 지난달 리포트, 잘못된 달
        JsonNode p = json(mvc.perform(get("/api/me/month-report?month=" + YearMonth.now().minusMonths(1)).header("Authorization", tok(vol))).andExpect(status().isOk()));
        assertEquals(1, p.path("give").path("count").asInt());
        mvc.perform(get("/api/me/month-report?month=abc").header("Authorization", tok(vol))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/month-report?month=" + YearMonth.now().plusMonths(1)).header("Authorization", tok(vol))).andExpect(status().isBadRequest());
    }
}
