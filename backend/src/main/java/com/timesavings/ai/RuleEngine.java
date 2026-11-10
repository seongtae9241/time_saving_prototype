package com.timesavings.ai;

import com.timesavings.domain.Enums.FilterLevel;
import com.timesavings.domain.Enums.Level;
import com.timesavings.domain.PublicService;
import com.timesavings.service.Rules;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AI 키가 없거나 호출이 실패할 때 쓰는 규칙 기반 대체 구현(키워드·정규식·템플릿). 상태가 없다. */
public final class RuleEngine {
    private RuleEngine() {}

    // ================= 금지 요청 필터 (B-09) =================
    public record FilterResult(FilterLevel level, String reason, String blockCategory, List<String> serviceKeywords) {}

    private record Rule(Pattern p, String category, String reason, List<String> svc) {}

    private static final List<Rule> BLOCK = List.of(
            new Rule(Pattern.compile("목욕|씻겨|씻기|샤워\\s*시켜|기저귀|대소변|용변|소변|대변|파스|옷\\s*갈아입|욕창|안마|마사지|업어|안아\\s*(서|올려|옮겨)|침대에서\\s*옮|휠체어로\\s*옮"),
                    "신체 돌봄", "목욕·기저귀·파스 붙이기처럼 몸을 직접 돌보는 일은 봉사로 할 수 없어요. 부축까지만 가능해요.", List.of("장기요양", "방문요양", "방문목욕", "신체")),
            new Rule(Pattern.compile("주사|투약|약\\s*(을|좀)?\\s*(먹여|먹이)|약\\s*챙겨\\s*먹여|인슐린|혈당\\s*(재|측정)|소독|드레싱|석션|상처\\s*치료|관장|약\\s*발라"),
                    "의료 행위", "주사·투약·약 먹이기 같은 의료 행위는 봉사로 할 수 없어요.", List.of("방문건강", "방문간호", "보건소", "의료")),
            new Rule(Pattern.compile("통장|카드\\s*(를|좀)?\\s*(맡|대신|빌려)|현금\\s*(을)?\\s*맡|돈\\s*(을|좀)?\\s*(맡|찾아|관리|빌려)|대출|송금\\s*대신|이체\\s*(해|대신)|계좌|비밀번호\\s*(를)?\\s*(알려|대신)|공인인증서\\s*대신|용돈\\s*관리"),
                    "금전 관리", "통장·카드·현금을 맡거나 돈을 대신 관리하는 일은 봉사로 할 수 없어요.", List.of("금전", "후견", "재산")),
            new Rule(Pattern.compile("사다리|지붕|고소\\s*작업|높은\\s*곳|전기\\s*공사|배선|두꺼비집|차단기\\s*수리|가스\\s*(배관|공사|수리|연결)|보일러\\s*(수리|교체)|용접|전기톱|창문\\s*밖"),
                    "위험 작업", "사다리·높은 곳·전기·가스 공사 같은 위험한 작업은 봉사로 할 수 없어요.", List.of("주거", "집수리", "수선", "안전"))
    );
    private static final List<Rule> CAUTION = List.of(
            new Rule(Pattern.compile("부축"), "부축", "부축은 가능하지만 넘어지지 않게 천천히, 신체 접촉은 부축까지만 해 주세요.", List.of()),
            new Rule(Pattern.compile("은행|ATM|현금\\s*인출"), "금전", "은행 동행은 가능하지만 돈·카드·비밀번호는 본인이 직접 다뤄야 해요.", List.of("금전")),
            new Rule(Pattern.compile("약국|처방|약\\s*(타|받)"), "약", "약을 받아 오는 것은 괜찮지만 먹이거나 챙겨 드리는 것은 할 수 없어요.", List.of()),
            new Rule(Pattern.compile("칼|가스레인지|불\\s*(을|써)|튀김|공구|드라이버"), "도구", "칼·불·공구를 쓰는 일은 다치지 않게 주의가 필요해요.", List.of()),
            new Rule(Pattern.compile("반려견|강아지|개가\\s*있"), "반려동물", "반려동물이 있다는 점을 미리 알고 방문해 주세요.", List.of()),
            new Rule(Pattern.compile("운전|차로\\s*모|자가용"), "운전", "봉사자 차량 운전은 사고 위험이 있어 대중교통·택시 이용을 권해요.", List.of()),
            new Rule(Pattern.compile("무거|20\\s*kg|20\\s*키로|이사"), "무거운 짐", "무거운 짐은 무리하지 않는 선에서 나눠 옮겨 주세요.", List.of())
    );
    private static final Pattern NEGATED_TAIL = Pattern.compile("^[^.!?\\n]{0,8}(말고|빼고|제외|안\\s*해도|필요\\s*없|하지\\s*않아도|는\\s*안\\s*돼)");

