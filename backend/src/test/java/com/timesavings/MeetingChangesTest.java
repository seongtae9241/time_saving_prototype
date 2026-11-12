package com.timesavings;

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

import org.hamcrest.Matchers;

import java.net.InetAddress;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 2026-11-09 회의 반영: 링크 미리보기 SSRF 차단, 수동 단골, 동네 소식 신청, 위치 공공 서비스·AI 정리(키 없음 → 규칙), 자주 받는 도움 순위.
 * 외부 네트워크·AI 를 부르지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MeetingChangesTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired OrganizationRepo orgs;
    @Autowired ElderRepo elders;
    @Autowired LedgerRepo ledger;
    @Autowired RequestRepo requests;
    @Autowired EventService events;
    @Autowired BankService bank;
    @Autowired AuthService auth;

    User teacher, s1, s2, s3, mgr;
    Organization org;
    final LocalDate d = LocalDate.now().plusDays(4);

    User user(String id, Role role, double earned) {
        User u = new User();
        u.loginId = id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "60대";
        u.idStatus = VerifyStatus.APPROVED;
        if (role == Role.ORG) u.orgId = org.id;
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, "초기 적립");
        return u;
    }

    String token(User u) { return "Bearer " + auth.issueToken(u, false); }

    double sum(User u, LedgerType t) {
        return ledger.findByUserIdOrderByCreatedAtDescIdDesc(u.id).stream().filter(l -> l.type == t).mapToDouble(l -> l.amount).sum();
    }

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.name = "행복동 주민센터"; org.orgType = "주민센터"; org.status = OrgStatus.APPROVED;
        orgs.save(org);
        teacher = user("tch", Role.USER, 0);
        s1 = user("st1", Role.USER, 10);
        s2 = user("st2", Role.USER, 10);
        s3 = user("st3", Role.USER, 10);
        mgr = user("mgr", Role.ORG, 0);
    }

    @Test
    void 링크_미리보기는_내부_주소를_막는다() throws Exception {
        String t = token(mgr);
        for (String url : List.of("http://127.0.0.1/", "http://localhost:8080/", "http://10.0.0.5/", "http://192.168.0.1/admin",
                "http://169.254.169.254/latest/meta-data/", "http://172.16.0.1/", "http://[::1]/", "http://0.0.0.0/",
                "file:///etc/passwd", "ftp://example.org/", "gopher://example.org/", "http://user:pw@example.org/", "http://example.org:22/")) {
            mvc.perform(post("/api/link-preview").header("Authorization", t).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"" + url + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("URL_NOT_ALLOWED"));
        }
        mvc.perform(post("/api/link-preview").header("Authorization", t).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("URL_REQUIRED"));
        mvc.perform(post("/api/link-preview").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.org\"}"))
                .andExpect(status().isUnauthorized());
        // IPv4 매핑 IPv6·CGNAT 도 차단
        assertTrue(invokeBlocked(InetAddress.getByName("::ffff:127.0.0.1")));
        assertTrue(invokeBlocked(InetAddress.getByName("100.64.1.1")));
        assertTrue(invokeBlocked(InetAddress.getByName("fd00::1")));
        assertFalse(invokeBlocked(InetAddress.getByName("93.184.216.34")));
        // HTML 파싱(네트워크 없음): og 우선, 상대 경로 이미지 → 절대 경로, javascript: 이미지는 버림
        var p = LinkPreviewService.parse("<html><head><title>예비 제목</title><meta property=\"og:title\" content=\"치매 예방 강좌 &amp; 상담\">"
                + "<meta content='/img/a.png' property='og:image'><meta name=\"description\" content=\"보건소 안내\"></head></html>", URI.create("https://example.org/x/y"));
        assertEquals("치매 예방 강좌 & 상담", p.title());
        assertEquals("https://example.org/img/a.png", p.image());
        assertEquals("보건소 안내", p.description());
        var p2 = LinkPreviewService.parse("<title>그냥 제목</title><meta property=\"og:image\" content=\"javascript:alert(1)\">", URI.create("https://example.org/"));
        assertEquals("그냥 제목", p2.title());
        assertNull(p2.image());
    }

    private static boolean invokeBlocked(InetAddress a) throws Exception {
        var m = LinkPreviewService.class.getDeclaredMethod("blocked", InetAddress.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, a);
    }

    Request done(User recipient, User volunteer, String cat) {
        Request r = new Request();
        r.kind = Kind.REQUEST; r.senderId = recipient.id; r.recipientId = recipient.id; r.volunteerId = volunteer.id;
        r.date = LocalDate.now().minusDays(3); r.startHour = 14; r.hours = 1; r.category = cat; r.content = "도움";
        r.difficulty = Level.BASIC; r.weight = 1.0; r.status = ReqStatus.DONE;
        return requests.save(r);
    }

    @Test
    void 수동_단골_등록과_해제() throws Exception {
        String t = token(s1);
        // 41판 B-65: 함께 끝낸 활동이 없어도 즐겨찾기처럼 직접 등록할 수 있다
        mvc.perform(get("/api/regulars/" + teacher.id + "/status").header("Authorization", t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.canMark").value(true)).andExpect(jsonPath("$.regular").value(false)).andExpect(jsonPath("$.doneCount").value(0));
        done(s1, teacher, "디지털 도움");
        mvc.perform(post("/api/regulars/" + teacher.id).header("Authorization", t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.manual").value(true)).andExpect(jsonPath("$.regular").value(true)).andExpect(jsonPath("$.auto").value(false));
        // 목록에 자동(3회)+수동이 합쳐지고 manual 표시
        mvc.perform(get("/api/regulars").header("Authorization", t)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(teacher.id)).andExpect(jsonPath("$[0].manual").value(true)).andExpect(jsonPath("$[0].auto").value(false));
        mvc.perform(post("/api/regulars/" + s1.id).header("Authorization", t)).andExpect(status().isBadRequest()); // 나 자신
        mvc.perform(delete("/api/regulars/" + teacher.id).header("Authorization", t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.manual").value(false)).andExpect(jsonPath("$.removed").value(true)).andExpect(jsonPath("$.canMark").value(true));
        mvc.perform(get("/api/regulars").header("Authorization", t)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        // 내가 해지한 사람은 다시 등록할 수 있다
        mvc.perform(post("/api/regulars/" + teacher.id).header("Authorization", t)).andExpect(status().isOk()).andExpect(jsonPath("$.regular").value(true));
    }

    @Test
    void 단골_배지_모아_보기와_양쪽_해지() throws Exception {
        // s1 을 3번 도움 → 자동 단골, s2 는 한 번도 안 만났지만 직접 등록
        done(s1, teacher, "디지털 도움"); done(s1, teacher, "동행"); done(s1, teacher, "동행");
        mvc.perform(post("/api/regulars/" + teacher.id).header("Authorization", token(s2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.manual").value(true)).andExpect(jsonPath("$.doneCount").value(0));
        // B-42: 받은 단골 배지 — 누구를 몇 회 도왔는지
        mvc.perform(get("/api/me/regular-badges").header("Authorization", token(teacher))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value(s1.id)).andExpect(jsonPath("$[0].count").value(3)).andExpect(jsonPath("$[0].auto").value(true))
                .andExpect(jsonPath("$[1].userId").value(s2.id)).andExpect(jsonPath("$[1].count").value(0)).andExpect(jsonPath("$[1].manual").value(true));
        // 프로필 배지(3회 이상만)
        mvc.perform(get("/api/users/" + teacher.id + "/profile").header("Authorization", token(s3))).andExpect(status().isOk())
                .andExpect(jsonPath("$.regularBadges.length()").value(1)).andExpect(jsonPath("$.eduDone").value(false)); // 기능 U-34: 교육 이수 표시를 다시 살렸다(연습을 안 했으면 false)
        // B-65: 등록된 쪽(봉사자)이 자동 단골을 해지 → 양쪽 목록에서 빠지고, 상대는 다시 등록할 수 없다
        mvc.perform(delete("/api/me/regular-badges/" + s1.id).header("Authorization", token(teacher))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].userId").value(s2.id));
        mvc.perform(get("/api/regulars").header("Authorization", token(s1))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/regulars/" + teacher.id + "/status").header("Authorization", token(s1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.regular").value(false)).andExpect(jsonPath("$.removedByThem").value(true)).andExpect(jsonPath("$.canMark").value(false));
        mvc.perform(post("/api/regulars/" + teacher.id).header("Authorization", token(s1))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REMOVED_BY_VOLUNTEER"));
        // 등록한 쪽(s2)의 해지 → 봉사자 배지에서도 빠진다
        mvc.perform(delete("/api/regulars/" + teacher.id).header("Authorization", token(s2))).andExpect(status().isOk());
        mvc.perform(get("/api/me/regular-badges").header("Authorization", token(teacher))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        // 남의 단골이 아닌데 해지하려 하면 404
        mvc.perform(delete("/api/me/regular-badges/" + s3.id).header("Authorization", token(teacher))).andExpect(status().isNotFound());
    }

    /** B-64·C-15(41판): 소식은 기관 페이지 링크로만. 앱 안 신청·정원·신청자 목록은 없다. */
    @Test
    void 기관_소식은_링크가_있어야_하고_앱_신청은_없다() throws Exception {
        assertThrows(ApiException.class, () -> events.create(s1, new EventService.EventCmd("행사", null, d, 14.0, "주민센터", "https://example.org/a", null, null, null)));
        assertEquals("URL_REQUIRED", assertThrows(ApiException.class, () -> events.create(mgr, new EventService.EventCmd("건강 강좌", null, d, 10.0, "보건소", null, null, null, null))).code);
        assertEquals("URL_NOT_ALLOWED", assertThrows(ApiException.class, () -> events.create(mgr, new EventService.EventCmd("건강 강좌", null, d, 10.0, "보건소", "http://127.0.0.1/a", null, null, null))).code);
        // 미리보기를 화면에서 받아 온 경우 그대로 저장(서버가 다시 가져오지 않음)
        OrgEvent link = events.create(mgr, new EventService.EventCmd("건강 강좌", "보건소 안내", d, 10.0, "보건소", "https://example.org/health", "건강 강좌 안내", "https://example.org/a.png", null));
        assertEquals("건강 강좌 안내", link.ogTitle);
        assertEquals("LINK", link.applyType);
        assertEquals(0, link.capacity);

        // 화면에서 올리기: 링크 없이는 400, 있으면 제목·일시·장소·링크가 그대로 나온다
        mvc.perform(post("/api/events").header("Authorization", token(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"사진 정리 교실\",\"date\":\"" + d + "\",\"startHour\":14,\"place\":\"주민센터\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("URL_REQUIRED"));
        mvc.perform(post("/api/events").header("Authorization", token(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"사진 정리 교실\",\"date\":\"" + d + "\",\"startHour\":14,\"place\":\"주민센터\",\"url\":\"https://example.org/photo\",\"ogTitle\":\"사진 교실\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("사진 정리 교실")).andExpect(jsonPath("$.date").value(d.toString()))
                .andExpect(jsonPath("$.place").value("주민센터")).andExpect(jsonPath("$.url").value("https://example.org/photo"))
                .andExpect(jsonPath("$.applyType").value("LINK"))
                .andExpect(jsonPath("$.applied").doesNotExist()).andExpect(jsonPath("$.capacity").doesNotExist())
                .andExpect(jsonPath("$.myApplied").doesNotExist()).andExpect(jsonPath("$.applicants").doesNotExist());
        // 앱 안 신청·취소 주소는 없어졌다
        mvc.perform(post("/api/events/" + link.id + "/apply").header("Authorization", token(s1))).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/events/" + link.id + "/cancel").header("Authorization", token(s1))).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/events").header("Authorization", token(s3))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/org/events").header("Authorization", token(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].applicants").doesNotExist());
        // 담당자가 내리면 사용자 목록에서 빠진다
        mvc.perform(post("/api/events/" + link.id + "/close").header("Authorization", token(mgr))).andExpect(status().isOk());
        mvc.perform(get("/api/events").header("Authorization", token(s3))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void 위치_공공서비스와_AI_정리는_키_없으면_규칙() throws Exception {
        mvc.perform(post("/api/ai/public-services/nearby").header("Authorization", token(s1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":37.5665,\"lng\":126.978,\"question\":\"혼자 사는데 쓰러지면 걱정돼요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("rule")).andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.aiGenerated").value(false));
        mvc.perform(post("/api/ai/public-services/nearby").header("Authorization", token(s1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":137.5,\"lng\":126.9}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
        mvc.perform(post("/api/ai/tidy-post").header("Authorization", token(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"다음 주 화요일 정형외과 병원 같이 가 줄 분. 무릎이 아프셔서 천천히. 연락은 010-1234-5678\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("rule"))
                .andExpect(jsonPath("$.title").value("병원 동행 (정형외과)"))
                .andExpect(jsonPath("$.caution").value(Matchers.containsString("천천히")))
                .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("1234-5678"))))
                .andExpect(jsonPath("$.piiRemoved[0]").value("phone"));
    }

    @Test
    void 자주_받는_도움_순위() throws Exception {
        done(s1, teacher, "동행");
        done(s1, teacher, "동행");
        done(s1, s2, "디지털 도움");
        mvc.perform(get("/api/me/top-categories").header("Authorization", token(s1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.top[0].category").value("동행")).andExpect(jsonPath("$.top[0].count").value(2))
                .andExpect(jsonPath("$.top[1].category").value("디지털 도움"))
                .andExpect(jsonPath("$.order.length()").value(6));
    }
}
