package com.timesavings.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * LLM 추가 A(7.12) 요청서 빠진 정보 점검: 봉사 요청(NEED) 공고를 올리기 직전에 카테고리별 체크리스트(코드)로
 * 실제로 필요한 정보(엘리베이터·계단, 무거운 짐, 반려동물, 만날 곳, 준비물 등)가 글에 있는지 보고, 빠진 것 1~2개만 되묻는다.
 * 무엇이 빠졌는지·선택지는 코드가 정하고, LLM 은 질문 문장만 부드럽게 다듬는다(키 없으면 코드 문장).
 */
@Component
public class PostCheckAi {
    public static final int MAX_QUESTIONS = 2;
    private final LlmJson llm;

    public PostCheckAi(LlmJson llm) { this.llm = llm; }

    /** 체크 항목: key, 이름, 글에 있으면 '이미 있음'으로 보는 낱말, 질문, 선택지, 답을 붙일 곳(detail|caution) */
    public record Item(String key, String label, Pattern covered, String question, List<String> choices, String target) {}

    static Item item(String key, String label, String covered, String q, String target, String... choices) {
        return new Item(key, label, Pattern.compile(covered), q, List.of(choices), target);
    }

    static final Item STAIRS = item("stairs", "엘리베이터·계단", "엘리베이터|엘베|계단|[0-9일이삼사오육칠팔구]\\s*층|1층|단독|주택", "엘리베이터가 있나요, 계단으로 올라가야 하나요?", "caution",
            "엘리베이터 있어요", "계단만 있어요", "1층이에요");
    static final Item PET = item("pet", "반려동물", "강아지|고양이|반려|애완|키우는|동물", "집에 반려동물이 있나요?", "caution",
            "없어요", "강아지가 있어요", "고양이가 있어요");
    static final Item HEAVY = item("heavy", "무거운 짐", "무거|생수|쌀|가벼|짐이|짐은|짐 |kg|킬로|박스|상자", "무거운 짐(쌀·생수·상자 등)이 있나요?", "caution",
            "무거운 짐이 있어요", "가벼운 것만 있어요");
    static final Item MEET_OUT = item("meet", "만날 곳", "만나|만날|집 앞|앞에서|정문|입구|로비|역에서|에서 출발|데리러|모시러", "어디에서 만나면 될까요?", "detail",
            "집 앞에서 만나요", "병원 앞에서 만나요", "수락 뒤 메시지로 알려 드릴게요");
    static final Item MOVE = item("move", "이동 방법", "택시|버스|지하철|걸어|도보|휠체어|지팡이|부축|차로|자가용|보행", "이동은 어떻게 하시나요?", "caution",
            "걸어서 가요", "택시를 타요", "휠체어를 써요");
    static final Item LIST = item("list", "살 물건", "목록|리스트|메모|살 것|살것|품목|사 올|사올|우유|계란|두부|반찬|과일|채소|라면", "살 물건은 정해졌나요?", "detail",
            "목록은 메시지로 드릴게요", "같이 가서 고를게요");
    static final Item TOOLS = item("tools", "준비물", "도구|청소기|걸레|장갑|세제|준비물|사다리|가져", "필요한 도구(청소 도구 등)는 집에 있나요?", "detail",
            "집에 있어요", "챙겨 와 주세요");
    static final Item DEVICE = item("device", "기기 종류", "갤럭시|아이폰|안드로이드|태블릿|노트북|컴퓨터|키오스크|카톡|카카오|폰 기종", "어떤 기기를 쓰세요?", "detail",
            "안드로이드 폰(갤럭시 등)", "아이폰", "태블릿·컴퓨터");
    static final Item MEET_IN = item("place", "만날 곳", "집에서|댁에서|자택|공원|카페|복지관|경로당|산책|주민센터|센터에서", "어디에서 만나 이야기하면 좋을까요?", "detail",
            "집에서 만나요", "동네 공원 산책", "근처 경로당·복지관");

