package com.timesavings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.ai.AiClient;
import com.timesavings.ai.CareMemoAi;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 요구사항 41판 — 재대조·화면 확인 뒤 남은 문제 고침(2026-11-12).
 * B-36 일지 한 건의 뚜렷한 표현 알림, B-67 금지 요청 → 근처 공공 서비스, 번호 없이 시작 없앰, 올린 파일 권한,
 * 기관 신청 횟수 제한, 퇴출과 정지 구분, 알림 날짜·조사, 대시보드 수, 알림 설정 역할. 규칙 모드(AI 호출 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Req41FixTest {
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired OrganizationRepo orgs;
    @Autowired RequestRepo requests;
    @Autowired PostRepo postRepo;
    @Autowired NotificationRepo notis;
    @Autowired ReportRepo reports;
    @Autowired InquiryRepo inquiries;
    @Autowired FamilyLinkRepo family;
    @Autowired RequestService reqs;
    @Autowired PostService postSvc;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired AiClient ai;
    @Autowired ActivityAiService actAi;
    @Autowired RateLimiter limiter;
    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    User vol, rec, young, mgr, admin, stranger;
    Elder elder;
    final LocalDate day = LocalDate.now().plusDays(3);

    User user(String id, Role role, String age, double earned) {
        User u = new User();
        u.loginId = "fx" + id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = age;
        u.idStatus = VerifyStatus.APPROVED;
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, "초기 적립");
        return u;
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    List<Notification> notiOf(User u, String type) {
        return notis.findByUserIdOrderByCreatedAtDescIdDesc(u.id).stream().filter(n -> type.equals(n.type)).toList();
    }

    @BeforeEach
    void setUp() {
        limiter.reset();
        vol = user("vol", Role.USER, "30대", 0);
        rec = user("rec", Role.USER, "70대", 20);
        young = user("young", Role.USER, "20대", 20);
        stranger = user("stranger", Role.USER, null, 20);
        mgr = user("mgr", Role.ORG, null, 0);
        admin = user("admin", Role.ADMIN, null, 0);
        elder = new Elder();
        elder.alias = "이순례"; elder.dong = "행복동"; elder.ageGroup = "80대"; elder.managerId = mgr.id; elder.needs = List.of("동행");
        elders.save(elder);
    }

    Post offer() {
        return postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day, 9.0, 18.0, "행복동",
                List.of("동행", "말동무·정서"), "도와드려요", null, null, null, false, null, null, null, null)).get(0);
    }

    /** 일정 확정까지. sender 가 담당자면 독거노인 건. */
    Request confirmed(User sender, String cat, double start) {
        Request r = reqs.create(sender, new RequestService.CreateCmd(Kind.REQUEST, offer().id, null, sender.role == Role.ORG ? elder.id : null,
                day, start, 1.0, cat, cat + " 부탁드려요", null));
        reqs.accept(vol, r.id);
        reqs.accept(sender, r.id);
        assertEquals(ReqStatus.CONFIRMED, r.status);
        return r;
    }

    /** 시작·종료 번호 확인 뒤 일지 제출. */
    Request logged(User sender, String log, String memo) {
        Request r = confirmed(sender, "말동무·정서", 10.0);
        reqs.checkIn(vol, r.id, reqs.codeFor(r), "code");
        reqs.checkOut(vol, r.id, reqs.codeFor(r), "code");
        return reqs.submitLog(vol, r.id, log, null, null, false, memo);
    }

    // ---------------- B-36 일지 기반 이상 징후 알림 ----------------
    @Test
    void B36_뚜렷한_표현은_감지_낱말에_있다() {
        assertEquals(List.of("쓰러짐"), CareMemoAi.ruleSignals("화장실 앞에서 쓰러지셨다가 금방 일어나셨어요"));
        assertEquals(List.of("쓰러짐"), CareMemoAi.ruleSignals("어제 쓰러짐. 가족에게 알림"));
        assertEquals(List.of("넘어짐"), CareMemoAi.ruleSignals("현관에서 넘어지셨다고 하셨어요"));
        assertEquals(List.of("식사 거름"), CareMemoAi.ruleSignals("오늘 식사를 거름"));
        assertEquals(List.of("식사 거름"), CareMemoAi.ruleSignals("점심을 거르셨대요"));
        assertTrue(CareMemoAi.ruleSignals("같이 산책하고 이야기 나눴어요").isEmpty());
        assertEquals(List.of("쓰러짐", "식사 거름"), CareMemoAi.urgentOf(List.of("쓰러짐", "기운 없음", "식사 거름")));
        assertTrue(CareMemoAi.urgentOf(List.of("기운 없음", "외출 감소")).isEmpty());
    }

    @Test
    void B36_일지_한_건의_쓰러짐만으로_담당자에게_요약_알림이_간다() {
        logged(mgr, "댁에 가 보니 아침에 한 번 쓰러지셨다고 하셨어요. 같이 이야기 나눴어요", null);
        List<Notification> got = notiOf(mgr, "CARE_SIGNAL");
        assertEquals(1, got.size(), "일지 한 건이어도 알림");
        Notification n = got.get(0);
        assertTrue(n.title.contains("이순례"));
        assertTrue(n.body.contains("쓰러짐"));
        assertTrue(n.body.contains("확인해 보세요"));
        assertTrue(n.body.contains("진단이 아니에요"));
        assertFalse(CareMemoAi.DIAGNOSIS.matcher(n.title + " " + n.body.replace("진단이 아니에요", "")).find(), "진단·건강 판단 문장은 쓰지 않는다");
        assertEquals("/o/elders/" + elder.id, n.link);
        // 봉사자·다른 사람에게는 가지 않는다
        assertTrue(notiOf(vol, "CARE_SIGNAL").isEmpty());
        assertTrue(notiOf(admin, "CARE_SIGNAL").isEmpty());
    }

    @Test
    void B36_식사_거름은_한_건으로_알리고_약한_신호_한_건은_알리지_않는다() {
        logged(mgr, "오늘 이야기 나눔", "요즘 기운이 없다고 하셨어요");
        assertTrue(notiOf(mgr, "CARE_SIGNAL").isEmpty(), "약한 신호는 한 번으로는 알리지 않는다(이어질 때만)");
        logged(mgr, "말벗", "점심을 거르셨다고 해서 죽을 같이 데워 드렸어요");
        assertEquals(1, notiOf(mgr, "CARE_SIGNAL").size());
        assertTrue(notiOf(mgr, "CARE_SIGNAL").get(0).body.contains("식사 거름"));
    }

    @Test
    void B36_일반_사용자의_일지는_담당자_알림이_없다() {
        logged(rec, "넘어지셨다고 하셨어요", null);
        assertTrue(notiOf(mgr, "CARE_SIGNAL").isEmpty());
        assertTrue(notiOf(rec, "CARE_SIGNAL").isEmpty());
    }

    // ---------------- B-67 · B-09 금지 요청 → 근처 공공 서비스 ----------------
    @Test
    void B67_금지_요청의_대체_안내에_근처_공공_서비스로_가는_길이_붙는다() throws Exception {
        Map<String, Object> f = ai.filter("목욕 시켜 주세요 010-1234-5678");
        assertEquals("BLOCK", f.get("level"));
        Map<?, ?> near = (Map<?, ?>) f.get("nearby");
        assertNotNull(near);
        assertTrue(String.valueOf(near.get("link")).startsWith("/public?near=1&q="));
        assertTrue(String.valueOf(near.get("label")).contains("근처"));
        assertFalse(String.valueOf(near.get("question")).contains("010-1234"), "연락처는 질문에 옮기지 않는다");
        assertTrue(f.containsKey("alternatives"), "공공 서비스 목록 안내는 그대로");
        assertNull(ai.filter("같이 산책해 주세요").get("nearby"), "허용 요청에는 붙지 않는다");
        mvc.perform(post("/api/ai/filter").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"목욕 시켜 주세요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.level").value("BLOCK")).andExpect(jsonPath("$.nearby.link").isNotEmpty());
    }

    // ---------------- 번호 없이 시작·마치기 없앰 ----------------
    @Test
    void 번호_없이_시작_마치기는_거절된다() throws Exception {
        Request r = confirmed(rec, "말동무·정서", 10.0);
        mvc.perform(post("/api/requests/" + r.id + "/check-in").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"manual\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MANUAL_NOT_ALLOWED"));
        assertEquals(ReqStatus.CONFIRMED, r.status);
        assertNull(r.checkInAt);
        // 번호 6자리, QR 은 그대로 된다
        mvc.perform(post("/api/requests/" + r.id + "/check-in").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + reqs.codeFor(r) + "\"}")).andExpect(status().isOk());
        assertEquals(ReqStatus.IN_PROGRESS, r.status);
        ApiException e = assertThrows(ApiException.class, () -> reqs.checkOut(vol, r.id, null, "manual"));
        assertEquals("MANUAL_NOT_ALLOWED", e.code);
        reqs.checkOut(vol, r.id, reqs.codeFor(r), "qr");
        assertEquals("qr", r.checkMethod);
        // 독거노인 건의 현관 카드 번호도 그대로
        Request er = confirmed(mgr, "말동무·정서", 13.0);
        assertThrows(ApiException.class, () -> reqs.checkIn(vol, er.id, null, "manual"));
        reqs.checkIn(vol, er.id, reqs.codeFor(er), "code");
        assertEquals(ReqStatus.IN_PROGRESS, er.status);
    }

    // ---------------- 올린 파일 권한 ----------------
    String upload(User u, String name) throws Exception {
        return json(mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", name, "image/png", new byte[]{1, 2, 3}))
                .header("Authorization", tok(u))).andExpect(status().isOk())).path("url").asText();
    }

    int open(String url, User u) throws Exception {
        return (u == null ? mvc.perform(get(url)) : mvc.perform(get(url).header("Authorization", tok(u)))).andReturn().getResponse().getStatus();
    }

    @Test
    void 올린_파일은_로그인_없이_열리지_않고_방금_올린_파일은_올린_사람만_연다() throws Exception {
        String url = upload(vol, "a.png");
        assertEquals(401, open(url, null));
        assertEquals(200, open(url, vol));
        assertEquals(403, open(url, stranger));
        assertEquals(403, open(url, mgr));
        assertEquals(200, open(url, admin));
        assertEquals(404, open("/api/files/00000000000000000000000000000000.png", vol));
    }

    @Test
    void 신분증과_범죄경력_회보서는_본인과_운영자만_연다() throws Exception {
        String id = upload(vol, "id.png"), crime = upload(vol, "crime.pdf");
        mvc.perform(post("/api/me/verify/id").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileUrl\":\"" + id + "\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/me/verify/crime").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileUrl\":\"" + crime + "\"}")).andExpect(status().isOk());
        for (String url : List.of(id, crime)) {
            assertEquals(401, open(url, null));
            assertEquals(200, open(url, vol));
            assertEquals(200, open(url, admin));
            assertEquals(403, open(url, stranger));
            assertEquals(403, open(url, mgr));
        }
        // 남이 올린 파일 주소를 내 서류·프로필 사진으로 붙여 열어 보는 길도 막는다
        mvc.perform(post("/api/me/verify/id").header("Authorization", tok(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileUrl\":\"" + id + "\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_NOT_MINE"));
        mvc.perform(put("/api/me").header("Authorization", tok(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"photoUrl\":\"" + id + "\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_NOT_MINE"));
        assertEquals(403, open(id, stranger));
        // 민감한 서류로 쓰인 파일은 본인이 프로필 사진으로 같이 걸어도 남에게 열리지 않는다
        mvc.perform(put("/api/me").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"photoUrl\":\"" + id + "\"}")).andExpect(status().isOk());
        assertEquals(403, open(id, stranger));
    }

    @Test
    void 프로필_사진은_로그인한_사용자가_보고_일지_사진은_그_요청의_당사자와_담당자만_본다() throws Exception {
        String photo = upload(vol, "me.png");
        mvc.perform(put("/api/me").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"photoUrl\":\"" + photo + "\"}")).andExpect(status().isOk());
        assertEquals(401, open(photo, null));
        assertEquals(200, open(photo, stranger));
        assertEquals(200, open(photo, mgr));

        // 일반 건: 봉사자·받는 사람
        Request r = confirmed(rec, "말동무·정서", 10.0);
        reqs.checkIn(vol, r.id, reqs.codeFor(r), "code");
        reqs.checkOut(vol, r.id, reqs.codeFor(r), "code");
        String logPhoto = upload(vol, "log.png");
        mvc.perform(post("/api/requests/" + r.id + "/log").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"raw\":\"이야기 나눔\",\"photoUrl\":\"" + logPhoto + "\",\"photoConsent\":true}")).andExpect(status().isOk());
        assertEquals(logPhoto, r.logPhotoUrl);
        assertEquals(200, open(logPhoto, vol));
        assertEquals(200, open(logPhoto, rec));
        assertEquals(200, open(logPhoto, admin));
        assertEquals(403, open(logPhoto, stranger));
        assertEquals(403, open(logPhoto, mgr), "이 요청의 담당자가 아니다");
        assertEquals(401, open(logPhoto, null));

        // 독거노인 건: 봉사자·그 대상의 담당자
        Request er = confirmed(mgr, "말동무·정서", 13.0);
        reqs.checkIn(vol, er.id, reqs.codeFor(er), "code");
        reqs.checkOut(vol, er.id, reqs.codeFor(er), "code");
        String ep = upload(vol, "elog.png");
        mvc.perform(post("/api/requests/" + er.id + "/log").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"raw\":\"이야기 나눔\",\"photoUrl\":\"" + ep + "\",\"photoConsent\":true}")).andExpect(status().isOk());
        assertEquals(200, open(ep, mgr));
        assertEquals(403, open(ep, rec));
        // 남이 올린 사진은 일지에 붙일 수 없다
        Request r2 = confirmed(young, "말동무·정서", 15.0);
        reqs.checkIn(vol, r2.id, reqs.codeFor(r2), "code");
        reqs.checkOut(vol, r2.id, reqs.codeFor(r2), "code");
        String others = upload(stranger, "x.png");
        mvc.perform(post("/api/requests/" + r2.id + "/log").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"raw\":\"이야기 나눔\",\"photoUrl\":\"" + others + "\",\"photoConsent\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_NOT_MINE"));
    }

    // ---------------- 기관 신청 횟수 제한 ----------------
    @Test
    void 기관_신청은_같은_IP에서_10분에_5번까지만_받는다() throws Exception {
        for (int i = 0; i < RateLimiter.MAX; i++)
            mvc.perform(multipart("/api/orgs/apply").file(new MockMultipartFile("proof", "p.pdf", "application/pdf", new byte[]{1}))
                    .param("name", "기관" + i).param("managerName", "담당").header("X-Forwarded-For", "203.0.113.7")).andExpect(status().isOk());
        long before = orgs.count();
        mvc.perform(multipart("/api/orgs/apply").file(new MockMultipartFile("proof", "p.pdf", "application/pdf", new byte[]{1}))
                        .param("name", "기관6").param("managerName", "담당").header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        String proof = orgs.findAll().stream().filter(o -> "기관0".equals(o.name)).findFirst().orElseThrow().proofFileUrl;
        mvc.perform(post("/api/orgs/apply").contentType(MediaType.APPLICATION_JSON).header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                .content("{\"name\":\"기관7\",\"managerName\":\"담당\",\"proofFileUrl\":\"" + proof + "\"}")).andExpect(status().isTooManyRequests());
        // 내용이 모자라 400 으로 돌아가는 신청은 세지 않는다(제한에 걸린 뒤에도 400)
        mvc.perform(post("/api/orgs/apply").contentType(MediaType.APPLICATION_JSON).header("X-Forwarded-For", "203.0.113.7")
                .content("{\"name\":\"기관8\",\"managerName\":\"담당\"}")).andExpect(status().isBadRequest());
        assertEquals(before, orgs.count(), "막힌 신청은 저장되지 않는다");
        // 다른 IP 는 된다
        mvc.perform(multipart("/api/orgs/apply").file(new MockMultipartFile("proof", "p.pdf", "application/pdf", new byte[]{1}))
                .param("name", "다른 기관").param("managerName", "담당").header("X-Forwarded-For", "203.0.113.8")).andExpect(status().isOk());
    }

    // ---------------- D-02 퇴출과 정지 ----------------
    Report reportOn(User target) {
        Report rp = new Report();
        rp.reporterId = stranger.id; rp.targetType = ReportTarget.USER; rp.targetId = target.id; rp.type = "부적절한 언행"; rp.content = "신고 내용";
        return reports.save(rp);
    }

    @Test
    void 퇴출은_정지와_달리_요청과_공고를_정리하고_다시_풀_수_없다() throws Exception {
        Request confirmedReq = confirmed(rec, "말동무·정서", 10.0);
        double hold = bank.summary(rec.id).hold();
        assertTrue(hold > 0);
        Post open = postSvc.create(rec, new PostService.PostCmd(PostType.NEED, day, 14.0, 16.0, "행복동", List.of("말동무·정서"),
                "말벗", "이야기", null, 1.0, false, null, null, null, null)).get(0);
        Request pending = reqs.create(vol, new RequestService.CreateCmd(Kind.PROPOSAL, open.id, null, null, day, 14.0, 1.0, null, "말벗 해 드릴게요", null));

        // 정지: 로그인만 막고 요청·공고는 그대로
        mvc.perform(post("/api/admin/reports/" + reportOn(young).id + "/handle").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"SUSPEND\"}")).andExpect(status().isOk());
        assertEquals(UserStatus.SUSPENDED, young.status);
        assertNotEquals(Boolean.TRUE, young.banned);
        mvc.perform(post("/api/admin/users/" + young.id + "/unsuspend").header("Authorization", tok(admin))).andExpect(status().isOk());
        assertEquals(UserStatus.ACTIVE, young.status);

        // 퇴출
        String recToken = tok(rec);
        mvc.perform(post("/api/admin/reports/" + reportOn(rec).id + "/handle").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"BAN\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("퇴출"));
        assertEquals(Boolean.TRUE, rec.banned);
        assertEquals(UserStatus.SUSPENDED, rec.status);
        assertEquals(ReqStatus.CANCELLED, confirmedReq.status);
        assertEquals(ReqStatus.CANCELLED, pending.status);
        assertEquals(0.0, bank.summary(rec.id).hold(), 1e-9, "보류 시간은 풀린다");
        assertEquals(PostStatus.CLOSED, open.status);
        assertFalse(notiOf(vol, "CANCELLED").isEmpty(), "상대에게 취소를 알린다");
        // 다시 로그인할 수 없고, 쓰던 로그인도 끊긴다
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"loginId\":\"fxrec\",\"password\":\"1234\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me").header("Authorization", recToken)).andExpect(status().isUnauthorized());
        // 정지 풀기로 되돌릴 수 없다
        mvc.perform(post("/api/admin/users/" + rec.id + "/unsuspend").header("Authorization", tok(admin)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BANNED"));
        assertEquals(UserStatus.SUSPENDED, rec.status);
        mvc.perform(get("/api/admin/users/" + rec.id).header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.banned").value(true)).andExpect(jsonPath("$.eduDone").doesNotExist());
    }

    // ---------------- 대시보드 · 알림 설정 · 문구 ----------------
    @Test
    void 대시보드의_신고_대기_수는_안부_확인_접수를_따로_센다() throws Exception {
        JsonNode before = json(mvc.perform(get("/api/admin/dashboard").header("Authorization", tok(admin))).andExpect(status().isOk())).path("pending");
        long rep = before.path("reports").asLong(), wel = before.path("welfare").asLong();
        Request er = confirmed(mgr, "말동무·정서", 10.0);
        mvc.perform(post("/api/reports").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"REQUEST\",\"targetId\":" + er.id + ",\"type\":\"이상 징후(안부 확인)\",\"kind\":\"WELFARE\",\"content\":\"기운이 없어 보이셨어요\"}"))
                .andExpect(status().isOk());
        JsonNode mid = json(mvc.perform(get("/api/admin/dashboard").header("Authorization", tok(admin))).andExpect(status().isOk()));
        assertEquals(rep, mid.path("pending").path("reports").asLong(), "안부 확인 접수는 신고 대기에 세지 않는다");
        assertEquals(wel + 1, mid.path("pending").path("welfare").asLong());
        assertTrue(mid.path("edu").path("eduDone").isMissingNode(), "수료 수는 더 주지 않는다");
        reportOn(young);
        JsonNode after = json(mvc.perform(get("/api/admin/dashboard").header("Authorization", tok(admin))).andExpect(status().isOk())).path("pending");
        assertEquals(rep + 1, after.path("reports").asLong());
        assertEquals(wel + 1, after.path("welfare").asLong());
    }

    @Test
    void 음성_전화와_문자_알림은_일반_사용자만_켤_수_있다() throws Exception {
        for (User staff : List.of(mgr, admin)) {
            mvc.perform(put("/api/me/noti-settings").header("Authorization", tok(staff)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"voiceAlert\":true}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("USER_ONLY"));
            mvc.perform(put("/api/me/noti-settings").header("Authorization", tok(staff)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"smsAlert\":true}")).andExpect(status().isBadRequest());
            // 다른 알림 설정과 끄기는 된다
            mvc.perform(put("/api/me/noti-settings").header("Authorization", tok(staff)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"req\":false,\"voiceAlert\":false,\"smsAlert\":false}")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.req").value(false)).andExpect(jsonPath("$.voiceAlert").value(false));
            assertFalse(users.findById(staff.id).orElseThrow().voiceAlert);
        }
        mvc.perform(put("/api/me/noti-settings").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"voiceAlert\":true,\"smsAlert\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.voiceAlert").value(true)).andExpect(jsonPath("$.smsAlert").value(true));
    }

    @Test
    void 가입_때의_위치_동의와_마이_설정의_위치_정보는_같은_값이다() throws Exception {
        mvc.perform(put("/api/me").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON).content("{\"locConsent\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locConsent").value(true));
        mvc.perform(get("/api/me").header("Authorization", tok(rec))).andExpect(jsonPath("$.locConsent").value(true));
        mvc.perform(put("/api/me").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON).content("{\"locConsent\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locConsent").value(false));
        assertEquals(Boolean.FALSE, users.findById(rec.id).orElseThrow().locConsent);
    }

    @Test
    void 문의_답변_초안의_규칙_문구는_예정_시간_기준이다() {
        String all = String.join(" ", OpsSafetyService.RULES.stream().map(r -> r[2]).toList());
        assertFalse(all.contains("실제 시간만큼"));
        assertFalse(all.contains("활동 시간 × 가중치"));
        assertTrue(all.contains("예정 시간 기준"));
        assertTrue(all.contains("예정 시간 × 가중치"));
    }

    // ---------------- 알림 날짜·링크, 조사 ----------------
    @Test
    void 관심_공고와_가족_대신_공고_알림은_공고로_가고_날짜가_다른_알림과_같은_모양이다() throws Exception {
        String md = Rules.md(day);
        assertTrue(md.matches("\\d{1,2}/\\d{1,2}\\([월화수목금토일]\\)"), md);
        mvc.perform(post("/api/interest-alerts").header("Authorization", tok(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"area\":\"행복동\",\"categories\":[\"말동무·정서\"],\"dayType\":\"\"}")).andExpect(status().isOk());
        FamilyLink f = new FamilyLink();
        f.requesterId = young.id; f.targetId = rec.id; f.relation = "할머니"; f.status = LinkStatus.ACCEPTED;
        family.save(f);
        Post p = postSvc.create(young, new PostService.PostCmd(PostType.NEED, day, 10.0, 12.0, "행복동", List.of("말동무·정서"),
                "말벗", "이야기", null, 1.0, false, null, null, null, null), rec.id).get(0);
        Notification interest = notiOf(stranger, "INTEREST").get(0), fam = notiOf(rec, "FAMILY_POST").get(0);
        for (Notification n : List.of(interest, fam)) {
            assertEquals("/posts/" + p.id, n.link, "누르면 그 공고로");
            assertTrue(n.body.contains(md), n.body);
            assertFalse(n.body.contains(day.toString()), "2026-11-20 모양은 쓰지 않는다");
        }
    }

    @Test
    void 조사는_받침에_맞게_붙는다() throws Exception {
        assertEquals("동행은", Rules.josa("동행", "은", "는"));
        assertEquals("장보기·심부름은", Rules.josa("장보기·심부름", "은", "는"));
        assertEquals("가벼운 집안일을", Rules.josa("가벼운 집안일", "을", "를"));
        assertEquals("장보기를", Rules.josa("장보기", "을", "를"));
        assertEquals("확인 중으로", Rules.ro("확인 중"));
        assertEquals("답변 완료로", Rules.ro("답변 완료"));
        assertEquals("내일로", Rules.ro("내일"));
        assertEquals("토요일로", Rules.ro("토요일"));
        // 난이도 근거·문의 상태 알림에 "은(는)", "(으)로"가 남지 않는다
        String reason = String.valueOf(ai.difficulty("병원 진료 같이 가 주세요", List.of("동행"), 2.0).get("reason"));
        assertFalse(reason.contains("은(는)"), reason);
        Inquiry q = new Inquiry();
        q.userId = rec.id; q.type = "기타"; q.title = "문의"; q.content = "내용";
        inquiries.save(q);
        mvc.perform(put("/api/admin/inquiries/" + q.id + "/status").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CHECKING\"}")).andExpect(status().isOk());
        String title = notiOf(rec, "INQUIRY").get(0).title;
        assertTrue(title.contains("'확인 중'으로 바뀌었어요"), title);
    }

    // ---------------- 담당자 대상 상세 · 브리핑 ----------------
    @Test
    void 담당자_대상_상세의_요청은_최근_날짜순이다() throws Exception {
        for (int[] d : new int[][]{{5, 10}, {40, 10}, {1, 14}, {1, 9}, {20, 10}}) {
            Request r = new Request();
            r.kind = Kind.REGULAR; r.senderId = mgr.id; r.volunteerId = vol.id; r.elderId = elder.id; r.date = LocalDate.now().minusDays(d[0]);
            r.startHour = d[1]; r.hours = 1; r.category = "말동무·정서"; r.content = "말벗"; r.weight = 1; r.status = ReqStatus.DONE;
            requests.save(r);
        }
        JsonNode list = json(mvc.perform(get("/api/org/elders/" + elder.id).header("Authorization", tok(mgr))).andExpect(status().isOk())).path("requests");
        assertEquals(5, list.size());
        for (int i = 1; i < list.size(); i++) {
            String a = list.get(i - 1).path("date").asText(), b = list.get(i).path("date").asText();
            assertTrue(a.compareTo(b) >= 0, a + " → " + b);
            if (a.equals(b)) assertTrue(list.get(i - 1).path("startHour").asDouble() >= list.get(i).path("startHour").asDouble());
        }
        assertEquals(LocalDate.now().minusDays(1).toString(), list.get(0).path("date").asText());
        assertEquals(14.0, list.get(0).path("startHour").asDouble());
    }

    @Test
    void 방문_전_브리핑은_받는_분_나이대에_맞는_문장을_쓴다() {
        List<?> forYoung = (List<?>) actAi.briefing(vol, confirmed(young, "말동무·정서", 10.0).id).get("lines");
        assertTrue(forYoung.stream().noneMatch(l -> String.valueOf(l).contains("또박또박") || String.valueOf(l).contains("반복하셔도")), forYoung.toString());
        List<?> forUnknown = (List<?>) actAi.briefing(vol, confirmed(stranger, "말동무·정서", 12.0).id).get("lines");
        assertTrue(forUnknown.stream().noneMatch(l -> String.valueOf(l).contains("또박또박") || String.valueOf(l).contains("어르신")), forUnknown.toString());
        List<?> forSenior = (List<?>) actAi.briefing(vol, confirmed(rec, "말동무·정서", 14.0).id).get("lines");
        assertTrue(forSenior.stream().anyMatch(l -> String.valueOf(l).contains("또박또박")), forSenior.toString());
        List<?> forElder = (List<?>) actAi.briefing(vol, confirmed(mgr, "말동무·정서", 16.0).id).get("lines");
        assertTrue(forElder.stream().anyMatch(l -> String.valueOf(l).contains("또박또박")), forElder.toString());
    }

    // ---------------- 서버 시간대 ----------------
    @Test
    void 애플리케이션은_기본_시간대를_한국_시간으로_정한다() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            TimeSavingsApplication.useKoreaTime();
            assertEquals("Asia/Seoul", TimeZone.getDefault().getID());
            assertEquals(9 * 3600 * 1000, TimeZone.getDefault().getRawOffset());
        } finally {
            TimeZone.setDefault(before);
        }
    }
}
