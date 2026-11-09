package com.timesavings;

import com.timesavings.ai.KoreanParser;
import com.timesavings.ai.PostChatRule;
import com.timesavings.ai.PostChatRule.Msg;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 말로 요청하기: 규칙 파서가 어르신 말투(군말·한글 수사·상대 날짜)를 실제로 푸는지. */
class VoiceParserTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 9); // 월요일

    @Test
    void 보고서_예문_다음주_화요일_오후_두시_두시간_동행() {
        var p = KoreanParser.parse("어 다음 주 화요일 오후 두 시에 정형외과 같이 가 줄 사람 있으면 좋겠어요 두 시간 정도", TODAY);
        assertEquals(LocalDate.of(2026, 11, 17), p.date);
        assertEquals(14.0, p.startHour);
        assertEquals(16.0, p.endHour);
        assertEquals(2.0, p.hours);
        assertTrue(p.categories.contains("동행"));
        assertEquals("정형외과", p.place);
        assertEquals("high", p.dateConf);
        assertFalse(p.cleaned.startsWith("어 "));
    }

    @Test
    void 범위와_반() {
        var p = KoreanParser.parse("모레 두 시부터 네 시까지 장 좀 봐 줘", TODAY);
        assertEquals(TODAY.plusDays(2), p.date);
        assertEquals(14.0, p.startHour);
        assertEquals(16.0, p.endHour);
        assertEquals(2.0, p.hours);
        assertTrue(p.categories.contains("장보기·심부름"));

        var q = KoreanParser.parse("내일 아침 열 시 반에 한 시간 반 정도 휴대폰 카톡 좀 알려 줘", TODAY);
        assertEquals(TODAY.plusDays(1), q.date);
        assertEquals(10.5, q.startHour);
        assertEquals(1.5, q.hours);
        assertEquals(12.0, q.endHour);
        assertTrue(q.categories.contains("디지털 도움"));
    }

    @Test
    void 숫자_시각과_날짜() {
        var p = KoreanParser.parse("14일 오후 3시 행복동 경로당에서 말벗", TODAY);
        assertEquals(LocalDate.of(2026, 11, 14), p.date);
        assertEquals(15.0, p.startHour);
        assertEquals("행복동", p.area);
        assertTrue(p.categories.contains("말동무·정서"));
        assertEquals(LocalDate.of(2026, 11, 13), KoreanParser.parse("이번 주 금요일", TODAY).date);
        assertEquals(LocalDate.of(2026, 12, 3), KoreanParser.parse("3일", TODAY).date); // 지난 날짜면 다음 달
        assertEquals(LocalDate.of(2026, 11, 20), KoreanParser.parse("11월 20일", TODAY).date);
    }

    @Test
    void 한시간은_한시로_읽지_않고_오시면은_시각이_아님() {
        var p = KoreanParser.parse("한 시간만 와 주시면 돼요 청소랑 빨래", TODAY);
        assertNull(p.startHour);
        assertEquals(1.0, p.hours);
        assertTrue(p.categories.contains("가벼운 집안일"));
        assertNull(KoreanParser.parse("천천히 오시면 돼요", TODAY).startHour);
    }

    @Test
    void 오전_오후_없으면_오후로_보고_확신도_보통() {
        var r = PostChatRule.run("NEED", List.of(new Msg("user", "내일 두 시에 옷장 정리 한 시간")), TODAY, "행복동", true);
        assertEquals(14.0, r.draft().startHour);
        assertEquals("medium", r.confidence().get("time"));
        assertEquals("medium", r.confidence().get("area")); // 프로필 지역으로 채움
        assertTrue(r.draft().categories.contains("생활 정리"));
        assertTrue(r.done());
    }

    @Test
    void 빠진_항목은_하나씩_되묻는다() {
        var r1 = PostChatRule.run("NEED", List.of(new Msg("user", "음 장 좀 봐 줄 사람")), TODAY, null, true);
        assertFalse(r1.done());
        assertEquals(List.of("date", "startHour", "estHours", "area"), r1.missing());
        assertTrue(r1.reply().contains("며칠"));
        var r2 = PostChatRule.run("NEED", List.of(new Msg("user", "음 장 좀 봐 줄 사람"), new Msg("assistant", r1.reply()),
                new Msg("user", "다음 주 수요일 오전 열 시"), new Msg("user", "두 시간"), new Msg("user", "희망동")), TODAY, null, true);
        assertTrue(r2.done());
        assertEquals(LocalDate.of(2026, 11, 18), r2.draft().date);
        assertEquals(12.0, r2.draft().endHour);
        assertEquals("희망동", r2.draft().area);
        assertEquals("11월 18일 수요일 오전 10시부터 2시간, 장보기 요청서를 만들었어요. 올릴까요?", r2.draft().summarySpeech);
    }

    @Test
    void 요약_문장_병원_동행() {
        var r = PostChatRule.run("NEED", List.of(new Msg("user", "어 다음 주 화요일 오후 두 시에 정형외과 같이 가 줄 사람 있으면 좋겠어요 두 시간 정도")), TODAY, "행복동", true);
        assertTrue(r.done());
        assertEquals("11월 17일 화요일 오후 2시부터 2시간, 병원 동행 요청서를 만들었어요. 올릴까요?", r.draft().summarySpeech);
        assertEquals("NEW_REQUEST", r.intent());
    }

    @Test
    void 봉사_가능_공고는_시간_범위를_묻는다() {
        var r = PostChatRule.run("OFFER", List.of(new Msg("user", "토요일 한 시부터 다섯 시까지 휴대폰 알려 드릴 수 있어요")), TODAY, "행복동", false);
        assertTrue(r.done());
        assertEquals(13.0, r.draft().startHour);
        assertEquals(17.0, r.draft().endHour);
        assertEquals(LocalDate.of(2026, 11, 14), r.draft().date);
    }
}
