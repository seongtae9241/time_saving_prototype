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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 요구사항 41판 — 공고 작성·목록·위치 범위.
 * B-06 다시 올리기(날짜만 골라), B-57 가족 대신 공고(주인=가족, 최종 수락·보류는 가족 본인, 올린 사람은 읽기만),
 * B-20·B-59 좌표로 거리 계산·날짜 범위, B-21 관심 조건 날짜. 규칙 모드(AI 호출 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Req41PostTest {
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired FamilyLinkRepo family;
    @Autowired NotificationRepo notis;
    @Autowired InterestAlertRepo alerts;
    @Autowired PostRepo postRepo;
    @Autowired PostService postSvc;
    @Autowired RequestService reqs;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    User son, mom, vol, stranger, mgr;
    Elder elder;
    final LocalDate day = LocalDate.now().plusDays(3);

    User user(String id, Role role, String area, double earned) {
        User u = new User();
        u.loginId = id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = area; u.ageGroup = "60대";
        u.idStatus = VerifyStatus.APPROVED; u.phone = "010-0000-" + (4000 + users.count());
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, "초기 적립");
        return u;
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    JsonNode json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    String needBody(String extra) {
        return "{\"type\":\"NEED\",\"date\":\"" + day + "\",\"startHour\":10,\"endHour\":12,\"area\":\"행복동\",\"categories\":[\"말동무·정서\"],"
                + "\"estHours\":1,\"title\":\"말벗\",\"detail\":\"이야기 나누기\"" + extra + "}";
    }

    @BeforeEach
    void setUp() {
        son = user("r41son", Role.USER, "행복동", 0);
        mom = user("r41mom", Role.USER, "행복동", 20);
        vol = user("r41vol", Role.USER, "희망동", 0);
        stranger = user("r41str", Role.USER, "미래동", 0);
        mgr = user("r41mgr", Role.ORG, "행복동", 0);
        elder = new Elder();
        elder.alias = "박순자"; elder.dong = "행복동"; elder.managerId = mgr.id; elder.needs = List.of("동행"); elder.ageGroup = "80대";
        elder.caution = "천천히 걸어 주세요";
        elders.save(elder);
        FamilyLink f = new FamilyLink();
        f.requesterId = son.id; f.targetId = mom.id; f.relation = "어머니"; f.status = LinkStatus.ACCEPTED;
        family.save(f);
    }

    // ---------------- B-06 다시 올리기 ----------------
    @Test
    void 다시_올리기는_같은_내용에_날짜만_바꿔_한_건() throws Exception {
        JsonNode first = json(mvc.perform(post("/api/posts").header("Authorization", tok(mom)).contentType(MediaType.APPLICATION_JSON)
                .content(needBody(",\"note\":\"엘리베이터 있어요\""))).andExpect(status().isOk()));
        long id = first.path("id").asLong();
        LocalDate again = day.plusDays(9);
        JsonNode np = json(mvc.perform(post("/api/posts/" + id + "/repost").header("Authorization", tok(mom)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + again + "\"}")).andExpect(status().isOk()));
        assertNotEquals(id, np.path("id").asLong());
        assertEquals(again.toString(), np.path("date").asText());
        assertEquals("말벗", np.path("title").asText());
        assertEquals("이야기 나누기", np.path("detail").asText());
        assertEquals("엘리베이터 있어요", np.path("note").asText());
        assertEquals(10.0, np.path("startHour").asDouble());
        assertEquals(12.0, np.path("endHour").asDouble());
        assertEquals(1.0, np.path("estHours").asDouble());
        assertEquals("OPEN", np.path("status").asText());
        assertEquals(id, np.path("repostOf").asLong());
        // 원래 공고는 그대로
        assertEquals(day, postRepo.findById(id).orElseThrow().date);

        // 날짜를 고르지 않으면, 지난 날짜면 안 된다
        mvc.perform(post("/api/posts/" + id + "/repost").header("Authorization", tok(mom)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/posts/" + id + "/repost").header("Authorization", tok(mom)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + LocalDate.now().minusDays(1) + "\"}")).andExpect(status().isBadRequest());
        // 남의 공고는 다시 올릴 수 없다
        mvc.perform(post("/api/posts/" + id + "/repost").header("Authorization", tok(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + again + "\"}")).andExpect(status().isForbidden());
    }

    @Test
    void 마감된_지난_공고도_다시_올릴_수_있다() {
        Post p = postSvc.create(mom, new PostService.PostCmd(PostType.NEED, day, 10.0, 12.0, "행복동", List.of("말동무·정서"),
                "말벗", "이야기", null, 1.0, false, null, null, null, null)).get(0);
        p.status = PostStatus.CLOSED;
        Post np = postSvc.repost(mom, p.id, day.plusDays(7));
        assertEquals(PostStatus.OPEN, np.status);
        assertEquals(mom.id, np.ownerId);
        p.status = PostStatus.DELETED;
        assertThrows(ApiException.class, () -> postSvc.repost(mom, p.id, day.plusDays(8)));
    }

    @Test
    void 담당자는_독거노인의_지난_요청을_같은_API로_다시_올린다() throws Exception {
        JsonNode first = json(mvc.perform(post("/api/posts").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                .content(needBody(",\"elderId\":" + elder.id + ",\"elderIssue\":\"오전에는 기운이 없으세요\""))).andExpect(status().isOk()));
        long id = first.path("id").asLong();
        // 대상별 지난 요청 목록
        JsonNode mine = json(mvc.perform(get("/api/posts/mine").param("elderId", String.valueOf(elder.id)).header("Authorization", tok(mgr))).andExpect(status().isOk()));
        assertEquals(1, mine.size());
        assertEquals(id, mine.get(0).path("id").asLong());
        JsonNode np = json(mvc.perform(post("/api/posts/" + id + "/repost").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + day.plusDays(7) + "\"}")).andExpect(status().isOk()));
        assertEquals(elder.id, np.path("elderId").asLong());
        assertTrue(np.path("ownerId").isNull());
        assertEquals("오전에는 기운이 없으세요", np.path("elderIssue").asText(), "관리 대상 이슈도 같이 가져온다");
        assertEquals(2, json(mvc.perform(get("/api/posts/mine").param("elderId", String.valueOf(elder.id)).header("Authorization", tok(mgr)))).size());
        // 다른 사람(일반 사용자)은 그 공고를 다시 올릴 수 없다
        mvc.perform(post("/api/posts/" + id + "/repost").header("Authorization", tok(son)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + day.plusDays(7) + "\"}")).andExpect(status().isForbidden());
    }

    // ---------------- B-57 가족 대신 공고 ----------------
    @Test
    void 가족_대신_공고는_주인이_가족이고_가족_이름으로_보인다() throws Exception {
        JsonNode p = json(mvc.perform(post("/api/posts").header("Authorization", tok(son)).contentType(MediaType.APPLICATION_JSON)
                .content(needBody(",\"forUserId\":" + mom.id))).andExpect(status().isOk()));
        assertEquals(mom.id, p.path("ownerId").asLong());
        assertEquals("r41mom", p.path("ownerName").asText());
        assertTrue(p.path("proxy").asBoolean());
        assertEquals("r41son", p.path("proxyByName").asText());
        assertTrue(p.path("proxyMine").asBoolean());
        assertFalse(p.path("mine").asBoolean(), "올린 사람의 공고가 아니라 가족의 공고");
        Post saved = postRepo.findById(p.path("id").asLong()).orElseThrow();
        assertEquals(son.id, saved.createdBy);
        assertEquals("말벗", saved.title, "제목에 이름을 덧붙이지 않는다");
        // 가족에게 알림
        assertTrue(notis.findByUserIdOrderByCreatedAtDescIdDesc(mom.id).stream().anyMatch(n -> "FAMILY_POST".equals(n.type)));
        // 가족이 보면 내 공고
        JsonNode asMom = json(mvc.perform(get("/api/posts/" + saved.id).header("Authorization", tok(mom))).andExpect(status().isOk()));
        assertTrue(asMom.path("mine").asBoolean());
        assertFalse(asMom.path("proxyMine").asBoolean());
        // 올린 사람의 "내가 올린 공고"에도 보인다(다시 올리기용)
        JsonNode mine = json(mvc.perform(get("/api/posts/mine").header("Authorization", tok(son))).andExpect(status().isOk()));
        assertEquals(1, mine.size());
        assertTrue(mine.get(0).path("proxyMine").asBoolean());
        // 다른 사람이 보는 목록에는 가족 이름으로
        JsonNode list = json(mvc.perform(get("/api/posts").param("type", "NEED").header("Authorization", tok(vol))).andExpect(status().isOk()));
        JsonNode card = null;
        for (JsonNode c : list) if (c.path("id").asLong() == saved.id) card = c;
        assertNotNull(card);
        assertEquals("r41mom", card.path("ownerName").asText());
    }

    @Test
    void 연결되지_않은_사람_대신으로는_올릴_수_없다() throws Exception {
        mvc.perform(post("/api/posts").header("Authorization", tok(son)).contentType(MediaType.APPLICATION_JSON)
                .content(needBody(",\"forUserId\":" + stranger.id))).andExpect(status().isForbidden());
        // 신청만 하고 아직 수락되지 않은 연결도 안 된다
        FamilyLink f = new FamilyLink();
        f.requesterId = son.id; f.targetId = stranger.id; f.status = LinkStatus.PENDING;
        family.save(f);
        mvc.perform(post("/api/posts").header("Authorization", tok(son)).contentType(MediaType.APPLICATION_JSON)
                .content(needBody(",\"forUserId\":" + stranger.id))).andExpect(status().isForbidden());
        // 봉사 가능 공고는 대신 올릴 수 없다
        ApiException e = assertThrows(ApiException.class, () -> postSvc.create(son, new PostService.PostCmd(PostType.OFFER, day, 10.0, 12.0, "행복동",
                List.of("말동무·정서"), null, null, null, null, false, null, null, null, null), mom.id));
        assertEquals("INVALID_TYPE", e.code);
    }

    @Test
    void 최종_수락과_시간_보류는_가족_본인이고_올린_사람은_읽기만() throws Exception {
        Post p = postSvc.create(son, new PostService.PostCmd(PostType.NEED, day, 10.0, 12.0, "행복동", List.of("말동무·정서"),
                "말벗", "이야기", null, 1.0, false, null, null, null, null), mom.id).get(0);
        Request r = reqs.create(vol, new RequestService.CreateCmd(Kind.PROPOSAL, p.id, null, null, day, 10.0, 1.0, null, "말벗 해 드릴게요", null));
        assertEquals(mom.id, r.recipientId, "도움 받는 사람은 가족");
        // 제안 알림은 가족에게
        assertTrue(notis.findByUserIdOrderByCreatedAtDescIdDesc(mom.id).stream().anyMatch(n -> "NEW_PROPOSAL".equals(n.type)));
        assertTrue(notis.findByUserIdOrderByCreatedAtDescIdDesc(son.id).stream().noneMatch(n -> "NEW_PROPOSAL".equals(n.type)));

        // 올린 사람: 공고 상세에서 진행 상황은 보이지만 누를 수 있는 것이 없다
        JsonNode asSon = json(mvc.perform(get("/api/posts/" + p.id).header("Authorization", tok(son))).andExpect(status().isOk()));
        assertEquals(1, asSon.path("requests").size());
        assertEquals("SENT", asSon.path("requests").get(0).path("status").asText());
        assertTrue(asSon.path("requests").get(0).path("myRole").isNull());
        assertEquals(0, asSon.path("requests").get(0).path("actions").size());
        // 올린 사람은 수락·거절·거부를 할 수 없다
        mvc.perform(post("/api/requests/" + r.id + "/accept").header("Authorization", tok(son))).andExpect(status().isForbidden());
        assertThrows(ApiException.class, () -> reqs.reject(son, r.id, "시간이 안 맞아요"));
        assertEquals(ReqStatus.SENT, r.status);

        // 가족 본인이 최종 수락 → 가족 통장에서 보류, 올린 사람 통장은 그대로
        double sonBefore = bank.summary(son.id).balance();
        reqs.accept(mom, r.id);
        assertEquals(ReqStatus.CONFIRMED, r.status);
        assertEquals(r.estEarn, bank.summary(mom.id).hold(), 1e-9);
        assertEquals(0.0, bank.summary(son.id).hold(), 1e-9);
        assertEquals(sonBefore, bank.summary(son.id).balance(), 1e-9);
        // 수락된 뒤에는 올린 사람도 공고를 바꿀 수 없다(B-11)
        assertThrows(ApiException.class, () -> postSvc.delete(son, p.id));
    }

    @Test
    void 올린_사람은_수락_전에는_자기가_쓴_공고를_고칠_수_있다() {
        Post p = postSvc.create(son, new PostService.PostCmd(PostType.NEED, day, 10.0, 12.0, "행복동", List.of("말동무·정서"),
                "말벗", "이야기", null, 1.0, false, null, null, null, null), mom.id).get(0);
        postSvc.update(son, p.id, new PostService.PostCmd(null, null, null, null, null, null, "말벗과 산책", null, null, null, false, 1, null, null, null));
        assertEquals("말벗과 산책", p.title);
        assertEquals(mom.id, p.ownerId);
        assertThrows(ApiException.class, () -> postSvc.update(stranger, p.id,
                new PostService.PostCmd(null, null, null, null, null, null, "다른 제목", null, null, null, false, 1, null, null, null)));
        // 다시 올리기도 가족 이름으로
        Post again = postSvc.repost(son, p.id, day.plusDays(7));
        assertEquals(mom.id, again.ownerId);
        assertEquals(son.id, again.createdBy);
    }

    // ---------------- B-20·B-59 위치·날짜 ----------------
    @Test
    void 좌표를_보내면_그_위치에서_공고_동네까지의_거리로_가까운_순() throws Exception {
        Post near = postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day, 13.0, 17.0, "미래동", List.of("말동무·정서"),
                null, null, "가까운 공고", null, false, null, null, null, null)).get(0);
        Post far = postSvc.create(stranger, new PostService.PostCmd(PostType.OFFER, day, 13.0, 17.0, "사랑동", List.of("말동무·정서"),
                null, null, "먼 공고", null, false, null, null, null, null)).get(0);
        double[] mirae = Geo.DONG.get("미래동");
        JsonNode list = json(mvc.perform(get("/api/posts").param("type", "OFFER").param("sort", "near")
                .param("lat", String.valueOf(mirae[0])).param("lng", String.valueOf(mirae[1])).header("Authorization", tok(mom))).andExpect(status().isOk()));
        int iNear = -1, iFar = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).path("id").asLong() == near.id) iNear = i;
            if (list.get(i).path("id").asLong() == far.id) iFar = i;
        }
        assertTrue(iNear >= 0 && iFar > iNear, "미래동에 서 있으면 미래동 공고가 사랑동 공고보다 먼저");
        assertEquals(0.0, list.get(iNear).path("distanceKm").asDouble(), 1e-9);
        assertEquals("location", list.get(iNear).path("distanceBy").asText());
        assertEquals(2.8, list.get(iFar).path("distanceKm").asDouble(), 0.11); // (0.6,-1.3) ↔ (-0.8,1.1) ≈ 2.78km

        // 좌표가 없으면 예전처럼 프로필 동네 기준
        JsonNode plain = json(mvc.perform(get("/api/posts").param("type", "OFFER").header("Authorization", tok(mom))).andExpect(status().isOk()));
        assertEquals("profile", plain.get(0).path("distanceBy").asText());
        // 틀린 좌표는 400
        mvc.perform(get("/api/posts").param("lat", "123").param("lng", "10").header("Authorization", tok(mom))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts").param("lat", "37.5").header("Authorization", tok(mom))).andExpect(status().isBadRequest());
    }

    @Test
    void 거리_계산_도우미() {
        assertEquals(0.0, Geo.km(37.5, 127.0, 37.5, 127.0), 1e-9);
        assertEquals(111.2, Geo.km(37.0, 127.0, 38.0, 127.0), 0.3); // 위도 1도 ≈ 111km
        assertNull(Geo.kmToDong(37.5, 127.0, "없는동"));
        assertFalse(Geo.valid(null, 127.0));
        assertFalse(Geo.valid(91.0, 127.0));
        assertTrue(Geo.valid(37.5, 127.0));
    }

    @Test
    void 목록_날짜_범위_필터() throws Exception {
        Post a = postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day, 13.0, 17.0, "행복동", List.of("말동무·정서"),
                null, null, "a", null, false, null, null, null, null)).get(0);
        Post b = postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day.plusDays(5), 13.0, 17.0, "행복동", List.of("말동무·정서"),
                null, null, "b", null, false, null, null, null, null)).get(0);
        JsonNode list = json(mvc.perform(get("/api/posts").param("type", "OFFER").param("dateFrom", day.plusDays(4).toString()).param("dateTo", day.plusDays(6).toString())
                .header("Authorization", tok(mom))).andExpect(status().isOk()));
        boolean hasA = false, hasB = false;
        for (JsonNode c : list) { if (c.path("id").asLong() == a.id) hasA = true; if (c.path("id").asLong() == b.id) hasB = true; }
        assertFalse(hasA);
        assertTrue(hasB);
        JsonNode one = json(mvc.perform(get("/api/posts").param("type", "OFFER").param("date", day.toString()).header("Authorization", tok(mom))).andExpect(status().isOk()));
        for (JsonNode c : one) assertEquals(day.toString(), c.path("date").asText());
    }

    // ---------------- B-21 관심 조건의 날짜 ----------------
    @Test
    void 관심_조건에_날짜를_넣으면_그_기간_공고만_알린다() throws Exception {
        JsonNode al = json(mvc.perform(post("/api/interest-alerts").header("Authorization", tok(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"area\":\"행복동\",\"categories\":[\"말동무·정서\"],\"dayType\":\"상관없음\",\"dateFrom\":\"" + day.plusDays(4) + "\",\"dateTo\":\"" + day.plusDays(6) + "\"}"))
                .andExpect(status().isOk()));
        assertEquals(day.plusDays(4).toString(), al.path("dateFrom").asText());
        assertEquals(day.plusDays(6).toString(), al.path("dateTo").asText());
        long before = notis.findByUserIdOrderByCreatedAtDescIdDesc(stranger.id).stream().filter(n -> "INTEREST".equals(n.type)).count();
        postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day, 13.0, 17.0, "행복동", List.of("말동무·정서"),
                null, null, "기간 밖", null, false, null, null, null, null));
        assertEquals(before, notis.findByUserIdOrderByCreatedAtDescIdDesc(stranger.id).stream().filter(n -> "INTEREST".equals(n.type)).count());
        postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day.plusDays(5), 13.0, 17.0, "행복동", List.of("말동무·정서"),
                null, null, "기간 안", null, false, null, null, null, null));
        assertEquals(before + 1, notis.findByUserIdOrderByCreatedAtDescIdDesc(stranger.id).stream().filter(n -> "INTEREST".equals(n.type)).count());

        // 빈 글자로 보내면 날짜 조건을 지운다 → 다시 날짜 상관없이 알림
        long id = al.path("id").asLong();
        JsonNode cleared = json(mvc.perform(put("/api/interest-alerts/" + id).header("Authorization", tok(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dateFrom\":\"\",\"dateTo\":\"\"}")).andExpect(status().isOk()));
        assertTrue(cleared.path("dateFrom").isNull());
        assertEquals("행복동", cleared.path("area").asText(), "다른 조건은 그대로");
        postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day.plusDays(1), 13.0, 17.0, "행복동", List.of("말동무·정서"),
                null, null, "아무 날", null, false, null, null, null, null));
        assertEquals(before + 2, notis.findByUserIdOrderByCreatedAtDescIdDesc(stranger.id).stream().filter(n -> "INTEREST".equals(n.type)).count());
        // 끝이 시작보다 앞이면 400, 날짜 모양이 틀려도 400
        mvc.perform(put("/api/interest-alerts/" + id).header("Authorization", tok(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dateFrom\":\"2026-12-10\",\"dateTo\":\"2026-12-01\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/interest-alerts/" + id).header("Authorization", tok(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dateFrom\":\"내일\"}")).andExpect(status().isBadRequest());
    }
}
