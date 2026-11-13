package com.timesavings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.AuthService;
import com.timesavings.service.WeatherAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 2026-11-13 대표 지시: 담당자 특보 알림, 공공 서비스 연계(전달 의사를 표시했을 때만), 운영자 대시보드 네 칸, 이용 리포트 토글.
 * AI 키 없이 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Change1113Test {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired OrganizationRepo orgs;
    @Autowired ElderRepo elders;
    @Autowired PostRepo posts;
    @Autowired PublicReferralRepo refs;
    @Autowired NotificationRepo notis;
    @Autowired AuthService auth;
    @Autowired WeatherAlertService weather;
    final ObjectMapper om = new ObjectMapper();
    static final String AREA = "특보시험동";

    Organization org, otherOrg;
    User admin, mgr, otherMgr, usr;

    User user(String id, Role role, Organization o) {
        User u = new User();
        u.loginId = id; u.name = "이름" + id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = role == Role.USER ? AREA : null; u.ageGroup = "70대";
        u.phone = "010-0000-0000"; u.idStatus = VerifyStatus.APPROVED;
        if (o != null) u.orgId = o.id;
        return users.save(u);
    }

    Organization org(String name) {
        Organization o = new Organization();
        o.name = name; o.orgType = "주민센터"; o.address = AREA + " 1번지"; o.status = OrgStatus.APPROVED;
        return orgs.save(o);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    @BeforeEach
    void setUp() {
        org = org("시험 주민센터"); otherOrg = org("다른 복지관");
        admin = user("c13admin", Role.ADMIN, null);
        mgr = user("c13mgr", Role.ORG, org);
        otherMgr = user("c13mgr2", Role.ORG, otherOrg);
        usr = user("c13usr", Role.USER, null);
    }

    @Test
    void 담당자는_특보와_운영자가_보낸_안내를_본다() throws Exception {
        Elder e = new Elder();
        e.alias = "특보대상"; e.dong = AREA; e.managerId = mgr.id; e.orgId = org.id; e.ageGroup = "80대"; e.needs = List.of("동행");
        elders.save(e);
        WeatherAlert a = weather.create("폭염", "경보", List.of(AREA), LocalDate.now(), LocalDate.now().plusDays(2), null, "MANUAL");
        // 운영자가 보내기 전: 특보는 보이지만 안내·배너는 없다
        mvc.perform(get("/api/org/weather").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[?(@.id==" + a.id + ")].mineArea").value(hasItem(true)))
                .andExpect(jsonPath("$.alerts[?(@.id==" + a.id + ")].myElders").value(hasItem(1)))
                .andExpect(jsonPath("$.guides[?(@.id==" + a.id + ")]").isEmpty())
                .andExpect(jsonPath("$.banner").value(nullValue()));
        mvc.perform(post("/api/admin/weather/alerts/" + a.id + "/send").header("Authorization", tok(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/org/weather").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.banner.id").value(a.id.intValue()))
                .andExpect(jsonPath("$.guides[?(@.id==" + a.id + ")].guide").value(hasItem(containsString("폭염"))));
        // 담당 대상이 그 지역에 없는 담당자: 특보는 보이지만 내 지역이 아니고 배너도 없다
        mvc.perform(get("/api/org/weather").header("Authorization", tok(otherMgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[?(@.id==" + a.id + ")].mineArea").value(hasItem(false)))
                .andExpect(jsonPath("$.banner").value(nullValue()));
        // 일반 사용자는 볼 수 없다
        mvc.perform(get("/api/org/weather").header("Authorization", tok(usr))).andExpect(status().isForbidden());
    }

    @Test
    void 공공_서비스_기록은_전달에_동의했을_때만_담당자에게_간다() throws Exception {
        long before = refs.count();
        // 공공 서비스 안내를 물어보기만 하면 아무것도 저장되지 않는다
        mvc.perform(post("/api/ai/public-services").header("Authorization", tok(usr)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"혼자 사는데 쓰러지면 걱정돼요\"}")).andExpect(status().isOk());
        assertEquals(before, refs.count());
        mvc.perform(get("/api/org/public-referrals").header("Authorization", tok(mgr))).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        String body = "{\"orgId\":" + org.id + ",\"question\":\"혼자 사는데 쓰러지면 걱정돼요\",\"answer\":\"응급안전안심서비스가 있어요\","
                + "\"services\":[{\"name\":\"응급안전안심서비스\",\"phone\":\"129\"}],\"note\":\"오후에 전화 주세요\"";
        // 동의가 없으면 받지 않는다
        mvc.perform(post("/api/public-referrals").header("Authorization", tok(usr)).contentType(MediaType.APPLICATION_JSON).content(body + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        assertEquals(before, refs.count());
        mvc.perform(post("/api/public-referrals").header("Authorization", tok(usr)).contentType(MediaType.APPLICATION_JSON).content(body + ",\"consent\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEW"));
        // 고른 기관의 담당자만 본다(대화·AI가 찾은 서비스·연락처), 다른 기관 담당자는 못 본다
        String list = mvc.perform(get("/api/org/public-referrals").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.newCount").value(1))
                .andExpect(jsonPath("$.items[0].question").value(containsString("쓰러지면")))
                .andExpect(jsonPath("$.items[0].services[0].name").value("응급안전안심서비스"))
                .andExpect(jsonPath("$.items[0].user.phone").value("010-0000-0000"))
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(list).get("items").get(0).get("id").asLong();
        mvc.perform(get("/api/org/public-referrals").header("Authorization", tok(otherMgr))).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(post("/api/org/public-referrals/" + id + "/status").header("Authorization", tok(otherMgr)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DONE\"}")).andExpect(status().isForbidden());
        // 담당자가 처리하면 사용자에게 알림이 가고, 사용자는 처리 메모를 보지 못한다
        mvc.perform(post("/api/org/public-referrals/" + id + "/status").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CONTACTED\",\"memo\":\"통화함\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.statusLabel").value("연락함"));
        assertTrue(notis.findAll().stream().anyMatch(n -> usr.id.equals(n.userId) && "PUBLIC_REFERRAL".equals(n.type)));
        mvc.perform(get("/api/public-referrals/mine").header("Authorization", tok(usr))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CONTACTED")).andExpect(jsonPath("$[0].memo").value(nullValue()))
                .andExpect(jsonPath("$[0].user").doesNotExist());
    }

    @Test
    void 대시보드_네_칸과_이용_리포트_토글() throws Exception {
        mvc.perform(get("/api/admin/dashboard").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.kpi.newUsers").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.kpi.totalUsers").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.kpi.orgCount").value(greaterThanOrEqualTo(2)));
        Post need = new Post();
        need.type = PostType.NEED; need.ownerId = usr.id; need.date = LocalDate.now(); need.startHour = 10.0; need.endHour = 12.0; need.area = AREA;
        need.categories = List.of("동행"); need.title = "병원 동행"; need.status = PostStatus.OPEN;
        posts.save(need);
        Post offer = new Post();
        offer.type = PostType.OFFER; offer.ownerId = usr.id; offer.date = LocalDate.now(); offer.startHour = 13.0; offer.endHour = 15.0; offer.area = AREA;
        offer.categories = List.of("디지털 도움"); offer.title = "폰 도와드려요"; offer.status = PostStatus.OPEN;
        posts.save(offer);
        JsonNode n = om.readTree(mvc.perform(get("/api/admin/stats/by-type?type=NEED").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode o = om.readTree(mvc.perform(get("/api/admin/stats/by-type?type=OFFER").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals("도움 요청", n.get("label").asText());
        assertEquals("봉사 가능", o.get("label").asText());
        assertEquals("동행", areaTop(n));
        assertEquals("디지털 도움", areaTop(o));
        assertEquals(12, n.get("monthly").size());
    }

    private String areaTop(JsonNode d) {
        for (JsonNode a : d.get("byArea")) if (AREA.equals(a.get("area").asText())) return a.get("top").get(0).get("category").asText();
        return null;
    }
}
