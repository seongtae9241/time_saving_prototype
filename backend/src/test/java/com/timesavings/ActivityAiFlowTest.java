package com.timesavings;

import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 7.13 LLM 추가 B 서버 흐름(키 없음 = 규칙 모드, 메모리 H2). */
@SpringBootTest
@Transactional
class ActivityAiFlowTest {
    @Autowired UserRepo users;
    @Autowired ElderRepo elders;
    @Autowired RequestRepo requests;
    @Autowired LedgerRepo ledger;
    @Autowired MessageRepo messages;
    @Autowired ActivityAiNoteRepo notes;
    @Autowired RequestService reqs;
    @Autowired PostService postSvc;
    @Autowired BankService bank;
    @Autowired AuthService auth;
    @Autowired ActivityAiService svc;

    User vol, rec, mgr;
    Elder elder;
    final LocalDate day = LocalDate.now().plusDays(3);

    User user(String id, Role role, double earned) {
        User u = new User();
        u.loginId = id + "-b"; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동";
        u.idStatus = VerifyStatus.APPROVED; u.phone = "010-0000-" + (2000 + id.length());
        users.save(u);
        if (earned > 0) bank.add(u.id, LedgerType.EARN, earned, earned, 1.0, null, "초기 적립");
        return u;
    }

    @BeforeEach
    void setUp() {
        vol = user("volb", Role.USER, 0);
        rec = user("recb", Role.USER, 20);
        mgr = user("mgrb", Role.ORG, 0);
        elder = new Elder();
        elder.alias = "이순례"; elder.dong = "행복동"; elder.managerId = mgr.id; elder.needs = List.of("동행"); elder.caution = "걸음이 느리셔서 천천히 걸어 주세요";
        elders.save(elder);
    }

    Request confirmed(User sender, Long elderId, String cat) {
        Post p = postSvc.create(vol, new PostService.PostCmd(PostType.OFFER, day, 10.0, 18.0, "행복동",
                List.of(cat), "도와드려요", null, null, null, false, null, null, null, null)).get(0);
        Request r = reqs.create(sender, new RequestService.CreateCmd(Kind.REQUEST, p.id, null, elderId, day, 14.0, 2.0, cat, "병원 진료 동행", null));
        reqs.accept(vol, r.id);
        reqs.accept(sender, r.id);
        return r;
    }

    @Test
    void 방문_전_브리핑은_확정_때_한_번_만들고_지난_전달_메모는_넣지_않는다() {
        // 지난 활동: 같은 대상, 전달 메모에 건강 정보
        Request old = confirmed(mgr, elder.id, "동행");
        reqs.checkIn(vol, old.id, reqs.codeFor(old), "qr");
        reqs.checkOut(vol, old.id, reqs.codeFor(old), "qr");
        reqs.submitLog(vol, old.id, "접수 도움", null, null, false, "혈압약 처방 바뀜, 다음 재진 때 꼭 물어보기");
        assertEquals(1, notes.findByKindAndRequestId(ActivityAiService.BRIEFING, old.id).size());

        Request r = confirmed(mgr, elder.id, "동행");
        assertEquals(1, notes.findByKindAndRequestId(ActivityAiService.BRIEFING, r.id).size()); // 확정 때 저장
        Map<String, Object> b = svc.briefing(vol, r.id);
        String all = b.toString();
        assertFalse(all.contains("혈압"), all);
        assertFalse(all.contains("재진"), all);
        assertFalse(((Map<?, ?>) b.get("basics")).containsKey("careMemo"));
        List<?> lines = (List<?>) b.get("lines");
        assertTrue(lines.size() >= 3 && lines.size() <= 6);
        assertTrue(lines.get(0).toString().contains("이○례 님"));
        assertTrue(all.contains("천천히 걸어 주세요")); // 주의사항은 들어감
        assertEquals("rule", b.get("source"));
        svc.briefing(vol, r.id);
        assertEquals(1, notes.findByKindAndRequestId(ActivityAiService.BRIEFING, r.id).size()); // 다시 만들지 않음
        // 봉사자에게만
        assertThrows(ApiException.class, () -> svc.briefing(mgr, r.id));
    }

    @Test
    void 메시지_분류는_한_번만_하고_자동_차단하지_않는다() {
        Request r = confirmed(rec, null, "말동무·정서");
        Message m = reqs.sendMessage(vol, r.id, "번호 좀 알려 주세요. 앱 말고 따로 연락해요");
        assertNotNull(messages.findById(m.id).orElse(null)); // 저장됨(차단 없음)
        ActivityAiNote n = notes.findByKindAndRefIdAndRefKey(ActivityAiService.MESSAGE, m.id, "").orElseThrow();
        assertEquals("warn", n.warnLevel);
        Map<String, Object> a = svc.messageAssist(rec, r.id);
        List<?> warns = (List<?>) a.get("warns");
        assertEquals(1, warns.size());
        assertTrue(((Map<?, ?>) warns.get(0)).get("reasons").toString().contains("연락처 교환"));
        assertTrue(((List<?>) a.get("replies")).contains("연락은 앱 메시지로만 할게요"));
        svc.messageAssist(rec, r.id);
        assertEquals(1, notes.findByKindAndRequestId(ActivityAiService.MESSAGE, r.id).size()); // 메시지당 1회
        // 보낸 사람 화면에는 경고 띠가 없다
        assertTrue(((List<?>) svc.messageAssist(vol, r.id).get("warns")).isEmpty());
        // 제재 없음: 상태·경고 횟수 그대로, 메시지도 계속 보낼 수 있음
        User v = users.findById(vol.id).orElseThrow();
        assertEquals(UserStatus.ACTIVE, v.status);
        assertEquals(0, v.warningCount);
        assertEquals(0, v.penaltyCount);
        assertDoesNotThrow(() -> reqs.sendMessage(vol, r.id, "네 알겠어요"));
    }

