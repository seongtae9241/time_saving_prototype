package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.ai.ActivityAi;
import com.timesavings.ai.ActivityRules;
import com.timesavings.ai.RuleEngine;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 7.13 LLM 추가 B(활동·메시지·시간 통장). 이벤트(확정·메시지 저장·조정/이의 신청) 때 한 번 만들어 ai_notes 에 저장하고 다시 쓴다.
 * 숫자·규칙 판정은 코드, AI(ActivityAi)는 정리·설명·제안만. 수락·제재·적립 조정 같은 결정은 하지 않는다.
 * 봉사자에게는 기본 정보(별칭·동·도움 내용·주의사항)만 넘기고, 지난 전달 메모는 넣지 않는다.
 */
@Service
@Transactional
public class ActivityAiService {
    private static final Logger log = LoggerFactory.getLogger(ActivityAiService.class);
    public static final String BRIEFING = "BRIEFING", MESSAGE = "MESSAGE", ADJUST = "ADJUST", APPEAL = "APPEAL", GOAL = "GOAL", MONTH = "MONTH";

    private final ActivityAiNoteRepo notes;
    private final RequestRepo requests;
    private final MessageRepo messages;
    private final DifficultyAppealRepo appeals;
    private final ElderRepo elders;
    private final UserRepo users;
    private final PostRepo posts;
    private final LedgerRepo ledger;
    private final BankService bank;
    private final Views views;
    private final ActivityAi ai;

    public ActivityAiService(ActivityAiNoteRepo notes, RequestRepo requests, MessageRepo messages, DifficultyAppealRepo appeals,
                             ElderRepo elders, UserRepo users, PostRepo posts, LedgerRepo ledger, BankService bank, Views views, ActivityAi ai) {
        this.notes = notes; this.requests = requests; this.messages = messages; this.appeals = appeals; this.elders = elders;
        this.users = users; this.posts = posts; this.ledger = ledger; this.bank = bank; this.views = views; this.ai = ai;
    }

    // ---------------- 저장 도우미 ----------------
    private Optional<ActivityAiNote> find(String kind, Long refId, String key) { return notes.findByKindAndRefIdAndRefKey(kind, refId, key == null ? "" : key); }

    private ActivityAiNote save(String kind, Long refId, String key, Long requestId, Map<String, Object> body, String fingerprint) {
        ActivityAiNote n = find(kind, refId, key).orElseGet(ActivityAiNote::new);
        n.kind = kind; n.refId = refId; n.refKey = key == null ? "" : key; n.requestId = requestId;
        n.fingerprint = fingerprint;
        n.source = String.valueOf(body.getOrDefault("source", "rule"));
        n.provider = (String) body.get("provider");
        n.bodyJson = Views.json(body);
        n.createdAt = LocalDateTime.now();
        return notes.save(n);
    }

    static Map<String, Object> body(ActivityAiNote n) {
        Map<String, Object> m = Views.parse(n.bodyJson, new TypeReference<LinkedHashMap<String, Object>>() {}, new LinkedHashMap<>());
        m.put("createdAt", n.createdAt);
        return m;
    }

    /** RequestService 의 훅이 부른다. 여기서 나는 오류는 본 흐름(확정·메시지 저장)을 막지 않는다. */
    private void quietly(String what, Runnable r) {
        try { r.run(); } catch (Exception e) { log.warn("{} 생성 실패({}), 건너뜀", what, e.getClass().getSimpleName()); }
    }

    private static String hhmm(double h) {
        int H = (int) Math.floor(h);
        int m = (int) Math.round((h - H) * 60);
        return H + ":" + (m < 10 ? "0" + m : m);
    }

    private static String when(Request r) {
        return r.date.getMonthValue() + "/" + r.date.getDayOfMonth() + " " + hhmm(r.startHour) + ", " + Rules.fmtHours(r.hours).replace(".0", "") + "시간";
    }

    // ===================== 1. 방문 전 브리핑 =====================
    public void onConfirmed(Request r) { quietly("방문 전 브리핑", () -> { if (find(BRIEFING, r.id, "").isEmpty()) makeBriefing(r); }); }

