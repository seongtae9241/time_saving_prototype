package com.timesavings;

import com.timesavings.config.ApiException;
import com.timesavings.domain.Enums.Level;
import com.timesavings.service.Rules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 적립 계산(B-26, B-27, B-29)과 일정 규칙(B-10). */
class RulesTest {
    @Test
    void 반올림은_0_5단위() {
        assertEquals(2.0, Rules.round05(1.8));
        assertEquals(1.0, Rules.round05(1.2));
        assertEquals(1.5, Rules.round05(1.25));
        assertEquals(3.0, Rules.round05(3.0));
        assertEquals(0.5, Rules.round05(0.6));
    }

    @Test
    void 적립은_시간_곱하기_가중치() {
        assertEquals(2.0, Rules.earn(2, Rules.categoryLevel("동행"))); // 동행 2시간 → 2.0 (7.21: 동행은 기본 1.0)
        assertEquals(3.0, Rules.earn(2, Level.RESPONSIBLE));   // 예전 기록(책임 1.5)을 다시 셈할 때만
        assertEquals(2.0, Rules.earn(1.5, Level.EFFORT));      // 장보기 1.5시간 → 1.8 → 2.0
        assertEquals(1.5, Rules.earn(1.5, Level.BASIC));
        assertEquals(3.0, Rules.earn(2.5, Level.EFFORT));      // 3.0
        assertEquals(0.5, Rules.earn(0.5, Level.EFFORT));      // 0.6 → 0.5
    }

    @Test
    void 복합_요청은_최고_등급() {
        assertEquals(Level.BASIC, Rules.maxLevel(List.of("디지털 도움", "동행")));
        assertEquals(Level.EFFORT, Rules.maxLevel(List.of("동행", "가벼운 집안일")));
        assertEquals(Level.EFFORT, Rules.maxLevel(List.of("말동무·정서", "장보기·심부름")));
        assertEquals(Level.BASIC, Rules.maxLevel(List.of("말동무·정서", "생활 정리")));
        assertThrows(ApiException.class, () -> Rules.maxLevel(List.of("목욕")));
    }

    @Test
    void 일정은_30분_단위_최대_4시간() {
        assertDoesNotThrow(() -> Rules.checkHours(14.5, 4));
        assertThrows(ApiException.class, () -> Rules.checkHours(14.25, 1));
        assertThrows(ApiException.class, () -> Rules.checkHours(14, 4.5));
        assertThrows(ApiException.class, () -> Rules.checkHours(14, 0));
    }

    @Test
    void 독거노인_별칭_가리기() {
        assertEquals("이○례 님", Rules.maskAlias("이순례"));
        assertEquals("남○궁 님", Rules.maskAlias("남궁"));
    }
}