    private static boolean hit(Pattern p, String t) {
        Matcher m = p.matcher(t);
        while (m.find()) {
            if (!NEGATED_TAIL.matcher(t.substring(m.end())).find()) return true;
        }
        return false;
    }

    public static FilterResult filter(String text) {
        String t = text == null ? "" : text;
        for (Rule r : BLOCK) if (hit(r.p, t)) return new FilterResult(FilterLevel.BLOCK, r.reason, r.category, r.svc);
        List<String> reasons = new ArrayList<>();
        List<String> svc = new ArrayList<>();
        for (Rule r : CAUTION) if (hit(r.p, t)) { reasons.add(r.reason); svc.addAll(r.svc); }
        if (!reasons.isEmpty()) return new FilterResult(FilterLevel.CAUTION, String.join(" ", reasons), null, svc);
        return new FilterResult(FilterLevel.ALLOW, "봉사로 할 수 있는 도움이에요.", null, List.of());
    }

    // ================= 난이도 (B-08, B-27) =================
    public record DifficultyResult(Level level, String reason, Map<String, Integer> factors) {}

    private static int score(String t, String... patterns) {
        int s = 0;
        for (String p : patterns) if (Pattern.compile(p).matcher(t).find()) s++;
        return Math.min(2, s);
    }

    public static DifficultyResult difficulty(String text, Collection<String> cats) {
        String t = text == null ? "" : text;
        Level base = cats == null || cats.isEmpty() ? Level.BASIC : Rules.maxLevel(cats);
        Map<String, Integer> f = new LinkedHashMap<>();
        f.put("physical", score(t, "무거|쌀|생수|kg|키로|나르|옮기|이사|대청소|짐", "20\\s*kg|20\\s*키로|여러\\s*(개|묶음)|묶음|계단으로"));
        int resp = score(t, "병원|진료|약국|은행|관공서|동행|모시고|모셔다|데려다|부축", "수술|검사|응급|재진");
        if (cats != null && cats.contains("동행")) resp = 2;
        f.put("responsibility", resp);
        f.put("risk", score(t, "칼|불|가스레인지|공구|반려견|강아지|미끄"));
        f.put("expertise", score(t, "수리|설치|자격증|서류|정부24|인증서|본인인증|키오스크", "세금|민원|신고서"));
        f.put("travel", score(t, "버스|택시|지하철|멀리|시장|다른\\s*동|차로|왕복"));
        int access = score(t, "계단|엘리베이터\\s*(가|는)?\\s*없|[3-9]\\s*층|옥탑|반지하");
        if (Pattern.compile("엘리베이터\\s*(가|는|도)?\\s*있").matcher(t).find()) access = Math.max(0, access - 1);
        f.put("access", access);
        int other = f.get("physical") + f.get("risk") + f.get("expertise") + f.get("travel") + f.get("access");
        Level calc = Level.BASIC;
        if (resp >= 2 || (resp >= 1 && other >= 2)) calc = Level.RESPONSIBLE;
        else if ((f.get("physical") >= 1 && (access >= 1 || f.get("physical") >= 2)) || other >= 3) calc = Level.EFFORT;
        Level level = Rules.max(base, calc);

        List<String> why = new ArrayList<>();
        if (base != Level.BASIC) why.add(String.join("·", cats.stream().filter(c -> Rules.categoryLevel(c) == base).toList()) + "은(는) 기본 " + Rules.levelKo(base) + " 등급");
        if (f.get("physical") > 0) why.add("무거운 짐 등 신체 부담");
        if (resp > 0 && base != Level.RESPONSIBLE) why.add("병원·기관 동행 등 책임");
        if (f.get("risk") > 0) why.add("도구·반려동물 등 위험 요소");
        if (f.get("expertise") > 0) why.add("서류·기기 설정 등 전문성");
        if (f.get("travel") > 0) why.add("이동 거리");
        if (access > 0) why.add("계단 등 접근성");
        String reason = why.isEmpty() ? "특별한 부담 요소가 없는 기본 활동이에요." :
                String.join(", ", why) + " → " + Rules.levelKo(level) + " 등급(×" + Rules.weight(level) + ")을 제안해요.";
        return new DifficultyResult(level, reason, f);
    }

