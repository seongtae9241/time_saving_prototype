package com.timesavings.ai;

import com.timesavings.service.RequestService;
import com.timesavings.service.Rules;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 7.13 LLM 추가 B의 코드 판정(키가 없을 때의 결과이자 AI 결과를 감싸는 안전 장치).
 * 방문 전 브리핑 규칙 표, 메시지 안전 감지, 일정 변경 감지, 빠른 답장 후보. 숫자·규칙 판정은 모두 여기(코드)에서 한다.
 */
public final class ActivityRules {
    private ActivityRules() {}

    // ===================== 1. 방문 전 브리핑 =====================
    /** 카테고리별 방문 규칙(코드 표). */
    public static final Map<String, List<String>> CATEGORY_RULES = Map.of(
            "동행", List.of("진료·치료 결정은 어르신 본인이 해요. 함께 듣고 기억을 돕는 데까지만", "부축까지만 해요. 업거나 안아 옮기지 않아요"),
            "장보기·심부름", List.of("영수증을 꼭 받아 드리고 잔돈을 그 자리에서 함께 확인해요", "카드·통장은 맡지 않아요. 장 볼 돈은 미리 준비된 것만 써요"),
            "디지털 도움", List.of("비밀번호·인증번호는 어르신이 직접 입력하게 하고 화면을 보지 않아요", "송금·결제는 대신 하지 않아요"),
            "말동무·정서", List.of("천천히, 짧게, 또박또박 말해요", "같은 이야기를 반복하셔도 처음 듣는 것처럼 반응해요"),
            "생활 정리", List.of("버릴 물건은 하나하나 어르신께 확인받아요", "귀중품·서류는 손대지 않고 자리만 알려 드려요"),
            "가벼운 집안일", List.of("높은 곳·무거운 짐·전기·가스 일은 하지 않아요", "칼·불을 쓸 때는 어르신과 함께 확인해요"));

    /** 받는 분이 어르신이 아니거나 나이대를 모를 때 쓰는 중립 문장(같은 규칙을 나이와 상관없는 말로). */
    public static final Map<String, List<String>> CATEGORY_RULES_NEUTRAL = Map.of(
            "동행", List.of("진료·치료 결정은 받는 분 본인이 해요. 함께 듣고 기억을 돕는 데까지만", "부축까지만 해요. 업거나 안아 옮기지 않아요"),
            "장보기·심부름", CATEGORY_RULES.get("장보기·심부름"),
            "디지털 도움", List.of("비밀번호·인증번호는 받는 분이 직접 입력하게 하고 화면을 보지 않아요", "송금·결제는 대신 하지 않아요"),
            "말동무·정서", List.of("상대의 말을 끝까지 듣고 편하게 이야기를 나눠요", "사적인 질문은 삼가고 불편해하는 이야기는 피해요"),
            "생활 정리", List.of("버릴 물건은 하나하나 받는 분께 확인받아요", "귀중품·서류는 손대지 않고 자리만 알려 드려요"),
            "가벼운 집안일", List.of("높은 곳·무거운 짐·전기·가스 일은 하지 않아요", "칼·불을 쓸 때는 받는 분과 함께 확인해요"));

    /** senior 가 true 일 때만 어르신용 문장, 아니면(일반 사용자·나이대 모름) 중립 문장. */
    public static List<String> categoryRules(String category, Object senior) {
        return (Boolean.TRUE.equals(senior) ? CATEGORY_RULES : CATEGORY_RULES_NEUTRAL).getOrDefault(category, List.of());
    }

    /** 나이대 글자("70대", "80대 이상")로 어르신(60대 이상)인지 본다. 모르면 null. */
    public static Boolean seniorOf(String ageGroup) {
        if (ageGroup == null || ageGroup.isBlank()) return null;
        java.util.regex.Matcher m = Pattern.compile("(\\d{2,3})").matcher(ageGroup);
        return m.find() ? Integer.parseInt(m.group(1)) >= 60 : null;
    }

    /** 교육 규칙 카드에서 가져온 공통 금지(신체 돌봄·금전·연락처). */
    public static final List<String> EDU_RULES = List.of(
            "목욕·기저귀·파스 붙이기 같은 신체 돌봄과 약 먹이기는 하지 않아요",
            "돈·선물은 마음만 받고 정중히 사양해요",
            "전화번호·카톡 아이디는 주고받지 않아요. 연락은 앱 메시지로만");

