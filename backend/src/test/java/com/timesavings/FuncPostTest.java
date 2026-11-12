package com.timesavings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.ai.BundleAi;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기능명세서(73개 기능) — 공고·요청 범위.
 * 기능 U-09 정기 요청(회차마다 공고 자동 생성, 다음 회차·남은 회차, 일시정지·해지),
 * 기능 U-12 빈 시간 묶음 추천(겹치지 않는 2~3건, 한 번에 제안, 일부 실패 알림), 기능 U-02 시간순 정렬.
 * 규칙 모드(AI 호출 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FuncPostTest {
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired FamilyLinkRepo family;
    @Autowired NotificationRepo notis;
    @Autowired PostRepo postRepo;
    @Autowired RequestRepo reqRepo;
    @Autowired RecurringPostRepo recurRepo;
    @Autowired RecurringService recur;
    @Autowired BundleService bundles;
    @Autowired PostService postSvc;
    @Autowired RequestService reqs;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    User son, mom, vol, vol2, mgr, other;
    Elder elder;
    FamilyLink link;
    /** 첫 회차가 7일 안에 들어오게: 모레 */
    final LocalDate first = LocalDate.now().plusDays(2);
    final int wd = first.getDayOfWeek().getValue();
    /** 묶음 추천용 날짜(시범 자료와 겹치지 않게 멀리) */
    final LocalDate day = LocalDate.now().plusDays(40);

    User user(String id, Role role, String area, double earned) {
        User u = new User();
        u.loginId = id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = area; u.ageGroup = "60대";
        u.idStatus = VerifyStatus.APPROVED; u.phone = "010-0000-" + (6000 + users.count());
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, "초기 적립");
        return u;
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    JsonNode json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    JsonNode call(User u, String path, String body, int expect) throws Exception {
        return json(mvc.perform(post(path).header("Authorization", tok(u)).contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body))
                .andExpect(status().is(expect)));
    }

    String recurBody(String extra) {
        return "{\"weekday\":" + wd + ",\"startDate\":\"" + LocalDate.now().plusDays(1) + "\",\"weeks\":4,\"startHour\":10,\"endHour\":12,\"estHours\":1,"
                + "\"area\":\"행복동\",\"categories\":[\"말동무·정서\"],\"title\":\"매주 말벗\",\"detail\":\"이야기 나누기\"" + extra + "}";
    }

    Post need(User owner, String area, double s, double e, double est, String cat) {
        return postSvc.create(owner, new PostService.PostCmd(PostType.NEED, day, s, e, area, List.of(cat), cat + " 부탁", "내용", null, est,
                false, 1, null, null, null)).get(0);
    }

    @BeforeEach
    void setUp() {
        son = user("fpson", Role.USER, "행복동", 0);
        mom = user("fpmom", Role.USER, "행복동", 30);
        vol = user("fpvol", Role.USER, "행복동", 0);
        vol2 = user("fpvol2", Role.USER, "희망동", 0);
        other = user("fpother", Role.USER, "미래동", 30);
        mgr = user("fpmgr", Role.ORG, "행복동", 0);
        elder = new Elder();
        elder.alias = "김복순"; elder.dong = "행복동"; elder.managerId = mgr.id; elder.needs = List.of("말동무·정서"); elder.ageGroup = "80대";
        elders.save(elder);
        link = new FamilyLink();
        link.requesterId = son.id; link.targetId = mom.id; link.relation = "어머니"; link.status = LinkStatus.ACCEPTED;
        family.save(link);
    }

    // ================= 기능 U-09 정기 요청 =================
    @Test
    void 정기_요청을_등록하면_다가오는_회차만_공고로_생긴다() throws Exception {
        JsonNode r = call(mom, "/api/recurring", recurBody(""), 200);
        assertEquals("ACTIVE", r.path("status").asText());
        assertEquals(4, r.path("totalCount").asInt());
        assertEquals(4, r.path("remainingCount").asInt());
        assertEquals(first.toString(), r.path("nextDate").asText());
        assertEquals(first.plusWeeks(3).toString(), r.path("endDate").asText());
        assertTrue(r.path("repeatLabel").asText().startsWith("매주 "));
        // 7일 안 회차 1건만 공고로 생겼다(한꺼번에 4건을 만들지 않는다)
        assertEquals(1, r.path("createdCount").asInt());
        long postId = r.path("createdPostIds").get(0).asLong();
        assertEquals(postId, r.path("nextPostId").asLong());
        Post p = postRepo.findById(postId).orElseThrow();
        assertEquals(first, p.date);
        assertEquals("RC" + r.path("id").asLong(), p.seriesId);
        assertEquals(r.path("repeatLabel").asText(), p.repeatRule);
        assertEquals(PostType.NEED, p.type);
        assertEquals(mom.id, p.ownerId);
        assertEquals(PostStatus.OPEN, p.status);
        assertEquals(1.0, p.estHours);
        // 같은 요일·시간으로 한 번 더 등록할 수 없다
        assertEquals("DUPLICATE_RECURRING", call(mom, "/api/recurring", recurBody(""), 409).path("code").asText());
        // 목록에 보인다. 남은 못 본다
        JsonNode list = json(mvc.perform(get("/api/recurring").header("Authorization", tok(mom))).andExpect(status().isOk()));
        assertEquals(1, list.size());
        assertEquals(0, json(mvc.perform(get("/api/recurring").header("Authorization", tok(other))).andExpect(status().isOk())).size());
        mvc.perform(get("/api/recurring/" + r.path("id").asLong()).header("Authorization", tok(other))).andExpect(status().isForbidden());
    }

    @Test
    void 스케줄러가_회차마다_공고를_한_건씩_만든다() {
        var c = recur.create(mom, new RecurringService.Cmd(wd, LocalDate.now().plusDays(1), null, 3, 10.0, 12.0, 1.0, "행복동",
                List.of("말동무·정서"), "매주 말벗", "이야기", null, null, null, null), LocalDateTime.now());
        RecurringPost r = c.rule();
        assertEquals(1, c.posts().size());
        // 같은 날 다시 돌려도 더 만들지 않는다
        assertEquals(0, recur.generateDue(LocalDateTime.now()));
        assertEquals(0, recur.generateDue(LocalDateTime.now().plusDays(1)));
        // 둘째 회차 7일 전이 되면 그 회차 1건, 한 번만
        LocalDateTime t2 = first.plusWeeks(1).minusDays(7).atTime(9, 0);
        assertEquals(1, recur.generateDue(t2));
        assertEquals(0, recur.generateDue(t2.plusMinutes(1)));
        // 셋째(마지막) 회차까지
        assertEquals(1, recur.generateDue(first.plusWeeks(1).atTime(9, 0)));
        List<Post> series = postRepo.findBySeriesId(r.seriesId());
        assertEquals(3, series.size());
        assertEquals(List.of(first, first.plusWeeks(1), first.plusWeeks(2)), series.stream().map(p -> p.date).sorted().toList());
        // 기간이 끝나면 더 만들지 않고 ENDED
        assertEquals(0, recur.generateDue(first.plusWeeks(2).plusDays(1).atTime(9, 0)));
        assertEquals("ENDED", recurRepo.findById(r.id).orElseThrow().status);
        // 회차 공고가 올라갈 때 등록한 사람에게 알림(등록할 때 만든 첫 회차는 알림 없음)
        assertEquals(2, notis.findAll().stream().filter(n -> "RECURRING_POST".equals(n.type) && mom.id.equals(n.userId)).count());
    }

    @Test
    void 오늘_시작_시각이_지난_회차는_건너뛴다() {
        LocalDate today = LocalDate.now();
        var c = recur.create(mom, new RecurringService.Cmd(today.getDayOfWeek().getValue(), today, null, 2, 10.0, 12.0, 1.0, "행복동",
                List.of("말동무·정서"), "매주 말벗", "이야기", null, null, null, null), today.atTime(15, 0));
        // 오늘 10시는 이미 지났으니 오늘 회차 공고는 없고, 다음 주 회차가 7일 안이라 그것만 생긴다
        assertEquals(1, c.posts().size());
        assertEquals(today.plusWeeks(1), c.posts().get(0).date);
    }

    @Test
    void 일시정지하면_만들지_않고_다시_시작하면_이어_만든다() throws Exception {
        JsonNode r = call(mom, "/api/recurring", recurBody(""), 200);
        long id = r.path("id").asLong();
        JsonNode paused = call(mom, "/api/recurring/" + id + "/pause", null, 200);
        assertEquals("PAUSED", paused.path("status").asText());
        assertTrue(paused.path("actions").toString().contains("resume"));
        LocalDateTime t2 = first.plusWeeks(1).minusDays(3).atTime(9, 0);
        assertEquals(0, recur.generateDue(t2));
        assertEquals(1, postRepo.findBySeriesId("RC" + id).size());
        call(mom, "/api/recurring/" + id + "/pause", null, 409); // 이미 멈춤
        call(other, "/api/recurring/" + id + "/resume", null, 403);
        JsonNode resumed = call(mom, "/api/recurring/" + id + "/resume", null, 200);
        assertEquals("ACTIVE", resumed.path("status").asText());
        assertEquals(1, recur.generateDue(t2));
        assertEquals(2, postRepo.findBySeriesId("RC" + id).size());
    }

    @Test
    void 해지하면_수락_안_된_앞으로의_회차_공고를_내리고_수락된_회차는_둔다() throws Exception {
        JsonNode r = call(mom, "/api/recurring", recurBody(""), 200);
        long id = r.path("id").asLong();
        long p1 = r.path("createdPostIds").get(0).asLong();
        assertEquals(1, recur.generateDue(first.plusWeeks(1).minusDays(2).atTime(9, 0)));
        Post second = postRepo.findBySeriesId("RC" + id).stream().filter(p -> p.id != p1).findFirst().orElseThrow();
        // 첫 회차: 제안 2건 중 1건 수락 → 공고 1개에 요청 1건 규칙 그대로(다른 제안은 자동 거절)
        Request a = reqs.create(vol, new RequestService.CreateCmd(Kind.PROPOSAL, p1, null, null, null, 10.0, 1.0, null, "제가 갈게요", null));
        Request b = reqs.create(vol2, new RequestService.CreateCmd(Kind.PROPOSAL, p1, null, null, null, 11.0, 1.0, null, "저도 가능해요", null));
        reqs.accept(mom, a.id);
        assertEquals(ReqStatus.CONFIRMED, reqRepo.findById(a.id).orElseThrow().status);
        assertEquals(ReqStatus.REJECTED, reqRepo.findById(b.id).orElseThrow().status);
        // 둘째 회차: 기다리는 제안 1건
        Request c = reqs.create(vol2, new RequestService.CreateCmd(Kind.PROPOSAL, second.id, null, null, null, 10.0, 1.0, null, "다음 주도 가능해요", null));
        JsonNode out = call(mom, "/api/recurring/" + id + "/cancel", null, 200);
        assertEquals("CANCELLED", out.path("status").asText());
        assertEquals(1, out.path("removedPosts").asInt());
        assertEquals(1, out.path("keptPosts").asInt());
        assertEquals(0, out.path("remainingCount").asInt());
        assertTrue(out.path("nextDate").isNull());
        assertEquals(PostStatus.DELETED, postRepo.findById(second.id).orElseThrow().status);
        assertEquals(ReqStatus.CANCELLED, reqRepo.findById(c.id).orElseThrow().status);
        assertEquals(PostStatus.MATCHED, postRepo.findById(p1).orElseThrow().status);
        assertEquals(ReqStatus.CONFIRMED, reqRepo.findById(a.id).orElseThrow().status);
        // 해지 뒤에는 만들지 않는다
        assertEquals(0, recur.generateDue(first.plusWeeks(2).minusDays(2).atTime(9, 0)));
        call(mom, "/api/recurring/" + id + "/cancel", null, 409);
    }

    @Test
    void 가족_대신_정기_요청은_공고_주인이_가족이고_연결이_끊기면_멈춘다() throws Exception {
        JsonNode r = call(son, "/api/recurring", recurBody(",\"forUserId\":" + mom.id), 200);
        long id = r.path("id").asLong();
        assertTrue(r.path("proxy").asBoolean());
        assertEquals(mom.id, r.path("ownerId").asLong());
        Post p = postRepo.findById(r.path("createdPostIds").get(0).asLong()).orElseThrow();
        assertEquals(mom.id, p.ownerId);
        assertEquals(son.id, p.createdBy);
        // 가족 본인도 목록에서 보고 멈출 수 있다
        assertEquals(1, json(mvc.perform(get("/api/recurring").header("Authorization", tok(mom))).andExpect(status().isOk())).size());
        // 연결되지 않은 사람 대신은 안 된다
        call(son, "/api/recurring", recurBody(",\"forUserId\":" + other.id), 403);
        // 연결이 끊기면 다음 회차를 만들지 못하고 멈춘다(까닭을 남기고 알림)
        family.delete(link);
        assertEquals(0, recur.generateDue(first.plusWeeks(1).minusDays(2).atTime(9, 0)));
        RecurringPost rule = recurRepo.findById(id).orElseThrow();
        assertEquals("PAUSED", rule.status);
        assertNotNull(rule.stopReason);
        assertTrue(notis.findAll().stream().anyMatch(n -> "RECURRING_STOPPED".equals(n.type) && son.id.equals(n.userId)));
    }

    @Test
    void 담당자는_관리_대상_대리_정기_요청을_등록한다() throws Exception {
        call(mgr, "/api/recurring", recurBody(""), 400); // 대상 없이
        JsonNode r = call(mgr, "/api/recurring", recurBody(",\"elderId\":" + elder.id + ",\"elderIssue\":\"천천히 걸어 주세요\""), 200);
        Post p = postRepo.findById(r.path("createdPostIds").get(0).asLong()).orElseThrow();
        assertEquals(elder.id, p.elderId);
        assertNull(p.ownerId);
        assertEquals("천천히 걸어 주세요", p.issueNote);
        assertEquals(1, json(mvc.perform(get("/api/recurring?elderId=" + elder.id).header("Authorization", tok(mgr))).andExpect(status().isOk())).size());
        call(mom, "/api/recurring", recurBody(",\"elderId\":" + elder.id), 403); // 담당자가 아님
        call(mgr, "/api/recurring", recurBody(",\"elderId\":" + elder.id + ",\"elderIssue\":\"전화 010-1234-5678\""), 400);
    }

    @Test
    void 정기_요청_입력_검사() throws Exception {
        assertEquals("INVALID_PERIOD", call(mom, "/api/recurring", recurBody("").replace("\"weeks\":4", "\"weeks\":1"), 400).path("code").asText());
        assertEquals("INVALID_PERIOD", call(mom, "/api/recurring", recurBody("").replace("\"weeks\":4", "\"weeks\":27"), 400).path("code").asText());
        assertEquals("INVALID_PERIOD", call(mom, "/api/recurring", recurBody("").replace("\"weeks\":4,", ""), 400).path("code").asText());
        assertEquals("INVALID_DATE", call(mom, "/api/recurring", recurBody("").replace(LocalDate.now().plusDays(1).toString(), LocalDate.now().minusDays(1).toString()), 400).path("code").asText());
        assertEquals("INVALID_CATEGORY", call(mom, "/api/recurring", recurBody("").replace("[\"말동무·정서\"]", "[]"), 400).path("code").asText());
        // 금지 요청(신체 돌봄)은 정기 요청으로도 막는다
        assertEquals("FORBIDDEN_REQUEST", call(mom, "/api/recurring", recurBody("").replace("이야기 나누기", "매주 목욕시켜 주세요"), 400).path("code").asText());
        // 끝 날짜로 정하면 그 안의 마지막 회차 날짜로 맞춘다
        JsonNode r = call(mom, "/api/recurring", recurBody("").replace("\"weeks\":4", "\"endDate\":\"" + first.plusWeeks(2).plusDays(3) + "\""), 200);
        assertEquals(3, r.path("totalCount").asInt());
        assertEquals(first.plusWeeks(2).toString(), r.path("endDate").asText());
        assertEquals(0, recurRepo.findAll().stream().filter(x -> x.createdBy.equals(other.id)).count());
    }

    // ================= 기능 U-12 빈 시간 묶음 추천 =================
    @Test
    void 이동_시간_계산은_코드가_한다() {
        assertEquals(0.0, BundleService.kmBetween("행복동", "행복동"));
        double km = BundleService.kmBetween("행복동", "희망동");
        assertTrue(km > 0.9 && km < 1.3, "행복동-희망동 " + km);
        assertEquals(BundleService.kmBetween("희망동", "행복동"), km);
        assertEquals(2.5, BundleService.kmBetween("행복동", "없는동"));
        assertEquals(0.5, BundleService.gapHours(0));      // 같은 동도 최소 30분
        assertEquals(0.5, BundleService.gapHours(1.0));    // 15분 + 10분 → 30분
        assertEquals(1.0, BundleService.gapHours(2.5));    // 38분 + 10분 → 1시간
        assertEquals(15, BundleService.travelMinutes(1.0));
    }

    @Test
    void 겹치지_않고_가까운_공고를_묶어_추천한다() throws Exception {
        Post a = need(mom, "행복동", 10, 11, 1, "말동무·정서");
        Post b = need(other, "행복동", 11, 14, 1.5, "장보기·심부름");
        Post c = need(other, "희망동", 10, 11, 1, "디지털 도움");   // a 와 시간이 겹친다
        need(vol, "행복동", 10, 12, 1, "말동무·정서");               // 내 공고는 빼야 한다
        JsonNode out = call(vol, "/api/bundles/recommend", "{\"date\":\"" + day + "\",\"fromHour\":9,\"toHour\":15}", 200);
        assertEquals("rule", out.path("source").asText());
        assertEquals(3, out.path("candidateCount").asInt());
        JsonNode bs = out.path("bundles");
        assertTrue(bs.size() >= 1 && bs.size() <= 3, out.toString());
        for (JsonNode bd : bs) {
            assertTrue(bd.path("count").asInt() >= 2 && bd.path("count").asInt() <= 3);
            assertFalse(bd.path("reason").asText().isBlank());
            assertEquals("rule", bd.path("source").asText());
            double prevEnd = -1;
            for (JsonNode it : bd.path("items")) {
                assertFalse(it.path("post").path("mine").asBoolean(), "내 공고는 묶지 않는다");
                double s = it.path("startHour").asDouble(), e = it.path("endHour").asDouble();
                assertTrue(s >= 9 && e <= 15);
                assertTrue(s >= it.path("post").path("startHour").asDouble() && e <= it.path("post").path("endHour").asDouble() + 1e-9, "공고 시간 범위 안");
                if (prevEnd >= 0) assertTrue(s >= prevEnd + BundleService.gapHours(it.path("travelKm").asDouble()) - 1e-9, "겹치지 않고 이동 여유가 있다");
                prevEnd = e;
            }
        }
        // 가장 좋은 묶음은 같은 동 두 건(a → b): 이동 0km
        JsonNode best = bs.get(0);
        assertEquals(0.0, best.path("totalKm").asDouble());
        List<Long> ids = new ArrayList<>();
        best.path("items").forEach(it -> ids.add(it.path("postId").asLong()));
        assertEquals(List.of(a.id, b.id), ids);
        assertEquals(10.0, best.path("items").get(0).path("startHour").asDouble());
        assertEquals(11.5, best.path("items").get(1).path("startHour").asDouble()); // 11:00 끝 + 30분 여유
        assertEquals(2.5, best.path("totalHours").asDouble());
        assertTrue(best.path("totalEarn").asDouble() >= 2.5);
        assertNotNull(c);
    }

    @Test
    void 맞는_공고가_모자라면_묶지_않고_까닭을_알려_준다() throws Exception {
        need(mom, "행복동", 10, 11, 1, "말동무·정서");
        JsonNode one = call(vol, "/api/bundles/recommend", "{\"date\":\"" + day + "\",\"fromHour\":9,\"toHour\":15}", 200);
        assertEquals(0, one.path("bundles").size());
        assertTrue(one.path("message").asText().contains("1건"));
        need(other, "행복동", 10, 11, 1, "말동무·정서"); // 같은 시간이라 겹친다
        JsonNode two = call(vol, "/api/bundles/recommend", "{\"date\":\"" + day + "\",\"fromHour\":9,\"toHour\":15}", 200);
        assertEquals(0, two.path("bundles").size());
        assertTrue(two.path("message").asText().contains("겹치"));
        assertEquals("RANGE_TOO_SHORT", call(vol, "/api/bundles/recommend", "{\"date\":\"" + day + "\",\"fromHour\":9,\"toHour\":10}", 400).path("code").asText());
        assertEquals("INVALID_DATE", call(vol, "/api/bundles/recommend", "{\"date\":\"" + LocalDate.now().minusDays(1) + "\",\"fromHour\":9,\"toHour\":15}", 400).path("code").asText());
        call(mgr, "/api/bundles/recommend", "{\"date\":\"" + day + "\",\"fromHour\":9,\"toHour\":15}", 403);
    }

    @Test
    void 내_다른_일정과_겹치는_시간은_피한다() throws Exception {
        Post a = need(mom, "행복동", 10, 13, 1, "말동무·정서");
        Post b = need(other, "행복동", 10, 15, 1, "장보기·심부름");
        Post busy = need(other, "행복동", 10, 11, 1, "디지털 도움");
        reqs.create(vol, new RequestService.CreateCmd(Kind.PROPOSAL, busy.id, null, null, null, 10.0, 1.0, null, "제가 갈게요", null)); // 10~11 은 이미 제안함
        JsonNode out = call(vol, "/api/bundles/recommend", "{\"date\":\"" + day + "\",\"fromHour\":10,\"toHour\":15}", 200);
        assertEquals(2, out.path("candidateCount").asInt(), "이미 제안한 공고는 후보에서 빠진다");
        JsonNode best = out.path("bundles").get(0);
        assertEquals(11.5, best.path("items").get(0).path("startHour").asDouble(), "10~11 일정 뒤 30분 여유를 두고 시작");
        assertNotNull(a); assertNotNull(b);
    }

    @Test
    void 한_번에_제안은_공고마다_제안을_보내고_일부_실패를_알려_준다() throws Exception {
        Post a = need(mom, "행복동", 10, 11, 1, "말동무·정서");
        Post b = need(other, "행복동", 11, 14, 1.5, "장보기·심부름");
        // 시간이 겹치는 묶음은 아무것도 보내지 않는다
        String overlap = "{\"items\":[{\"postId\":" + a.id + ",\"startHour\":10,\"hours\":1},{\"postId\":" + b.id + ",\"startHour\":10.5,\"hours\":1.5}]}";
        assertEquals("TIME_OVERLAP", call(vol, "/api/bundles/propose", overlap, 400).path("code").asText());
        assertEquals(0, reqRepo.findByPostId(a.id).size());
        // 그 사이 b 가 마감되면: a 는 보내지고 b 는 실패로 알려 준다
        b.status = PostStatus.CLOSED; postRepo.save(b);
        String body = "{\"items\":[{\"postId\":" + b.id + ",\"startHour\":11.5,\"hours\":1.5},{\"postId\":" + a.id + ",\"startHour\":10,\"hours\":1}]}";
        JsonNode out = call(vol, "/api/bundles/propose", body, 200);
        assertEquals(1, out.path("okCount").asInt());
        assertEquals(1, out.path("failCount").asInt());
        JsonNode r0 = out.path("results").get(0), r1 = out.path("results").get(1); // 시간 순서대로
        assertEquals(a.id, r0.path("postId").asLong());
        assertTrue(r0.path("ok").asBoolean());
        assertFalse(r1.path("ok").asBoolean());
        assertEquals("POST_CLOSED", r1.path("code").asText());
        assertFalse(r1.path("error").asText().isBlank());
        Request sent = reqRepo.findById(r0.path("requestId").asLong()).orElseThrow();
        assertEquals(Kind.PROPOSAL, sent.kind);
        assertEquals(ReqStatus.SENT, sent.status);
        assertEquals(vol.id, sent.volunteerId);
        assertEquals(mom.id, sent.recipientId);
        assertNotNull(sent.deadlineAt); // 기능 U-13: 상대가 24시간 안에 수락·거절
        assertTrue(sent.deadlineAt.isAfter(LocalDateTime.now().plusHours(23)) && sent.deadlineAt.isBefore(LocalDateTime.now().plusHours(25)));
        assertTrue(notis.findAll().stream().anyMatch(n -> "NEW_PROPOSAL".equals(n.type) && mom.id.equals(n.userId)));
        // 다시 보내면 중복이라 실패로 알려 준다
        JsonNode again = call(vol, "/api/bundles/propose", "{\"items\":[{\"postId\":" + a.id + ",\"startHour\":10,\"hours\":1}]}", 200);
        assertEquals(0, again.path("okCount").asInt());
        assertEquals("DUPLICATE", again.path("results").get(0).path("code").asText());
        call(mgr, "/api/bundles/propose", body, 403);
    }

    @Test
    void 묶음_설명의_숫자는_코드_사실_안의_것만() {
        assertTrue(BundleAi.numbersWithin("10:00~11:00 이동 1.2km 30분", "1.2km만 걸으면 되고 30분 여유가 있어요"));
        assertFalse(BundleAi.numbersWithin("10:00~11:00 이동 1.2km 30분", "5분이면 가요"));
    }

    // ================= 기능 U-02 시간순 정렬 =================
    @Test
    void 시간순은_활동_날짜와_시작_시각이_빠른_순() throws Exception {
        Post late = need(mom, "행복동", 14, 16, 1, "말동무·정서");
        Post early = need(other, "희망동", 9, 11, 1, "말동무·정서");
        JsonNode list = json(mvc.perform(get("/api/posts?type=NEED&sort=time&date=" + day).header("Authorization", tok(vol))).andExpect(status().isOk()));
        assertEquals(2, list.size());
        assertEquals(early.id, list.get(0).path("id").asLong());
        assertEquals(late.id, list.get(1).path("id").asLong());
    }
}
