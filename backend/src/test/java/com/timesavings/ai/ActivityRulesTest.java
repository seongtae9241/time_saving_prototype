package com.timesavings.ai;

import com.timesavings.service.ActivityAiService;
import com.timesavings.service.ActivityAiService.GoalCmd;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 7.13 B 코드 판정: 일정 변경 감지·메시지 안전 감지·답장 후보·브리핑 규칙·목표 플랜 계산. AI 없음. */
class ActivityRulesTest {

    @Test
    void 일정_변경_감지_규칙() {
        ActivityRules.Schedule a = ActivityRules.schedule("죄송한데 1시간 늦게 와 주셔도 될까요?", 14.0);
        assertEquals("DELAY", a.kind());
        assertEquals(60, a.deltaMin());
        assertEquals(15.0, a.newStartHour());

        ActivityRules.Schedule b = ActivityRules.schedule("혹시 3시로 바꿔도 될까요", 14.0);
        assertEquals("TIME", b.kind());
        assertEquals(15.0, b.newStartHour()); // 오전·오후 없는 1~6시는 오후

        ActivityRules.Schedule c = ActivityRules.schedule("이번엔 어렵고 다음 주로 미뤄도 될까요?", 14.0);
        assertEquals("DATE", c.kind());
        assertTrue(c.text().contains("다음 주"));

        assertEquals("DELAY", ActivityRules.schedule("한 시간 반 정도 늦어질 것 같아요", 10.0).kind());
        assertEquals(90, ActivityRules.schedule("한 시간 반 정도 늦어질 것 같아요", 10.0).deltaMin());
        assertEquals("EARLY", ActivityRules.schedule("30분 일찍 와 주실 수 있어요?", 10.0).kind());
        assertEquals("CANCEL", ActivityRules.schedule("몸이 안 좋아서 못 갈 것 같아요", 10.0).kind());
        assertEquals("TIME", ActivityRules.schedule("오전 10시 반으로 옮겨도 괜찮을까요", 14.0).kind());
        assertEquals(10.5, ActivityRules.schedule("오전 10시 반으로 옮겨도 괜찮을까요", 14.0).newStartHour());

        // 변경이 아닌 것: 짧은 지각·평범한 말
        assertNull(ActivityRules.schedule("10분 늦어요. 곧 도착할게요", 14.0));
        assertNull(ActivityRules.schedule("네 알겠어요. 그때 봬요", 14.0));
        assertNull(ActivityRules.schedule("2시에 뵐게요", 14.0));
    }

    @Test
    void 메시지_안전_감지는_수준과_이유만_정한다() {
        assertEquals("warn", ActivityRules.safety("제 번호 010-1234-5678 이에요").level());
        ActivityRules.Safety s = ActivityRules.safety("번호 좀 알려 주세요, 따로 연락할게요");
        assertEquals("warn", s.level());
        assertTrue(s.reasons().contains("연락처 교환"));
        assertTrue(ActivityRules.safety("앱 말고 우리끼리 따로 만나요").reasons().contains("앱 밖 만남 유도"));
        assertTrue(ActivityRules.safety("돈 좀 빌려 줄 수 있어요?").reasons().contains("금전·선물 요구"));
        assertEquals("caution", ActivityRules.safety("고마워서 다음에 밥 한 번 살게요").level());
        assertEquals("none", ActivityRules.safety("내일 2시에 뵐게요. 감사합니다").level());
        assertNotNull(ActivityRules.warnText(s));
        assertNull(ActivityRules.warnText(ActivityRules.safety("네")));
    }

    @Test
    void 답장_후보는_상황별로_2_3개이고_위험한_후보는_버린다() {
        List<String> r = ActivityRules.replies("1시간 늦게 와도 될까요?", ActivityRules.safety("x"), ActivityRules.schedule("1시간 늦게 와도 될까요?", 14.0));
        assertTrue(r.size() >= 2 && r.size() <= 3);
        assertTrue(ActivityRules.replies("번호 알려 주세요", ActivityRules.safety("번호 알려 주세요"), null).contains("연락은 앱 메시지로만 할게요"));
        List<String> clean = ActivityRules.cleanReplies(List.of("네 좋아요", "제 번호는 010-1111-2222", "카톡으로 연락 주세요", "이 문장은 너무 길어서 버튼에 들어가지 않는 답장 후보라서 버려야 해요 정말로요"));
        assertEquals(List.of("네 좋아요"), clean);
    }