    // ================= 활동 일지 정리 (B-24) =================
    public static String logTidy(String raw) {
        String s = KoreanParser.removeFillers(raw == null ? "" : raw).replaceAll("ㅎ+|ㅋ+|ㅠ+|ㅜ+", "").trim();
        if (s.isEmpty()) return "";
        String[] parts = s.split("\\s*(?:[.,·\\n/]|그리고|그 다음에|다음에|하고 나서)\\s*");
        List<String> lines = new ArrayList<>();
        for (String p : parts) {
            String x = p.trim();
            if (x.length() < 2) continue;
            x = x.replaceAll("(했음|함)$", "했어요").replaceAll("(드림)$", "드렸어요").replaceAll("(나눔)$", "나눴어요")
                    .replaceAll("(옴|왔음)$", "왔어요").replaceAll("(감)$", "갔어요")
                    .replaceAll("버림$", "버렸어요").replaceAll("씻음$", "씻었어요").replaceAll("(?<=\\s)삼$", "샀어요")
                    .replaceAll("봄$", "봤어요").replaceAll("줌$", "줬어요").replaceAll("옮김$", "옮겼어요").replaceAll("들름$", "들렀어요")
                    .replaceAll("알려\\s*줌$", "알려 드렸어요").replaceAll("걸음$", "걸었어요");
            if (!x.matches(".*[.!?요다]$")) x = x + ".";
            else if (!x.matches(".*[.!?]$")) x = x + ".";
            lines.add(x);
            if (lines.size() == 3) break;
        }
        if (lines.isEmpty()) lines.add(s.endsWith(".") ? s : s + ".");
        return String.join("\n", lines);
    }

    // ================= 교육 역할극 (프로토타입 rpClassify / rpScore) =================
    public record RpClass(String quality, String flag) {}

    private static final Pattern RP_CONTACT = Pattern.compile("010|번호는|번호 알려|카톡|아이디는|아이디 알려");
    private static final Pattern RP_MONEY = Pattern.compile("받을게|받겠|이번만|잘 마실|잘 먹을");
    private static final Pattern RP_BODY = Pattern.compile("붙여 드릴|붙여드릴|씻겨|먹여 드");
    private static final Pattern RP_PRIVACY = Pattern.compile("비밀번호 알려|인증번호 알려|폰 주세요");
    private static final Pattern RP_REFUSE = Pattern.compile("안 돼|안돼|못 해|할 수 없|규칙");

    public static RpClass rpClassify(String t) {
        String flag = RP_CONTACT.matcher(t).find() ? "연락처"
                : (RP_MONEY.matcher(t).find() && !t.contains("마음만")) ? "금전·선물"
                : RP_BODY.matcher(t).find() ? "신체 돌봄"
                : RP_PRIVACY.matcher(t).find() ? "개인정보" : null;
        if (flag != null && !RP_REFUSE.matcher(t).find()) return new RpClass("bad", flag);
        return new RpClass(t.length() >= 22 ? "good" : "ok", null);
    }

    public record RpScore(boolean safe, int comm, int emp, boolean pass, List<String> badFlags) {}

    public static RpScore rpScore(List<String> qualities, List<String> flags) {
        long good = qualities.stream().filter("good"::equals).count();
        long ok = qualities.stream().filter("ok"::equals).count();
        long poor = qualities.stream().filter("poor"::equals).count();
        long bad = qualities.stream().filter("bad"::equals).count();
        int comm = (int) Math.min(100, 40 + 20 * good + 10 * ok);
        int emp = (int) Math.max(0, Math.min(100, 46 + 18 * good + 8 * ok - 6 * poor));
        boolean safe = bad == 0;
        return new RpScore(safe, comm, emp, safe && comm >= 60 && emp >= 60, flags.stream().filter(Objects::nonNull).toList());
    }

