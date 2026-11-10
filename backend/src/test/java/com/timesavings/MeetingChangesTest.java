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
 * 2026-11-09 회의 반영: 클래스 평등 정산(참석 인원 × 시간)·주제 제한·여럿 수업은 기관 장소,
 * 링크 미리보기 SSRF 차단, 수동 단골, 동네 소식 신청, 위치 공공 서비스·AI 정리(키 없음 → 규칙), 자주 받는 도움 순위.
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
    @Autowired SpaceRepo spaces;
    @Autowired ClassService classes;
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
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, null, "초기 적립");
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

    ClassService.ClassCmd group(String kind, Integer cap, String place) {
        return new ClassService.ClassCmd(kind, "키오스크 주문 연습", "천천히 눌러 봐요", List.of(), null, null,
                d, 14.0, 1.5, cap, null, "GROUP", null, null, null, null, null, null, place, null);
    }

    @Test
    void 클래스_정산은_참석_인원_곱하기_시간() {
        // 사용자가 연 여럿 수업 → 기관이 장소를 정해야 열림
        NeighborClass c = classes.create(teacher, group("전자기기 사용법", 4, null));
        assertEquals(ClassStatus.SPACE_PENDING, c.status);
        assertThrows(ApiException.class, () -> classes.apply(s1, c.id)); // 아직 모집 전
        classes.assignSpace(mgr, c.id, null, "행복동 주민센터 1층");
        assertEquals(ClassStatus.OPEN, c.status);

        for (User s : List.of(s1, s2, s3)) classes.apply(s, c.id);
        assertEquals(1.5, sum(s1, LedgerType.HOLD)); // 수강 = 수업 시간 그대로(×0.5 없음)
        classes.attend(s1, c.id, c.checkCode);
        classes.attend(s2, c.id, c.checkCode);
        var r = classes.finish(teacher, c.id, List.of("화면 보기"), null);
        assertEquals(2, r.get("attended"));
        assertEquals(3.0, r.get("teacherEarn")); // 2명 × 1.5시간 (준비 1시간 없음)
        assertEquals(3.0, sum(teacher, LedgerType.CLASS_EARN));
        assertEquals(-1.5, sum(s1, LedgerType.CLASS_FEE));
        assertEquals(0.0, sum(s3, LedgerType.CLASS_FEE)); // 불참: 보류만 풀림
        assertTrue(ledger.findAll().stream().noneMatch(l -> l.type == LedgerType.POOL && c.id.equals(l.classId))); // 공동 적립 없음
    }

    @Test
    void 클래스_주제는_두_가지만_여럿_수업은_4명_기관_장소() {
        assertEquals("INVALID_KIND", assertThrows(ApiException.class, () -> classes.create(teacher, group("요리", 4, null))).code);
        assertEquals("INVALID_KIND", assertThrows(ApiException.class, () -> classes.create(teacher, group("일·경험", 4, null))).code);
        assertEquals("INVALID_CAPACITY", assertThrows(ApiException.class, () -> classes.create(teacher, group("말벗·이야기", 5, null))).code);

        Elder e = new Elder();
        e.alias = "한영자"; e.dong = "행복동"; e.managerId = mgr.id; e.orgId = org.id; e.needs = List.of("디지털 도움"); e.ageGroup = "70대";
        elders.save(e);
        ClassService.ClassCmd noPlace = new ClassService.ClassCmd("말벗·이야기", "옛날 노래 이야기", null, List.of(), null, null,
                d, 15.0, 1.0, 4, e.id, "GROUP", null, null, null, null, null, null, null, null);
        assertEquals("SPACE_REQUIRED", assertThrows(ApiException.class, () -> classes.create(mgr, noPlace)).code);
        ClassService.ClassCmd withPlace = new ClassService.ClassCmd("말벗·이야기", "옛날 노래 이야기", null, List.of(), null, null,
                d, 15.0, 1.0, 4, e.id, "GROUP", null, null, null, null, null, null, "행복동 경로당", null);
        NeighborClass c = classes.create(mgr, withPlace);
        assertEquals(ClassStatus.OPEN, c.status); // 기관이 장소를 정하면 바로 모집
        assertEquals("행복동 경로당", c.place);
        classes.apply(s1, c.id);
        classes.attend(s1, c.id, c.checkCode);
        classes.finish(mgr, c.id, List.of(), null);
        double elderEarn = ledger.findAll().stream().filter(l -> e.id.equals(l.elderId) && l.type == LedgerType.CLASS_EARN).mapToDouble(l -> l.amount).sum();
        assertEquals(1.0, elderEarn); // 1명 × 1시간, 기관이 대신 받음
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
        // 함께 끝낸 활동이 없으면 등록 불가
        mvc.perform(post("/api/regulars/" + teacher.id).header("Authorization", t)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_DONE_ACTIVITY"));
        done(s1, teacher, "디지털 도움");
        mvc.perform(get("/api/regulars/" + teacher.id + "/status").header("Authorization", t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.canMark").value(true)).andExpect(jsonPath("$.regular").value(false));
        mvc.perform(post("/api/regulars/" + teacher.id).header("Authorization", t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.manual").value(true)).andExpect(jsonPath("$.regular").value(true)).andExpect(jsonPath("$.auto").value(false));
        // 목록에 자동(3회)+수동이 합쳐지고 manual 표시
        mvc.perform(get("/api/regulars").header("Authorization", t)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(teacher.id)).andExpect(jsonPath("$[0].manual").value(true)).andExpect(jsonPath("$[0].auto").value(false));
        mvc.perform(post("/api/regulars/" + s1.id).header("Authorization", t)).andExpect(status().isBadRequest()); // 나 자신
        mvc.perform(delete("/api/regulars/" + teacher.id).header("Authorization", t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.manual").value(false));
        mvc.perform(get("/api/regulars").header("Authorization", t)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 동네_소식_신청과_주소_검사() throws Exception {
        assertThrows(ApiException.class, () -> events.create(s1, new EventService.EventCmd("행사", null, d, 14.0, "주민센터", 2, "APP", null, null, null, null)));
        assertEquals("URL_REQUIRED", assertThrows(ApiException.class, () -> events.create(mgr, new EventService.EventCmd("건강 강좌", null, d, 10.0, "보건소", 0, "LINK", null, null, null, null))).code);
        assertEquals("URL_NOT_ALLOWED", assertThrows(ApiException.class, () -> events.create(mgr, new EventService.EventCmd("건강 강좌", null, d, 10.0, "보건소", 0, "LINK", "http://127.0.0.1/a", null, null, null))).code);
        // 미리보기를 화면에서 받아 온 경우 그대로 저장(서버가 다시 가져오지 않음)
        OrgEvent link = events.create(mgr, new EventService.EventCmd("건강 강좌", "보건소 안내", d, 10.0, "보건소", 0, "LINK", "https://example.org/health", "건강 강좌 안내", "https://example.org/a.png", null));
        assertEquals("건강 강좌 안내", link.ogTitle);
        assertEquals("EXTERNAL_APPLY", assertThrows(ApiException.class, () -> events.apply(s1, link.id)).code);

        OrgEvent e = events.create(mgr, new EventService.EventCmd("사진 정리 교실", "휴대폰만 가져오세요", d, 14.0, "주민센터", 2, "APP", null, null, null, null));
        mvc.perform(post("/api/events/" + e.id + "/apply").header("Authorization", token(s1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.myApplied").value(true)).andExpect(jsonPath("$.applied").value(1));
        events.apply(s2, e.id);
        assertEquals("EVENT_FULL", assertThrows(ApiException.class, () -> events.apply(s3, e.id)).code);
        assertEquals("DUPLICATE", assertThrows(ApiException.class, () -> events.apply(s1, e.id)).code);
        mvc.perform(get("/api/events").header("Authorization", token(s3))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/org/events").header("Authorization", token(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].applicants.length()").value(2));
        events.cancel(s1, e.id);
        events.apply(s3, e.id);
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
