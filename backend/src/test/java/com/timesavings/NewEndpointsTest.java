package com.timesavings;

import com.timesavings.domain.User;
import com.timesavings.domain.Enums.Role;
import com.timesavings.repo.UserRepo;
import com.timesavings.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 휴대폰 인증 설정·데모 코드, AI 상태, 서버 음성 인식(키 없음 → 503). 외부 API 없이. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NewEndpointsTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired AuthService auth;

    @Test
    void phoneConfigAndDemoCode() throws Exception {
        mvc.perform(get("/api/auth/phone/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.firebase").value(false)).andExpect(jsonPath("$.demo").value(true));
        mvc.perform(post("/api/auth/phone/verify").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"010-1234-5678\",\"code\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verifyToken").isNotEmpty()).andExpect(jsonPath("$.method").value("demo"));
        mvc.perform(post("/api/auth/phone/verify").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"010-1234-5678\",\"code\":\"111111\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CODE"));
        mvc.perform(post("/api/auth/phone/verify").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"010-1234-5678\",\"firebaseIdToken\":\"abc\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FIREBASE_DISABLED"));
    }

    @Test
    void aiStatusIsRuleWithoutKeys() throws Exception {
        mvc.perform(get("/api/ai/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("rule")).andExpect(jsonPath("$.stt").value(false)).andExpect(jsonPath("$.mode").value("rule"));
    }

    @Test
    void sttUnavailableWithoutKey() throws Exception {
        MockMultipartFile f = new MockMultipartFile("audio", "a.webm", "audio/webm", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/stt").file(f)).andExpect(status().isUnauthorized());
        User u = new User();
        u.loginId = "sttuser"; u.name = "s"; u.passwordHash = auth.hash("1234"); u.role = Role.USER;
        users.save(u);
        String token = auth.issueToken(u, false);
        mvc.perform(multipart("/api/stt").file(f).param("lang", "ko").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STT_UNAVAILABLE"));
    }
}