    @Test
    void 일정_변경_메시지는_제안만_하고_시간은_그대로() {
        Request r = confirmed(rec, null, "말동무·정서");
        reqs.sendMessage(rec, r.id, "죄송해요, 1시간 늦게 와 주셔도 될까요?");
        Map<String, Object> a = svc.messageAssist(vol, r.id);
        Map<?, ?> sc = (Map<?, ?>) a.get("schedule");
        assertEquals("DELAY", sc.get("kind"));
        assertEquals(15.0, sc.get("newStartHour"));
        assertEquals(false, a.get("autoChange"));
        List<?> rp = (List<?>) a.get("replies");
        assertTrue(rp.size() >= 2 && rp.size() <= 3);
        Request again = requests.findById(r.id).orElseThrow();
        assertEquals(14.0, again.startHour);
        assertEquals(ReqStatus.CONFIRMED, again.status);
        // 내가 마지막에 보낸 메시지면 답장 후보 없음
        reqs.sendMessage(vol, r.id, "네 괜찮아요");
        assertTrue(((List<?>) svc.messageAssist(vol, r.id).get("replies")).isEmpty());
    }

    @Test
    void 시간_조정_판단_보조는_신청_때_만들고_표를_코드로_비교한다() {
        Request r = confirmed(rec, null, "말동무·정서");
        reqs.checkIn(vol, r.id, reqs.codeFor(r), "qr");
        reqs.checkOut(vol, r.id, reqs.codeFor(r), "qr");
        reqs.submitLog(vol, r.id, "이야기 나누고 산책, 병원 대기가 길었음", null, null, false);
        reqs.adjust(vol, r.id, 3.0, "대기가 길어 1시간 더 걸렸어요");
        assertEquals(1, notes.findByKindAndRequestId(ActivityAiService.ADJUST, r.id).size());
        Map<String, Object> j = svc.judge(rec, "ADJUST", r.id);
        List<?> t = (List<?>) j.get("table");
        assertTrue(t.size() >= 5);
        assertTrue(j.get("pro").toString().contains("대기"));
        assertEquals("운영자(또는 받는 분)가 정해요", j.get("decision"));
        assertEquals("PENDING", requests.findById(r.id).orElseThrow().adjustStatus); // 결정은 그대로 사람
        assertThrows(ApiException.class, () -> svc.judge(vol, "ADJUST", r.id));
        assertThrows(ApiException.class, () -> svc.judge(rec, "APPEAL", 1L));
    }

    @Test
    void 월별_결산_설명의_숫자는_통장과_같고_거래가_바뀌면_다시_만든다() {
        Request r = confirmed(rec, null, "동행");
        bank.add(vol.id, LedgerType.EARN, 3.0, 2.0, 1.5, r.id, "동행 적립");
        bank.add(vol.id, LedgerType.EARN, 1.0, 1.0, 1.0, null, "말동무 적립");
        Map<String, Object> a = svc.monthNote(vol, YearMonth.now().toString());
        Map<?, ?> nm = (Map<?, ?>) a.get("numbers");
        assertEquals(4.0, nm.get("earned"));
        assertEquals(1.0, nm.get("bonus"));
        double monthIn = (double) bank.monthly(vol.id, 1).get(0).get("in");
        assertEquals(monthIn, nm.get("earned")); // 통장 월별 결산 숫자와 같다
        assertTrue(a.get("text").toString().contains("4시간 쌓았어요"), a.get("text").toString());
        assertTrue(a.get("text").toString().contains("동행이 1.5배라 1시간 더"), a.get("text").toString());
        assertEquals("rule", a.get("source"));
        Object created = a.get("createdAt");
        assertEquals(created, svc.monthNote(vol, null).get("createdAt")); // 같은 달·같은 거래면 저장본
        bank.add(vol.id, LedgerType.EARN, 1.5, 1.0, 1.5, null, "추가");
        Map<String, Object> b = svc.monthNote(vol, null);
        assertEquals(5.5, ((Map<?, ?>) b.get("numbers")).get("earned"));
        assertTrue(b.get("text").toString().contains("5.5시간"));
        assertEquals(1, notes.countByKind(ActivityAiService.MONTH));
    }

    @Test
    void 적립_목표_플랜은_계산과_근처_공고를_돌려준다() {
        postSvc.create(rec, new PostService.PostCmd(PostType.NEED, day, 14.0, 16.0, "행복동",
                List.of("말동무·정서"), "말벗해 주세요", null, null, 1.0, false, null, null, null, null));
        Map<String, Object> g = svc.goalPlan(vol, new ActivityAiService.GoalCmd("동행", 1.0, 2.0, 5, 10, null));
        Map<?, ?> plan = (Map<?, ?>) g.get("plan");
        assertEquals(520.0, plan.get("totalNeeded")); // 동행은 기본 1.0(7.21): 2시간 × 52주 × 5년
        assertEquals(4.5, plan.get("monthlyTarget"));
        assertFalse(((List<?>) g.get("posts")).isEmpty());
        assertTrue(g.get("text").toString().contains("4.5시간씩"));
        assertEquals("rule", g.get("source"));
    }
}
