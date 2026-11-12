package com.timesavings.ai;

import java.time.LocalDate;
import java.util.*;

/**
 * 대화형 공고 작성(B-02)의 규칙 대체 구현.
 * 사용자 발화를 차례로 읽어 초안을 채우고, 빠진 항목을 하나씩 되묻는다.
 */
public final class PostChatRule {
    private PostChatRule() {}

    public record Msg(String role, String text) {}

    public static final class Draft {
        public LocalDate date;
        public Double startHour;
        public Double endHour;
        public String area;
        public String place;
        public List<String> categories = new ArrayList<>();
        public String title;
        public String detail;
        public String note;
        public Double estHours;
        public String summarySpeech;
        public String caution;
        public String repeat;
        public boolean hoursTooLong;
    }

    public record Result(String reply, boolean done, Draft draft, List<String> missing,
                         String intent, Map<String, String> confidence, boolean emergency, List<String> piiRemoved) {}

    private static final Map<String, String> TITLE_WORD = Map.of(
            "동행", "동행", "장보기·심부름", "장보기", "디지털 도움", "휴대폰·기기 도움",
            "말동무·정서", "말벗", "가벼운 집안일", "집안일 도움", "생활 정리", "정리 도움");

    public static Result run(String type, List<Msg> messages, LocalDate today, String defaultArea, boolean voice) {
        boolean offer = "OFFER".equalsIgnoreCase(type);
        Draft d = new Draft();
        List<String> details = new ArrayList<>();
        LinkedHashSet<String> pii = new LinkedHashSet<>();
        StringBuilder allText = new StringBuilder();
        String last = "";
        boolean emergency = false, areaSpoken = false, periodExplicit = false;
        String dateConf = "low";
        for (Msg m : messages) {
            if (m == null || m.text() == null || !"user".equals(m.role())) continue;
            if (VoiceSafety.emergency(m.text())) emergency = true;
            VoiceSafety.Scrubbed sc = VoiceSafety.scrub(m.text());
            pii.addAll(sc.removed());
            last = sc.text();
            allText.append(sc.text()).append(" ");
            KoreanParser.Parsed p = KoreanParser.parse(sc.text(), today);
            if (p.date != null) { d.date = p.date; dateConf = p.dateConf; }
            if (p.area != null) areaSpoken = true;
            if (p.startHour != null) periodExplicit = p.periodExplicit;
            if (p.startHour != null) {
                d.startHour = p.startHour;
                d.endHour = p.endHour; // 새 시작 시각이 나오면 끝 시각도 그 발화 기준
            } else if (p.endHour != null) d.endHour = p.endHour;
            if (p.hours != null) { d.estHours = p.hours; d.hoursTooLong = false; }
            if (p.hoursTooLong) d.hoursTooLong = true;
            if (p.area != null) d.area = p.area;
            if (p.place != null) d.place = p.place;
            for (String c : p.categories) if (!d.categories.contains(c)) d.categories.add(c);
            if (!p.cleaned.isBlank() && (p.categories.size() > 0 || p.cleaned.length() >= 8)) details.add(p.cleaned);
        }
        if (d.startHour != null && d.endHour == null && d.estHours != null) d.endHour = d.startHour + d.estHours;
        if (d.startHour != null && d.endHour != null && d.estHours == null && !offer) {
            double span = d.endHour - d.startHour;
            if (span > 0 && span <= 4) d.estHours = span;
        }
        if (d.endHour != null && d.endHour > 24) d.endHour = 24.0;
        if (d.area == null && defaultArea != null && !defaultArea.isBlank()) d.area = defaultArea;
        String all = allText.toString();
        d.caution = VoiceSafety.caution(all);
        d.repeat = VoiceSafety.repeat(all);
        // 핵심 카테고리를 앞에: 정리 요청에 "냉장고"가 있으면 생활 정리 우선 등은 순서 유지
        String main = d.categories.isEmpty() ? null : d.categories.get(0);
        if (!details.isEmpty()) {
            String det = VoiceSafety.stripHealth(details.get(0));
            d.detail = det.length() > 200 ? det.substring(0, 200) : det;
        }
        if (offer) {
            d.title = d.categories.isEmpty() ? null :
                    d.categories.stream().limit(2).map(c -> TITLE_WORD.getOrDefault(c, c)).reduce((a, b) -> a + "·" + b).orElse("") + " 도와드려요";
            d.note = d.detail;
        } else if (main != null) {
            String word = TITLE_WORD.getOrDefault(main, main);
            if ("동행".equals(main) && d.place != null) d.title = d.place + " " + word;
            else if (d.place != null && !d.place.endsWith("동")) d.title = d.place + " " + word;
            else d.title = word + " 요청";
            if (d.place != null) d.note = "장소: " + d.place;
        }

        List<String> missing = new ArrayList<>();
        if (d.categories.isEmpty()) missing.add("content");
        if (d.date == null || d.date.isBefore(today)) missing.add("date");
        if (d.startHour == null) missing.add("startHour");
        if (offer) { if (d.endHour == null) missing.add("endHour"); }
        else if (d.estHours == null) missing.add("estHours");
        if (d.area == null) missing.add("area");

        Map<String, String> conf = new LinkedHashMap<>();
        conf.put("date", d.date == null ? "low" : dateConf);
        conf.put("time", d.startHour == null ? "low" : periodExplicit ? "high" : "medium");
        conf.put("area", areaSpoken ? "high" : d.area != null ? "medium" : "low");
        conf.put("category", d.categories.isEmpty() ? "low" : d.categories.size() == 1 ? "high" : "medium");
        String intent = VoiceSafety.intent(last, !d.categories.isEmpty(), d.date != null || d.startHour != null);
        if (emergency) intent = "EMERGENCY";

        String reply;
        if (emergency) {
            return new Result(VoiceSafety.emergencyReply(voice), false, d, missing, intent, conf, true, new ArrayList<>(pii));
        }
        String side = VoiceSafety.intentReply(intent, voice);
        if (side != null && (d.categories.isEmpty() || !"CHAT".equals(intent) && !"QUESTION".equals(intent))
                && !"NEW_REQUEST".equals(intent) && !"REGULAR_REQUEST".equals(intent)) {
            return new Result(side, false, d, missing, intent, conf, false, new ArrayList<>(pii));
        }
        if (missing.isEmpty()) {
            if (!offer && d.startHour != null && d.estHours != null) d.endHour = Math.max(d.endHour == null ? 0 : d.endHour, d.startHour + d.estHours);
            d.summarySpeech = summary(d, offer);
            reply = voice ? d.summarySpeech : "정리했어요. 아래 요약을 확인하고 괜찮으면 올려 주세요.";
        } else {
            reply = ask(missing.get(0), offer, voice, d);
        }
        return new Result(reply, missing.isEmpty(), d, missing, intent, conf, false, new ArrayList<>(pii));
    }