    // ================= 공공 서비스 검색 (P-01, RAG의 검색 단계) =================
    private static final Map<String, List<String>> TOPIC_WORDS = Map.of(
            "신체", List.of("목욕", "기저귀", "씻", "요양", "거동", "누워", "신체", "파스", "옷 갈아"),
            "의료", List.of("주사", "약", "투약", "간호", "건강", "혈압", "혈당", "병", "아프", "치매", "기억"),
            "금전", List.of("돈", "통장", "카드", "재산", "금전", "후견", "계좌"),
            "안전", List.of("응급", "쓰러", "혼자", "위급", "안전", "넘어", "화재", "가스"),
            "주거", List.of("집수리", "수리", "전기", "보일러", "지붕", "사다리", "주거", "곰팡이"),
            "일자리", List.of("일자리", "일하고", "용돈 벌", "사회활동", "소일거리"),
            "디지털", List.of("휴대폰", "스마트폰", "키오스크", "디지털", "컴퓨터", "인터넷"),
            "돌봄", List.of("돌봄", "외로", "안부", "혼자 사", "독거", "말벗", "가사"));

    public static List<PublicService> searchServices(String question, Collection<String> extraKeywords, String area, List<PublicService> all, int limit) {
        String q = (question == null ? "" : question) + " " + (extraKeywords == null ? "" : String.join(" ", extraKeywords));
        Set<String> topics = new HashSet<>();
        for (var e : TOPIC_WORDS.entrySet()) for (String w : e.getValue()) if (q.contains(w)) topics.add(e.getKey());
        record Scored(PublicService s, int score) {}
        List<Scored> list = new ArrayList<>();
        for (PublicService s : all) {
            if (area != null && s.region != null && !s.region.equals("전국") && !s.region.contains(area)) continue;
            int sc = 0;
            for (String k : s.keywords) {
                if (k.isBlank()) continue;
                if (q.contains(k)) sc += 3;
                if (topics.contains(k)) sc += 2;
            }
            if (q.contains(s.name)) sc += 5;
            if (sc > 0) list.add(new Scored(s, sc));
        }
        list.sort((a, b) -> b.score - a.score);
        return list.stream().limit(limit).map(Scored::s).toList();
    }

    public static String servicesAnswer(List<PublicService> found) {
        if (found.isEmpty())
            return "등록된 공공 서비스 중에서 딱 맞는 것을 찾지 못했어요. 가까운 행정복지센터(주민센터)에 상황을 말씀하시면 알맞은 곳을 안내받을 수 있어요.";
        StringBuilder b = new StringBuilder("이런 공공 서비스를 알아볼 수 있어요.\n");
        for (PublicService s : found) {
            b.append("• ").append(s.name).append(": ").append(s.content == null ? "" : s.content);
            if (s.applyHow != null) b.append(" 신청은 ").append(s.applyHow).append(".");
            b.append("\n");
        }
        b.append("대상이 되는지는 제가 판단할 수 없어요. 신청 전에 기관에 꼭 확인해 주세요.");
        return b.toString();
    }

    // ================= AI 도움 추천 =================
    public static boolean wantsToHelp(String q) {
        return Pattern.compile("돕고\\s*싶|도와\\s*드리고|봉사하고|봉사를\\s*하고|도울\\s*수").matcher(q).find();
    }

    // ================= 생활 변화 리포트 서술 (P-03) =================
    public static String narrative(String subjectLabel, int count, int prevCount, Map<String, Integer> byCat, List<String> logs) {
        StringBuilder b = new StringBuilder();
        int d = count - prevCount;
        b.append(subjectLabel).append("의 이번 달 활동은 ").append(count).append("회로, 지난달(").append(prevCount).append("회)");
        b.append(d > 0 ? "보다 " + d + "회 늘었어요. " : d < 0 ? "보다 " + (-d) + "회 줄었어요. " : "과 같아요. ");
        byCat.entrySet().stream().max(Map.Entry.comparingByValue())
                .ifPresent(e -> b.append(e.getKey()).append(" 활동이 ").append(e.getValue()).append("회로 가장 많았어요. "));
        if (!logs.isEmpty()) {
            String first = logs.get(0).split("\\n")[0];
            b.append("활동 일지에는 \"").append(first.length() > 40 ? first.substring(0, 40) + "…" : first).append("\" 같은 기록이 있어요.");
        } else {
            b.append("이번 달에는 남겨진 활동 일지가 없어요.");
        }
        return b.toString().trim();
    }

    public static String todayKo(LocalDate d) { return KoreanParser.dateSpeech(d); }
}
