package com.timesavings;

import com.timesavings.ai.PostChatRule;
import com.timesavings.ai.PostChatRule.Msg;
import com.timesavings.ai.VoiceSafety;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 음성 보고서 4.3·4.5·4.6: 의도·위급·개인정보 지우기·주의사항·반복. */
class VoiceSafetyTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Test
    void 전화번호_계좌_주소는_지운다() {
        var s = VoiceSafety.scrub("제 번호는 010-1234-5678이고 계좌는 110-123-456789, 행복아파트 102동 1203호예요");
        assertTrue(s.removed().containsAll(List.of("phone", "account", "address")));
        assertFalse(s.text().contains("010"));
        assertFalse(s.text().contains("1203"));
        assertTrue(s.text().contains(VoiceSafety.PII_NOTE));
        var k = VoiceSafety.scrub("번호는 공일공 일이삼사 오육칠팔 이에요");
        assertTrue(k.removed().contains("phone"));
    }

    @Test
    void 위급_표현이면_요청서를_멈추고_119() {
        var r = PostChatRule.run("NEED", List.of(new Msg("user", "할머니가 쓰러졌어요 숨이 안 쉬어져요")), TODAY, "행복동", true);
        assertTrue(r.emergency());
        assertEquals("EMERGENCY", r.intent());
        assertFalse(r.done());
        assertTrue(r.reply().contains("119"));
        assertTrue(VoiceSafety.emergency("손에서 피가 나요"));
        assertFalse(VoiceSafety.emergency("내일 병원 같이 가요"));
    }

    @Test
    void 주의사항에는_병명을_남기지_않는다() {
        assertEquals("걸음이 불편하셔서 천천히 걸어 주세요", VoiceSafety.caution("무릎 수술을 해서 천천히 걸어야 해"));
        String c = VoiceSafety.sanitizeCaution("치매 초기라 같은 말을 반복하세요");
        assertFalse(c.contains("치매"));
        assertEquals("귀가 어두우셔서 천천히 크게 말해 주세요. 반려동물이 있어요", VoiceSafety.caution("귀가 어둡고 강아지가 있어요"));
        var r = PostChatRule.run("NEED", List.of(new Msg("user", "내일 오후 두 시 한 시간 말벗 해 주세요 치매가 있어서 깜빡하세요")), TODAY, "행복동", true);
        assertFalse(r.draft().detail.contains("치매"));
        assertNotNull(r.draft().caution);
        assertFalse(r.draft().caution.contains("치매"));
    }

    @Test
    void 반복_규칙() {
        assertEquals("매주 목요일", VoiceSafety.repeat("매주 목요일마다 와 주세요"));
        assertEquals("매주 화요일", VoiceSafety.repeat("화요일마다"));
        assertNull(VoiceSafety.repeat("내일 와 주세요"));
    }

    @Test
    void 의도_구분() {
        assertEquals("CHANGE_CANCEL", VoiceSafety.intent("목요일 거 취소해 줘", false, true));
        assertEquals("CONFIRM", VoiceSafety.intent("응 왔다 갔어 잘해 줬어", false, false));
        assertEquals("REGULAR_REQUEST", VoiceSafety.intent("지난번 그 학생이 또 와 줬으면 좋겠어 장 좀", true, false));
        assertEquals("QUESTION", VoiceSafety.intent("병원비 지원 같은 거 없나?", false, false));
        assertEquals("CHAT", VoiceSafety.intent("그냥 심심해서", false, false));
        assertEquals("NEW_REQUEST", VoiceSafety.intent("장 좀 봐 줄 사람", true, false));
        var r = PostChatRule.run("NEED", List.of(new Msg("user", "목요일 거 취소해 줘")), TODAY, "행복동", true);
        assertEquals("CHANGE_CANCEL", r.intent());
        assertFalse(r.done());
        assertTrue(r.reply().contains("내 활동"));
    }
}
