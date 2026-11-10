package com.timesavings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * LLM 추가 A(7.12) 흐름: 키 없음(규칙 모드)에서 빠진 정보 점검, 추천 이유 저장, 미매칭 제안 1회만, 쉬운 말 캐시, 비서(자동 실행 금지), 전화 알림 문장.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LlmAssistFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired PostRepo posts;
    @Autowired RequestRepo requests;
    @Autowired NotificationRepo notis;
    @Autowired PostService postSvc;
    @Autowired SchedulerService scheduler;
    @Autowired NotificationService noti;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    final ObjectMapper om = new ObjectMapper();

    User vol, rec, other;
    final LocalDate day = LocalDate.now().plusDays(3);

    User user(String id, String name, double earned) {
        User u = new User();
        u.loginId = id; u.name = name; u.passwordHash = auth.hash("1234"); u.role = Role.USER; u.area = "행복동"; u.ageGroup = "30대";
        u.idStatus = VerifyStatus.APPROVED;
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, "초기 적립");
        return u;
    }

    String token(User u) { return "Bearer " + auth.issueToken(u, false); }

    JsonNode json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        vol = user("v1", "김하늘", 0);
        vol.eduDone = true; vol.crimeStatus = VerifyStatus.APPROVED;
        rec = user("r1", "김복남", 12);
        other = user("o1", "이도윤", 0);
    }

    Post offer(User owner, List<String> cats) {
        return postSvc.create(owner, new PostService.PostCmd(PostType.OFFER, day, 13.0, 17.0, "행복동", cats, "도와드려요", null, "가능해요", null, false, 1, null, null, null)).get(0);
    }

    Post need(User owner, List<String> cats, String detail) {
        return postSvc.create(owner, new PostService.PostCmd(PostType.NEED, day, 9.0, 12.0, "행복동", cats, "도움 요청", detail, null, 2.0, false, 1, null, null, null)).get(0);
    }

    @Test
    void 요청서_빠진_정보_점검() throws Exception {
        JsonNode r = json(mvc.perform(post("/api/ai/post-check").header("Authorization", token(rec)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"draft\":{\"categories\":[\"가벼운 집안일\"],\"title\":\"베란다 청소\",\"detail\":\"베란다 청소 도와주세요\"}}"))
                .andExpect(status().isOk()));
        assertEquals("rule", r.path("source").asText());
        assertEquals(2, r.path("questions").size());
        assertEquals("stairs", r.path("questions").get(0).path("key").asText());
        assertEquals("caution", r.path("questions").get(0).path("target").asText());
        assertTrue(r.path("questions").get(0).path("choices").size() >= 2);
        // 답을 붙여 다시 점검하면 그 항목은 빠진다
        r = json(mvc.perform(post("/api/ai/post-check").header("Authorization", token(rec)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"draft\":{\"categories\":[\"가벼운 집안일\"],\"title\":\"베란다 청소\",\"detail\":\"베란다 청소 도와주세요\",\"caution\":\"엘리베이터 있어요\"}}")));
        assertEquals("pet", r.path("questions").get(0).path("key").asText());
        mvc.perform(post("/api/ai/post-check").header("Authorization", token(rec)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 추천_이유는_요청_생성_때_코드로_저장되고_목록에도_보인다() throws Exception {
        Post p = offer(vol, List.of("동행"));
        // 목록: 남의 봉사자 공고에 코드 이유
        JsonNode list = json(mvc.perform(get("/api/posts?type=OFFER").header("Authorization", token(rec))).andExpect(status().isOk()));
        JsonNode card = null;
        for (JsonNode c : list) if (c.path("id").asLong() == p.id) card = c;
        assertNotNull(card);
        assertTrue(card.path("matchReason").asText().contains("같은 동네"), card.toString());
        assertTrue(card.path("matchScore").asDouble() > 0);
        // 요청 생성 → match_reason 저장
        JsonNode rv = json(mvc.perform(post("/api/requests").header("Authorization", token(rec)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"REQUEST\",\"postId\":" + p.id + ",\"date\":\"" + day + "\",\"startHour\":14,\"hours\":2,\"category\":\"동행\",\"content\":\"병원 같이 가 주세요\"}"))
                .andExpect(status().isOk()));
        Request saved = requests.findById(rv.path("id").asLong()).orElseThrow();
        assertEquals("같은 동네 · 범죄경력 확인 · 안전 교육 수료", saved.matchReason);
        assertEquals(saved.matchReason, rv.path("volunteer").path("matchReason").asText());
        // 봉사자가 받은 요청 목록에도 같은 한 줄
        JsonNode recv = json(mvc.perform(get("/api/requests?box=received").header("Authorization", token(vol))));
        assertEquals(saved.matchReason, recv.get(0).path("volunteer").path("matchReason").asText());
    }

    @Test
    void 미매칭_공고_제안은_한_번만_만들고_알린다() throws Exception {
        Post p = need(rec, List.of("장보기·심부름", "가벼운 집안일"), "장보기");
        Post busy = need(other, List.of("동행"), "병원");
        offer(vol, List.of("장보기·심부름")); // 반대쪽 공고: 오후 13~17
        p.createdAt = LocalDateTime.now().minusHours(25);
        busy.createdAt = LocalDateTime.now().minusHours(25);
        Request r = new Request();
        r.kind = Kind.PROPOSAL; r.postId = busy.id; r.senderId = vol.id; r.volunteerId = vol.id; r.recipientId = other.id;
        r.date = day; r.startHour = 9; r.hours = 1; r.category = "동행"; r.content = "제안"; r.status = ReqStatus.SENT;
        requests.save(r);
        // 24시간이 안 된 공고는 제외
        Post fresh = need(other, List.of("디지털 도움"), "폰");

        var out = scheduler.runAll(LocalDateTime.now());
        assertEquals(1, out.get("unmatchedTips"));
        Post after = posts.findById(p.id).orElseThrow();
        assertNotNull(after.fixCheckedAt);
        assertNotNull(after.fixTipsJson);
        assertNull(posts.findById(fresh.id).orElseThrow().fixCheckedAt);
        assertNull(posts.findById(busy.id).orElseThrow().fixTipsJson);
        long n1 = notis.findByUserIdOrderByCreatedAtDescIdDesc(rec.id).stream().filter(x -> "POST_TIPS".equals(x.type)).count();
        assertEquals(1, n1);

        // 두 번째 실행: 다시 만들지 않는다
        out = scheduler.runAll(LocalDateTime.now().plusMinutes(1));
        assertEquals(0, out.get("unmatchedTips"));
        assertEquals(1, notis.findByUserIdOrderByCreatedAtDescIdDesc(rec.id).stream().filter(x -> "POST_TIPS".equals(x.type)).count());

        // 공고 상세(주인)에 제안: 오전 공고인데 반대쪽은 오후 → TIME, 카테고리 2개 → SPLIT
        JsonNode d = json(mvc.perform(get("/api/posts/" + p.id).header("Authorization", token(rec))).andExpect(status().isOk()));
        JsonNode tips = d.path("fixTips").path("tips");
        assertTrue(tips.size() >= 2 && tips.size() <= 3, d.path("fixTips").toString());
        assertEquals("TIME", tips.get(0).path("action").asText());
        assertEquals(13.0, tips.get(0).path("suggest").path("startHour").asDouble());
        assertEquals("SPLIT", tips.get(1).path("action").asText());
        assertEquals("rule", d.path("fixTips").path("source").asText());
        assertEquals(1, d.path("fixTips").path("evidence").path("oppositeOpen").asInt());
        // 남에게는 보이지 않는다
        mvc.perform(get("/api/posts/" + p.id + "/fix-tips").header("Authorization", token(vol))).andExpect(status().isForbidden());
        JsonNode od = json(mvc.perform(get("/api/posts/" + p.id).header("Authorization", token(vol))));
        assertTrue(od.path("fixTips").isMissingNode());
    }

    @Test
    void 쉬운_말은_한_번_만들고_저장해_다시_쓴다() throws Exception {
        Post p = offer(vol, List.of("동행"));
        JsonNode a = json(mvc.perform(get("/api/posts/" + p.id + "/easy").header("Authorization", token(rec))).andExpect(status().isOk()));
        assertFalse(a.path("cached").asBoolean());
        String t = a.path("text").asText();
        assertTrue(t.startsWith("김하늘 님이"), t);
        assertTrue(t.contains("오후 한 시부터 다섯 시 사이"), t);
        assertFalse(t.matches(".*\\d.*"), t);
        JsonNode b = json(mvc.perform(get("/api/posts/" + p.id + "/easy").header("Authorization", token(other))));
        assertTrue(b.path("cached").asBoolean());
        assertEquals(t, b.path("text").asText());
        // 공고를 고치면 다시 만든다
        postSvc.update(vol, p.id, new PostService.PostCmd(null, null, 14.0, 17.0, null, null, null, null, null, null, false, 1, null, null, null));
        JsonNode c = json(mvc.perform(get("/api/posts/" + p.id + "/easy").header("Authorization", token(rec))));
        assertFalse(c.path("cached").asBoolean());
        assertTrue(c.path("text").asText().contains("오후 두 시부터"));
    }

    Request confirmed(LocalDate date, double start) {
        Request r = new Request();
        r.kind = Kind.REQUEST; r.senderId = rec.id; r.volunteerId = vol.id; r.recipientId = rec.id;
        r.date = date; r.startHour = start; r.hours = 2; r.category = "동행"; r.content = "병원 동행"; r.status = ReqStatus.CONFIRMED;
        r.weight = 1.5; r.estEarn = 3.0; r.checkCode = "123456";
        return requests.save(r);
    }

    @Test
    void 비서는_내_데이터로_답하고_취소는_실행하지_않는다() throws Exception {
        LocalDate thu = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.THURSDAY));
        Request r = confirmed(thu, 14);
        String t = token(rec);
        JsonNode a = json(mvc.perform(post("/api/ai/assistant").header("Authorization", t).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"목요일 거 취소해 줘\"}")).andExpect(status().isOk()));
        assertEquals("CANCEL", a.path("intent").asText());
        assertFalse(a.path("executed").asBoolean());
        assertTrue(a.path("actions").get(0).path("confirm").asBoolean());
        assertEquals("/req/" + r.id, a.path("actions").get(0).path("link").asText());
        assertEquals(ReqStatus.CONFIRMED, requests.findById(r.id).orElseThrow().status); // 자동 실행 금지
        assertTrue(a.path("answer").asText().contains("김하늘 님"));

        a = json(mvc.perform(post("/api/ai/assistant").header("Authorization", t).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"내 시간 얼마 남았어?\"}")));
        assertEquals("BALANCE", a.path("intent").asText());
        assertTrue(a.path("answer").asText().contains("12.0시간"), a.path("answer").asText());
        assertFalse(a.path("speech").asText().matches(".*\\d.*"), a.path("speech").asText());

        confirmed(LocalDate.now().plusDays(1), 10);
        a = json(mvc.perform(post("/api/ai/assistant").header("Authorization", t).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"내일 누가 와?\"}")));
        assertEquals("TOMORROW", a.path("intent").asText());
        assertTrue(a.path("answer").asText().contains("김하늘 님이 동행 하러 와요"), a.path("answer").asText());
        assertTrue(a.path("speech").asText().contains("오전 열 시"), a.path("speech").asText());

        a = json(mvc.perform(post("/api/ai/assistant").header("Authorization", t).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"다음 일정 언제야?\"}")));
        assertEquals("NEXT", a.path("intent").asText());
        assertEquals(2, a.path("items").size());

        a = json(mvc.perform(post("/api/ai/assistant").header("Authorization", t).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"단골 누구야?\"}")));
        assertEquals("REGULAR", a.path("intent").asText());
        assertTrue(a.path("answer").asText().contains("아직 단골"));

        a = json(mvc.perform(post("/api/ai/assistant").header("Authorization", t).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"장보기 도와줄 사람 불러 줘\"}")));
        assertEquals("CREATE", a.path("intent").asText());
        assertEquals("/voice", a.path("actions").get(0).path("link").asText());
        assertEquals(0, posts.findByOwnerId(rec.id).size()); // 공고도 만들지 않는다

        // 봉사자는 자기 봉사 일정으로 답을 받는다
        a = json(mvc.perform(post("/api/ai/assistant").header("Authorization", token(vol)).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"내일 일정 뭐야\"}")));
        assertTrue(a.path("answer").asText().contains("김복남 님 동행 봉사가 있어요"), a.path("answer").asText());
    }

    @Test
    void 음성_알림_켜진_사용자에게_전화_문장을_저장() throws Exception {
        rec.voiceAlert = true;
        other.voiceAlert = false;
        Notification n = noti.send(rec.id, "NEW", "김하늘 님이 도움 요청을 수락했어요", "11/18 14:00 · 동행 · 24시간 안에 최종 수락해 주세요", "/req/1");
        assertNotNull(n.voiceText);
        assertFalse(n.voiceText.matches(".*\\d.*"), n.voiceText);
        assertTrue(n.voiceText.contains("오후 두 시"), n.voiceText);
        Notification m = noti.send(other.id, "NEW", "제목", "11/18 14:00", "/req/1");
        assertNull(m.voiceText);
        JsonNode p = json(mvc.perform(get("/api/me/voice-preview").header("Authorization", token(rec))).andExpect(status().isOk()));
        assertEquals(n.voiceText, p.path("text").asText());
        assertEquals(n.id.longValue(), p.path("notificationId").asLong());
        // 문장이 없던 알림은 미리 듣기 때 한 번 만들어 저장
        JsonNode q = json(mvc.perform(get("/api/me/voice-preview").header("Authorization", token(other))));
        assertFalse(q.path("text").asText().isBlank());
        assertNotNull(notis.findById(m.id).orElseThrow().voiceText);
    }
}
