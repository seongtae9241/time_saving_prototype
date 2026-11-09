package com.timesavings;

import com.timesavings.ai.RuleEngine;
import com.timesavings.domain.Enums.FilterLevel;
import com.timesavings.domain.Enums.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 금지 요청 필터(B-09) 규칙 모드, 난이도 제안, 역할극 판정, 일지 정리. */
class RuleEngineTest {
    @Test
    void 불가_네가지() {
        assertBlock("어머니 목욕 좀 시켜 주세요", "신체 돌봄");
        assertBlock("기저귀 갈아 주실 분", "신체 돌봄");
        assertBlock("파스 좀 붙여 줘", "신체 돌봄");
        assertBlock("저녁에 약 좀 먹여 주세요", "의료 행위");
        assertBlock("인슐린 주사 놔 주세요", "의료 행위");
        assertBlock("통장 맡아서 돈 좀 찾아 주세요", "금전 관리");
        assertBlock("대출 서류 대신 해 주세요", "금전 관리");
        assertBlock("사다리 타고 형광등 갈아 주세요", "위험 작업");
        assertBlock("가스 배관 연결해 주세요", "위험 작업");
    }

    private static void assertBlock(String text, String cat) {
        var r = RuleEngine.filter(text);
        assertEquals(FilterLevel.BLOCK, r.level(), text);
        assertEquals(cat, r.blockCategory(), text);
    }

    @Test
    void 주의와_허용() {
        assertEquals(FilterLevel.CAUTION, RuleEngine.filter("병원 가는 길 부축해 주세요").level());
        assertEquals(FilterLevel.CAUTION, RuleEngine.filter("은행 같이 가 주세요").level());
        assertEquals(FilterLevel.ALLOW, RuleEngine.filter("카페 키오스크 주문 연습").level());
        assertEquals(FilterLevel.ALLOW, RuleEngine.filter("목욕은 안 해도 되고 말벗만 해 주세요").level()); // 부정 표현
    }

    @Test
    void 난이도_제안() {
        assertEquals(Level.RESPONSIBLE, RuleEngine.difficulty("정형외과 진료 동행", List.of("동행")).level());
        assertEquals(Level.EFFORT, RuleEngine.difficulty("쌀 20kg 4층까지 계단으로", List.of("생활 정리")).level());
        assertEquals(Level.BASIC, RuleEngine.difficulty("영상통화 설정", List.of("디지털 도움")).level());
        // 가중치는 규칙으로: 카테고리 기본 등급보다 낮아지지 않음
        assertEquals(Level.EFFORT, RuleEngine.difficulty("우유 사 오기", List.of("장보기·심부름")).level());
    }

    @Test
    void 역할극_판정은_프로토타입_규칙() {
        assertEquals(new RuleEngine.RpClass("bad", "연락처"), RuleEngine.rpClassify("네! 제 번호는 010-1234-5678이에요."));
        assertEquals("good", RuleEngine.rpClassify("앱에서 저한테 단골 요청을 보내 주시면 돼요. 개인 연락처는 주고받지 않는 게 규칙이에요.").quality());
        assertEquals(new RuleEngine.RpClass("bad", "금전·선물"), RuleEngine.rpClassify("그럼 이번만 받을게요"));
        assertEquals("ok", RuleEngine.rpClassify("괜찮아요~").quality());
        var s = RuleEngine.rpScore(List.of("good", "good", "good"), java.util.Arrays.asList(null, null, null));
        assertTrue(s.pass());
        assertEquals(100, s.comm());
        var f = RuleEngine.rpScore(List.of("good", "bad", "good"), java.util.Arrays.asList(null, "연락처", null));
        assertFalse(f.safe());
        assertFalse(f.pass());
    }

    @Test
    void 일지_정리() {
        String t = RuleEngine.logTidy("어 행복마트에서 쌀 10kg 삼, 반찬거리도 같이 장보기 함");
        assertTrue(t.contains("행복마트"));
        assertTrue(t.split("\n").length >= 2);
        assertFalse(t.startsWith("어 "));
    }
}
