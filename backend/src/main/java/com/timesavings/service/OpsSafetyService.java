package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.ai.AdminAi;
import com.timesavings.ai.VoiceSafety;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 7.14 운영자 안전·운영 보조(C4~C7). 집계·심각도·근거는 코드, 글은 AdminAi(LLM, 실패하면 규칙).
 * 제재·적립 보류·답변 등록·상대 등록은 하지 않는다(운영자가 기존 버튼으로). 결과는 ai_results 에 저장해 다시 쓴다.
 */
@Service
@Transactional
public class OpsSafetyService {
    public static final String TRIAGE = "REPORT_TRIAGE", ANOMALY = "ANOMALY_NOTE", INQUIRY = "INQUIRY_DRAFT";
    private final ReportRepo reports;
    private final MessageRepo messages;
    private final RequestRepo requests;
    private final PostRepo posts;
    private final UserRepo users;
    private final ElderRepo elders;
    private final InquiryRepo inquiries;
    private final NoticeRepo notices;
    private final LedgerRepo ledger;
    private final AiResultRepo results;
    private final AdminAi ai;

    public OpsSafetyService(ReportRepo reports, MessageRepo messages, RequestRepo requests, PostRepo posts, UserRepo users, ElderRepo elders,
                            InquiryRepo inquiries, NoticeRepo notices, LedgerRepo ledger, AiResultRepo results, AdminAi ai) {
        this.reports = reports; this.messages = messages; this.requests = requests; this.posts = posts; this.users = users; this.elders = elders;
        this.inquiries = inquiries; this.notices = notices; this.ledger = ledger; this.results = results; this.ai = ai;
    }

    // ---------- 저장 도우미 ----------
    private Map<String, Object> load(String kind, String key, String fp) {
        return results.findByKindAndRefKey(kind, key).filter(r -> fp == null || fp.equals(r.fingerprint))
                .map(r -> Views.parse(r.payload, new TypeReference<Map<String, Object>>() {}, null)).orElse(null);
    }

    private void store(String kind, String key, String fp, Map<String, Object> payload) {
        AiResult r = results.findByKindAndRefKey(kind, key).orElseGet(AiResult::new);
        r.kind = kind; r.refKey = key; r.fingerprint = fp; r.payload = Views.json(payload);
        r.source = (String) payload.get("source"); r.provider = (String) payload.get("provider"); r.createdAt = LocalDateTime.now();
        results.save(r);
    }

    /** 이름(사용자 이름·대상 별칭)·연락처·주소를 지운다. LLM 에 넘기기 전과 사례 문장에 쓴다. */
    String anonymize(String t) {
        if (t == null) return "";
        String s = VoiceSafety.scrub(t).text();
        for (User u : users.findAll()) if (u.name != null && u.name.length() >= 2) s = s.replace(u.name, "○○");
        for (Elder e : elders.findAll()) if (e.alias != null && e.alias.length() >= 2) s = s.replace(e.alias, "○○");
        return s;
    }

    // ===================== C5 신고 정리·우선순위 =====================
    public static final Pattern HIGH = Pattern.compile("만지|만졌|손을\\s*잡|껴안|안으려|추행|성희롱|몸을\\s*(더듬|만)|신체\\s*접촉|때리|때렸|폭행|밀쳤|밀쳐|위협|협박|죽여|가만\\s*안\\s*둬|돈을?\\s*(달라|요구|빌려|보내)|돈\\s*좀\\s*(빌|달)|송금|계좌로|빌려\\s*달|현금을?\\s*(달라|요구)|용돈을?\\s*(달라|요구)");
    public static final Pattern MEDIUM = Pattern.compile("연락처|전화번호|번호를|카톡|아이디|노쇼|안\\s*왔|오지\\s*않|욕을|욕설|반말|소리를\\s*지|불쾌|불친절|무례|집에\\s*찾아|따로\\s*만나");
    public static final List<String> LEVELS = List.of("low", "medium", "high");

    public record Severity(String level, List<String> reasons) {}

