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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 요구사항 41판 — 활동 진행·안전·신고(B-51~B-53, C-11, B-36·B-37·D-02, B-13, B-25).
 * AI 없이 규칙 모드, 메모리 H2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Req41ActivityTest {
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired RequestRepo requests;
    @Autowired NotificationRepo notis;
    @Autowired ReportRepo reports;
    @Autowired RequestService reqs;
    @Autowired PostService postSvc;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired Views views;
    @Autowired SchedulerService scheduler;
    @Autowired MockMvc mvc;

    User vol, rec, mgr, admin, stranger;
    Elder elder;
    final LocalDate day = LocalDate.now().plusDays(3);

    User user(String id, Role role, double earned) {
        User u = new User();
        u.loginId = "r41" + id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동";
        u.idStatus = VerifyStatus.APPROVED; u.phone = "010-0000-" + (2000 + id.length());
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, "초기 적립");
        return u;
    }

    @BeforeEach
    void setUp() {
        vol = user("vol", Role.USER, 0);
        rec = user("rec", Role.USER, 20);
        stranger = user("stranger", Role.USER, 20);
        mgr = user("mgr", Role.ORG, 0);
        admin = user("admin", Role.ADMIN, 0);
        elder = new Elder();
        elder.alias = "이순례"; elder.dong = "행복동"; elder.ageGroup = "80대"; elder.managerId = mgr.id; elder.needs = List.of("동행");
        elders.save(elder);
    }

    Post offer() {
        return postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day, 13.0, 17.0, "행복동",
                List.of("동행", "말동무·정서"), "도와드려요", null, null, null, false, null, null, null, null)).get(0);
    }

    /** 일정 확정까지. elderId 가 있으면 담당자가 대신 요청. */
    Request confirmed(Long elderId) {
        User sender = elderId == null ? rec : mgr;
        Request r = reqs.create(sender, new RequestService.CreateCmd(Kind.REQUEST, offer().id, null, elderId, day, 13.0, 2.0, "동행", "병원 진료 동행", null));
        reqs.accept(vol, r.id);
        reqs.accept(sender, r.id);
        assertEquals(ReqStatus.CONFIRMED, r.status);
        return r;
    }

    List<String> types(User u) {
        return notis.findByUserIdOrderByCreatedAtDescIdDesc(u.id).stream().map(n -> n.type).toList();
    }

    long count(User u, String type) { return types(u).stream().filter(type::equals).count(); }

    // ---------------- B-51 QR 30초 갱신 ----------------
    @Test
    void 시각_기반_코드는_구간마다_바뀌고_같은_구간에서는_같다() {
        String a = Rules.rotatingCode("secret-a", 100), b = Rules.rotatingCode("secret-a", 101);
        assertEquals(6, a.length());
        assertTrue(a.matches("\\d{6}"));
        assertEquals(a, Rules.rotatingCode("secret-a", 100));
        // 구간·비밀값이 다르면 (거의 항상) 다른 번호: 연속 20구간이 전부 같을 수는 없다
        long same = java.util.stream.LongStream.range(0, 20).filter(w -> Rules.rotatingCode("secret-a", w).equals(a)).count();
        assertTrue(same < 20);
        assertNotNull(b);
        assertEquals(0, Rules.qrWindow(29_999));
        assertEquals(1, Rules.qrWindow(30_000));
        assertEquals(30, Rules.qrLeftSec(0));
        assertEquals(1, Rules.qrLeftSec(29_000));
    }

    @Test
    void 일반_건은_지금_또는_직전_구간_코드만_받는다() {
        Request r = confirmed(null);
        assertNotNull(r.qrSecret);
        long w = Rules.qrWindow(System.currentTimeMillis());
        String now = Rules.rotatingCode(r.qrKey(), w), prev = Rules.rotatingCode(r.qrKey(), w - 1), old = Rules.rotatingCode(r.qrKey(), w - 3);
        if (!old.equals(now) && !old.equals(prev)) {
            ApiException e = assertThrows(ApiException.class, () -> reqs.checkIn(vol, r.id, old, "qr")); // 지난 코드는 못 쓴다
            assertEquals("WRONG_CODE", e.code);
        }
        // 확정 때 만든 예전 고정 번호도 더는 통하지 않는다
        if (!r.checkCode.equals(now) && !r.checkCode.equals(prev))
            assertThrows(ApiException.class, () -> reqs.checkIn(vol, r.id, r.checkCode, "code"));
        reqs.checkIn(vol, r.id, prev, "qr"); // 찍는 사이 바뀌어도 직전 구간까지는 받는다
        assertEquals(ReqStatus.IN_PROGRESS, r.status);
        assertEquals("qr", r.checkMethod);
        reqs.checkOut(vol, r.id, reqs.codeFor(r), "code"); // 번호 6자리로도 된다
        assertEquals(ReqStatus.CHECKED_OUT, r.status);
    }

    @Test
    void 받는_쪽_화면만_QR을_받아_가고_30초_남은_시간이_온다() throws Exception {
        Request r = confirmed(null);
        Map<String, Object> q = reqs.qrInfo(rec, r.id);
        assertEquals(reqs.codeFor(r).length(), ((String) q.get("code")).length());
        assertTrue(((String) q.get("qrText")).startsWith("TS1|REQ|" + r.id + "|"));
        int left = (Integer) q.get("leftSec");
        assertTrue(left >= 1 && left <= 30);
        assertThrows(ApiException.class, () -> reqs.qrInfo(vol, r.id)); // 봉사자는 받는 분 코드를 볼 수 없다
        assertNull(views.requestView(r, vol).get("checkCode"));
        assertNotNull(views.requestView(r, rec).get("qrLeftSec"));
        mvc.perform(get("/api/requests/" + r.id + "/qr").header("Authorization", "Bearer " + auth.issueToken(rec, false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").exists()).andExpect(jsonPath("$.stepSec").value(30));
        mvc.perform(get("/api/requests/" + r.id + "/qr").header("Authorization", "Bearer " + auth.issueToken(vol, false)))
                .andExpect(status().isForbidden());
    }

    // ---------------- B-52 지각·노쇼 감지 ----------------
    @Test
    void 지각은_10분_노쇼_의심은_30분에_양쪽으로_한_번씩() {
        Request r = confirmed(null);
        LocalDateTime start = r.startAt();
        assertEquals(0, reqs.lateCheck(start.plusMinutes(9)));
        assertEquals(1, reqs.lateCheck(start.plusMinutes(10)));
        assertEquals(1, count(vol, "LATE"));
        assertEquals(1, count(rec, "LATE"));
        assertEquals(0, reqs.lateCheck(start.plusMinutes(20))); // 같은 단계는 다시 보내지 않는다
        assertEquals(1, reqs.lateCheck(start.plusMinutes(30)));
        assertEquals(1, count(vol, "NOSHOW_SUSPECT"));
        assertEquals(1, count(rec, "NOSHOW_SUSPECT"));
        assertEquals(0, reqs.lateCheck(start.plusMinutes(45)));
        assertEquals(1, count(vol, "LATE"));
        assertTrue(r.lateNotified && r.noshowNotified);
    }

    @Test
    void 독거노인_건의_지각_노쇼_의심은_담당자가_받는다() {
        Request r = confirmed(elder.id);
        reqs.lateCheck(r.startAt().plusMinutes(11));
        reqs.lateCheck(r.startAt().plusMinutes(31));
        assertEquals(1, count(mgr, "LATE"));
        assertEquals(1, count(mgr, "NOSHOW_SUSPECT"));
        assertEquals(1, count(vol, "NOSHOW_SUSPECT"));
    }

    @Test
    void 시작한_활동에는_지각_알림이_가지_않는다() {
        Request r = confirmed(null);
        reqs.checkIn(vol, r.id, reqs.codeFor(r), "qr");
        assertEquals(0, reqs.lateCheck(r.startAt().plusMinutes(40)));
        assertEquals(0, count(rec, "LATE") + count(rec, "NOSHOW_SUSPECT"));
    }

    @Test
    void 노쇼_신고는_시작_30분이_지나야_한다() {
        Request r = confirmed(null);
        LocalDateTime t = LocalDateTime.now().minusMinutes(20); // 20분 전 시작
        r.date = t.toLocalDate(); r.startHour = t.getHour() + t.getMinute() / 60.0;
        assertEquals("TOO_EARLY", assertThrows(ApiException.class, () -> reqs.noshow(rec, r.id)).code);
        assertFalse(views.actions(r, "RECIPIENT", false).contains("noshow"));
        LocalDateTime t2 = LocalDateTime.now().minusMinutes(31);
        r.date = t2.toLocalDate(); r.startHour = t2.getHour() + t2.getMinute() / 60.0;
        assertTrue(views.actions(r, "RECIPIENT", false).contains("noshow"));
        reqs.noshow(rec, r.id);
        assertEquals(ReqStatus.NOSHOW, r.status);
        assertEquals(1, users.findById(vol.id).orElseThrow().penaltyCount);
        assertEquals(0.0, bank.summary(rec.id).hold()); // 보류된 시간은 돌아온다
    }

    // ---------------- B-53 자동 마감 · B-51 30분 차이 안내 ----------------
    @Test
    void 종료_QR이_없으면_예정_종료_30분_뒤_예정_시각으로_마감하고_일지_알림() {
        Request r = confirmed(null);
        reqs.checkIn(vol, r.id, reqs.codeFor(r), "qr");
        r.checkInAt = r.startAt().plusMinutes(2);
        LocalDateTime end = r.endAt();
        assertEquals(day.atTime(15, 0), end);
        assertEquals(0, reqs.autoCloseDue(end.plusMinutes(29)));
        assertEquals(ReqStatus.IN_PROGRESS, r.status);
        Map<String, Object> out = scheduler.runAll(end.plusMinutes(30));
        assertEquals(1, out.get("autoClosed"));
        assertEquals(ReqStatus.CHECKED_OUT, r.status);
        assertEquals(end, r.checkOutAt);       // 마감 시각은 예정 종료 시각
        assertTrue(r.autoClosed);
        assertEquals(1, count(vol, "AUTO_CLOSED")); // 일지 작성 알림
        assertEquals(1, count(rec, "AUTO_CLOSED"));
        assertEquals(0, count(vol, "TIME_GAP"));    // 2분 차이는 안내하지 않는다
        assertEquals(Boolean.TRUE, views.requestView(r, vol).get("autoClosed"));
        assertTrue(views.actions(r, "VOLUNTEER", false).contains("log"));
        reqs.submitLog(vol, r.id, "병원에 함께 다녀옴", null, null, false);
        assertEquals(ReqStatus.LOG_SUBMITTED, r.status);
        reqs.confirm(rec, r.id);
        assertEquals(2.0, r.earned); // 적립은 예정 시간(2시간 × 1.0) 기준 그대로
    }

    @Test
    void 기록된_시간이_예정과_30분_이상_다르면_양쪽에_한_번_안내() {
        Request r = confirmed(null);
        reqs.checkIn(vol, r.id, reqs.codeFor(r), "qr");
        r.checkInAt = r.startAt().plusMinutes(40); // 40분 늦게 시작 → 자동 마감하면 80분(예정 120분)
        reqs.autoCloseDue(r.endAt().plusMinutes(31));
        assertEquals(-40L, RequestService.gapMinutes(r));
        assertEquals(1, count(vol, "TIME_GAP"));
        assertEquals(1, count(rec, "TIME_GAP"));
        assertTrue(r.gapNotified);
        assertEquals(-40L, views.requestView(r, rec).get("gapMinutes"));
    }

    @Test
    void 종료를_바로_찍어_예정보다_짧으면_안내가_간다() {
        Request r = confirmed(null);
        reqs.checkIn(vol, r.id, reqs.codeFor(r), "qr");
        reqs.checkOut(vol, r.id, reqs.codeFor(r), "qr"); // 0분 활동 (예정 2시간)
        assertEquals(1, count(vol, "TIME_GAP"));
        assertEquals(1, count(rec, "TIME_GAP"));
    }

    // ---------------- C-11 도움 대상 현관 QR 카드 ----------------
    @Test
    void 독거노인_건은_대상별_현관_카드로_시작하고_다시_발급하면_이전_카드는_못_쓴다() {
        Request r = confirmed(elder.id);
        Elder e = elders.findById(elder.id).orElseThrow();
        assertNotNull(e.qrCode);            // 확정될 때 카드가 준비된다
        assertNotNull(e.qrIssuedAt);
        String card = e.qrCode;
        String rotating = Rules.rotatingCode(r.qrKey(), Rules.qrWindow(System.currentTimeMillis()));
        if (!rotating.equals(card)) assertThrows(ApiException.class, () -> reqs.checkIn(vol, r.id, rotating, "qr"));
        reqs.checkIn(vol, r.id, card, "qr");
        assertEquals(ReqStatus.IN_PROGRESS, r.status);

        reqs.reissueCard(e);
        assertNotEquals(card, e.qrCode);
        assertEquals("WRONG_CODE", assertThrows(ApiException.class, () -> reqs.checkOut(vol, r.id, card, "qr")).code); // 이전 카드
        reqs.checkOut(vol, r.id, e.qrCode, "qr");
        assertEquals(ReqStatus.CHECKED_OUT, r.status);

        // 같은 대상의 다음 활동도 같은 카드로
        Request r2 = confirmed(elder.id);
        reqs.checkIn(vol, r2.id, e.qrCode, "code");
        assertEquals(ReqStatus.IN_PROGRESS, r2.status);
    }

    @Test
    void 현관_카드_보기와_재발급은_담당자만_HTTP() throws Exception {
        String t = auth.issueToken(mgr, false);
        mvc.perform(get("/api/org/elders/" + elder.id + "/qr-card").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.qrText").value(org.hamcrest.Matchers.startsWith("TS1|ELD|" + elder.id + "|")));
        String first = elders.findById(elder.id).orElseThrow().qrCode;
        mvc.perform(post("/api/org/elders/" + elder.id + "/qr-card/reissue").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(first)));
        mvc.perform(get("/api/org/elders/" + elder.id + "/qr-card").header("Authorization", "Bearer " + auth.issueToken(vol, false)))
                .andExpect(status().isForbidden());
        User mgr2 = user("mgr2", Role.ORG, 0);
        mvc.perform(post("/api/org/elders/" + elder.id + "/qr-card/reissue").header("Authorization", "Bearer " + auth.issueToken(mgr2, false)))
                .andExpect(status().isForbidden());
    }

    // ---------------- B-13 독거노인 정보 노출 범위 ----------------
    @Test
    @SuppressWarnings("unchecked")
    void 봉사자에게는_나이대와_카드_번호가_가지_않는다() {
        Request r = confirmed(elder.id);
        Map<String, Object> forVol = (Map<String, Object>) views.requestView(r, vol).get("elder");
        assertFalse(forVol.containsKey("ageGroup"));
        assertFalse(forVol.containsKey("alias"));
        assertEquals("이○례 님", forVol.get("displayName"));
        assertNull(views.requestView(r, vol).get("checkCode"));
        Map<String, Object> mv = views.requestView(r, mgr);
        assertEquals("80대", ((Map<String, Object>) mv.get("elder")).get("ageGroup"));
        assertEquals(Boolean.TRUE, mv.get("elderCard"));
        assertEquals(elders.findById(elder.id).orElseThrow().qrCode, mv.get("checkCode"));
    }

    // ---------------- B-25 응답 전 취소 ----------------
    @Test
    void 보낸_요청은_상대_응답_전에_보낸_쪽만_취소한다() {
        Request r = reqs.create(rec, new RequestService.CreateCmd(Kind.REQUEST, offer().id, null, null, day, 13.0, 2.0, "동행", "병원 진료 동행", null));
        assertTrue(views.actions(r, "RECIPIENT", false, true).contains("cancel"));
        assertFalse(views.actions(r, "VOLUNTEER", false, false).contains("cancel"));
        assertThrows(ApiException.class, () -> reqs.cancel(vol, r.id, "받은 쪽"));
        assertEquals(ReqStatus.SENT, r.status);
        reqs.cancel(rec, r.id, "일정이 생겼어요");
        assertEquals(ReqStatus.CANCELLED, r.status);
        assertEquals(0, users.findById(rec.id).orElseThrow().penaltyCount);
    }

    // ---------------- B-36·B-37·D-02 신고·안부 확인 ----------------
    String json(String s) { return s.replace('\'', '"'); }

    @Test
    void 이상_징후_접수는_담당자에게_알리고_운영자_목록에_제재_없는_기록으로_남는다() throws Exception {
        Request r = confirmed(elder.id);
        String tv = auth.issueToken(vol, false), ta = auth.issueToken(admin, false);
        mvc.perform(post("/api/reports").header("Authorization", "Bearer " + tv).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'targetType':'REQUEST','targetId':" + r.id + ",'type':'이상 징후(안부 확인)','kind':'WELFARE','content':'점심을 거르셨고 기운이 없어 보이셨어요'}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("WELFARE")).andExpect(jsonPath("$.elderId").value(elder.id));
        assertEquals(1, count(mgr, "WELFARE"));   // 그 대상의 담당자에게
        assertEquals(1, count(admin, "REPORT"));  // 운영자 목록 기록
        Report rp = reports.findAll().stream().filter(Report::welfare).findFirst().orElseThrow();
        assertFalse(rp.targetLabel.contains("이순례")); // 별칭 원문은 운영자 목록에 싣지 않는다

        mvc.perform(get("/api/admin/reports").header("Authorization", "Bearer " + ta))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + rp.id + ")].welfare").value(true))
                .andExpect(jsonPath("$[?(@.id==" + rp.id + ")].targetUser").value(org.hamcrest.Matchers.contains((Object) null)));
        // 제재는 할 수 없다
        mvc.perform(post("/api/admin/reports/" + rp.id + "/handle").header("Authorization", "Bearer " + ta).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'action':'SUSPEND'}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_SANCTION"));
        mvc.perform(post("/api/admin/reports/" + rp.id + "/handle").header("Authorization", "Bearer " + ta).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'action':'NONE'}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.result").value("접수 기록 확인"));
        assertEquals(UserStatus.ACTIVE, users.findById(vol.id).orElseThrow().status);
        assertEquals(0, users.findById(vol.id).orElseThrow().warningCount);
        assertEquals(1, count(vol, "REPORT_RESULT"));
    }

    @Test
    void 이상_징후는_독거노인_활동의_당사자만_내용과_함께_접수한다() throws Exception {
        Request general = confirmed(null);
        Request er = confirmed(elder.id);
        String tv = auth.issueToken(vol, false);
        mvc.perform(post("/api/reports").header("Authorization", "Bearer " + tv).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'targetType':'REQUEST','targetId':" + general.id + ",'type':'이상 징후(안부 확인)','content':'걱정돼요'}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_ELDER_ACTIVITY"));
        mvc.perform(post("/api/reports").header("Authorization", "Bearer " + tv).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'targetType':'REQUEST','targetId':" + er.id + ",'type':'이상 징후(안부 확인)','content':' '}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONTENT_REQUIRED"));
        mvc.perform(post("/api/reports").header("Authorization", "Bearer " + auth.issueToken(stranger, false)).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'targetType':'REQUEST','targetId':" + er.id + ",'type':'이상 징후(안부 확인)','content':'걱정돼요'}")))
                .andExpect(status().isForbidden());
        assertEquals(0, reports.findAll().stream().filter(Report::welfare).count());
    }

    @Test
    void 활동_상대를_직접_신고할_수_있고_일반_신고는_제재가_된다() throws Exception {
        String tr = auth.issueToken(rec, false), ta = auth.issueToken(admin, false);
        mvc.perform(post("/api/reports").header("Authorization", "Bearer " + tr).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'targetType':'USER','targetId':" + vol.id + ",'type':'부적절한 언행','content':'반말을 했어요'}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").doesNotExist()).andExpect(jsonPath("$.targetLabel").value("활동 상대 · vol 님"));
        mvc.perform(post("/api/reports").header("Authorization", "Bearer " + tr).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'targetType':'USER','targetId':" + rec.id + ",'type':'부적절한 언행'}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/reports").header("Authorization", "Bearer " + tr).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'targetType':'USER','targetId':99999999,'type':'부적절한 언행'}")))
                .andExpect(status().isBadRequest());
        Report rp = reports.findAll().stream().filter(x -> x.targetType == ReportTarget.USER).findFirst().orElseThrow();
        mvc.perform(post("/api/admin/reports/" + rp.id + "/handle").header("Authorization", "Bearer " + ta).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'action':'WARNING'}")))
                .andExpect(status().isOk());
        assertEquals(1, users.findById(vol.id).orElseThrow().warningCount);
    }
}
