package com.timesavings.service;

import java.util.*;

/** 첫 활동 교육의 규칙 카드·퀴즈(채택 프로토타입 RULES / RULE_FIT / QZ 그대로). */
public final class EduContent {
    private EduContent() {}

    public record Card(String icon, String title, String body, String example, boolean fit) {}

    public record Quiz(String q, List<String> options, int answer, String explain, int card) {}

    public static final List<Card> RULES = List.of(
            new Card("clock", "도운 시간만큼\n통장에 쌓여요", "1시간을 도우면 1시간이 쌓여요. 장보기·집안일처럼 수고가 드는 일은 1.2배예요.", "예) 병원 동행 2시간 → +2.0시간", false),
            new Card("check", "도착하면 QR,\n끝나면 QR", "받는 분 화면이나 인쇄 카드의 QR을 찍어야 활동 시간이 재져요.", "QR이 안 찍히면 받는 분 화면의 번호 6자리를 입력해요", false),
            new Card("pen", "끝나면\n일지 한 줄", "무엇을 했는지 한 줄만 적으면 AI가 다듬어 줘요. 받는 분이 확인해야 시간이 들어와요.", "예) 행복마트에서 쌀 10kg, 반찬거리 장보기", false),
            new Card("shield", "할 수 없는 일이\n있어요", "목욕·기저귀·파스 붙이기 같은 신체 돌봄, 약 먹이기, 돈과 카드 관리는 봉사로 하지 않아요.", "부탁받으면 정중히 거절하고, 담당자나 공공 서비스로 안내해요", false),
            new Card("msg", "연락처와 돈은\n주고받지 않아요", "전화번호, 카톡 아이디, 용돈, 기프티콘 모두 안 돼요. 다시 만나고 싶으면 앱의 단골 요청을 써요.", "“마음만 감사히 받을게요. 대신 시간이 통장에 쌓여요!”", false));

    public static final Map<String, Card> RULE_FIT = Map.of(
            "디지털 도움", new Card("gear", "비밀번호는\n직접 입력하게 해요", "인증번호, 비밀번호, 계좌번호는 상대가 직접 누르게 하고 화면을 보지 않아요.", "맞춤 · 디지털 도움을 골라서 넣었어요", true),
            "장보기·심부름", new Card("coin", "영수증과 잔돈은\n그 자리에서", "장 본 돈은 받는 분이나 담당자가 미리 준비해요. 영수증과 잔돈을 함께 확인해요.", "맞춤 · 장보기를 골라서 넣었어요", true),
            "말동무·정서", new Card("heart", "같은 이야기는\n처음 듣는 것처럼", "귀가 어두운 분께는 천천히, 짧게, 또박또박. 같은 말을 반복하셔도 처음처럼 반응해요.", "맞춤 · 말동무를 골라서 넣었어요", true),
            "동행", new Card("pin", "진료 결정은\n본인이 해요", "병원 동행은 이동과 접수를 돕는 일이에요. 진료 내용은 받는 분이 결정하고, 부축까지만 해요.", "맞춤 · 동행을 골라서 넣었어요", true));

    public static final List<Quiz> QUIZ = List.of(
            new Quiz("어르신이 “고마워서 그래” 하며 만 원을 주세요. 어떻게 할까요?", List.of("감사히 받는다", "마음만 받고 정중히 거절한다", "다음에 받겠다고 한다"), 1, "돈과 선물은 받지 않아요. 대신 시간이 통장에 쌓인다고 알려 드려요.", 4),
            new Quiz("다리가 아프다며 파스를 붙여 달라고 하세요.", List.of("붙여 드린다", "할 수 없다고 말하고, 손 닿는 곳에 꺼내 드린다", "못 들은 척한다"), 1, "파스 붙이기도 신체 돌봄이에요. 많이 아프시면 담당자에게 알려요.", 3),
            new Quiz("도착했는데 QR이 찍히지 않아요.", List.of("일단 시작하고 나중에 말한다", "받는 분 화면의 번호 6자리를 입력한다", "활동을 취소한다"), 1, "번호 6자리로도 시작할 수 있어요.", 1),
            new Quiz("O / X  다시 만나고 싶으면 서로 전화번호를 주고받아도 된다.", List.of("O", "X"), 1, "연락처는 주고받지 않아요. 앱에서 단골 요청을 보내면 돼요.", 4),
            new Quiz("활동이 끝나면 무엇을 하나요?", List.of("QR을 찍고 일지를 쓴다", "바로 집에 간다", "받는 분께 시간을 보내 달라고 한다"), 0, "끝 QR과 일지가 있어야 시간이 정산돼요.", 2));

    public static List<Card> cardsFor(List<String> cats) {
        List<Card> out = new ArrayList<>(RULES);
        if (cats != null) cats.stream().filter(RULE_FIT::containsKey).limit(2).forEach(c -> out.add(RULE_FIT.get(c)));
        return out;
    }
}