    /** 코드 규칙: 신체 접촉·금전 요구·위협은 높음. 연락처·노쇼·부적절한 언행은 보통. 그 밖은 낮음. */
    public static Severity ruleSeverity(String type, String content, int sameTargetCount) {
        String t = Objects.toString(type, ""), c = Objects.toString(content, "");
        List<String> reasons = new ArrayList<>();
        String lv = "low";
        java.util.regex.Matcher m = HIGH.matcher(c);
        if (m.find()) { lv = "high"; reasons.add("내용에 '" + m.group() + "' (신체 접촉·금전 요구·위협)"); }
        else if (t.contains("신체 접촉") && !Pattern.compile("방문\\s*외\\s*연락|연락만").matcher(c).find()) { lv = "high"; reasons.add("유형: 신체 접촉"); }
        else {
            m = MEDIUM.matcher(c);
            if (m.find()) { lv = "medium"; reasons.add("내용에 '" + m.group() + "'"); }
            else if (t.contains("연락처") || t.contains("노쇼") || t.contains("부적절") || t.contains("방문 외 연락")) { lv = "medium"; reasons.add("유형: " + t); }
            else reasons.add("유형: " + (t.isEmpty() ? "기타" : t));
        }
        if (sameTargetCount >= 2 && lv.equals("low")) { lv = "medium"; reasons.add("같은 대상 신고 " + sameTargetCount + "건"); }
        else if (sameTargetCount >= 1) reasons.add("같은 대상 다른 신고 " + sameTargetCount + "건");
        return new Severity(lv, reasons);
    }

    public static String label(String lv) { return switch (lv) { case "high" -> "높음"; case "medium" -> "보통"; default -> "낮음"; }; }

    Long targetUser(Report r) {
        if (r.targetId == null) return null;
        return switch (r.targetType) {
            case USER -> r.targetId;
            case MESSAGE -> messages.findById(r.targetId).map(m -> m.senderId).orElse(null);
            case POST -> posts.findById(r.targetId).map(p -> p.ownerId).orElse(null);
            case REQUEST -> requests.findById(r.targetId).map(q -> r.reporterId.equals(q.volunteerId) ? q.recipientId : q.volunteerId).orElse(null);
        };
    }

    /** 신고 대상 메시지·요청 맥락(이름 없이). */
    Map<String, Object> context(Report r) {
        Map<String, Object> ctx = Views.map("type", r.type, "content", anonymize(r.content), "targetType", r.targetType);
        if (r.targetId == null) return ctx;
        switch (r.targetType) {
            case MESSAGE -> messages.findById(r.targetId).ifPresent(m -> {
                ctx.put("message", anonymize(m.text));
                ctx.put("messageWarn", m.warn != null);
                requests.findById(m.requestId).ifPresent(q -> ctx.put("request", Views.map("category", q.category, "status", q.status, "date", q.date.toString())));
            });
            case REQUEST -> requests.findById(r.targetId).ifPresent(q -> ctx.put("request", Views.map("category", q.category, "status", q.status,
                    "date", q.date.toString(), "content", anonymize(q.content))));
            case POST -> posts.findById(r.targetId).ifPresent(p -> ctx.put("post", Views.map("title", anonymize(p.title), "categories", p.categories)));
            case USER -> { }
        }
        return ctx;
    }