    /** basics: alias(표시 이름)·dong·content·caution·when·senior(받는 분이 어르신인가, 없으면 중립 문장). 지난 전달 메모는 받지 않는다. */
    public static List<String> briefingRule(Map<String, Object> basics, String category) {
        List<String> out = new ArrayList<>();
        String head = String.join(" · ", nonBlank(basics.get("alias"), basics.get("dong"), basics.get("content")));
        Object when = basics.get("when");
        out.add(head + (when == null ? "" : " (" + when + ")"));
        Object caution = basics.get("caution");
        if (caution != null && !caution.toString().isBlank()) out.add("주의: " + caution.toString().trim());
        out.addAll(categoryRules(category, basics.get("senior")));
        out.add(EDU_RULES.get(0));
        if (out.size() < 6) out.add(EDU_RULES.get(2));
        if (out.size() < 6 && ("장보기·심부름".equals(category) || "디지털 도움".equals(category) || "말동무·정서".equals(category))) out.add(EDU_RULES.get(1));
        return out.subList(0, Math.min(6, out.size()));
    }

    private static List<String> nonBlank(Object... xs) {
        List<String> out = new ArrayList<>();
        for (Object x : xs) if (x != null && !x.toString().isBlank()) out.add(x.toString().trim());
        return out;
    }

    // ===================== 2. 메시지 안전 감지 =====================
    public record Safety(String level, List<String> reasons) {
        public boolean flagged() { return !"none".equals(level); }
    }

    public static final List<String> REASONS = List.of("연락처 교환", "금전·선물 요구", "앱 밖 만남 유도", "부적절한 말");
    private static final Pattern P_CONTACT = Pattern.compile("번호\\s*(좀|를|도)?\\s*(알려|줘|주세|주실|남겨|드릴|줄게)|전화\\s*(해\\s*줘|해\\s*주세|드릴게|할게요|번호)|문자\\s*(로|해|줘|주세)|따로\\s*연락|개인\\s*연락|연락처|폰\\s*번호|공일공|010|인스타|라인\\s*아이디|텔레그램");
    private static final Pattern P_MONEY_HARD = Pattern.compile("용돈|현금|송금|입금|계좌|상품권|기프티콘|사례금|돈\\s*(좀|을|를)?\\s*(빌려|줘|주세|보내|드릴|줄게|받)|빌려\\s*(줘|주세|줄|달)");
    private static final Pattern P_MONEY_SOFT = Pattern.compile("선물|사례|밥\\s*(한\\s*번\\s*)?(사|살게|살께|대접)|커피\\s*(사|살게)|답례");
    private static final Pattern P_MEET = Pattern.compile("따로\\s*(만나|보자|봬|볼까)|우리\\s*끼리|우리끼리|앱\\s*(말고|없이|밖)|개인적으로\\s*(만나|보자|봬|볼까|연락)|밖에서\\s*(만나|보자)|몰래|비밀로");
    private static final Pattern P_RUDE_HARD = Pattern.compile("꺼져|닥쳐|미친|병신|씨발|시발|개새|섹시|뽀뽀|사귀자|애인\\s*(해|하자|있)|안아\\s*줘|만져");
    private static final Pattern P_RUDE_SOFT = Pattern.compile("바보|멍청|짜증\\s*나|느려\\s*터|답답해\\s*죽|예쁘(네|다|시네)|잘생기");

    public static Safety safety(String text) {
        if (text == null || text.isBlank()) return new Safety("none", List.of());
        LinkedHashSet<String> hard = new LinkedHashSet<>(), soft = new LinkedHashSet<>();
        if (RequestService.CONTACT.matcher(text).find()) {
            String t = text;
            if (Pattern.compile("01[016789][-\\s.]?\\d{3,4}[-\\s.]?\\d{4}|카톡|아이디").matcher(t).find()) hard.add("연락처 교환");
            if (Pattern.compile("계좌|송금|입금|용돈|현금|기프티콘").matcher(t).find()) hard.add("금전·선물 요구");
        }
        if (P_CONTACT.matcher(text).find()) hard.add("연락처 교환");
        if (P_MONEY_HARD.matcher(text).find()) hard.add("금전·선물 요구");
        else if (P_MONEY_SOFT.matcher(text).find()) soft.add("금전·선물 요구");
        if (P_MEET.matcher(text).find()) hard.add("앱 밖 만남 유도");
        if (P_RUDE_HARD.matcher(text).find()) hard.add("부적절한 말");
        else if (P_RUDE_SOFT.matcher(text).find()) soft.add("부적절한 말");
        soft.removeAll(hard);
        List<String> reasons = new ArrayList<>(hard);
        reasons.addAll(soft);
        String level = !hard.isEmpty() ? "warn" : !soft.isEmpty() ? "caution" : "none";
        return new Safety(level, reasons);
    }