    /** 봉사자에게 넘기는 재료: 별칭(가린 표시 이름)·동·도움 내용·주의사항·일시. 지난 전달 메모·연락처·주소는 넣지 않는다. */
    public Map<String, Object> basics(Request r) {
        Elder e = r.elderId == null ? null : elders.findById(r.elderId).orElse(null);
        User rec = r.recipientId == null ? null : users.findById(r.recipientId).orElse(null);
        Post p = r.postId == null ? null : posts.findById(r.postId).orElse(null);
        String alias = e != null ? Rules.maskAlias(e.alias) : rec != null ? rec.name + " 님" : "받는 분";
        String dong = e != null ? e.dong : p != null && p.area != null ? p.area : rec != null ? rec.area : null;
        String caution = e != null ? e.caution
                : p != null && p.type == PostType.NEED && rec != null && rec.id.equals(p.ownerId) ? p.note : null;
        // 기능 O-04: 담당자가 그 공고에만 적은 이슈 메모(관리 대상 특이사항)도 확정된 봉사자의 브리핑에 넣는다
        // (브리핑은 일정이 확정된 봉사자만 볼 수 있다 — briefing() 의 확인 참고)
        String issue = e != null && p != null && e.id.equals(p.elderId) && p.issueNote != null && !p.issueNote.isBlank() ? p.issueNote.trim() : null;
        if (issue != null) caution = caution == null || caution.isBlank() ? issue : caution.contains(issue) ? caution : caution.trim() + " / " + issue;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alias", alias);
        m.put("dong", dong);
        m.put("content", r.content);
        m.put("caution", caution == null || caution.isBlank() ? null : caution.trim());
        m.put("when", when(r));
        // 받는 분 나이대에 맞는 문장을 고른다: 기관 도움 대상(독거노인)·60대 이상이면 어르신용, 그 밖이거나 모르면 중립
        Boolean senior = e != null ? Boolean.TRUE : com.timesavings.ai.ActivityRules.seniorOf(rec == null ? null : rec.ageGroup);
        if (senior != null) m.put("senior", senior);
        return m;
    }

    private ActivityAiNote makeBriefing(Request r) {
        Map<String, Object> b = basics(r);
        List<String> rule = ActivityRules.briefingRule(b, r.category);
        Map<String, Object> res = ai.briefing(b, r.category, rule);
        res.put("category", r.category);
        return save(BRIEFING, r.id, "", r.id, res, null);
    }

