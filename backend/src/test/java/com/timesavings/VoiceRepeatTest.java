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

/** 말로 요청하기에서 "매주 …"가 와도 400 없이 한 건만 등록되고(41판 B-06 자동 반복 없음) 다시 올리기를 안내하는지. */
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

    /** 41판 B-06: 자동 반복 등록은 하지 않는다. "매주"가 와도 한 건만 올리고 다시 올리기를 안내한다. */
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

    @Test
    void summaryTellsOnceAndRepost() {
        String s = PostChatRule.withRepeatNotice("11월 19일 목요일 오전 10시부터 1시간, 말벗 요청서를 만들었어요. 올릴까요?", PostChatRule.REPEAT_NOTICE);
        assertTrue(s.contains("한 번만") && s.contains("다시 올리기") && s.endsWith("올릴까요?"), s);
        assertFalse(s.contains("4주"), s);
        assertEquals("그대로", PostChatRule.withRepeatNotice("그대로", null));
    }

    /** 말에 "매주"가 있으면 초안은 한 번짜리이고, 답과 초안에 다시 올리기 안내가 실린다(반복 규칙 칸은 없다). */
    @Test
    void chatDraftHasNoRepeatRule() {
        var today = LocalDate.of(2026, 11, 12);
        var r = PostChatRule.run("NEED", java.util.List.of(new PostChatRule.Msg("user", "매주 목요일 오전 10시에 한 시간 말벗 해 줄 사람 행복동")), today, "행복동", true);
        assertTrue(r.done(), r.missing().toString());
        assertNotNull(r.draft().repeatNotice);
        var plain = PostChatRule.run("NEED", java.util.List.of(new PostChatRule.Msg("user", "다음 주 목요일 오전 10시에 한 시간 말벗 해 줄 사람 행복동")), today, "행복동", true);
        assertNull(plain.draft().repeatNotice);
    }

    @Test
    void chatApiSaysOnceInReply() throws Exception {
        String body = "{\"type\":\"NEED\",\"inputMode\":\"voice\",\"messages\":[{\"role\":\"user\",\"text\":\"매주 목요일 오전 10시에 한 시간 말벗 해 줄 사람 행복동\"}]}";
        String res = mvc.perform(post("/api/ai/post-chat").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode j = om.readTree(res);
        assertTrue(j.path("done").asBoolean(), res);
        assertFalse(j.path("draft").has("repeat"), "초안에 반복 규칙 칸이 없다");
        assertTrue(j.path("draft").path("repeatNotice").asText().contains("다시 올리기"), res);
        assertTrue(j.path("reply").asText().contains("한 번만"), res);
        assertFalse(j.path("reply").asText().contains("4주"), res);
    }
}