    public static int levelRank(String l) { return "warn".equals(l) ? 2 : "caution".equals(l) ? 1 : 0; }

    /** 받는 사람 화면의 경고 띠 문장. */
    public static String warnText(Safety s) {
        if (!s.flagged()) return null;
        String why = String.join("·", s.reasons());
        return ("warn".equals(s.level()) ? "주의가 필요한 메시지예요" : "한 번 살펴봐 주세요") + (why.isEmpty() ? "" : " (" + why + ")")
                + ". 연락처·돈·앱 밖 만남은 주고받지 않아요. 불편하면 신고해 주세요.";
    }

    // ===================== 3. 일정 변경 감지 =====================
    /** kind: DELAY(늦추기)·EARLY(당기기)·TIME(시각 바꾸기)·DATE(날짜 바꾸기)·CANCEL(취소 의사). */
    public record Schedule(String kind, String text, Integer deltaMin, Double newStartHour) {}

    private static final String NUM = "(\\d+|한|두|세|네|반)";
    private static final Pattern S_DELAY_H = Pattern.compile(NUM + "\\s*시간(\\s*반)?\\s*(정도|쯤|가량)?\\s*(늦|미루|미뤄|뒤로|늘)");
    private static final Pattern S_DELAY_M = Pattern.compile("(\\d+)\\s*분\\s*(정도|쯤|가량)?\\s*(늦|미루|미뤄|뒤로)");
    private static final Pattern S_EARLY_H = Pattern.compile(NUM + "\\s*시간(\\s*반)?\\s*(정도|쯤)?\\s*(일찍|당겨|당기|앞당|빨리)");
    private static final Pattern S_EARLY_M = Pattern.compile("(\\d+)\\s*분\\s*(정도|쯤)?\\s*(일찍|당겨|당기|앞당|빨리)");
    private static final Pattern S_TIME = Pattern.compile("(오전|오후|아침|저녁|낮)?\\s*(\\d{1,2})\\s*시(\\s*(반|30\\s*분))?\\s*(로|으로|에|쯤으로|쯤에)\\s*(바꿔|바꾸|바꿀|옮겨|옮기|옮길|변경|해도|하면|할까|가능|괜찮|어때|어떠|와\\s*주|오셔도|와도|만나도|봬도)");
    private static final Pattern S_DATE = Pattern.compile("(다음\\s*주|담주|다음\\s*번|내일|모레|다른\\s*날|주말|(\\d{1,2})\\s*일|[월화수목금토일]요일)\\s*(로|으로|에)?\\s*(바꿔|바꾸|바꿀|옮겨|옮기|미뤄|미루|미룰|변경|해도|하면|할까|가능|괜찮|어때|어떠|만나|봬도|와\\s*주|오셔도|와도)");
    private static final Pattern S_DATE_BARE = Pattern.compile("다음\\s*주로|다른\\s*날로|담주로");
    private static final Pattern S_CANCEL = Pattern.compile("취소\\s*(해야|할게|하고\\s*싶|해도|해\\s*주세|하겠|할까)|못\\s*(갈|가|올|오)\\s*(것|겠|게|듯)|못\\s*갈\\s*것\\s*같");

    private static double num(String s) {
        return switch (s) { case "한" -> 1; case "두" -> 2; case "세" -> 3; case "네" -> 4; case "반" -> 0.5; default -> Double.parseDouble(s); };
    }

    private static String minText(int m) {
        int a = Math.abs(m);
        if (a % 60 == 0) return (a / 60) + "시간";
        if (a > 60) return (a / 60) + "시간 " + (a % 60) + "분";
        return a + "분";
    }

    private static String hourText(double h) {
        int H = (int) Math.floor(h);
        String ap = H < 12 ? "오전" : "오후";
        int h12 = H % 12 == 0 ? 12 : H % 12;
        return ap + " " + h12 + "시" + (h - H >= 0.5 ? " 30분" : "");
    }

