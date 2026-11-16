package com.timesavings.service;

import com.timesavings.ai.AiClient;
import com.timesavings.ai.AssistantAi;
import com.timesavings.ai.OrgAssistantAi;
import com.timesavings.ai.VoiceSafety;
import com.timesavings.config.ApiException;
import com.timesavings.domain.Elder;
import com.timesavings.domain.Enums.*;
import com.timesavings.domain.Request;
import com.timesavings.domain.User;
import com.timesavings.repo.ElderRepo;
import com.timesavings.repo.RequestRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 담당자 AI 도우미(2026-11-16, 프로토타입 "AI 도우미" 패널 answer(q)·SUG).
 * - 숫자·목록·링크는 여기서 내 자료(내 관리 대상·우리 기관 것만)로 계산한다. AI(OrgAssistantAi)는 의도 나누기와 문장 다듬기만 한다.
 * - 아무것도 실행하지 않는다(executed:false). 수락·등록·위임은 담당자가 화면에서 직접 누른다.
 * - 건강을 판단해 달라는 질문에는 답하지 않고, 안부 확인 순서와 기록 화면만 알려 준다.
 * - 프로토타입 답에 있던 시연용 값(추천 봉사자 이름, "혼자 사는 분 7명", 작업 시간 "7초 동안 작업했어요")은 근거 자료가 없어 넣지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class OrgAssistantService {
    public static final int MAX_QUESTION = 300;
    public static final List<String> SUGGESTIONS = List.of("이번 주 빈칸 정리해줘", "먼저 안부 물어볼 분 알려줘", "한파 대비 할 일 정리해줘",
            "오늘 처리할 일 알려줘", "기관 공고 신청 몇 건이야?", "만료되는 위임 알려줘", "봉사자 현황 알려줘");
    public static final String NOTICE = "숫자와 목록은 시스템이 계산했고, AI는 문장만 다듬어요. 수락·등록·위임은 화면에서 직접 눌러야 바뀌어요.";

    private final OrgAssistantAi ai;
    private final AiClient aiClient;
    private final ElderRepo elders;
    private final RequestRepo requests;
    private final CareScheduleService schedule;
    private final ElderChangeService change;
    private final WeatherAlertService weather;
    private final DelegationService delegations;
    private final OrgNoticeService notices;
    private final OrgVolunteerService volunteers;
    private final Views views;

    public OrgAssistantService(OrgAssistantAi ai, AiClient aiClient, ElderRepo elders, RequestRepo requests, CareScheduleService schedule,
                               ElderChangeService change, WeatherAlertService weather, DelegationService delegations, OrgNoticeService notices,
                               OrgVolunteerService volunteers, Views views) {
        this.ai = ai; this.aiClient = aiClient; this.elders = elders; this.requests = requests; this.schedule = schedule; this.change = change;
        this.weather = weather; this.delegations = delegations; this.notices = notices; this.volunteers = volunteers; this.views = views;
    }

    /** 코드가 만든 답: 문장, 카드(제목·링크·줄), 근거 값 */
    record Answer(String text, Map<String, Object> card, Map<String, Object> facts) {}

    private static Map<String, Object> card(String title, String link, String linkLabel, List<Map<String, Object>> rows) {
        return Views.map("title", title, "link", link, "linkLabel", linkLabel, "rows", rows);
    }

    private static Map<String, Object> row(String label, String value, String link) { return Views.map("label", label, "value", value, "link", link); }

    public Map<String, Object> intro() {
        return Views.map("name", "담당자 도우미", "suggestions", SUGGESTIONS, "notice", NOTICE, "maxLength", MAX_QUESTION);
    }

    public Map<String, Object> ask(User mgr, String question, String screen) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) throw ApiException.bad("QUESTION_REQUIRED", "무엇을 도와드릴지 적어 주세요");
        if (q.length() > MAX_QUESTION) throw ApiException.bad("TOO_LONG", "질문은 " + MAX_QUESTION + "자 안으로 적어 주세요");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", VoiceSafety.scrub(q).text());
        out.put("screen", screen);
        out.put("executed", false);
        out.put("notice", NOTICE);
        out.put("suggestions", SUGGESTIONS);
        if (VoiceSafety.emergency(q)) return fixed(out, "EMERGENCY", VoiceSafety.emergencyReply(false), null);
        if (OrgAssistantAi.asksDiagnosis(q)) {
            return fixed(out, "NO_DIAGNOSIS", "건강 상태를 판단하는 말은 하지 않아요. 봉사자 일지에 적힌 내용과 안부 확인 순서는 리포트에서 볼 수 있어요. 걱정되면 직접 안부를 확인해 주세요.",
                    card("리포트", "/o/report", "리포트 열기", List.of()));
        }
        OrgAssistantAi.Intent in = ai.classify(q);
        Answer a = switch (in.intent()) {
            case "GAPS" -> gaps(mgr);
            case "CHECK_FIRST" -> checkFirst(mgr);
            case "WEATHER" -> weather(mgr);
            case "DELEGATION" -> delegation(mgr);
            case "APPLICATIONS" -> applications(mgr);
            case "VOLUNTEERS" -> volunteers(mgr);
            case "TODO" -> todo(mgr);
            case "DRAFT" -> draft(mgr, q);
            default -> new Answer("관리 대상, 편성표, 요청, 기관 공고 신청, 권한 위임, 리포트 중에 무엇을 도와드릴까요? 아래 예시를 눌러도 돼요.", null, Map.of());
        };
        String text = a.text();
        // 대신 결정·실행해 달라는 말에는 직접 눌러야 한다는 안내를 붙인다(이 문장은 AI 가 다듬지 않는다)
        String tail = OrgAssistantAi.asksDecision(q) ? " 수락·등록 같은 결정은 제가 대신하지 않아요. 화면에서 확인하고 직접 눌러 주세요." : "";
        Map<String, Object> polished = "HELP".equals(in.intent()) ? Views.map("text", text, "source", "rule") : ai.polish(q, text, a.facts());
        out.put("intent", in.intent());
        out.put("text", polished.get("text") + tail);
        out.put("card", a.card());
        out.put("facts", a.facts());
        out.put("source", polished.get("source"));
        if (polished.get("provider") != null) out.put("provider", polished.get("provider"));
        return out;
    }

    private static Map<String, Object> fixed(Map<String, Object> out, String intent, String text, Map<String, Object> card) {
        out.put("intent", intent);
        out.put("text", text);
        out.put("card", card);
        out.put("facts", Map.of());
        out.put("source", "rule");
        return out;
    }

    private List<Elder> myElders(User mgr) {
        return elders.findByManagerId(mgr.id).stream().filter(e -> e.status == ElderStatus.ACTIVE).sorted(Comparator.comparing((Elder e) -> e.id)).toList();
    }

    // ---------- 이번 주 빈칸 ----------
    @SuppressWarnings("unchecked")
    private Answer gaps(User mgr) {
        Map<String, Object> w = schedule.week(mgr, LocalDate.now(), null);
        Map<String, Object> counts = (Map<String, Object>) w.get("counts");
        int gaps = ((Number) counts.get("gaps")).intValue(), fixed = ((Number) counts.get("fixed")).intValue();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) w.get("elders")) {
            for (Map<String, Object> c : (List<Map<String, Object>>) e.get("cells")) {
                Map<String, Object> g = (Map<String, Object>) c.get("gap");
                if (g == null || rows.size() >= 6) continue;
                LocalDate d = LocalDate.parse((String) c.get("date"));
                rows.add(row(e.get("displayName") + " · " + Rules.md(d), g.get("category") + " · " + g.get("reason"), "/o/schedule?elder=" + e.get("elderId")));
            }
        }
        String text = gaps == 0 ? "이번 주에는 평소 방문이 있던 요일 가운데 비어 있는 칸이 없어요. 확정된 일정은 " + fixed + "건이에요."
                : "이번 주 빈칸이 " + gaps + "개 있어요. 편성표에서 칸을 누르면 봉사자 추천을 보고 직접 요청할 수 있어요.";
        return new Answer(text, card("빈칸 " + gaps + "개", "/o/schedule", "편성표 열기", rows), Views.map("gaps", gaps, "fixed", fixed, "weekStart", w.get("weekStart")));
    }

    // ---------- 먼저 안부 물어볼 분 ----------
    @SuppressWarnings("unchecked")
    private Answer checkFirst(User mgr) {
        // 리포트 문장은 여기서 다시 AI 로 만들지 않는다(질문 한 번에 여러 번 부르지 않게 규칙 모드로만 읽는다)
        Map<String, Object>[] box = new Map[1];
        aiClient.ruleOnly(() -> box[0] = change.report(mgr, false));
        List<Map<String, Object>> items = (List<Map<String, Object>>) box[0].get("items");
        List<Map<String, Object>> first = items.stream().filter(i -> Boolean.TRUE.equals(i.get("checkFirst"))).limit(4).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> i : first) {
            List<String> reasons = (List<String>) i.get("reasons");
            rows.add(row(++n + ". " + i.get("displayName"), reasons == null || reasons.isEmpty() ? "" : String.join(" · ", reasons), "/o/report?elder=" + i.get("elderId")));
        }
        long all = items.stream().filter(i -> Boolean.TRUE.equals(i.get("checkFirst"))).count();
        String text = all == 0 ? "지금은 먼저 안부를 확인해 보라고 표시된 분이 없어요. 최근 " + ElderChangeService.WINDOW_DAYS + "일 활동·요청 기록으로 본 결과예요."
                : "최근 " + ElderChangeService.WINDOW_DAYS + "일 활동·요청 기록으로 보면 먼저 안부를 확인해 볼 분이 " + all + "명이에요. 진단이 아니라 확인 순서예요.";
        return new Answer(text, card("먼저 안부 물어볼 분", "/o/report", "리포트 열기", rows), Views.map("checkFirst", all, "total", items.size(), "windowDays", ElderChangeService.WINDOW_DAYS));
    }

    // ---------- 특보 대비 ----------
    @SuppressWarnings("unchecked")
    private Answer weather(User mgr) {
        Map<String, Object> w = weather.forManager(mgr);
        List<Map<String, Object>> alerts = ((List<Map<String, Object>>) w.get("alerts")).stream().filter(a -> Boolean.TRUE.equals(a.get("mineArea"))).toList();
        if (alerts.isEmpty())
            return new Answer("지금 내 담당 지역에 진행 중인 기상 특보가 없어요.", card("특보 알림", "/o/weather", "특보 알림 열기", List.of()), Views.map("alerts", 0));
        Map<String, Object> a = alerts.get(0);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (a.get("actions") instanceof List<?> l) for (Object x : l) { if (rows.size() < 6) rows.add(row(String.valueOf(x), null, null)); }
        String title = a.get("type") + " " + a.get("level");
        String text = title + "(" + a.get("from") + " ~ " + a.get("to") + ") 지역에 내 관리 대상이 " + a.get("myElders") + "명 있고, 그 기간에 예정된 활동이 "
                + a.get("myActivities") + "건이에요. 할 일을 정리했어요.";
        return new Answer(text, card("할 일 " + rows.size() + "개", "/o/weather", "특보 알림 열기", rows),
                Views.map("alerts", alerts.size(), "type", a.get("type"), "level", a.get("level"), "from", a.get("from"), "to", a.get("to"),
                        "myElders", a.get("myElders"), "myActivities", a.get("myActivities"), "actions", rows.size()));
    }

    // ---------- 권한 위임 ----------
    @SuppressWarnings("unchecked")
    private Answer delegation(User mgr) {
        Map<String, Object> d = delegations.listForManager(mgr);
        Map<String, Object> c = (Map<String, Object>) d.get("counts");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> i : (List<Map<String, Object>>) d.get("items")) {
            String st = (String) i.get("status");
            boolean soon = Boolean.TRUE.equals(i.get("expiringSoon"));
            if (rows.size() >= 6 || ("ACTIVE".equals(st) && !soon)) continue;
            rows.add(row((String) i.get("displayName"), i.get("statusLabel") + (i.get("endDate") == null ? "" : " · " + i.get("endDate") + "까지"), "/o/delegations?elder=" + i.get("elderId")));
        }
        String text = "유효한 위임이 " + c.get("active") + "건이고, 그중 " + DelegationService.SOON_DAYS + "일 안에 끝나는 것이 " + c.get("expiringSoon") + "건이에요. 만료 "
                + c.get("expired") + "건, 위임 없음 " + c.get("none") + "건, 받은 요청 " + c.get("requests") + "건이에요.";
        return new Answer(text, card("확인할 위임 " + rows.size() + "건", "/o/delegations", "권한 위임 열기", rows), new LinkedHashMap<>(c));
    }

    // ---------- 기관 공고 신청 ----------
    @SuppressWarnings("unchecked")
    private Answer applications(User mgr) {
        Map<String, Object> a = notices.applicationsForOrg(mgr, "todo", null);
        Map<String, Object> c = (Map<String, Object>) a.get("counts");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> i : (List<Map<String, Object>>) a.get("items")) {
            if (rows.size() >= 6) break;
            Map<String, Object> n = (Map<String, Object>) i.get("notice");
            rows.add(row(i.get("name") + " 님 · " + n.get("kindLabel"), (String) n.get("title"), "/o/notices?tab=apps&app=" + i.get("id")));
        }
        String text = "기관 공고에 들어온 신청 가운데 답을 기다리는 것이 " + c.get("todo") + "건, 확정이 " + c.get("confirmed") + "건이에요. 수락·거절은 신청 화면에서 직접 눌러 주세요.";
        return new Answer(text, card("답할 신청 " + c.get("todo") + "건", "/o/notices?tab=apps", "신청 보기", rows), new LinkedHashMap<>(c));
    }

    // ---------- 봉사자 현황 ----------
    @SuppressWarnings("unchecked")
    private Answer volunteers(User mgr) {
        Map<String, Object> v = volunteers.status(mgr, 6);
        Map<String, Object> c = (Map<String, Object>) v.get("counts");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> i : (List<Map<String, Object>>) v.get("items"))
            rows.add(row(i.get("name") + " 님", i.get("stateLabel") + " · " + i.get("sub"), null));
        String text = "내 관리 대상과 엮인 봉사자는 " + c.get("total") + "명이에요. 지금 활동 중 " + c.get("activeNow") + "명, 오늘 예정 " + c.get("today")
                + "명, 응답 대기 " + c.get("pending") + "명이고 단골은 " + c.get("regulars") + "명이에요.";
        return new Answer(text, card("봉사자 현황", "/o/home", "홈에서 보기", rows), new LinkedHashMap<>(c));
    }

    // ---------- 오늘 처리할 일 ----------
    private Answer todo(User mgr) {
        Set<Long> ids = new HashSet<>();
        myElders(mgr).forEach(e -> ids.add(e.id));
        List<Request> mine = requests.findAll().stream().filter(r -> r.elderId != null && ids.contains(r.elderId)).toList();
        long fin = mine.stream().filter(r -> r.status == ReqStatus.VOL_ACCEPTED || (r.status == ReqStatus.SENT && r.kind == Kind.PROPOSAL)).count();
        long logs = mine.stream().filter(r -> r.status == ReqStatus.LOG_SUBMITTED).count();
        long adjust = mine.stream().filter(r -> "PENDING".equals(r.adjustStatus) && !r.status.terminal()).count();
        long apps = notices.pendingCount(mgr);
        @SuppressWarnings("unchecked")
        long dlg = ((Number) ((Map<String, Object>) delegations.listForManager(mgr).get("counts")).get("requests")).longValue();
        List<Map<String, Object>> rows = List.of(
                row("최종 수락·제안 답하기", fin + "건", "/o/reqs"), row("활동 일지 확인", logs + "건", "/o/reqs"), row("시간 조정 답하기", adjust + "건", "/o/reqs"),
                row("기관 공고 신청 답하기", apps + "건", "/o/notices?tab=apps"), row("위임 요청 답하기", dlg + "건", "/o/delegations?tab=requests"));
        long total = fin + logs + adjust + apps + dlg;
        String text = total == 0 ? "지금 답을 기다리는 일이 없어요."
                : "지금 처리할 일이 모두 " + total + "건이에요. 최종 수락·제안 " + fin + "건, 일지 확인 " + logs + "건, 시간 조정 " + adjust + "건, 기관 공고 신청 " + apps + "건, 위임 요청 " + dlg + "건이에요.";
        return new Answer(text, card("처리할 일 " + total + "건", "/o/reqs", "요청 관리 열기", rows),
                Views.map("total", total, "finalAccept", fin, "logs", logs, "adjust", adjust, "applications", apps, "delegationRequests", dlg));
    }

    // ---------- 대리 요청 초안 ----------
    private static final String[][] CAT_WORDS = {{"동행|병원|같이\\s*가", "동행"}, {"장보기|심부름|마트", "장보기·심부름"}, {"말동무|말벗|산책|안부", "말동무·정서"},
            {"청소|설거지|빨래|집안일", "가벼운 집안일"}, {"스마트폰|휴대폰|디지털|키오스크|영상통화", "디지털 도움"}, {"정리", "생활 정리"}};

    static String categoryOf(String q) {
        for (String[] c : CAT_WORDS) if (java.util.regex.Pattern.compile(c[0]).matcher(q).find() && Rules.isCategory(c[1])) return c[1];
        return null;
    }

    private Answer draft(User mgr, String q) {
        List<Elder> mine = myElders(mgr);
        Elder e = mine.stream().filter(x -> x.alias != null && !x.alias.isBlank() && q.contains(x.alias)).findFirst().orElse(null);
        if (e == null) {
            List<Map<String, Object>> rows = mine.stream().limit(6).map(x -> row(x.alias + " 님", x.dong, "/write/need?elderId=" + x.id)).toList();
            return new Answer("어느 분의 요청인지 알려 주세요. 아래에서 고르면 대리 요청 쓰기 화면으로 가요.", card("관리 대상 고르기", "/o/elders", "관리 대상 열기", rows),
                    Views.map("elders", mine.size()));
        }
        String cat = categoryOf(q);
        if (cat == null && e.needs != null && e.needs.size() == 1) cat = e.needs.get(0);
        LocalDate date = AssistantAi.date(q, LocalDate.now());
        if (date != null && date.isBefore(LocalDate.now())) date = null;
        List<String> regs = views.regularsOf(null, e.id).stream().map(m -> m.get("name") + " 님").limit(2).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("대상", e.alias + " 님 · " + e.dong, "/o/elders/" + e.id));
        rows.add(row("도움 종류", cat == null ? "쓰기 화면에서 골라 주세요" : cat, null));
        rows.add(row("날짜", date == null ? "쓰기 화면에서 정해 주세요" : Rules.md(date), null));
        if (e.caution != null && !e.caution.isBlank()) rows.add(row("봉사자에게 보일 주의사항", e.caution, null));
        if (!regs.isEmpty()) rows.add(row("단골 봉사자", String.join(", ", regs), "/regular"));
        String link = "/write/need?elderId=" + e.id + (cat == null ? "" : "&cat=" + java.net.URLEncoder.encode(cat, java.nio.charset.StandardCharsets.UTF_8))
                + (date == null ? "" : "&date=" + date);
        String text = e.alias + " 님" + (cat == null ? "" : " " + cat) + " 요청 초안을 준비했어요. 아직 올린 것은 아니에요. 쓰기 화면에서 날짜·시간을 확인하고 직접 올려 주세요.";
        Map<String, Object> card = card("대리 요청 초안", link, "쓰기 화면 열기", rows);
        card.put("draft", Views.map("elderId", e.id, "category", cat, "date", date == null ? null : date.toString()));
        return new Answer(text, card, Views.map("elder", e.alias, "category", cat, "date", date == null ? null : date.toString(), "regulars", regs.size()));
    }
}