    /** 카테고리별 체크리스트(중요한 순). */
    public static final Map<String, List<Item>> CHECKLIST = Map.of(
            "동행", List.of(MEET_OUT, MOVE),
            "장보기·심부름", List.of(HEAVY, LIST, STAIRS),
            "가벼운 집안일", List.of(STAIRS, PET, TOOLS),
            "생활 정리", List.of(HEAVY, STAIRS, PET),
            "말동무·정서", List.of(MEET_IN, PET),
            "디지털 도움", List.of(DEVICE, MEET_IN));

    /** 코드: 빠진 항목(최대 2개, 여러 카테고리면 앞 카테고리부터, 같은 key 는 한 번). */
    public static List<Item> missing(List<String> categories, String text) {
        String t = text == null ? "" : text;
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String c : categories == null ? List.<String>of() : categories) {
            for (Item it : CHECKLIST.getOrDefault(c, List.of())) {
                if (!seen.add(it.key())) continue;
                if (it.covered().matcher(t).find()) continue;
                out.add(it);
                if (out.size() >= MAX_QUESTIONS) return out;
            }
        }
        return out;
    }

    /** 코드: 체크리스트 중 이미 글에 있는 항목 key */
    public static List<String> covered(List<String> categories, String text) {
        String t = text == null ? "" : text;
        List<String> out = new ArrayList<>();
        for (String c : categories == null ? List.<String>of() : categories)
            for (Item it : CHECKLIST.getOrDefault(c, List.of()))
                if (!out.contains(it.key()) && it.covered().matcher(t).find()) out.add(it.key());
        return out;
    }

    public Map<String, Object> check(List<String> categories, String title, String detail, String caution, String note, String extraCaution) {
        String text = String.join(" ", Objects.toString(title, ""), Objects.toString(detail, ""), Objects.toString(caution, ""),
                Objects.toString(note, ""), Objects.toString(extraCaution, ""));
        List<Item> miss = missing(categories, text);
        List<Map<String, Object>> qs = new ArrayList<>();
        for (Item it : miss) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("key", it.key());
            q.put("label", it.label());
            q.put("text", it.question());
            q.put("choices", it.choices());
            q.put("target", it.target());
            qs.add(q);
        }
        String source = "rule";
        if (!qs.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> q : qs) sb.append("- key=").append(q.get("key")).append(" (").append(q.get("label")).append("): ").append(q.get("text")).append('\n');
            Optional<JsonNode> j = llm.ask("""
                    너는 어르신·이웃이 올리는 '도움 요청 공고'의 빠진 정보를 짧게 되묻는 도우미다.
                    - 아래 항목(key)마다 질문 문장 하나만 다듬는다. 항목을 늘리거나 빼지 않는다.
                    - 30자 안, 쉬운 존댓말, 한 번에 하나만 묻는다. 병명·개인정보(주소·전화)는 묻지 않는다.
                    출력: {"questions":[{"key":"...","text":"..."}]}
                    """, "공고 종류: " + String.join(", ", categories == null ? List.of() : categories) + "\n공고 글: " + clip(text, 400) + "\n되물을 항목:\n" + sb, 250);
            if (j.isPresent()) {
                int used = 0;
                for (JsonNode n : j.get().path("questions")) {
                    String k = n.path("key").asText(""), t = n.path("text").asText("").trim();
                    if (t.isEmpty() || t.length() > 60 || t.matches(".*\\d{3,}.*")) continue;
                    for (Map<String, Object> q : qs) if (k.equals(q.get("key"))) { q.put("text", t); used++; }
                }
                if (used > 0) source = "ai";
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questions", qs);
        out.put("covered", covered(categories, text));
        out.put("skippable", true);
        out.put("source", source);
        if ("ai".equals(source)) out.put("provider", llm.providerName());
        return out;
    }

    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
}
