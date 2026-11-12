package com.timesavings.ai;

import com.timesavings.domain.Enums.Level;
import com.timesavings.service.Rules;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 기능 U-30 공공 서비스 안내 AI 의 "봉사로 가능한 일인지와 봉사 기준" 안내.
 * 가능 여부의 바탕은 금지 요청 필터(AiClient.filter — LLM 이 문장을 읽고, 규칙이 불가로 잡은 것은 LLM 이 풀지 못한다)와
 * 6가지 도움 종류 찾기(KoreanParser, 코드)다. 난이도·가중치·시간 기준은 코드(Rules)의 값을 그대로 쓴다.
 * 키가 없으면 규칙만으로 끝까지 동작한다(source:'rule').
 */
@Component
public class VolunteerGuideAi {
    /** 봉사 기준(고정 문구 — 숫자는 Rules 와 같다) */
    public static final List<String> CRITERIA = List.of(
            "봉사로 받을 수 있는 도움은 6가지예요: " + String.join(", ", Rules.CATEGORIES),
            "목욕·기저귀 같은 신체 돌봄, 투약·주사 같은 의료 행위, 통장·카드 같은 돈 관리, 사다리·전기·가스 같은 위험 작업은 봉사로 받을 수 없어요",
            "한 번에 30분~4시간까지 30분 단위로 정해요",
            "받은 시간 × 난이도(기본 1.0 · 수고 1.2 · 책임 1.5)만큼 내 시간 통장에서 빠져요. 돈은 오가지 않아요",
            "신분증 확인과 첫 활동 교육(안전 교육)을 마친 봉사자만 와요. 연락처 교환·금전 요구는 금지예요");

    private final AiClient ai;

    public VolunteerGuideAi(AiClient ai) { this.ai = ai; }

    /**
     * {possible: YES|CAUTION|NO|UNKNOWN, label, message, reason, categories:[{category, level, levelLabel, weight}],
     *  criteria[], writeLink|null, source}
     */
    public Map<String, Object> guide(String question) {
        String q = question == null ? "" : question.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        if (q.isEmpty()) {
            out.put("possible", "UNKNOWN");
            out.put("label", "봉사로 받을 수 있는 도움");
            out.put("message", "필요한 도움을 적어 주시면 이웃 봉사로 받을 수 있는 일인지도 함께 알려 드려요.");
            out.put("reason", null);
            out.put("categories", List.of());
            out.put("criteria", CRITERIA);
            out.put("writeLink", null);
            out.put("source", "rule");
            return out;
        }
        Map<String, Object> f = ai.filter(q);
        String level = String.valueOf(f.getOrDefault("level", "ALLOW"));
        List<Map<String, Object>> cats = new ArrayList<>();
        for (String c : KoreanParser.categories(q)) {
            if (!Rules.isCategory(c)) continue;
            Level lv = Rules.categoryLevel(c);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", c); m.put("level", lv); m.put("levelLabel", Rules.levelKo(lv)); m.put("weight", Rules.weight(lv));
            cats.add(m);
        }
        String names = String.join("·", cats.stream().map(c -> String.valueOf(c.get("category"))).toList());
        String possible, label, message;
        if ("BLOCK".equals(level)) {
            possible = "NO"; label = "봉사로는 받을 수 없는 일이에요";
            message = "이 일은 이웃 봉사로 부탁할 수 없어요. 위에 안내한 공공 서비스나 기관에 문의해 주세요.";
        } else if (cats.isEmpty()) {
            possible = "UNKNOWN"; label = "봉사로 받을 수 있는지 확인이 필요해요";
            message = "말씀하신 내용이 봉사 6가지 도움 가운데 어디에 드는지 찾지 못했어요. 아래 기준에 맞으면 봉사 요청 공고를 올려 볼 수 있어요.";
        } else if ("CAUTION".equals(level)) {
            possible = "CAUTION"; label = "봉사로 받을 수 있지만 주의할 점이 있어요";
            message = names + " 도움은 이웃 봉사로 받을 수 있어요. 다만 아래 주의할 점을 지켜야 해요.";
        } else {
            possible = "YES"; label = "봉사로 받을 수 있는 일이에요";
            message = names + " 도움은 이웃 봉사로 받을 수 있어요. 봉사 요청 공고를 올리면 이웃이 제안해요.";
        }
        out.put("possible", possible);
        out.put("label", label);
        out.put("message", message);
        out.put("reason", "ALLOW".equals(level) ? null : f.get("reason"));
        out.put("categories", cats);
        out.put("criteria", CRITERIA);
        String clip = VoiceSafety.scrub(q).text().replaceAll("\\s+", " ").trim();
        if (clip.length() > 80) clip = clip.substring(0, 80);
        out.put("writeLink", "NO".equals(possible) ? null : "/write/need?prefill=" + java.net.URLEncoder.encode(clip, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        out.put("source", f.getOrDefault("source", "rule"));
        return out;
    }
}
