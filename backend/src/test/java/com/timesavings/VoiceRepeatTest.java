package com.timesavings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.ai.PostChatRule;
import com.timesavings.domain.User;
import com.timesavings.repo.UserRepo;
import com.timesavings.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/posts 는 "매주 …"가 와도 400 없이 한 건만 등록하고 정기 요청·다시 올리기를 안내한다. 대화 초안은 매주면 정기 요청(기능 U-09)으로 이어 준다. */
@SpringBootTest
@AutoConfigureMockMvc
class VoiceRepeatTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired AuthService auth;
    final ObjectMapper om = new ObjectMapper();

    String token() {
        User u = users.findByLoginId("voice-repeat").orElseGet(() -> {
            User n = new User();
            n.loginId = "voice-repeat"; n.name = "반복 시험"; n.passwordHash = auth.hash("1234");
            n.role = com.timesavings.domain.Enums.Role.USER; n.area = "행복동"; n.ageGroup = "70대";
            return users.save(n);
        });
        return "Bearer " + auth.issueToken(u, false);
    }

    JsonNode create(String repeatJson) throws Exception {
        String date = LocalDate.now().plusDays(3).toString();
        String body = "{\"type\":\"NEED\",\"date\":\"" + date + "\",\"startHour\":10,\"endHour\":11,\"area\":\"행복동\","
                + "\"categories\":[\"말동무·정서\"],\"estHours\":1,\"title\":\"말벗\",\"detail\":\"이야기 나누기\""
                + (repeatJson == null ? "" : ",\"repeat\":" + repeatJson) + "}";
        String res = mvc.perform(post("/api/posts").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return om.readTree(res);
    }

    int count(JsonNode n) { return n.path("createdCount").asInt(1); }

    /** 이 주소는 늘 한 건만 올린다. "매주"가 와도 한 건만 올리고 정기 요청·다시 올리기를 안내한다. */
    @Test
    void weeklyTextPostsOnceWithNotice() throws Exception {
        JsonNode a = create("\"매주 목요일\"");
        assertEquals(1, count(a));
        assertEquals(1, a.path("createdIds").size());
        assertTrue(a.path("repeatNotice").asText().contains("다시 올리기"), a.toString());
        assertTrue(a.path("repeatRule").isNull(), "반복 규칙을 남기지 않는다");
        assertTrue(a.path("seriesId").isNull());
        assertEquals(1, count(create("\"매주\"")));
    }

    @Test
    void otherShapesPostOnceWithout400() throws Exception {
        assertEquals(1, count(create("\"격주 화요일\"")));
        assertEquals(1, count(create("\"매달\"")));
        assertEquals(1, count(create("{\"weekly\":true,\"weeks\":3}")));
        assertEquals(1, count(create("true")));
        JsonNode off = create("{\"weekly\":false}");
        assertEquals(1, count(off));
        assertFalse(off.has("repeatNotice"));
        assertFalse(create("null").has("repeatNotice"));
        assertFalse(create(null).has("repeatNotice"));
    }

    /** 기능 U-09: 매주면 정기 요청으로 올릴 수 있다고 알리고, 격주·매달은 한 번만 올린다고 알린다. */
    @Test
    void summaryTellsRecurringOrOnce() {
        String s = PostChatRule.withRepeatNotice("11월 19일 목요일 오전 10시부터 1시간, 말벗 요청서를 만들었어요. 올릴까요?", PostChatRule.REPEAT_NOTICE);
        assertTrue(s.contains("정기 요청") && s.endsWith("올릴까요?"), s);
        String once = PostChatRule.withRepeatNotice("요청서를 만들었어요. 올릴까요?", PostChatRule.REPEAT_ONCE_NOTICE);
        assertTrue(once.contains("한 번만") && once.contains("다시 올리기") && once.endsWith("올릴까요?"), once);
        assertEquals("그대로", PostChatRule.withRepeatNotice("그대로", null));
        assertEquals(4, PostChatRule.weekdayOf("매주 목요일"));
        assertNull(PostChatRule.weekdayOf("매주"));
        assertTrue(PostChatRule.weekly("매주 화요일"));
        assertFalse(PostChatRule.weekly("격주 화요일"));
    }

    /** 말에 "매주"가 있으면 초안에 반복 표시가 실리고, 없으면 실리지 않는다. */
    @Test
    void chatDraftKeepsRepeatLabel() {
        var today = LocalDate.of(2026, 11, 12);
        var r = PostChatRule.run("NEED", java.util.List.of(new PostChatRule.Msg("user", "매주 목요일 오전 10시에 한 시간 말벗 해 줄 사람 행복동")), today, "행복동", true);
        assertTrue(r.done(), r.missing().toString());
        assertEquals(PostChatRule.REPEAT_NOTICE, r.draft().repeatNotice);
        assertEquals("매주 목요일", r.draft().repeatLabel);
        var biweekly = PostChatRule.run("NEED", java.util.List.of(new PostChatRule.Msg("user", "격주 목요일 오전 10시에 한 시간 말벗 해 줄 사람 행복동")), today, "행복동", true);
        assertEquals(PostChatRule.REPEAT_ONCE_NOTICE, biweekly.draft().repeatNotice);
        var plain = PostChatRule.run("NEED", java.util.List.of(new PostChatRule.Msg("user", "다음 주 목요일 오전 10시에 한 시간 말벗 해 줄 사람 행복동")), today, "행복동", true);
        assertNull(plain.draft().repeatNotice);
        assertNull(plain.draft().repeatLabel);
    }

    /** 기능 U-09: "매주 목요일"이면 초안에 repeat {weekly, weekday=4} 가 실려 정기 요청으로 이어 갈 수 있다. 격주면 실리지 않는다. */
    @Test
    void chatApiOffersRecurring() throws Exception {
        String body = "{\"type\":\"NEED\",\"inputMode\":\"voice\",\"messages\":[{\"role\":\"user\",\"text\":\"매주 목요일 오전 10시에 한 시간 말벗 해 줄 사람 행복동\"}]}";
        String res = mvc.perform(post("/api/ai/post-chat").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode j = om.readTree(res);
        assertTrue(j.path("done").asBoolean(), res);
        assertTrue(j.path("draft").path("repeat").path("weekly").asBoolean(), res);
        assertEquals(4, j.path("draft").path("repeat").path("weekday").asInt(), res);
        assertEquals("매주 목요일", j.path("draft").path("repeat").path("label").asText(), res);
        assertTrue(j.path("draft").path("repeatNotice").asText().contains("정기 요청"), res);
        assertTrue(j.path("reply").asText().contains("정기 요청"), res);
        String res2 = mvc.perform(post("/api/ai/post-chat").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("매주 목요일", "격주 목요일")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode k = om.readTree(res2);
        assertFalse(k.path("draft").has("repeat"), res2);
        assertTrue(k.path("draft").path("repeatNotice").asText().contains("한 번만"), res2);
    }
}
