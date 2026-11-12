package com.timesavings;

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

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 요구사항 41판 — 계정·마이페이지·통장·알림 설정·연습 범위(A-01, A-06, B-43, B-46, B-54, B-55, B-56, B-58).
 * AI 키 없이 규칙 모드로 돈다(외부 호출 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Req41MeTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired RequestRepo requests;
    @Autowired NotificationRepo notis;
    @Autowired RoleplayPersonaRepo personas;
    @Autowired NotificationService noti;
    @Autowired RequestService reqs;
    @Autowired AuthService auth;

    User vol, rec, mgr, admin;
    Elder elder;

    User user(String id, Role role) {
        User u = new User();
        u.loginId = id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "50대";
        u.idStatus = VerifyStatus.APPROVED;
        return users.save(u);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    Request req(User volunteer, User recipient, Elder e, ReqStatus st, LocalDate date, String cat, double hours) {
        Request r = new Request();
        r.kind = Kind.REQUEST; r.senderId = recipient != null ? recipient.id : mgr.id; r.recipientId = recipient == null ? null : recipient.id;
        r.elderId = e == null ? null : e.id; r.volunteerId = volunteer.id;
        r.date = date; r.startHour = 14; r.hours = hours; r.category = cat; r.content = "도움";
        r.difficulty = Level.BASIC; r.weight = 1.0; r.status = st;
        if (st == ReqStatus.DONE) { r.earned = hours; r.doneAt = date.atTime(18, 0); }
        return requests.save(r);
    }

    long count(User u, String type) { return notis.findAll().stream().filter(n -> n.userId.equals(u.id) && type.equals(n.type)).count(); }

    @BeforeEach
    void setUp() {
        vol = user("r41vol", Role.USER);
        rec = user("r41rec", Role.USER);
        mgr = user("r41mgr", Role.ORG);
        admin = user("r41admin", Role.ADMIN);
        elder = new Elder();
        elder.alias = "이순례"; elder.dong = "행복동"; elder.managerId = mgr.id; elder.needs = List.of("동행");
        elders.save(elder);
    }

    // ---------- A-01 가입 약관 동의 ----------
    String signupBody(String id, String vt, String agree) {
        return "{\"loginId\":\"" + id + "\",\"password\":\"1234\",\"name\":\"새 회원\",\"phone\":\"010-7777-1234\",\"verifyToken\":\"" + vt + "\",\"area\":\"행복동\"" + agree + "}";
    }

    @Test
    void 가입은_필수_약관_2개가_있어야_하고_선택_동의를_저장한다() throws Exception {
        String vt = auth.verify("010-7777-1234", AuthService.DEMO_CODE);
        // 필수 동의가 없거나 하나만 있으면 거절
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody("r41new", vt, "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TERMS_REQUIRED"));
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody("r41new", vt, ",\"agreeTerms\":true,\"agreeLocation\":true")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TERMS_REQUIRED"));
        assertTrue(users.findByLoginId("r41new").isEmpty());
        // 필수 2개만으로 가입된다. 선택은 꺼진 채 저장
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody("r41new", vt, ",\"agreeTerms\":true,\"agreePrivacy\":true")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.locConsent").value(false)).andExpect(jsonPath("$.user.interestConsent").value(false))
                .andExpect(jsonPath("$.user.notiSettings.interest").value(false)).andExpect(jsonPath("$.user.notiSettings.req").value(true))
                .andExpect(jsonPath("$.user.accountNo", startsWith("TS-"))).andExpect(jsonPath("$.user.eduDone").doesNotExist());
        User u = users.findByLoginId("r41new").orElseThrow();
        assertNotNull(u.termsAgreedAt);
        assertNotNull(u.privacyAgreedAt);
        // 관심 공고 알림 받기에 동의하지 않았으니 그 알림은 만들어지지 않는다
        assertNull(noti.send(u.id, "INTEREST", "관심 조건에 맞는 새 공고가 올라왔어요", null, "/posts/1"));
        // 선택 2개까지 동의한 가입
        String vt2 = auth.verify("010-7777-1234", AuthService.DEMO_CODE);
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("r41new2", vt2, ",\"agreeTerms\":true,\"agreePrivacy\":true,\"agreeLocation\":true,\"agreeInterest\":true")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.locConsent").value(true)).andExpect(jsonPath("$.user.notiSettings.interest").value(true));
    }

    // ---------- B-58 · B-43 알림 설정, B-55 큰 글씨 ----------
    @Test
    void 알림_종류를_끄면_그_알림은_만들어지지_않고_담당자도_같은_방식으로_설정한다() throws Exception {
        mvc.perform(get("/api/me/noti-settings").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.req").value(true)).andExpect(jsonPath("$.msg").value(true)).andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.remind").value(true)).andExpect(jsonPath("$.interest").value(true)).andExpect(jsonPath("$.smsAlert").value(false));
        mvc.perform(put("/api/me/noti-settings").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"req\":false,\"msg\":false,\"smsAlert\":true,\"voiceAlert\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.req").value(false)).andExpect(jsonPath("$.msg").value(false)).andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.smsAlert").value(true)).andExpect(jsonPath("$.voiceAlert").value(true));
        assertNull(noti.send(vol.id, "NEW_REQUEST", "새 요청", null, "/requests/1"));
        assertNull(noti.send(vol.id, "NEW_REGULAR", "단골 요청", null, "/requests/1"));
        assertNull(noti.send(vol.id, "MESSAGE", "새 메시지", null, "/requests/1"));
        assertNotNull(noti.send(vol.id, "REJECTED", "거절됐어요", null, "/requests/1"));   // 켜 둔 종류
        assertNotNull(noti.send(vol.id, "CONFIRMED", "일정 확정", null, "/requests/1"));  // 끌 수 없는 종류
        assertEquals(0, count(vol, "NEW_REQUEST"));
        assertEquals(1, count(vol, "CONFIRMED"));
        // 담당자: 담당 독거노인 건의 제안 알림을 끈다
        mvc.perform(put("/api/me/noti-settings").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content("{\"req\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.req").value(false));
        Request r = req(vol, null, elder, ReqStatus.SENT, LocalDate.now().plusDays(2), "동행", 1);
        noti.toRecipientSide(r, "NEW_PROPOSAL", "봉사 제안", null);
        noti.toRecipientSide(r, "VOL_ACCEPTED", "수락", null);
        assertEquals(0, count(mgr, "NEW_PROPOSAL"));
        assertEquals(1, count(mgr, "VOL_ACCEPTED"));
        // B-55 큰 글씨는 서버에 저장되어 다음 접속(GET /api/me)에도 유지된다
        mvc.perform(put("/api/me").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON).content("{\"textSize\":\"BIG\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.textSize").value("BIG"));
        mvc.perform(get("/api/me").header("Authorization", tok(mgr))).andExpect(status().isOk()).andExpect(jsonPath("$.textSize").value("BIG"));
    }

    @Test
    void 리마인드는_시작_1시간_전에_한_번만_가고_끈_사람에게는_안_간다() {
        LocalDate d = LocalDate.now().plusDays(1);
        Request r = req(vol, rec, null, ReqStatus.CONFIRMED, d, "동행", 2); // 14시 시작
        rec.notiRemind = false;
        users.save(rec);
        assertEquals(0, reqs.remindAt(d.atTime(12, 0)));   // 2시간 전: 아직
        assertEquals(1, reqs.remindAt(d.atTime(13, 5)));   // 55분 전
        assertEquals(0, reqs.remindAt(d.atTime(13, 30)));  // 한 번만
        assertEquals(1, count(vol, "REMIND"));
        assertEquals(0, count(rec, "REMIND"));
        // 이미 시작 시각이 지난 일정에는 보내지 않는다
        Request late = req(vol, rec, null, ReqStatus.CONFIRMED, d, "동행", 1);
        late.reminded = false;
        assertEquals(0, reqs.remindAt(d.atTime(14, 10)));
        assertTrue(r.reminded);
    }

    // ---------- A-06 통장 번호, B-46 월별 횟수, B-56 확인서 ----------
    @Test
    void 통장_번호와_월별_횟수_요약과_기간별_확인서() throws Exception {
        LocalDate today = LocalDate.now();
        req(vol, rec, null, ReqStatus.DONE, today.minusDays(1), "동행", 2);
        req(vol, null, elder, ReqStatus.DONE, today.minusDays(1), "말동무·정서", 1);
        req(vol, null, elder, ReqStatus.DONE, today.minusDays(200), "동행", 1.5);
        req(rec, vol, null, ReqStatus.DONE, today.minusDays(1), "디지털 도움", 1); // vol 이 도움 받은 건
        req(vol, rec, null, ReqStatus.CANCELLED, today.minusDays(1), "동행", 1);
        String no = BankService.accountNo(vol.id);
        mvc.perform(get("/api/bank").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNo").value(no)).andExpect(jsonPath("$.monthly", hasSize(6)))
                .andExpect(jsonPath("$.totalEarned").exists()).andExpect(jsonPath("$.totalUsed").exists());
        mvc.perform(get("/api/my/activity-summary?months=12").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.months", hasSize(12))).andExpect(jsonPath("$.totalHelpCount").value(3)).andExpect(jsonPath("$.totalReceivedCount").value(1));
        // 전체 기간
        mvc.perform(get("/api/certificate").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3)).andExpect(jsonPath("$.totalHours").value(4.5)).andExpect(jsonPath("$.elderCount").value(2))
                .andExpect(jsonPath("$.eduDone").doesNotExist()).andExpect(jsonPath("$.accountNo").value(no))
                .andExpect(jsonPath("$.summarySource").value("rule")).andExpect(jsonPath("$.summary", containsString("3회")))
                .andExpect(jsonPath("$.summary", containsString("어르신을 도운 활동이 2회")));
        // 기간 선택: 최근 30일
        mvc.perform(get("/api/certificate?from=" + today.minusDays(30) + "&to=" + today).header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2)).andExpect(jsonPath("$.totalHours").value(3.0)).andExpect(jsonPath("$.elderCount").value(1))
                .andExpect(jsonPath("$.byCategory", hasSize(2))).andExpect(jsonPath("$.period").value(today.minusDays(30) + " ~ " + today));
        // 활동이 없는 기간, 잘못된 기간
        mvc.perform(get("/api/certificate?from=" + today.plusDays(1)).header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0)).andExpect(jsonPath("$.summary").doesNotExist());
        mvc.perform(get("/api/certificate?from=" + today + "&to=" + today.minusDays(3)).header("Authorization", tok(vol))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/certificate?from=11월").header("Authorization", tok(vol))).andExpect(status().isBadRequest());
    }

    // ---------- B-54 첫 활동 연습 ----------
    RoleplayPersona persona(String turnsJson) {
        RoleplayPersona p = new RoleplayPersona();
        p.name = "김○자 님"; p.initial = "자"; p.ageLabel = "80세 · 어르신"; p.trait = "말수가 적으세요"; p.goal = "규칙을 지키며 따뜻하게"; p.opening = "어서 와요.";
        p.turnsJson = turnsJson;
        return personas.save(p);
    }

    @Test
    void 첫_활동_연습은_선택이고_수료가_없으며_위반하면_바꿔_말하기를_알려_준다() throws Exception {
        RoleplayPersona p = persona("[]"); // 대본 없음 → 서버 설정의 상황 목록으로 진행(키 없음)
        mvc.perform(get("/api/edu").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.optional").value(true)).andExpect(jsonPath("$.completed").doesNotExist())
                .andExpect(jsonPath("$.situations", hasSize(4))).andExpect(jsonPath("$.situations[0].title").value("연락처를 물어볼 때"))
                .andExpect(jsonPath("$.situations[0].options", hasSize(2))).andExpect(jsonPath("$.situations[0].bad").doesNotExist());
        // 상황 하나 연습(프로토타입): 부탁을 들어준 답 → 이렇게 바꿔 말해 보세요, 규칙을 지킨 답 → 칭찬
        mvc.perform(post("/api/edu/practice").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"money\",\"text\":\"네, 일단 제가 내 드릴게요.\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.flag").value("금전·선물"))
                .andExpect(jsonPath("$.feedback.reason", containsString("금전 거래는 안 돼요")))
                .andExpect(jsonPath("$.feedback.better", containsString("장 보는 걸 같이")));
        mvc.perform(post("/api/edu/practice").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"privacy\",\"text\":\"네 저는 행복아파트 살아요\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.feedback.violation").value(true));
        mvc.perform(post("/api/edu/practice").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"contact\",\"text\":\"번호는 서로 못 주고받게 돼 있어요. 필요하시면 앱에서 공고로 다시 불러 주세요.\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.praise", containsString("잘했어요"))).andExpect(jsonPath("$.feedback").doesNotExist());
        mvc.perform(post("/api/edu/practice").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"none\",\"text\":\"네\"}")).andExpect(status().isNotFound());
        // 수료 API 는 없다
        mvc.perform(post("/api/edu/complete").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(result -> assertNotEquals(200, result.getResponse().getStatus()));
        // 1번째 답: 무난 → 어르신이 첫 상황(연락처)을 던진다
        mvc.perform(post("/api/ai/roleplay").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personaId\":" + p.id + ",\"history\":[{\"who\":\"them\",\"text\":\"어서 와요.\"}],\"text\":\"안녕하세요, 시간 적금에서 온 봉사자예요. 오늘 잘 부탁드려요.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quality").value("good")).andExpect(jsonPath("$.feedback").doesNotExist())
                .andExpect(jsonPath("$.next", containsString("전화번호"))).andExpect(jsonPath("$.done").value(false)).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.source").value("rule"));
        // 2번째 답: 연락처를 알려 줌 → 위반 알림 + 바꿔 말하기
        String h2 = "[{\"who\":\"them\",\"text\":\"어서 와요.\"},{\"who\":\"me\",\"text\":\"안녕하세요\"},{\"who\":\"them\",\"text\":\"전화번호 좀 적어 주고 가.\"}]";
        mvc.perform(post("/api/ai/roleplay").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personaId\":" + p.id + ",\"history\":" + h2 + ",\"text\":\"네, 제 번호는 010-1234-5678 이에요.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quality").value("bad")).andExpect(jsonPath("$.flag").value("연락처"))
                .andExpect(jsonPath("$.feedback.violation").value(true)).andExpect(jsonPath("$.feedback.message", containsString("안전 규칙")))
                .andExpect(jsonPath("$.feedback.better", containsString("단골")));
        // 마치기: 통과·수료 없이 피드백만
        mvc.perform(post("/api/edu/roleplay/" + p.id + "/finish").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"log\":[{\"who\":\"me\",\"text\":\"안녕하세요, 시간 적금에서 온 봉사자예요. 오늘 잘 부탁드려요.\"},{\"who\":\"me\",\"text\":\"네, 제 번호는 010-1234-5678 이에요.\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.safe").value(false)).andExpect(jsonPath("$.pass").doesNotExist())
                .andExpect(jsonPath("$.fixes", hasSize(1))).andExpect(jsonPath("$.fixes[0].violation").value(true))
                .andExpect(jsonPath("$.good.text", containsString("봉사자예요")));
        // 연습을 했든 안 했든 내 정보·프로필에 수료 표시가 없다
        mvc.perform(get("/api/me").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.eduDone").doesNotExist()).andExpect(jsonPath("$.verify.eduDone").doesNotExist());
        assertFalse(users.findById(vol.id).orElseThrow().eduDone);
    }

    @Test
    void 연습_상황_목록은_운영자가_서버_설정으로_바꾼다() throws Exception {
        String body = "{\"items\":[{\"title\":\"선물을 주실 때\",\"line\":\"이거 내가 담근 김치인데 한 통 가져가.\",\"flag\":\"금전·선물\"},{\"title\":\"약을 부탁할 때\",\"line\":\"이 약 좀 대신 먹여 줘.\"}]}";
        mvc.perform(put("/api/admin/edu/situations").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/edu/situations").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.items[1].key").value("s2"));
        mvc.perform(put("/api/admin/edu/situations").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"title\":\"가\",\"line\":\"짧\"}]}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/edu/situations").header("Authorization", tok(vol))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.items[0].title").value("선물을 주실 때"));
        mvc.perform(get("/api/admin/edu/situations").header("Authorization", tok(vol))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/edu/situations").header("Authorization", tok(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].flag").value("금전·선물"));
        // 바뀐 상황이 연습 대화에 쓰인다
        RoleplayPersona p = persona("[]");
        mvc.perform(post("/api/ai/roleplay").header("Authorization", tok(vol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personaId\":" + p.id + ",\"history\":[],\"text\":\"안녕하세요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.next", containsString("김치")));
    }

    @Test
    void 빈_시간_후보는_공고_범위_안에서_겹치지_않는_30분_단위() {
        assertEquals(List.of(15.0), PlannerService.freeStarts(14, 16, 1, List.of(new double[]{14, 15})));
        assertEquals(List.of(9.0, 9.5, 10.0), PlannerService.freeStarts(9, 12, 2, List.of()));
        assertEquals(List.of(), PlannerService.freeStarts(9, 10, 1, List.of(new double[]{9.5, 11})));
        assertEquals(List.of(13.0, 16.0), PlannerService.freeStarts(13, 17, 1, List.of(new double[]{14, 16})).stream().filter(x -> x == 13.0 || x == 16.0).toList());
    }
}
