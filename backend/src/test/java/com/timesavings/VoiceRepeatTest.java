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

/** 말로 요청하기에서 "매주 …"가 글자로 와도 등록되는지(예전엔 400). */
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

    @Test
    void weeklyTextRepeatsFourWeeks() throws Exception {
        assertEquals(4, count(create("\"매주 목요일\"")));
        assertEquals(4, count(create("\"매주\"")));
    }

    @Test
    void unsupportedTextPostsOnce() throws Exception {
        assertEquals(1, count(create("\"격주 화요일\"")));
        assertEquals(1, count(create("\"매달\"")));
        assertEquals(1, count(create("null")));
        assertEquals(1, count(create(null)));
    }

    @Test
    void objectFormStillWorks() throws Exception {
        assertEquals(3, count(create("{\"weekly\":true,\"weeks\":3}")));
        assertEquals(1, count(create("{\"weekly\":false}")));
    }

    @Test
    void summaryMentionsRepeat() {
        String s = PostChatRule.withRepeat("11월 19일 목요일 오전 10시부터 1시간, 말벗 요청서를 만들었어요. 올릴까요?", "매주 목요일");
        assertTrue(s.contains("4주치"), s);
        String t = PostChatRule.withRepeat("요청서를 만들었어요. 올릴까요?", "격주 화요일");
        assertTrue(t.contains("한 번만"), t);
        assertEquals("그대로", PostChatRule.withRepeat("그대로", null));
    }
}