    public Map<String, Object> triage(Report r, boolean refresh) {
        Long tu = targetUser(r);
        List<Report> all = reports.findAll();
        int sameTarget = tu == null ? 0 : (int) all.stream().filter(x -> !x.id.equals(r.id) && tu.equals(targetUser(x))).count();
        Severity code = ruleSeverity(r.type, r.content, sameTarget);
        List<Map<String, Object>> similar = all.stream().filter(x -> !x.id.equals(r.id) && x.status == ReportStatus.DONE
                        && (Objects.equals(x.type, r.type) || (tu != null && tu.equals(targetUser(x)))))
                .sorted(Comparator.comparing((Report x) -> x.createdAt).reversed()).limit(3)
                .map(x -> Views.map("id", x.id, "type", x.type, "action", x.action, "result", x.result,
                        "createdAt", x.createdAt, "sameTarget", tu != null && tu.equals(targetUser(x)))).toList();
        Map<String, Object> text = refresh ? null : load(TRIAGE, String.valueOf(r.id), null);
        if (text == null) {
            Map<String, Object> ctx = context(r);
            String summary = "[" + r.type + "] " + (r.content == null || r.content.isBlank() ? "내용 없이 접수됐어요." : anonymize(r.content));
            if (ctx.get("message") != null) summary += " 신고된 메시지: \"" + ctx.get("message") + "\"";
            Map<String, Object> rule = Views.map("summary", summary, "suggestion", switch (code.level()) {
                case "high" -> "대상 계정의 활동을 먼저 멈추는 것을 검토해 보세요. 신고자에게 안전한지 확인하고, 필요하면 112·기관에 알리는 것도 참고하세요.";
                case "medium" -> "같은 대상의 지난 신고와 메시지를 확인하고, 경고 여부를 검토해 보세요.";
                default -> "내용을 확인하고 조치 없음 또는 안내로 마무리할지 검토해 보세요.";
            }, "aiSeverity", null);
            ctx.put("codeSeverity", code.level());
            ctx.put("similarCount", similar.size());
            text = ai.triage(ctx, rule);
            store(TRIAGE, String.valueOf(r.id), null, text);
        }
        String aiSev = (String) text.get("aiSeverity");
        String fin = aiSev != null && LEVELS.indexOf(aiSev) > LEVELS.indexOf(code.level()) ? aiSev : code.level(); // 코드 규칙이 바닥, AI 는 올리기만
        Map<String, Object> out = new LinkedHashMap<>(text);
        out.put("reportId", r.id);
        out.put("severity", fin);
        out.put("severityLabel", label(fin));
        out.put("rank", LEVELS.indexOf(fin));
        out.put("codeSeverity", code.level());
        out.put("reasons", code.reasons());
        out.put("similar", similar);
        out.put("status", r.status);
        out.put("createdAt", r.createdAt);
        out.put("notice", "요약·권장 조치는 참고예요. 제재는 운영자가 직접 정해요.");
        return out;
    }

