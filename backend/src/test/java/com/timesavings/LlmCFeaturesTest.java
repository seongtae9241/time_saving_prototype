package com.timesavings;

import com.timesavings.ai.CareMemoAi;
import com.timesavings.ai.OrgAi;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 7.14 LLM 추가 C(담당자·운영자·안전) — 키 없는 규칙 모드. 실제 외부 AI 는 부르지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LlmCFeaturesTest {
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired NotificationRepo notis;
    @Autowired ReportRepo reports;
    @Autowired InquiryRepo inquiries;
    @Autowired RequestRepo requests;
    @Autowired RoleplayPersonaRepo personas;
    @Autowired CareNoteRepo careNotes;
    @Autowired RequestService reqs;
    @Autowired PostService postSvc;
    @Autowired AuthService auth;
    @Autowired CareSignalService signals;
    @Autowired OrgAi orgAi;
    @Autowired MockMvc mvc;

    User vol, mgr, mgr2, admin, rec;
    Elder elder;
    final LocalDate day = LocalDate.now().plusDays(3);

    User user(String id, Role role) {
        User u = new User();
        u.loginId = id; u.name = "이름" + id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "60대";
        u.idStatus = VerifyStatus.APPROVED; u.phone = "010-0000-" + (3000 + id.length());
        return users.save(u);
    }

    String tok(User u) { return "Bearer " + auth.issueToken(u, false); }

    @BeforeEach
    void setUp() {
        vol = user("cvol", Role.USER);
        rec = user("crec", Role.USER);
        mgr = user("cmgr", Role.ORG);
        mgr2 = user("cmgr2", Role.ORG);
        admin = user("cadmin", Role.ADMIN);
        elder = new Elder();
        elder.alias = "이순례"; elder.dong = "행복동"; elder.managerId = mgr.id; elder.needs = List.of("동행"); elder.ageGroup = "80대";
        elder.caution = "귀가 어두우셔서 천천히 크게 말해 주세요";
        elders.save(elder);
    }

    /** 대상 공고 → 제안 → 확정 → 시작 → 종료 → 일지(전달 메모) */
    Request logWith(String memo) {
        Post p = postSvc.create(mgr, new PostService.PostCmd(PostType.NEED, day, 10.0, 16.0, "행복동", List.of("말동무·정서"),
                "말벗", "이야기 나눠요", null, 1.0, false, null, elder.id, null, null)).get(0);
        Request r = reqs.create(vol, new RequestService.CreateCmd(Kind.PROPOSAL, p.id, null, null, day, 11.0, 1.0, null, "말벗 해 드릴게요", null));
        reqs.accept(mgr, r.id);
        reqs.checkIn(vol, r.id, r.checkCode, null);
        reqs.checkOut(vol, r.id, r.checkCode, null);
        return reqs.submitLog(vol, r.id, "이야기 나눔", "이야기를 나눴어요.", null, false, memo);
    }

    long signalNotis() { return notis.findAll().stream().filter(n -> mgr.id.equals(n.userId) && "CARE_SIGNAL".equals(n.type)).count(); }

    // ---------- C1 ----------
    @Test
    void C1_변화_신호는_2건_이상일_때만_확인_권장() throws Exception {
        logWith("손녀 이야기를 즐겁게 하셨어요. 다음에 사진첩 같이 보기로 했어요.");
        logWith("요즘 입맛이 없다며 점심을 거르셨대요.");
        assertFalse(signals.status(SubjectType.ELDER, elder.id, null).flagged()); // 1건은 아직
        assertEquals(0, signalNotis());
        logWith("기운이 없다고 하셔서 산책은 다음에 하자고 하셨어요.");
        CareSignalService.Signal s = signals.status(SubjectType.ELDER, elder.id, null);
        assertTrue(s.flagged());
        assertTrue(s.labels().containsAll(List.of("식사 거름", "기운 없음", "외출 감소")), s.labels().toString());
        assertEquals(1, signalNotis()); // 담당자에게 한 번
        logWith("오늘도 잠을 잘 못 주무셨대요.");
        assertEquals(1, signalNotis()); // 이미 켜져 있으면 다시 보내지 않음
        mvc.perform(get("/api/org/care-signals").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flagged").value(true)).andExpect(jsonPath("$[0].notice").value(CareSignalService.NOTICE));
        mvc.perform(get("/api/reports/recent?subject=elder:" + elder.id).header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.signal.flagged").value(true))
                .andExpect(jsonPath("$.items[0].careNote.life[0]").exists());
        // 다른 담당자는 볼 수 없다
        mvc.perform(get("/api/reports/recent?subject=elder:" + elder.id).header("Authorization", tok(mgr2))).andExpect(status().isForbidden());
    }

    @Test
    void C1_메모_정리에_진단_문장과_병명이_없다() {
        Request r = logWith("내과에서 당뇨 약 처방이 바뀌었다고 하셨어요. 치매 증상이 의심돼요. 다음 진료는 일주일 뒤예요. 다음 방문 때 약 봉투 위치 확인하기로 했어요. 점심을 거르셨대요.");
        CareNote n = careNotes.findByRequestId(r.id).orElseThrow();
        String all = String.join(" ", Objects.toString(n.nextSteps, ""), Objects.toString(n.hospital, ""), Objects.toString(n.lifeChanges, ""));
        assertTrue(n.hospital.startsWith("병원 안내 있음"), n.hospital);
        assertFalse(CareMemoAi.DIAGNOSIS.matcher(all).find(), all);
        for (String w : List.of("당뇨", "치매", "의심", "증상", "처방")) assertFalse(all.contains(w), w + " in " + all);
        assertTrue(n.signals.contains("식사 거름"));
        // 신호도 메모도 없으면 저장하지 않는다
        Request plain = logWith(null);
        assertTrue(careNotes.findByRequestId(plain.id).isEmpty());
    }

    // ---------- C2 ----------
    @Test
    void C2_통화_메모가_규칙_모드에서_3건으로_나뉜다() throws Exception {
        LocalDate today = LocalDate.now();
        Map<String, Object> out = orgAi.memoToPosts("이순례 님 매주 목요일 오전 말벗, 다음 주 화요일 오후 정형외과 동행, 냉장고 정리 한 번",
                "이순례", "행복동", "귀가 어두우세요", today);
        assertEquals("rule", out.get("source"));
        List<?> d = (List<?>) out.get("drafts");
        assertEquals(3, d.size());
        Map<?, ?> a = (Map<?, ?>) d.get(0), b = (Map<?, ?>) d.get(1), c = (Map<?, ?>) d.get(2);
        assertEquals(List.of("말동무·정서"), a.get("categories"));
        assertEquals(true, ((Map<?, ?>) a.get("repeat")).get("weekly"));
        assertEquals(DayOfWeek.THURSDAY, LocalDate.parse((String) a.get("date")).getDayOfWeek());
        assertEquals(9.0, a.get("startHour"));
        assertEquals(List.of("동행"), b.get("categories"));
        LocalDate nextTue = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).plusDays(1);
        assertEquals(nextTue.toString(), b.get("date"));
        assertEquals(13.0, b.get("startHour"));
        assertEquals(2.0, b.get("estHours"));
        assertEquals(List.of("생활 정리"), c.get("categories"));
        assertNull(c.get("date"));
        assertEquals(false, c.get("checked")); // 날짜를 골라야 등록 가능
        assertTrue(((List<?>) c.get("missing")).contains("date"));

        // 엔드포인트: 담당자 본인 대상만, 저장하지 않음(공고 수 그대로)
        long before = requests.count();
        mvc.perform(post("/api/org/ai/memo-to-posts").header("Authorization", tok(mgr)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":" + elder.id + ",\"memo\":\"이순례 님 매주 목요일 오전 말벗, 다음 주 화요일 오후 정형외과 동행, 냉장고 정리 한 번\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3)).andExpect(jsonPath("$.drafts[1].title").value("병원 동행 (정형외과)"));
        mvc.perform(post("/api/org/ai/memo-to-posts").header("Authorization", tok(mgr2)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"elderId\":" + elder.id + ",\"memo\":\"말벗\"}")).andExpect(status().isForbidden());
        assertEquals(before, requests.count());
    }

    @Test
    void C2_금지된_내용과_개인정보는_초안에서_막힌다() {
        Map<String, Object> out = orgAi.memoToPosts("내일 오후 목욕 도와주기, 010-1234-5678로 연락 주시고 다음 주 월요일 장보기", "이순례", "행복동", null, LocalDate.now());
        List<?> d = (List<?>) out.get("drafts");
        Map<?, ?> bath = (Map<?, ?>) d.get(0);
        assertEquals("BLOCK", ((Map<?, ?>) bath.get("filter")).get("level"));
        assertEquals(false, bath.get("checked"));
        assertTrue(((List<?>) out.get("piiRemoved")).contains("phone"));
        assertFalse(d.toString().contains("1234-5678"));
    }

    // ---------- C3 ----------
    @Test
    void C3_주간_브리핑은_코드가_세고_저장한_요약을_다시_쓴다() throws Exception {
        logWith("다음 방문 때 사진첩 보기로 했어요.");
        mvc.perform(get("/api/org/briefing").header("Authorization", tok(mgr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.logPending").value(1)).andExpect(jsonPath("$.counts.elders").value(1))
                .andExpect(jsonPath("$.cached").value(false)).andExpect(jsonPath("$.summary.source").value("rule"))
                .andExpect(jsonPath("$.summary.todo[0]").value(org.hamcrest.Matchers.containsString("일지 1건")));
        mvc.perform(get("/api/org/briefing").header("Authorization", tok(mgr))).andExpect(jsonPath("$.cached").value(true));
        // 큰 변화(확인 권장)가 생기면 다시 만든다
        logWith("점심을 거르셨대요.");
        logWith("기운이 없다고 하셨어요.");
        mvc.perform(get("/api/org/briefing").header("Authorization", tok(mgr))).andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.reason").value("change")).andExpect(jsonPath("$.counts.flagged").value(1));
        mvc.perform(get("/api/org/briefing?refresh=true").header("Authorization", tok(mgr))).andExpect(jsonPath("$.reason").value("refresh"));
        mvc.perform(get("/api/org/briefing").header("Authorization", tok(admin))).andExpect(status().isForbidden());
    }

    // ---------- C5 ----------
    @Test
    void C5_신고_심각도는_코드_규칙이_먼저() {
        assertEquals("high", OpsSafetyService.ruleSeverity("부적절한 언행", "자꾸 손을 잡으려고 했어요", 0).level());
        assertEquals("high", OpsSafetyService.ruleSeverity("연락처 교환·금전 요구", "돈을 빌려 달라고 했어요", 0).level());
        assertEquals("high", OpsSafetyService.ruleSeverity("기타", "가만 안 둬 하고 위협했어요", 0).level());
        assertEquals("high", OpsSafetyService.ruleSeverity("신체 접촉·방문 외 연락", "", 0).level());
        assertEquals("medium", OpsSafetyService.ruleSeverity("연락처 교환·금전 요구", "카톡 아이디를 계속 물어봐요", 0).level());
        assertEquals("medium", OpsSafetyService.ruleSeverity("노쇼", "약속 시간에 오지 않았어요", 0).level());
        assertEquals("low", OpsSafetyService.ruleSeverity("허위 공고", "공고 내용과 달랐어요", 0).level());
        assertEquals("medium", OpsSafetyService.ruleSeverity("허위 공고", "공고 내용과 달랐어요", 2).level()); // 같은 대상 반복
    }

    Report report(String type, String content, int daysAgo) { return report(type, content, daysAgo, vol); }

    Report report(String type, String content, int daysAgo, User target) {
        Report r = new Report();
        r.reporterId = rec.id; r.targetType = ReportTarget.USER; r.targetId = target.id; r.targetLabel = "봉사자"; r.type = type; r.content = content;
        r.createdAt = LocalDateTime.now().minusDays(daysAgo);
        return reports.save(r);
    }

    @Test
    void C5_신고_목록은_심각도_접수일_순이고_자동_제재는_없다() throws Exception {
        Report low = report("허위 공고", "시간이 달랐어요", 0, mgr2);
        Report high = report("부적절한 언행", "돈을 보내 달라고 했어요", 3);
        Report mid = report("노쇼", "오지 않았어요", 1);
        mvc.perform(post("/api/reports").header("Authorization", tok(rec)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"USER\",\"targetId\":" + vol.id + ",\"type\":\"신체 접촉·방문 외 연락\",\"content\":\"어깨를 만졌어요\"}")).andExpect(status().isOk());
        String body = mvc.perform(get("/api/admin/ai/report-triage").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].severity").value("high")).andExpect(jsonPath("$[1].severity").value("high"))
                .andExpect(jsonPath("$[1].reportId").value(high.id)) // 같은 높음이면 최근 접수 먼저
                .andExpect(jsonPath("$[2].reportId").value(mid.id)).andExpect(jsonPath("$[3].reportId").value(low.id))
                .andExpect(jsonPath("$[0].notice").exists()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"similar\""));
        mvc.perform(get("/api/admin/ai/report-triage").header("Authorization", tok(mgr))).andExpect(status().isForbidden());
        // 자동 제재 없음: 상태·경고 수 그대로
        assertTrue(reports.findAll().stream().allMatch(r -> r.status == ReportStatus.RECEIVED && r.action == ReportAction.NONE));
        assertEquals(0, users.findById(vol.id).orElseThrow().warningCount);
        assertEquals(UserStatus.ACTIVE, users.findById(vol.id).orElseThrow().status);
    }

    // ---------- C6 ----------
    @Test
    void C6_이상_적립_설명은_코드_근거를_쓰고_보류는_하지_않는다() throws Exception {
        for (int i = 0; i < 3; i++) {
            Request r = new Request();
            r.kind = Kind.REQUEST; r.senderId = rec.id; r.volunteerId = vol.id; r.recipientId = rec.id; r.date = LocalDate.now().minusDays(2 + i * 3);
            r.startHour = 14; r.hours = 1.0; r.category = "말동무·정서"; r.status = ReqStatus.DONE; r.earned = 1.0; r.weight = 1.0;
            r.logText = "이야기"; r.checkMethod = "manual";
            requests.save(r);
        }
        mvc.perform(get("/api/admin/ai/anomalies").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].evidence.count").value(3)).andExpect(jsonPath("$[0].evidence.topSlot").value("오후"))
                .andExpect(jsonPath("$[0].text").value(org.hamcrest.Matchers.containsString("3번")))
                .andExpect(jsonPath("$[0].checks[0]").value(org.hamcrest.Matchers.containsString("버튼 확인")))
                .andExpect(jsonPath("$[0].source").value("rule"));
        assertTrue(requests.findAll().stream().noneMatch(r -> r.anomalyHold));
    }

    // ---------- C7 ----------
    @Test
    void C7_문의_답변_초안은_자동_발송하지_않는다() throws Exception {
        Request r = logWith(null);
        r.status = ReqStatus.CHECKED_OUT; // 일지 전 상태처럼
        Inquiry q = new Inquiry();
        q.userId = vol.id; q.type = "적립 오류"; q.title = "적립 시간이 안 들어왔어요"; q.content = "지난주 활동했는데 시간이 안 들어왔어요.";
        inquiries.save(q);
        long before = notis.count();
        mvc.perform(post("/api/admin/ai/inquiries/" + q.id + "/draft").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.autoSend").value(false)).andExpect(jsonPath("$.source").value("rule"))
                .andExpect(jsonPath("$.draft").value(org.hamcrest.Matchers.containsString("일지")))
                .andExpect(jsonPath("$.basis[0].label").value(org.hamcrest.Matchers.containsString("이용 규칙")));
        Inquiry after = inquiries.findById(q.id).orElseThrow();
        assertEquals(InquiryStatus.RECEIVED, after.status);
        assertNull(after.answer);
        assertEquals(before, notis.count());
    }

    // ---------- C4 ----------
    @Test
    void C4_역할극_상대_초안은_가명_3턴이고_자동_등록하지_않는다() throws Exception {
        report("연락처 교환·금전 요구", "이순례 님이 봉투에 돈을 넣어 주셨어요", 1);
        long before = personas.count();
        mvc.perform(get("/api/admin/ai/persona-cases").header("Authorization", tok(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.cases[0].key").value("money"))
                .andExpect(jsonPath("$.cases[0].samples[0]").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("이순례"))));
        mvc.perform(post("/api/admin/ai/persona-draft").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"caseKey\":\"money\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.draft.name").value(org.hamcrest.Matchers.containsString("○")))
                .andExpect(jsonPath("$.draft.turns.length()").value(3)).andExpect(jsonPath("$.draft.turns[0].options.length()").value(4))
                .andExpect(jsonPath("$.draft.active").value(false)).andExpect(jsonPath("$.source").value("rule"));
        mvc.perform(post("/api/admin/ai/persona-draft").header("Authorization", tok(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"caseKey\":\"body\"}"))
                .andExpect(jsonPath("$.draft.fitCategory").value("동행"));
        assertEquals(before, personas.count());
    }
}