    public Map<String, Object> briefing(User u, Long requestId) {
        Request r = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("요청을 찾을 수 없어요"));
        if (!u.id.equals(r.volunteerId)) throw ApiException.forbidden("방문 전 브리핑은 봉사하는 분만 볼 수 있어요");
        if (r.confirmedAt == null && !List.of(ReqStatus.CONFIRMED, ReqStatus.IN_PROGRESS, ReqStatus.CHECKED_OUT, ReqStatus.LOG_SUBMITTED, ReqStatus.DONE).contains(r.status))
            throw ApiException.conflict("NOT_CONFIRMED", "일정이 확정되면 볼 수 있어요");
        ActivityAiNote n = find(BRIEFING, r.id, "").orElseGet(() -> makeBriefing(r));
        Map<String, Object> m = body(n);
        m.put("requestId", r.id);
        m.put("basics", basics(r));
        return m;
    }

    // ===================== 2·3. 메시지 안전 감지·빠른 답장·일정 변경 =====================
    public void onMessage(Request r, Message m) { quietly("메시지 분류", () -> { if (!m.systemMsg && find(MESSAGE, m.id, "").isEmpty()) classify(r, m, true); }); }

    private String senderSide(Request r, Message m) {
        if (m.senderId == null) return "시스템";
        if (m.senderId.equals(r.volunteerId)) return "봉사자";
        return r.elderId != null ? "담당자(대상 대신)" : "도움 받는 분";
    }

    private ActivityAiNote classify(Request r, Message m, boolean useAi) {
        ActivityRules.Safety s = ActivityRules.safety(m.text);
        ActivityRules.Schedule sc = ActivityRules.schedule(m.text, r.startHour);
        List<String> rp = ActivityRules.replies(m.text, s, sc);
        Map<String, Object> res;
        if (useAi) {
            String ctx = "카테고리 " + r.category + ", 약속 " + when(r) + ", 보낸 사람: " + senderSide(r, m);
            res = ai.message(m.text, ctx, s, sc, rp, r.startHour);
        } else {
            res = new LinkedHashMap<>();
            res.put("level", s.level());
            res.put("reasons", s.reasons());
            res.put("warnText", ActivityRules.warnText(s));
            res.put("replies", rp);
            res.put("schedule", ActivityAi.scheduleMap(sc));
            res.put("source", "rule");
        }
        ActivityAiNote n = save(MESSAGE, m.id, "", r.id, res, null);
        n.warnLevel = String.valueOf(res.get("level"));
        return n;
    }

    /** 메시지 화면용: 내가 받은 메시지의 경고 띠, 마지막 상대 메시지의 답장 후보·일정 변경 제안. 자동 차단·제재·자동 시간 변경 없음. */
    public Map<String, Object> messageAssist(User u, Long requestId) {
        Request r = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("요청을 찾을 수 없어요"));
        String role = views.roleOf(r, u);
        if (role == null) throw ApiException.forbidden("이 요청에 접근할 수 없어요");
        List<Message> list = messages.findByRequestIdOrderByIdAsc(r.id).stream().filter(x -> !x.systemMsg && x.senderId != null).toList();
        Message last = list.isEmpty() ? null : list.get(list.size() - 1);
        List<Map<String, Object>> warns = new ArrayList<>();
        Map<String, Object> lastBody = null;
        for (Message x : list) {
            if (x.senderId.equals(u.id)) continue;
            boolean isLast = x == last;
            // 저장된 결과가 없으면(예전 메시지) 마지막 메시지만 AI, 나머지는 규칙으로 한 번 만들어 저장
            ActivityAiNote n = find(MESSAGE, x.id, "").orElseGet(() -> classify(r, x, isLast));
            Map<String, Object> b = body(n);
            if (!"none".equals(b.get("level"))) warns.add(Views.map("messageId", x.id, "level", b.get("level"), "reasons", b.get("reasons"), "warnText", b.get("warnText"), "source", b.get("source")));
            if (isLast) lastBody = b;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("warns", warns);
        boolean open = r.status == ReqStatus.VOL_ACCEPTED || r.status == ReqStatus.CONFIRMED || r.status == ReqStatus.IN_PROGRESS
                || r.status == ReqStatus.CHECKED_OUT || r.status == ReqStatus.LOG_SUBMITTED;
        if (lastBody != null && open) {
            out.put("replyTo", last.id);
            out.put("replies", lastBody.get("replies"));
            Object sc = lastBody.get("schedule");
            boolean scheduleOk = r.status == ReqStatus.VOL_ACCEPTED || r.status == ReqStatus.CONFIRMED;
            out.put("schedule", scheduleOk ? sc : null);
            out.put("source", lastBody.get("source"));
        } else {
            out.put("replies", List.of());
            out.put("schedule", null);
        }
        out.put("autoChange", false);
        return out;
    }

    // ===================== 4. 시간 조정·난이도 이의 판단 보조 =====================
    public void onAdjust(Request r) { quietly("시간 조정 판단 보조", () -> makeAdjust(r)); }

    public void onAppeal(Request r, DifficultyAppeal a) { quietly("난이도 이의 판단 보조", () -> makeAppeal(r, a)); }

    private static final Pattern EXTRA = Pattern.compile("대기|기다|늦게\\s*끝|오래|추가|더\\s*걸|연장|길어|막혀|차가\\s*밀");
    private static final Pattern HEAVY = Pattern.compile("무거|계단|쌀|짐|부축|휠체어|여러\\s*곳|멀|오르막|비가|눈이|많이\\s*걸|오래\\s*걸|대기");

    private static List<String> clues(Pattern p, String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) return List.of();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group().replaceAll("\\s+", " "));
        return new ArrayList<>(out);
    }

    /** 체크 기록 시간(시간 단위, 소수 둘째 자리). 없으면 null. */
    public static Double checkedHours(Request r) {
        if (r.checkInAt == null || r.checkOutAt == null) return null;
        return BankService.r2(Duration.between(r.checkInAt, r.checkOutAt).toMinutes() / 60.0);
    }

    private static String durText(Double h) {
        if (h == null) return "기록 없음";
        long min = Math.round(h * 60);
        return (min / 60 > 0 ? (min / 60) + "시간 " : "") + (min % 60 > 0 ? (min % 60) + "분" : "").trim();
    }

    private static String tm(LocalDateTime t) { return t == null ? "기록 없음" : t.getHour() + ":" + String.format("%02d", t.getMinute()); }

    private static Map<String, Object> row(String item, Object planned, Object actual, String flag, String note) {
        return Views.map("item", item, "planned", planned, "actual", actual, "flag", flag, "note", note);
    }

    /** 시간 조정: 예정 vs 체크인/아웃·조정 요청 시간·체크 방법·일지 단서를 코드로 비교. */
    public Map<String, Object> adjustTable(Request r) {
        Double ch = checkedHours(r);
        List<Map<String, Object>> t = new ArrayList<>();
        long lateMin = r.checkInAt == null ? 0 : Duration.between(r.startAt(), r.checkInAt).toMinutes();
        t.add(row("시작 시각", hhmm(r.startHour), tm(r.checkInAt), lateMin > 15 ? "check" : "ok", lateMin > 15 ? lateMin + "분 늦게 시작" : null));
        t.add(row("끝난 시각", hhmm(r.startHour + r.hours), tm(r.checkOutAt), "ok", null));
        t.add(row("활동 시간", Rules.fmtHours(r.hours) + "시간", durText(ch), ch == null ? "check" : "ok", null));
        boolean near = ch != null && r.adjustHours != null && Math.abs(r.adjustHours - ch) <= 0.5;
        t.add(row("조정 요청", Rules.fmtHours(r.hours) + "시간", r.adjustHours == null ? "-" : Rules.fmtHours(r.adjustHours) + "시간",
                near ? "ok" : "check", ch == null ? "체크 기록이 없어 비교할 수 없어요" : near ? "체크 기록과 30분 안쪽 차이" : "체크 기록과 " + durText(Math.abs(r.adjustHours - ch)) + " 차이"));
        boolean manual = "manual".equals(r.checkMethod);
        t.add(row("체크 방법", "QR·번호", manual ? "버튼(수동)" : r.checkMethod == null ? "기록 없음" : "QR·번호", manual ? "check" : "ok", manual ? "버튼 기록이라 시간 확인이 더 필요해요" : null));
        t.add(row("카테고리", r.category, Rules.levelKo(r.difficulty) + " ×" + r.weight, "ok", null));
        List<String> cl = clues(EXTRA, (r.logText == null ? "" : r.logText) + " " + (r.adjustReason == null ? "" : r.adjustReason));
        t.add(row("일지·사유 단서", "-", cl.isEmpty() ? "없음" : String.join(", ", cl), cl.isEmpty() ? "check" : "ok", null));
        List<String> pro = new ArrayList<>(), con = new ArrayList<>();
        if (near) pro.add("체크 기록(" + durText(ch) + ")이 조정 요청 " + Rules.fmtHours(r.adjustHours) + "시간과 비슷해요");
        else if (ch != null && r.adjustHours != null) con.add("체크 기록(" + durText(ch) + ")과 조정 요청 " + Rules.fmtHours(r.adjustHours) + "시간 차이가 커요");
        else con.add("체크 기록이 없어 실제 시간을 확인하기 어려워요");
        if (!cl.isEmpty()) pro.add("일지·사유에 '" + String.join(", ", cl) + "' 내용이 있어요");
        else con.add("일지·사유에 시간이 늘어난 까닭이 드러나지 않아요");
        if (manual) con.add("버튼으로 기록돼 시간을 한 번 더 확인해 주세요");
        if (lateMin > 15) con.add("예정보다 " + lateMin + "분 늦게 시작했어요");
        String summary = pro.size() > con.size() ? "기록상 근거가 있어 보여요. 받는 분 확인 후 결정해 주세요." : "확인할 점이 있어요. 기록을 보고 결정해 주세요.";
        return Views.map("table", t, "rulePro", pro, "ruleCon", con, "ruleSummary", summary);
    }

    /** 난이도 이의: 카테고리 기본 등급·현재/요청 등급·일지로 다시 본 규칙 등급·힘든 일 단서를 코드로 비교. */
    public Map<String, Object> appealTable(Request r, DifficultyAppeal a) {
        Level base = Rules.isCategory(r.category) ? Rules.categoryLevel(r.category) : Level.BASIC;
        String text = (r.content == null ? "" : r.content) + " " + (r.logText == null ? "" : r.logText);
        RuleEngine.DifficultyResult re = RuleEngine.difficulty(text, Rules.isCategory(r.category) ? List.of(r.category) : List.of());
        Double ch = checkedHours(r);
        double hours = "ACCEPTED".equals(r.adjustStatus) && r.adjustHours != null ? r.adjustHours : r.hours;
        List<String> cl = clues(HEAVY, r.logText + " " + a.reason);
        List<Map<String, Object>> t = new ArrayList<>();
        t.add(row("카테고리 기본 등급", r.category, Rules.levelKo(base), "ok", null));
        t.add(row("등급", Rules.levelKo(r.difficulty) + " ×" + r.weight, Rules.levelKo(a.requestedLevel) + " ×" + Rules.weight(a.requestedLevel) + " 요청", "check", null));
        boolean ruleAgrees = re.level().ordinal() >= a.requestedLevel.ordinal();
        t.add(row("일지로 다시 본 규칙 등급", Rules.levelKo(r.difficulty), Rules.levelKo(re.level()), ruleAgrees ? "ok" : "check", re.reason()));
        t.add(row("활동 시간", Rules.fmtHours(r.hours) + "시간", durText(ch), "ok", null));
        t.add(row("힘든 일 단서", r.content, cl.isEmpty() ? "없음" : String.join(", ", cl), cl.isEmpty() ? "check" : "ok", null));
        double diff = Rules.earn(hours, a.requestedLevel) - Rules.earn(hours, r.difficulty);
        t.add(row("받아들이면 차액", "-", "+" + BankService.r2(diff) + "시간", "ok", null));
        List<String> pro = new ArrayList<>(), con = new ArrayList<>();
        if (ruleAgrees) pro.add("일지로 다시 본 규칙 등급도 " + Rules.levelKo(re.level()) + "이에요");
        else con.add("일지로 다시 본 규칙 등급은 " + Rules.levelKo(re.level()) + "이에요");
        if (!cl.isEmpty()) pro.add("일지·사유에 '" + String.join(", ", cl) + "' 내용이 있어요");
        else con.add("일지에 더 힘든 일이 드러나지 않아요");
        if (ch != null && ch > r.hours + 0.5) pro.add("실제 활동이 예정보다 " + durText(ch - r.hours) + " 길었어요");
        String summary = pro.size() >= con.size() ? "근거가 있어 보여요. 일지와 함께 보고 결정해 주세요." : "근거가 약해 보여요. 봉사자 사유를 한 번 더 확인해 주세요.";
        return Views.map("table", t, "rulePro", pro, "ruleCon", con, "ruleSummary", summary);
    }

    @SuppressWarnings("unchecked")
    private ActivityAiNote judgeNote(String kind, Long refId, Request r, Map<String, Object> cmp, String reason, String fp) {
        Map<String, Object> res = ai.judge(kind, (List<Map<String, Object>>) cmp.get("table"), reason, r.logText,
                (List<String>) cmp.get("rulePro"), (List<String>) cmp.get("ruleCon"), (String) cmp.get("ruleSummary"));
        res.put("kind", kind);
        res.put("table", cmp.get("table"));
        res.put("reason", reason);
        res.put("decision", "운영자(또는 받는 분)가 정해요");
        return save(kind, refId, "", r.id, res, fp);
    }

    private static String adjustFp(Request r) { return r.adjustHours + "|" + Objects.toString(r.adjustReason, ""); }

    private ActivityAiNote makeAdjust(Request r) {
        Optional<ActivityAiNote> n = find(ADJUST, r.id, "");
        if (n.isPresent() && adjustFp(r).equals(n.get().fingerprint)) return n.get();
        return judgeNote(ADJUST, r.id, r, adjustTable(r), r.adjustReason, adjustFp(r));
    }

    private ActivityAiNote makeAppeal(Request r, DifficultyAppeal a) {
        return find(APPEAL, a.id, "").orElseGet(() -> judgeNote(APPEAL, a.id, r, appealTable(r, a), a.reason, null));
    }

    /** kind=ADJUST: id=요청 id(받는 쪽·담당자·운영자), kind=APPEAL: id=이의 신청 id(운영자). */
    public Map<String, Object> judge(User u, String kind, Long id) {
        if (ADJUST.equalsIgnoreCase(kind)) {
            Request r = requests.findById(id).orElseThrow(() -> ApiException.notFound("요청을 찾을 수 없어요"));
            String role = views.roleOf(r, u);
            if (u.role != Role.ADMIN && !"RECIPIENT".equals(role) && !"MANAGER".equals(role)) throw ApiException.forbidden("받는 쪽이나 운영자만 볼 수 있어요");
            if (r.adjustHours == null) throw ApiException.notFound("시간 조정 요청이 없어요");
            return body(makeAdjust(r));
        }
        if (APPEAL.equalsIgnoreCase(kind)) {
            if (u.role != Role.ADMIN) throw ApiException.forbidden("운영자만 볼 수 있어요");
            DifficultyAppeal a = appeals.findById(id).orElseThrow(() -> ApiException.notFound("이의 신청을 찾을 수 없어요"));
            Request r = requests.findById(a.requestId).orElseThrow(() -> ApiException.notFound("요청을 찾을 수 없어요"));
            return body(makeAppeal(r, a));
        }
        throw ApiException.bad("INVALID_KIND", "kind 는 ADJUST 또는 APPEAL 이에요");
    }

    // ===================== 5. 적립 목표 코치(거꾸로 플랜) =====================
    public record GoalCmd(String category, Double perWeek, Double hoursPer, Integer years, Integer yearsLater, Integer startAge) {}

    /** "60대" → 65, "70대 이상" → 75. 모르면 null. */
    public static Integer ageOf(String ageGroup) {
        if (ageGroup == null) return null;
        Matcher m = Pattern.compile("(\\d{2})").matcher(ageGroup);
        return m.find() ? Integer.parseInt(m.group(1)) + 5 : null;
    }

    /** 코드 계산: 필요한 총 시간(가중치 반영)과 지금부터 월 몇 시간씩 쌓아야 하는지. */
    public static Map<String, Object> goalCompute(GoalCmd c, double have, Integer currentAge) {
        if (c == null || c.category() == null || !Rules.isCategory(c.category())) throw ApiException.bad("INVALID_CATEGORY", "받고 싶은 도움을 골라 주세요");
        double perWeek = c.perWeek() == null ? 1 : c.perWeek();
        double hoursPer = c.hoursPer() == null ? 1 : c.hoursPer();
        int years = c.years() == null ? 1 : c.years();
        if (perWeek < 0.25 || perWeek > 7) throw ApiException.bad("INVALID_FREQ", "횟수는 월 1회~주 7회 사이로 골라 주세요");
        if (hoursPer < 0.5 || hoursPer > 4 || !Rules.halfStep(hoursPer)) throw ApiException.bad("INVALID_HOURS", "1회 시간은 30분~4시간, 30분 단위예요");
        if (years < 1 || years > 30) throw ApiException.bad("INVALID_YEARS", "받는 기간은 1~30년이에요");
        Integer later = c.yearsLater();
        if (later == null && c.startAge() != null) {
            if (currentAge == null) throw ApiException.bad("AGE_UNKNOWN", "프로필에 나이대가 없어 '몇 년 뒤'로 알려 주세요");
            later = c.startAge() - currentAge;
        }
        if (later == null || later < 1 || later > 50) throw ApiException.bad("INVALID_START", "지금부터 1~50년 뒤로 정해 주세요");
        double w = Rules.weight(Rules.categoryLevel(c.category()));
        double perSession = Rules.earn(hoursPer, w);
        double sessions = BankService.r2(perWeek * 52 * years);
        double total = Rules.round05(perSession * perWeek * 52 * years);
        double remaining = Math.max(0, BankService.r2(total - have));
        int months = later * 12;
        double monthly = Math.ceil(remaining / months * 2) / 2.0; // 0.5 단위 올림
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", c.category());
        m.put("weight", w);
        m.put("perWeek", perWeek);
        m.put("freq", PersonalInsightService.freqText(perWeek));
        m.put("hoursPer", hoursPer);
        m.put("years", years);
        m.put("yearsLater", later);
        m.put("startAge", c.startAge() != null ? c.startAge() : currentAge == null ? null : currentAge + later);
        m.put("ageEstimated", currentAge != null);
        m.put("perSession", perSession);
        m.put("sessions", sessions);
        m.put("totalNeeded", total);
        m.put("have", BankService.r2(have));
        m.put("remaining", remaining);
        m.put("months", months);
        m.put("monthlyTarget", monthly);
        m.put("weeklyTarget", BankService.r2(monthly / PersonalInsightService.WEEKS_PER_MONTH));
        return m;
    }

    static String n(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v); }

    public Map<String, Object> goalPlan(User u, GoalCmd c) {
        BankService.Summary s = bank.summary(u.id);
        Map<String, Object> plan = goalCompute(c, s.available(), ageOf(u.ageGroup));
        List<Request> done = requests.findAll().stream().filter(r -> u.id.equals(r.volunteerId) && r.status == ReqStatus.DONE).toList();
        Map<String, Integer> cats = new LinkedHashMap<>(), slots = new LinkedHashMap<>();
        double hsum = 0;
        for (Request r : done) {
            cats.merge(r.category, 1, Integer::sum);
            slots.merge(PersonalInsightService.slot(r.startHour), 1, Integer::sum);
            hsum += r.hours;
        }
        String topCat = cats.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        String topSlot = slots.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        double avgH = done.isEmpty() ? 2 : Math.max(1, Rules.round05(hsum / done.size()));
        String suggestCat = topCat != null && Rules.isCategory(topCat) ? topCat : (u.categories != null && !u.categories.isEmpty() && Rules.isCategory(u.categories.get(0)) ? u.categories.get(0) : "말동무·정서");
        double w2 = Rules.weight(Rules.categoryLevel(suggestCat));
        double per = Rules.earn(avgH, w2);
        double monthly = (double) plan.get("monthlyTarget");
        int perMonth = monthly <= 0 ? 0 : (int) Math.ceil(monthly / per);
        Map<String, Object> act = Views.map("doneCount", done.size(), "topCategory", topCat, "topSlot", topSlot, "avgHours", avgH,
                "suggestCategory", suggestCat, "suggestWeight", w2, "suggestHours", avgH, "suggestEarnPerSession", per, "suggestPerMonth", perMonth,
                "earnedSelf", s.earnedSelf(), "canUseSelf", s.canUseSelf());
        plan.put("suggest", act);

        // 근처 봉사 공고 후보(코드): 열린 도움 요청 공고, 오늘 이후, 내 것 아님, 같은 동·잘하는 카테고리 먼저
        LocalDate today = LocalDate.now();
        List<Post> cand = posts.findByStatus(PostStatus.OPEN).stream()
                .filter(p -> p.type == PostType.NEED && !p.date.isBefore(today) && !u.id.equals(p.ownerId))
                .sorted(Comparator.comparingInt((Post p) -> -((u.area != null && u.area.equals(p.area) ? 2 : 0) + (p.categories.contains(suggestCat) ? 2 : 0)
                        + (topSlot != null && topSlot.equals(PersonalInsightService.slot(p.startHour)) ? 1 : 0))).thenComparing(p -> p.date))
                .limit(5).toList();
        List<Map<String, Object>> brief = new ArrayList<>();
        for (int i = 0; i < cand.size(); i++) {
            Post p = cand.get(i);
            brief.add(Views.map("no", i, "title", p.title, "area", p.area, "date", p.date.toString(), "start", hhmm(p.startHour), "categories", p.categories, "estHours", p.estHours));
        }
        String startTxt = plan.get("startAge") != null ? plan.get("startAge") + "살쯤" : plan.get("yearsLater") + "년 뒤";
        double remaining = (double) plan.get("remaining");
        String ruleText = remaining <= 0
                ? startTxt + "부터 " + c.category() + " " + plan.get("freq") + " " + n(c.hoursPer() == null ? 1 : c.hoursPer()) + "시간씩 " + plan.get("years") + "년 받으려면 모두 " + n((double) plan.get("totalNeeded")) + "시간이 필요해요. 지금 시간으로 이미 충분해요."
                : startTxt + "부터 " + c.category() + " " + plan.get("freq") + " " + n((double) plan.get("hoursPer")) + "시간씩 " + plan.get("years") + "년 받으려면 모두 " + n((double) plan.get("totalNeeded"))
                + "시간이 필요해요. 지금 " + n((double) plan.get("have")) + "시간이 있으니 한 달에 " + n(monthly) + "시간씩 쌓으면 돼요. "
                + (topCat != null ? "잘하시는 " : "") + suggestCat + "(×" + w2 + ")" + com.timesavings.ai.InsightAi.josa(suggestCat, "을", "를") + (topSlot != null ? " " + topSlot + "에 " : " ") + "한 달 " + perMonth + "번(" + n(avgH) + "시간씩) 하면 채울 수 있어요.";
        List<String> ruleTips = new ArrayList<>();
        if (!s.canUseSelf()) ruleTips.add("직접 적립 10시간을 먼저 채우면 내 시간을 쓸 수 있어요");
        if (w2 < 1.2) ruleTips.add("장보기·집안일(×1.2)을 섞으면 같은 시간에 더 많이 쌓여요");
        ruleTips.add("관심 알림을 켜 두면 맞는 공고를 놓치지 않아요");

        String fp = Views.json(plan) + "|" + done.size() + "|" + cand.stream().map(p -> p.id).toList();
        fp = Integer.toHexString(fp.hashCode());
        Optional<ActivityAiNote> saved = find(GOAL, u.id, "");
        Map<String, Object> coach;
        if (saved.isPresent() && fp.equals(saved.get().fingerprint)) coach = body(saved.get());
        else {
            coach = ai.goalCoach(plan, act, brief, ruleText, ruleTips);
            save(GOAL, u.id, "", null, coach, fp);
        }
        List<Map<String, Object>> picked = new ArrayList<>();
        Object pk = coach.get("pick");
        if (pk instanceof List<?> l) for (Object o : l) {
            int i = o instanceof Number num ? num.intValue() : -1;
            if (i >= 0 && i < cand.size()) picked.add(views.postCard(cand.get(i), u));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", plan);
        out.put("text", coach.get("text"));
        out.put("tips", coach.get("tips"));
        out.put("posts", picked);
        out.put("source", coach.get("source"));
        if (coach.get("provider") != null) out.put("provider", coach.get("provider"));
        return out;
    }

    // ===================== 6. 월별 결산 한 줄 설명 =====================
    /** 코드 집계: 그 달 적립·사용·카테고리별(가중치 덕분에 더 쌓인 시간). */
    public Map<String, Object> monthNumbers(Long userId, YearMonth ym) {
        double earned = 0, used = 0, giftIn = 0, bonus = 0;
        int count = 0;
        long maxId = 0;
        Map<String, double[]> byCat = new LinkedHashMap<>(); // [적립, 기본 시간, 횟수, 가중치]
        for (Ledger l : ledger.findByUserIdOrderByCreatedAtDescIdDesc(userId)) {
            if (l.type == LedgerType.HOLD || l.type == LedgerType.RELEASE) continue;
            if (!YearMonth.from(l.createdAt).equals(ym)) continue;
            count++;
            maxId = Math.max(maxId, l.id);
            if (l.type == LedgerType.GIFT_IN) giftIn += l.amount;
            if (l.amount < 0) used += -l.amount;
            if (l.type == LedgerType.EARN || (l.type == LedgerType.ADJUST && l.requestId != null && l.amount > 0)) {
                earned += l.amount;
                Request r = l.requestId == null ? null : requests.findById(l.requestId).orElse(null);
                String cat = r != null ? r.category : "기타";
                double[] v = byCat.computeIfAbsent(cat, k -> new double[4]);
                v[0] += l.amount;
                if (l.type == LedgerType.EARN) {
                    v[2]++;
                    if (l.baseHours != null) { v[1] += l.baseHours; bonus += l.amount - l.baseHours; }
                    if (l.weight != null) v[3] = Math.max(v[3], l.weight);
                }
            }
        }
        List<Map<String, Object>> cats = new ArrayList<>();
        String topBonusCat = null;
        double topBonus = 0, topW = 0;
        for (Map.Entry<String, double[]> e : byCat.entrySet()) {
            double[] v = e.getValue();
            double b = BankService.r2(v[0] - v[1]);
            cats.add(Views.map("category", e.getKey(), "earned", BankService.r2(v[0]), "baseHours", BankService.r2(v[1]), "count", (int) v[2], "weight", v[3], "bonus", b));
            if (b > topBonus) { topBonus = b; topBonusCat = e.getKey(); topW = v[3]; }
        }
        return Views.map("month", ym.toString(), "earned", BankService.r2(earned), "used", BankService.r2(used), "giftIn", BankService.r2(giftIn),
                "bonus", BankService.r2(bonus), "byCategory", cats, "topBonusCategory", topBonusCat, "topBonus", BankService.r2(topBonus), "topBonusWeight", topW,
                "fingerprint", count + "|" + BankService.r2(earned) + "|" + BankService.r2(used) + "|" + maxId);
    }

    @SuppressWarnings("unchecked")
    public static String[] monthRule(Map<String, Object> nm) {
        double earned = (double) nm.get("earned"), used = (double) nm.get("used"), topBonus = (double) nm.get("topBonus");
        String cat = (String) nm.get("topBonusCategory");
        List<Map<String, Object>> cats = (List<Map<String, Object>>) nm.get("byCategory");
        String text;
        if (earned <= 0 && used <= 0) text = "이번 달은 아직 통장 거래가 없어요.";
        else {
            text = earned > 0 ? "이번 달 " + n(earned) + "시간 쌓았어요." : "이번 달은 적립이 없어요.";
            if (cat != null && topBonus > 0) text += " " + cat + com.timesavings.ai.InsightAi.josa(cat, "이", "가") + " " + n((double) nm.get("topBonusWeight")) + "배라 " + n(topBonus) + "시간 더 늘었어요.";
            if (used > 0) text += " 도움 받는 데 " + n(used) + "시간 썼어요.";
        }
        String sug;
        if (earned <= 0) sug = "다음 달엔 가까운 말동무 공고로 1시간부터 시작해 보세요.";
        else if (cats.stream().noneMatch(c -> ((Number) c.get("weight")).doubleValue() > 1.0)) sug = "다음 달엔 장보기나 가벼운 집안일(1.2배)도 한 번 해 보세요.";
        else {
            String top = cats.stream().max(Comparator.comparingDouble(c -> ((Number) c.get("earned")).doubleValue())).map(c -> (String) c.get("category")).orElse("봉사");
            sug = "다음 달에도 " + top + " 한 번 더 이어 가 보세요.";
        }
        return new String[]{text, sug};
    }

    @SuppressWarnings("unchecked")
    static Set<Double> allowedNumbers(Map<String, Object> nm, YearMonth ym) {
        Set<Double> s = new HashSet<>(List.of(1.0, 1.2, (double) ym.getMonthValue(), (double) ym.getYear(), (double) ym.plusMonths(1).getMonthValue()));
        for (String k : List.of("earned", "used", "giftIn", "bonus", "topBonus", "topBonusWeight")) if (nm.get(k) instanceof Number x) s.add(x.doubleValue());
        for (Map<String, Object> c : (List<Map<String, Object>>) nm.get("byCategory"))
            for (Object v : c.values()) if (v instanceof Number x) s.add(x.doubleValue());
        return s;
    }

    public Map<String, Object> monthNote(User u, String month) {
        YearMonth ym;
        try { ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month); }
        catch (Exception e) { throw ApiException.bad("INVALID_MONTH", "달은 YYYY-MM 으로 알려 주세요"); }
        Map<String, Object> nm = monthNumbers(u.id, ym);
        String fp = (String) nm.remove("fingerprint");
        Optional<ActivityAiNote> saved = find(MONTH, u.id, ym.toString());
        Map<String, Object> note;
        if (saved.isPresent() && fp.equals(saved.get().fingerprint)) note = body(saved.get());
        else {
            String[] rule = monthRule(nm);
            note = ai.monthNote(nm, allowedNumbers(nm, ym), rule[0], rule[1]);
            note.put("createdAt", save(MONTH, u.id, ym.toString(), null, note, fp).createdAt);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ym.toString());
        out.put("numbers", nm);
        out.put("text", note.get("text"));
        out.put("suggestion", note.get("suggestion"));
        out.put("source", note.get("source"));
        if (note.get("provider") != null) out.put("provider", note.get("provider"));
        out.put("createdAt", note.get("createdAt"));
        return out;
    }
}