    @Test
    void 브리핑_규칙은_기본_정보와_카테고리_규칙_교육_규칙으로_3_6줄() {
        List<String> lines = ActivityRules.briefingRule(Map.of("alias", "이○례 님", "dong", "행복동", "content", "병원 동행", "caution", "천천히 걸어 주세요", "when", "11/12 14:00, 2시간", "senior", true), "동행");
        assertTrue(lines.size() >= 3 && lines.size() <= 6);
        assertTrue(lines.get(0).startsWith("이○례 님 · 행복동 · 병원 동행"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("결정은 어르신 본인")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("부축까지만")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("신체 돌봄")));
        assertTrue(ActivityRules.briefingRule(Map.of("alias", "a", "content", "c"), "디지털 도움").stream().anyMatch(l -> l.contains("비밀번호")));
        // 받는 분이 어르신이 아니거나 나이대를 모르면 어르신용 문장을 쓰지 않는다
        for (Map<String, Object> basics : List.of(Map.<String, Object>of("alias", "손유진 님", "content", "말벗", "senior", false), Map.<String, Object>of("alias", "받는 분", "content", "말벗"))) {
            List<String> young = ActivityRules.briefingRule(basics, "말동무·정서");
            assertTrue(young.stream().noneMatch(l -> l.contains("또박또박") || l.contains("반복하셔도") || l.contains("어르신")), young.toString());
            assertTrue(young.stream().anyMatch(l -> l.contains("끝까지 듣고")));
        }
        assertTrue(ActivityRules.briefingRule(Map.of("alias", "이○례 님", "content", "말벗", "senior", true), "말동무·정서").stream().anyMatch(l -> l.contains("또박또박")));
        assertEquals(Boolean.TRUE, ActivityRules.seniorOf("80대 이상"));
        assertEquals(Boolean.TRUE, ActivityRules.seniorOf("60대"));
        assertEquals(Boolean.FALSE, ActivityRules.seniorOf("20대"));
        assertNull(ActivityRules.seniorOf(null));
    }

    @Test
    void 목표_플랜_계산은_가중치를_넣어_월_목표를_정한다() {
        // 동행(기본 ×1.0, 7.21) 주 1회 2시간을 5년 → 1회 2시간 × 52주 × 5년 = 520시간, 지금 20시간 → 500시간 / 120개월 = 4.17 → 0.5 단위 올림 4.5
        Map<String, Object> p = ActivityAiService.goalCompute(new GoalCmd("동행", 1.0, 2.0, 5, 10, null), 20, null);
        assertEquals(1.0, p.get("weight"));
        assertEquals(2.0, p.get("perSession"));
        assertEquals(520.0, p.get("totalNeeded"));
        assertEquals(500.0, p.get("remaining"));
        assertEquals(120, p.get("months"));
        assertEquals(4.5, p.get("monthlyTarget"));
        // 나이로 입력: 지금 60대(65살로 어림) → 75살부터면 10년 뒤
        Map<String, Object> q = ActivityAiService.goalCompute(new GoalCmd("말동무·정서", 0.5, 1.0, 2, null, 75), 0, ActivityAiService.ageOf("60대"));
        assertEquals(10, q.get("yearsLater"));
        assertEquals(52.0, q.get("totalNeeded")); // 1시간 × 0.5회 × 52 × 2
        assertEquals(0.5, q.get("monthlyTarget")); // 52 / 120 = 0.43 → 0.5
        // 이미 충분하면 0
        assertEquals(0.0, ActivityAiService.goalCompute(new GoalCmd("말동무·정서", 1.0, 1.0, 1, 1, null), 100, null).get("monthlyTarget"));
        assertThrows(Exception.class, () -> ActivityAiService.goalCompute(new GoalCmd("목욕", 1.0, 1.0, 1, 1, null), 0, null));
    }

    @Test
    void 결산_숫자_검사() {
        assertTrue(ActivityAi.numbersMatch("이번 달 6.5시간 쌓았어요. 동행이 1.5배라 많이 늘었어요", List.of(6.5, 1.5)));
        assertFalse(ActivityAi.numbersMatch("이번 달 7시간 쌓았어요", List.of(6.5, 1.5)));
    }
}
