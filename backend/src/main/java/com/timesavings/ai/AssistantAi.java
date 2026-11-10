package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * LLM 추가 A(7.12) 음성 비서 "물어보기"의 의도 분류. LLM(있으면) 또는 규칙이 질문을 의도 하나로 나누기만 한다.
 * 답은 AssistantService 가 내 데이터로 코드로 만든다. 취소·요청 만들기 같은 실행은 하지 않는다(화면 링크 + 확인 버튼만).
 */
@Component
public class AssistantAi {
    public static final List<String> INTENTS = List.of("BALANCE", "TOMORROW", "NEXT", "CANCEL", "REGULAR", "NOTI", "CREATE", "UNKNOWN");
    private final LlmJson llm;

    public AssistantAi(LlmJson llm) { this.llm = llm; }

    public record Intent(String intent, LocalDate date, String source) {}

    private static final Pattern CANCEL = Pattern.compile("취소|못\\s*가|못\\s*와|안\\s*와도|안\\s*오셔도|빼\\s*줘|미뤄|연기");
    private static final Pattern REGULAR = Pattern.compile("단골");
    private static final Pattern BALANCE = Pattern.compile("(시간|잔액|통장|적립).*(남|얼마|몇|있어|확인|알려)|잔액|통장");
    private static final Pattern TOMORROW = Pattern.compile("내일|낼");
    private static final Pattern NEXT = Pattern.compile("일정|약속|언제|스케줄|누가\\s*(와|오)|오는\\s*(사람|분)|다음\\s*(봉사|활동)");
    private static final Pattern NOTI = Pattern.compile("알림|소식|메시지|연락|새로\\s*온|온\\s*거");
    private static final Pattern CREATE = Pattern.compile("요청|불러|와\\s*줄|도와\\s*줄|부탁|필요해|해\\s*줄\\s*사람|구해");

    /** 규칙 분류(키 없을 때·LLM 실패 때). */
    public static String ruleIntent(String text) {
        String t = text == null ? "" : text;
        if (CANCEL.matcher(t).find()) return "CANCEL";
        if (REGULAR.matcher(t).find()) return "REGULAR";
        if (BALANCE.matcher(t).find() && !TOMORROW.matcher(t).find()) return "BALANCE";
        if (TOMORROW.matcher(t).find() && (NEXT.matcher(t).find() || t.contains("와") || t.contains("오"))) return "TOMORROW";
        if (NEXT.matcher(t).find()) return "NEXT";
        if (NOTI.matcher(t).find()) return "NOTI";
        if (CREATE.matcher(t).find()) return "CREATE";
        return "UNKNOWN";
    }

    /** 글에서 날짜(요일·내일·11월 20일 등). 없으면 null. */
    public static LocalDate date(String text, LocalDate today) {
        try { return KoreanParser.parseDate(text == null ? "" : text, today); } catch (Exception e) { return null; }
    }

    public Intent classify(String text, LocalDate today) {
        String t = text == null ? "" : text.trim();
        Optional<JsonNode> j = llm.ask("""
                너는 동네 봉사 앱의 음성 비서다. 사용자의 말을 아래 의도 중 하나로만 나눈다. 답을 지어내지 않는다.
                BALANCE(내 시간·잔액), TOMORROW(내일 일정·누가 오는지), NEXT(다음 일정·언제), CANCEL(일정 취소·못 감),
                REGULAR(단골 봉사자), NOTI(알림·새 소식), CREATE(도움 요청을 새로 만들고 싶음), UNKNOWN(그 밖).
                출력: {"intent":"BALANCE"}
                """, "말: " + (t.length() > 200 ? t.substring(0, 200) : t), 60);
        LocalDate d = date(t, today);
        if (j.isPresent()) {
            String i = j.get().path("intent").asText("").trim().toUpperCase();
            if (INTENTS.contains(i)) return new Intent(i, d, "ai");
        }
        return new Intent(ruleIntent(t), d, "rule");
    }
}