    /** 상대 메시지가 일정 변경을 바라는지(코드). startHour: 지금 약속 시작 시각(새 시각 계산용, 없으면 null). 30분 미만 지각은 변경이 아니다. */
    public static Schedule schedule(String text, Double startHour) {
        if (text == null || text.isBlank()) return null;
        String t = text.replace("?", " ").replace("~", " ");
        Matcher m;
        if ((m = S_CANCEL.matcher(t)).find()) return new Schedule("CANCEL", "이번 약속을 못 하게 됐다고 했어요", null, null);
        if ((m = S_DELAY_H.matcher(t)).find()) {
            int min = (int) Math.round(num(m.group(1)) * 60) + (m.group(2) != null ? 30 : 0);
            return delta(min, startHour);
        }
        if ((m = S_EARLY_H.matcher(t)).find()) {
            int min = (int) Math.round(num(m.group(1)) * 60) + (m.group(2) != null ? 30 : 0);
            return delta(-min, startHour);
        }
        if ((m = S_DELAY_M.matcher(t)).find()) {
            int min = Integer.parseInt(m.group(1));
            if (min >= 30) return delta(min, startHour);
        }
        if ((m = S_EARLY_M.matcher(t)).find()) {
            int min = Integer.parseInt(m.group(1));
            if (min >= 30) return delta(-min, startHour);
        }
        if ((m = S_TIME.matcher(t)).find()) {
            int h = Integer.parseInt(m.group(2));
            String ap = m.group(1);
            if (h <= 23) {
                boolean pm = "오후".equals(ap) || "저녁".equals(ap) || ("낮".equals(ap) && h < 6) || (ap == null && h >= 1 && h <= 6);
                double nh = (pm && h < 12 ? h + 12 : h) + (m.group(3) != null ? 0.5 : 0);
                return new Schedule("TIME", "시작 시각을 " + hourText(nh) + "로 바꾸자고 했어요", startHour == null ? null : (int) Math.round((nh - startHour) * 60), nh);
            }
        }
        String when = null;
        if ((m = S_DATE.matcher(t)).find()) when = m.group(1);
        else if ((m = S_DATE_BARE.matcher(t)).find()) when = m.group().replaceAll("로$", "");
        if (when != null) return new Schedule("DATE", "날짜를 " + Rules.ro(when.replaceAll("\\s+", " ").trim()) + " 바꾸자고 했어요", null, null);
        return null;
    }

    private static Schedule delta(int min, Double startHour) {
        Double nh = startHour == null ? null : Rules.round05(startHour + min / 60.0);
        String text = "약속을 " + minText(min) + " " + (min > 0 ? "늦추자고" : "당기자고") + " 했어요" + (nh == null ? "" : " (" + hourText(nh) + " 시작)");
        return new Schedule(min > 0 ? "DELAY" : "EARLY", text, min, nh);
    }

    public static final Set<String> SCHEDULE_KINDS = Set.of("DELAY", "EARLY", "TIME", "DATE", "CANCEL");

    // ===================== 3. 빠른 답장 =====================
    public static List<String> replies(String text, Safety s, Schedule sc) {
        String t = text == null ? "" : text;
        if (s != null && "warn".equals(s.level())) {
            List<String> out = new ArrayList<>();
            if (s.reasons().contains("연락처 교환") || s.reasons().contains("앱 밖 만남 유도")) out.add("연락은 앱 메시지로만 할게요");
            if (s.reasons().contains("금전·선물 요구")) out.add("마음만 감사히 받을게요");
            out.add("그건 어려워요. 담당자에게 물어볼게요");
            return out.subList(0, Math.min(3, out.size()));
        }
        if (sc != null) {
            if ("CANCEL".equals(sc.kind())) return List.of("알겠어요. 다음에 다시 부탁드려요", "괜찮아요. 몸 조심하세요");
            return List.of("네, 그렇게 해요", "그 시간은 어려워요. 원래 시간이 좋아요", "확인하고 다시 말씀드릴게요");
        }
        if (t.matches("(?s).*(고마|감사).*")) return List.of("저도 고마워요", "다음에 또 봬요");
        if (t.matches("(?s).*(도착|다 왔|앞이에요|앞에 왔).*")) return List.of("네, 지금 나갈게요", "조금만 기다려 주세요");
        if (t.matches("(?s).*늦.*")) return List.of("괜찮아요, 천천히 오세요", "네, 기다릴게요");
        if (t.matches("(?s).*(\\?|까요|나요|세요\\?|되나|될까|어때).*")) return List.of("네, 좋아요", "아니요, 괜찮아요", "확인하고 알려 드릴게요");
        return List.of("네, 알겠어요", "고마워요", "확인했어요");
    }

    /** AI 가 준 답장 후보 안전 거르기: 연락처·돈 패턴, 너무 긴 문장은 버린다. */
    public static List<String> cleanReplies(List<String> xs) {
        List<String> out = new ArrayList<>();
        for (String x : xs) {
            String s = x == null ? "" : x.trim();
            if (s.isEmpty() || s.length() > 40) continue;
            if (safety(s).flagged()) continue;
            if (!out.contains(s)) out.add(s);
            if (out.size() >= 3) break;
        }
        return out;
    }
}