    /** 신고 접수 직후 한 번(실패해도 접수는 그대로). */
    public void onReport(Report r) {
        try { triage(r, false); } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(OpsSafetyService.class).warn("신고 정리 실패({})", e.getClass().getSimpleName());
        }
    }

    /** 정렬: 처리 전 먼저 → 심각도 높은 순 → 접수 최신순. */
    public List<Map<String, Object>> triageList() {
        return reports.findAll().stream().map(r -> triage(r, false))
                .sorted(Comparator.comparing((Map<String, Object> m) -> m.get("status") == ReportStatus.DONE)
                        .thenComparing(m -> -((Integer) m.get("rank")))
                        .thenComparing(m -> (LocalDateTime) m.get("createdAt"), Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public Map<String, Object> triageOne(Long id, boolean refresh) {
        return triage(reports.findById(id).orElseThrow(() -> ApiException.notFound("신고를 찾을 수 없어요")), refresh);
    }

    // ===================== C6 이상 적립 설명 =====================
    static String slot(double h) { return h < 12 ? "오전" : h < 17 ? "오후" : "저녁"; }

    public List<Map<String, Object>> anomalies(boolean refresh) {
        LocalDate since = LocalDate.now().minusDays(30);
        Map<String, List<Request>> g = requests.findAll().stream()
                .filter(r -> r.status == ReqStatus.DONE && r.hours <= 1.0 && !r.date.isBefore(since))
                .collect(Collectors.groupingBy(r -> r.volunteerId + ":" + (r.elderId != null ? "E" + r.elderId : "U" + r.recipientId), TreeMap::new, Collectors.toList()));
        List<Map<String, Object>> out = new ArrayList<>();
        g.forEach((k, list) -> {
            if (list.size() < 3) return;
            List<Request> l = list.stream().sorted(Comparator.comparing((Request r) -> r.date)).toList();
            Map<String, Object> ev = evidence(l);
            String fp = l.stream().map(r -> String.valueOf(r.id)).collect(Collectors.joining(","));
            Map<String, Object> note = refresh ? null : load(ANOMALY, k, fp);
            if (note == null) {
                note = ai.anomaly(ev, ruleAnomaly(ev));
                store(ANOMALY, k, fp, note);
            }
            Map<String, Object> m = new LinkedHashMap<>(note);
            m.put("key", k);
            m.put("evidence", ev);
            m.put("requestIds", l.stream().map(r -> r.id).toList());
            m.put("notice", "설명은 참고예요. 보류·해제는 운영자가 정해요.");
            out.add(m);
        });
        return out;
    }

    static Map<String, Object> evidence(List<Request> l) {
        Map<String, Integer> slots = new LinkedHashMap<>(Map.of("오전", 0, "오후", 0, "저녁", 0));
        Map<String, Integer> methods = new TreeMap<>();
        Map<String, Integer> logs = new HashMap<>();
        double sumH = 0, sumE = 0, sumLen = 0;
        for (Request r : l) {
            slots.merge(slot(r.startHour), 1, Integer::sum);
            methods.merge(r.checkMethod == null ? "code" : r.checkMethod, 1, Integer::sum);
            String log = Objects.toString(r.logText, "").trim();
            logs.merge(log, 1, Integer::sum);
            sumH += r.hours; sumE += r.earned == null ? 0 : r.earned; sumLen += log.length();
        }
        String topSlot = slots.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
        long span = l.get(l.size() - 1).date.toEpochDay() - l.get(0).date.toEpochDay();
        int sameLog = logs.values().stream().max(Integer::compare).orElse(0);
        return Views.map("count", l.size(), "avgHours", Math.round(sumH / l.size() * 10) / 10.0, "totalEarned", Math.round(sumE * 10) / 10.0,
                "slots", slots, "topSlot", topSlot, "avgLogLength", Math.round(sumLen / l.size()), "sameLogMax", sameLog,
                "checkMethods", methods, "spanDays", span, "avgGapDays", l.size() > 1 ? Math.round(span * 10.0 / (l.size() - 1)) / 10.0 : 0,
                "category", l.get(0).category, "held", l.stream().filter(r -> r.anomalyHold).count());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> ruleAnomaly(Map<String, Object> ev) {
        String text = "같은 봉사자와 받는 분이 최근 30일 동안 1시간 이하 활동을 " + ev.get("count") + "번 했어요(평균 " + ev.get("avgHours")
                + "시간, " + ev.get("spanDays") + "일 동안 평균 " + ev.get("avgGapDays") + "일 간격). 주로 " + ev.get("topSlot") + " 활동이고, 일지는 평균 "
                + ev.get("avgLogLength") + "자예요. 단골 활동일 수도 있어 이것만으로 부정이라고 볼 수는 없어요.";
        List<String> checks = new ArrayList<>();
        Map<String, Integer> m = (Map<String, Integer>) ev.get("checkMethods");
        if (m.getOrDefault("manual", 0) > 0) checks.add("버튼 확인(코드 없이) " + m.get("manual") + "건 — 실제 방문 확인하기");
        if (((Number) ev.get("sameLogMax")).intValue() >= 2) checks.add("같은 일지 문장 " + ev.get("sameLogMax") + "번 — 내용 비교하기");
        if (((Number) ev.get("avgLogLength")).intValue() < 15) checks.add("일지가 짧아요 — 활동 내용 물어보기");
        checks.add("받는 분(또는 담당자)에게 방문했는지 확인하기");
        return Views.map("text", text, "checks", checks);
    }

    // ===================== C7 문의 답변 초안 =====================
    /** 계약서 3장 규칙 요약(근거로 쓰는 코드 상수). */
    public static final List<String[]> RULES = List.of(
            new String[]{"적립", "적립·정산", "적립 시간 = 예정 시간 × 가중치(기본 1.0, 수고 1.2), 0.5 단위 반올림이에요. 받는 분이 일지를 확인하거나 24시간 뒤 자동 확정되면 적립돼요."},
            new String[]{"일지", "활동 일지", "끝 QR(번호) 뒤 활동 일지를 내야 정산돼요. 일지가 없으면 자동 확정되지 않아요."},
            new String[]{"취소", "취소·노쇼", "시작 24시간 전까지는 무료로 취소돼요. 그 뒤 봉사자가 취소하거나 오지 않으면 경고가 쌓여요."},
            new String[]{"사용", "본인 사용 조건", "직접 적립한 시간이 10시간 이상이어야 본인 요청에 쓸 수 있어요. 선물받은 시간은 바로 쓸 수 있어요."},
            new String[]{"선물", "시간 선물", "시간 선물은 승인된 가족 연결 상대에게만 돼요. 그 밖의 양도·매매는 없어요."},
            new String[]{"보류", "보류", "받는 쪽은 일정이 확정될 때 예정 시간 기준(난이도 가중치 포함)으로 보류되고, 활동이 끝나면 예정 시간 기준(양쪽이 받아들인 시간 조정이 있으면 그 시간)으로 차감돼요."},
            new String[]{"연락처", "연락처·돈", "연락처·계좌·돈은 주고받지 않아요. 다시 만나고 싶으면 단골 요청을 써요."},
            new String[]{"인증", "봉사 자격", "신분증 인증이 끝나야 봉사 가능 공고·제안을 할 수 있어요."});
    private static final Map<String, Pattern> RULE_HINT = Map.of(
            "적립", Pattern.compile("적립|시간이\\s*안|안\\s*들어|정산|들어오"),
            "일지", Pattern.compile("적립|일지|확정|정산"),
            "취소", Pattern.compile("취소|노쇼|못\\s*가|경고"),
            "사용", Pattern.compile("사용|쓸\\s*수|요청이\\s*안|10시간"),
            "선물", Pattern.compile("선물|가족|보내"),
            "보류", Pattern.compile("보류|차감|빠졌|줄었"),
            "연락처", Pattern.compile("연락처|번호|카톡|돈"),
            "인증", Pattern.compile("인증|신분증|봉사\\s*가능|제안"));

    @SuppressWarnings("unchecked")
    public Map<String, Object> inquiryDraft(Long id, boolean refresh) {
        Inquiry q = inquiries.findById(id).orElseThrow(() -> ApiException.notFound("문의를 찾을 수 없어요"));
        String all = q.type + " " + q.title + " " + Objects.toString(q.content, "");
        List<Map<String, Object>> basis = new ArrayList<>();
        for (String[] r : RULES) if (RULE_HINT.get(r[0]).matcher(all).find()) basis.add(Views.map("kind", "rule", "label", "이용 규칙 · " + r[1], "text", r[2]));
        if (basis.isEmpty()) basis.add(Views.map("kind", "rule", "label", "이용 규칙 · " + RULES.get(0)[1], "text", RULES.get(0)[2]));
        List<String> words = Arrays.stream(all.split("[\\s,.?!]+")).filter(w -> w.length() >= 2).toList();
        notices.findAll().stream().sorted(Comparator.comparing((Notice n) -> n.createdAt).reversed())
                .filter(n -> words.stream().anyMatch(w -> (n.title + " " + n.content).contains(w.replaceAll("(은|는|이|가|을|를|요|에)$", "")) && w.length() >= 2)).limit(2)
                .forEach(n -> basis.add(Views.map("kind", "notice", "label", "공지 · " + n.title, "text", n.content)));
        List<Ledger> led = ledger.findByUserIdOrderByCreatedAtDescIdDesc(q.userId).stream().limit(5).toList();
        if (!led.isEmpty()) basis.add(Views.map("kind", "bank", "label", "통장 최근 내역 " + led.size() + "건",
                "text", led.stream().map(l -> l.createdAt.toLocalDate().toString().substring(5) + " " + l.label + " " + (l.amount >= 0 ? "+" : "") + l.amount).collect(Collectors.joining(" / "))));
        List<Request> rel = requests.findAll().stream().filter(r -> q.userId.equals(r.volunteerId) || q.userId.equals(r.recipientId) || q.userId.equals(r.senderId))
                .filter(r -> !r.status.terminal() || r.status == ReqStatus.DONE)
                .sorted(Comparator.comparing((Request r) -> r.date).reversed()).limit(4).toList();
        if (!rel.isEmpty()) basis.add(Views.map("kind", "request", "label", "관련 요청 " + rel.size() + "건",
                "text", rel.stream().map(r -> r.date.toString().substring(5) + " " + r.category + " " + statusKo(r.status) + (q.userId.equals(r.volunteerId) ? "(봉사)" : "(받음)")).collect(Collectors.joining(" / "))));

        String fp = Integer.toHexString((all + basis).hashCode());
        Map<String, Object> d = refresh ? null : load(INQUIRY, String.valueOf(q.id), fp);
        if (d == null) {
            String rule = ruleDraft(q, rel, basis);
            d = ai.inquiry(Views.map("type", q.type, "title", q.title, "content", anonymize(q.content)), basis, rule);
            store(INQUIRY, String.valueOf(q.id), fp, d);
        }
        Map<String, Object> out = new LinkedHashMap<>(d);
        out.put("inquiryId", q.id);
        out.put("basis", basis);
        out.put("autoSend", false);
        out.put("notice", "초안이에요. 고친 뒤 '답변 등록'을 눌러야 사용자에게 가요.");
        return out;
    }

    static String statusKo(ReqStatus s) {
        return switch (s) {
            case SENT -> "응답 대기"; case VOL_ACCEPTED -> "최종 수락 대기"; case CONFIRMED -> "확정"; case IN_PROGRESS -> "진행 중";
            case CHECKED_OUT -> "일지 미제출"; case LOG_SUBMITTED -> "확인 대기"; case DONE -> "완료"; default -> s.name();
        };
    }

    static String ruleDraft(Inquiry q, List<Request> rel, List<Map<String, Object>> basis) {
        StringBuilder sb = new StringBuilder("안녕하세요, 시간 적금입니다. 문의하신 '" + q.title + "' 내용을 확인했어요. ");
        Request noLog = rel.stream().filter(r -> r.status == ReqStatus.CHECKED_OUT && q.userId.equals(r.volunteerId)).findFirst().orElse(null);
        Request wait = rel.stream().filter(r -> r.status == ReqStatus.LOG_SUBMITTED && q.userId.equals(r.volunteerId)).findFirst().orElse(null);
        boolean earn = Pattern.compile("적립|시간이\\s*안|정산").matcher(q.title + q.content).find();
        if (earn && noLog != null) sb.append(noLog.date.getMonthValue()).append("월 ").append(noLog.date.getDayOfMonth()).append("일 ").append(noLog.category)
                .append(" 활동은 아직 활동 일지가 제출되지 않아 정산 전이에요. 일지를 올리시면 받는 분 확인 뒤 적립돼요. ");
        else if (earn && wait != null) sb.append(wait.date.getMonthValue()).append("월 ").append(wait.date.getDayOfMonth()).append("일 ").append(wait.category)
                .append(" 활동은 일지가 제출돼 받는 분 확인을 기다리고 있어요. 24시간 안에 확인이 없으면 자동 확정돼 적립돼요. ");
        else basis.stream().filter(b -> "rule".equals(b.get("kind"))).limit(2).forEach(b -> sb.append(b.get("text")).append(" "));
        sb.append("더 궁금한 점이 있으면 다시 문의해 주세요.");
        return sb.toString();
    }

    // ===================== C4 맞춤 역할극 상대 =====================
    public record Case(String key, String label, String flag, String fit, Pattern p) {}

    public static final List<Case> CASES = List.of(
            new Case("money", "돈·선물을 주려 하거나 달라고 함", "금전·선물", "장보기·심부름", Pattern.compile("돈|용돈|현금|봉투|선물|기프티콘|사례|차비|송금|계좌")),
            new Case("contact", "연락처를 묻거나 따로 만나자고 함", "연락처", "말동무·정서", Pattern.compile("전화번호|연락처|카톡|아이디|번호\\s*(알려|좀|주)|010|따로\\s*만나")),
            new Case("body", "신체 돌봄(파스·목욕·약)을 부탁함", "신체 돌봄", "동행", Pattern.compile("파스|목욕|씻겨|기저귀|약을?\\s*(먹여|챙겨|발라)|업어|신체\\s*접촉")),
            new Case("privacy", "비밀번호·인증번호·카드를 맡기려 함", "개인정보", "디지털 도움", Pattern.compile("비밀번호|인증번호|카드\\s*번호|통장|공인인증|비번")));

    public Map<String, Object> personaCases() {
        LocalDateTime since = LocalDateTime.now().minusDays(180);
        List<Report> rps = reports.findAll().stream().filter(r -> r.createdAt == null || r.createdAt.isAfter(since)).toList();
        List<Message> warned = messages.findAll().stream().filter(m -> m.warn != null && !m.systemMsg && (m.createdAt == null || m.createdAt.isAfter(since))).toList();
        List<String> memos = requests.findAll().stream().map(r -> r.careMemo).filter(m -> m != null && !m.isBlank()).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Case c : CASES) {
            int nr = 0, nm = 0, nmemo = 0;
            List<String> samples = new ArrayList<>();
            for (Report r : rps) { String t = r.type + " " + Objects.toString(r.content, ""); if (c.p().matcher(t).find()) { nr++; addSample(samples, r.content); } }
            for (Message m : warned) if (c.p().matcher(m.text).find()) { nm++; addSample(samples, m.text); }
            for (String m : memos) if (c.p().matcher(m).find()) { nmemo++; addSample(samples, m); }
            out.add(Views.map("key", c.key(), "label", c.label(), "flag", c.flag(), "fitCategory", c.fit(), "count", nr + nm + nmemo,
                    "reports", nr, "messages", nm, "memos", nmemo, "samples", samples));
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> -((Integer) m.get("count"))));
        return Views.map("cases", out, "period", "최근 180일", "notice", "이름·연락처·주소를 지운 뒤 상황 종류만 셌어요.");
    }

    private void addSample(List<String> samples, String raw) {
        if (samples.size() >= 3 || raw == null || raw.isBlank()) return;
        String s = anonymize(raw);
        s = s.length() > 40 ? s.substring(0, 40) + "…" : s;
        if (!samples.contains(s)) samples.add(s);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> personaDraft(String caseKey) {
        Map<String, Object> cs = personaCases();
        List<Map<String, Object>> cases = (List<Map<String, Object>>) cs.get("cases");
        Map<String, Object> focus = cases.stream().filter(c -> c.get("key").equals(caseKey)).findFirst().orElse(cases.get(0));
        Map<String, Object> draft = ai.persona(cases.stream().map(c -> Views.map("label", c.get("label"), "count", c.get("count"), "samples", c.get("samples"))).toList(),
                Views.map("label", focus.get("label"), "flag", focus.get("flag"), "fitCategory", focus.get("fitCategory"), "samples", focus.get("samples")),
                template((String) focus.get("key")));
        String source = (String) draft.remove("source");
        Object provider = draft.remove("provider");
        Map<String, Object> out = Views.map("draft", draft, "case", focus, "cases", cases, "source", source,
                "notice", "초안이에요. 고친 뒤 '저장'을 눌러야 교육에 들어가요(처음엔 '교육에 사용' 꺼짐).");
        if (provider != null) out.put("provider", provider);
        return out;
    }

    private static Map<String, Object> o(String t, String q, String f) { Map<String, Object> m = Views.map("t", t, "q", q); if (f != null) m.put("f", f); return m; }
    private static Map<String, Object> turn(String next, Map<String, Object>... opts) { return Views.map("options", List.of(opts), "next", next); }

    /** 규칙 모드 템플릿(가명). money·contact → 장보기 상대, body → 동행 상대, privacy → 디지털 상대. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> template(String key) {
        Map<String, Object> p;
        if ("body".equals(key)) {
            p = Views.map("name", "최○식 님", "initial", "식", "ageLabel", "86세 · 어르신", "fitCategory", "동행",
                    "trait", "무릎이 불편하시고 미안한 마음에 이것저것 부탁하세요", "goal", "신체 돌봄 부탁을 정중히 거절하고 담당자에게 알려 보세요",
                    "opening", "병원 다녀오느라 고생했어. 근데 허리가 결려서 그러는데…",
                    "turns", List.of(
                            turn("저기 파스 좀 붙여 줄 수 있어? 손이 안 닿아서.",
                                    o("오늘 진료 잘 받으셨어요. 집까지 천천히 같이 걸어갈게요.", "good", null), o("네, 다 왔어요.", "ok", null),
                                    o("저도 좀 피곤하네요.", "poor", null), o("허리요? 제가 주물러 드릴게요.", "bad", "신체 돌봄")),
                            turn("그럼 약이라도 좀 까서 먹여 줘. 손이 떨려서.",
                                    o("파스 붙이기는 제가 할 수 없어요. 손 닿는 곳에 꺼내 드리고 담당자님께 알려 드릴게요.", "good", null), o("음, 그건 좀 어려워요.", "ok", null),
                                    o("그런 건 가족한테 부탁하세요.", "poor", null), o("네, 제가 붙여 드릴게요. 셔츠 올려 주세요.", "bad", "신체 돌봄")),
                            turn("그래, 담당자한테 말해 줘. 오늘 고마웠어.",
                                    o("약 먹여 드리는 건 봉사로 할 수 없어요. 약 봉투를 잘 보이게 두고, 담당자님께 꼭 전할게요.", "good", null), o("그건 안 돼요.", "ok", null),
                                    o("제가 그런 것까지 해야 해요?", "poor", null), o("네, 제가 먹여 드릴게요.", "bad", "신체 돌봄"))));
        } else if ("privacy".equals(key)) {
            p = Views.map("name", "윤○희 님", "initial", "희", "ageLabel", "74세 · 어르신", "fitCategory", "디지털 도움",
                    "trait", "휴대폰이 어렵다며 통째로 맡기려 하세요", "goal", "비밀번호·인증번호는 직접 누르시게 하고 화면을 보지 않아요",
                    "opening", "은행 앱이 또 안 열려. 그냥 자기가 다 해 줘.",
                    "turns", List.of(
                            turn("비밀번호는 1234야. 그거 눌러 줘.",
                                    o("같이 해 볼게요. 어디서 막히는지 화면만 보여 주세요.", "good", null), o("한번 볼게요.", "ok", null),
                                    o("이런 건 자녀분께 물어보세요.", "poor", null), o("폰 주세요. 제가 다 할게요.", "bad", "개인정보")),
                            turn("문자로 인증번호 왔대. 자기가 읽고 넣어.",
                                    o("비밀번호는 제가 들으면 안 돼요. 직접 눌러 주시고 저는 화면을 안 볼게요.", "good", null), o("음, 직접 하시는 게 좋아요.", "ok", null),
                                    o("비밀번호가 너무 쉬워요.", "poor", null), o("네, 1234 누를게요.", "bad", "개인정보")),
                            turn("됐다! 다음에도 와 줘.",
                                    o("인증번호도 직접 넣어 주세요. 숫자 칸만 손가락으로 알려 드릴게요.", "good", null), o("직접 해 보세요.", "ok", null),
                                    o("빨리 넣으세요.", "poor", null), o("번호 불러 주세요, 제가 넣을게요.", "bad", "개인정보"))));
        } else {
            p = Views.map("name", "박○자 님", "initial", "자", "ageLabel", "83세 · 어르신", "fitCategory", "장보기·심부름",
                    "trait", "정이 많아 고마움을 돈·연락처로 표현하세요", "goal", "마음은 받되 돈과 연락처는 정중히 거절해 보세요",
                    "opening", "아이고, 무거운 걸 들고 왔네. 앉아서 좀 쉬어.",
                    "turns", List.of(
                            turn("(지폐를 내밀며) 이거 얼마 안 돼. 차비 해.",
                                    o("별말씀을요! 산 물건이랑 영수증, 잔돈 먼저 같이 확인해 주세요.", "good", null), o("어디에 둘까요?", "ok", null),
                                    o("다음엔 조금만 사세요, 무거워요.", "poor", null), o("네, 그럼 이번만 받을게요.", "bad", "금전·선물")),
                            turn("그럼 전화번호라도 줘. 급할 때 부르게.",
                                    o("마음만 감사히 받을게요. 돈은 받지 않는 규칙이에요. 대신 제 통장에 시간이 쌓여요!", "good", null), o("괜찮아요, 안 주셔도 돼요.", "ok", null),
                                    o("돈은 좀 그렇네요.", "poor", null), o("감사합니다, 잘 쓸게요!", "bad", "금전·선물")),
                            turn("그래, 담당자한테 자기 또 보내 달라고 할게.",
                                    o("앱에서 저를 단골로 요청해 주시면 돼요. 번호는 주고받지 않는 게 규칙이에요.", "good", null), o("번호는 어려워요.", "ok", null),
                                    o("그건 나중에요.", "poor", null), o("제 번호 010-0000-0000이에요.", "bad", "연락처"))));
        }
        Map<String, Object> m = new LinkedHashMap<>(p);
        m.put("active", false);
        return m;
    }
}