    /** 소리로 읽어 줄 요약에 반복 여부를 덧붙인다(올리기 전에 몇 건이 올라가는지 알 수 있게). */
    public static String withRepeat(String summary, String repeat) {
        if (summary == null || repeat == null || repeat.isBlank() || summary.contains("반복")) return summary;
        String tail = repeat.replace(" ", "").startsWith("매주")
                ? "매주 반복으로 4주치를 올릴까요?"
                : repeat + " 반복은 아직 안 돼서 이번 한 번만 올려요. 올릴까요?";
        return summary.contains("올릴까요?") ? summary.replace("올릴까요?", tail) : summary + " " + tail;
    }

    static String ask(String field, boolean offer, boolean voice, Draft d) {
        if (d.hoursTooLong && field.equals("estHours"))
            return "한 번에 최대 4시간까지예요. 몇 시간으로 할까요?";
        return switch (field) {
            case "content" -> offer
                    ? (voice ? "어떤 도움을 드릴 수 있으세요?" : "어떤 도움을 드릴 수 있나요? 예를 들어 병원 동행, 장보기, 휴대폰 사용 도움처럼 말씀해 주세요.")
                    : (voice ? "어떤 도움이 필요하세요?" : "어떤 도움이 필요하세요? 예를 들어 병원 동행, 장보기, 휴대폰 사용 같은 거요.");
            case "date" -> voice ? "며칠에 하면 좋을까요?" : "날짜는 언제가 좋을까요? \"다음 주 화요일\"이나 \"14일\"처럼 말씀해 주세요.";
            case "startHour" -> voice ? "몇 시에 시작할까요?" : "몇 시에 시작하면 좋을까요? \"오후 두 시\"처럼 말씀해 주세요.";
            case "endHour" -> voice ? "몇 시까지 가능하세요?" : "몇 시까지 가능하세요? 예를 들어 \"한 시부터 다섯 시까지\"처럼요.";
            case "estHours" -> voice ? "몇 시간 정도 걸릴까요?" : "몇 시간 정도 걸릴까요? 30분 단위로 최대 4시간까지예요.";
            case "area" -> voice ? "어느 동네에서 하나요?" : "어느 동네에서 하나요? \"행복동\"처럼 동 이름으로 말씀해 주세요.";
            default -> "조금 더 자세히 말씀해 주세요.";
        };
    }

    static String summary(Draft d, boolean offer) {
        String when = KoreanParser.dateSpeech(d.date) + " " + KoreanParser.hourSpeech(d.startHour);
        if (offer) {
            return when + "부터 " + KoreanParser.hourSpeech(d.endHour) + "까지, " + d.area + " 봉사 가능 공고를 만들었어요. 올릴까요?";
        }
        String main = d.categories.get(0);
        String label = switch (main) {
            case "동행" -> (d.place != null && d.place.matches(".*(병원|의원|과|한의원|보건소)$")) ? "병원 동행" : "동행";
            case "장보기·심부름" -> "장보기";
            case "디지털 도움" -> "휴대폰 도움";
            case "말동무·정서" -> "말벗";
            case "가벼운 집안일" -> "집안일 도움";
            default -> "정리 도움";
        };
        return when + "부터 " + KoreanParser.durationSpeech(d.estHours) + ", " + label + " 요청서를 만들었어요. 올릴까요?";
    }
}
