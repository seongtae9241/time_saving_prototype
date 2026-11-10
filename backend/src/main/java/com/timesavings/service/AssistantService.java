package com.timesavings.service;

import com.timesavings.ai.AssistantAi;
import com.timesavings.ai.KoreanParser;
import com.timesavings.ai.KoreanSpeech;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * LLM 추가 A(7.12) 음성 비서 "물어보기": 의도만 AssistantAi(LLM 또는 규칙)가 정하고, 답은 코드가 내 데이터(잔액·일정·단골·알림)로 만든다.
 * 취소·요청 만들기 같은 실행은 **하지 않는다** — 해당 화면 링크와 확인 버튼(actions)만 돌려준다(executed:false).
 */
@Service
@Transactional(readOnly = true)
public class AssistantService {
    private final AssistantAi ai;
    private final BankService bank;
    private final RequestRepo requests;
    private final NotificationRepo notis;
    private final ElderRepo elders;
    private final UserRepo users;
    private final Views views;

    public AssistantService(AssistantAi ai, BankService bank, RequestRepo requests, NotificationRepo notis, ElderRepo elders, UserRepo users, Views views) {
        this.ai = ai; this.bank = bank; this.requests = requests; this.notis = notis; this.elders = elders; this.users = users; this.views = views;
    }

    private static final List<ReqStatus> UPCOMING = List.of(ReqStatus.VOL_ACCEPTED, ReqStatus.CONFIRMED, ReqStatus.IN_PROGRESS);

    /** 내가 당사자(봉사자·받는 사람·보낸 사람·담당 대상의 담당자)인 다가오는 일정, 가까운 순. */
    List<Request> upcoming(User me, LocalDate today) {
        return requests.findByStatusIn(UPCOMING).stream()
                .filter(r -> views.roleOf(r, me) != null && !r.date.isBefore(today))
                .sorted(Comparator.comparing((Request r) -> r.date).thenComparingDouble(r -> r.startHour))
                .toList();
    }

    private String who(Request r, User me) {
        String role = views.roleOf(r, me);
        if ("VOLUNTEER".equals(role)) {
            if (r.elderId != null) return elders.findById(r.elderId).map(e -> Rules.maskAlias(e.alias)).orElse("도움 대상");
            return users.findById(r.recipientId).map(u -> u.name + " 님").orElse("상대");
        }
        return users.findById(r.volunteerId).map(u -> u.name + " 님").orElse("봉사자");
    }

    /** 일정 한 줄: [화면 문장, 소리 문장] */
    private String[] line(Request r, User me, boolean withDate) {
        String role = views.roleOf(r, me);
        String w = who(r, me);
        String cat = r.category == null ? "도움" : r.category;
        String dTxt = withDate ? KoreanParser.dateSpeech(r.date) + " " : "";
        String dSp = withDate ? KoreanSpeech.date(r.date) + " " : "";
        String tTxt = KoreanParser.hourSpeech(r.startHour), tSp = KoreanSpeech.clock(r.startHour);
        String state = r.status == ReqStatus.VOL_ACCEPTED ? " (아직 최종 수락 전)" : r.status == ReqStatus.IN_PROGRESS ? " (지금 활동 중)" : "";
        if ("VOLUNTEER".equals(role)) {
            return new String[]{dTxt + tTxt + "에 " + w + " " + cat + " 봉사가 있어요" + state, dSp + tSp + "에 " + w + " " + cat + " 봉사가 있어요" + state};
        }
        if ("MANAGER".equals(role)) {
            String el = r.elderId == null ? "" : elders.findById(r.elderId).map(e -> e.alias + " 님께 ").orElse("");
            return new String[]{dTxt + tTxt + "에 " + w + "이 " + el + cat + " 하러 가요" + state, dSp + tSp + "에 " + w + "이 " + el + cat + " 하러 가요" + state};
        }
        return new String[]{dTxt + tTxt + "에 " + w + "이 " + cat + " 하러 와요" + state, dSp + tSp + "에 " + w + "이 " + cat + " 하러 와요" + state};
    }

    private static Map<String, Object> act(String label, String link, boolean confirm) {
        return Views.map("label", label, "link", link, "confirm", confirm);
    }

    private Map<String, Object> item(Request r, User me) {
        return Views.map("requestId", r.id, "date", r.date.toString(), "startHour", r.startHour, "hours", r.hours, "category", r.category,
                "status", r.status, "who", who(r, me), "myRole", views.roleOf(r, me));
    }

    public Map<String, Object> answer(User me, String text, LocalDate today) {
        AssistantAi.Intent in = ai.classify(text, today);
        String intent = in.intent();
        String ans, sp;
        List<Map<String, Object>> actions = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        switch (intent) {
            case "BALANCE" -> {
                if (me.role != Role.USER) { ans = sp = "담당자 계정에는 시간 통장이 없어요."; break; }
                BankService.Summary s = bank.summary(me.id);
                ans = "지금 쓸 수 있는 시간은 " + Rules.fmtHours(s.usable()) + "시간이에요." + (s.hold() > 0 ? " 확정된 요청으로 " + Rules.fmtHours(s.hold()) + "시간이 보류 중이에요." : "")
                        + (s.canUseSelf() ? "" : " 직접 적립한 시간이 " + Rules.fmtHours(BankService.SELF_USE_MIN) + "시간이 되기 전에는 선물받은 시간만 쓸 수 있어요.");
                sp = KoreanSpeech.speakable(ans);
                actions.add(act("시간 통장 보기", "/bank", false));
            }
            case "TOMORROW" -> {
                LocalDate tm = today.plusDays(1);
                List<Request> list = upcoming(me, today).stream().filter(r -> r.date.equals(tm)).toList();
                if (list.isEmpty()) { ans = sp = "내일은 잡힌 일정이 없어요."; }
                else {
                    StringBuilder a = new StringBuilder("내일 "), b = new StringBuilder("내일 ");
                    for (int i = 0; i < list.size() && i < 3; i++) {
                        String[] l = line(list.get(i), me, false);
                        if (i > 0) { a.append(" 그리고 "); b.append(" 그리고 "); }
                        a.append(l[0]); b.append(l[1]);
                        items.add(item(list.get(i), me));
                    }
                    ans = a.append(".").toString(); sp = b.append(".").toString();
                    actions.add(act("일정 자세히 보기", "/req/" + list.get(0).id, false));
                }
            }
            case "NEXT" -> {
                List<Request> list = upcoming(me, today);
                if (in.date() != null) list = list.stream().filter(r -> r.date.equals(in.date())).toList();
                if (list.isEmpty()) { ans = sp = in.date() != null ? "그날은 잡힌 일정이 없어요." : "다가오는 일정이 없어요."; }
                else {
                    String[] l = line(list.get(0), me, true);
                    ans = "다음 일정은 " + l[0] + "." + (list.size() > 1 ? " 그 뒤로 " + (list.size() - 1) + "건이 더 있어요." : "");
                    sp = "다음 일정은 " + l[1] + "." + (list.size() > 1 ? " 그 뒤로 " + KoreanSpeech.nativeNum(list.size() - 1) + " 건이 더 있어요." : "");
                    for (Request r : list.subList(0, Math.min(3, list.size()))) items.add(item(r, me));
                    actions.add(act("일정 자세히 보기", "/req/" + list.get(0).id, false));
                }
            }
            case "CANCEL" -> {
                List<Request> list = upcoming(me, today).stream().filter(r -> r.status != ReqStatus.IN_PROGRESS).toList();
                if (in.date() != null) list = list.stream().filter(r -> r.date.equals(in.date())).toList();
                if (list.isEmpty()) { ans = sp = in.date() != null ? "그날 취소할 일정을 찾지 못했어요. 내 활동에서 확인해 주세요." : "취소할 일정이 없어요."; actions.add(act("내 활동 보기", "/act", false)); }
                else {
                    Request r = list.get(0);
                    String[] l = line(r, me, true);
                    boolean late = LocalDateTime.now().isAfter(r.startAt().minusHours(24));
                    String warn = late && "VOLUNTEER".equals(views.roleOf(r, me)) ? " 시작 24시간 안이라 봉사자가 취소하면 경고가 남을 수 있어요." : "";
                    ans = l[0] + ". 이 일정을 취소하려면 아래 버튼을 눌러 화면에서 한 번 더 확인해 주세요. 제가 바로 취소하지는 않아요." + warn;
                    sp = l[1] + ". 취소하려면 화면의 버튼을 눌러 한 번 더 확인해 주세요." + (warn.isEmpty() ? "" : KoreanSpeech.speakable(warn));
                    items.add(item(r, me));
                    Map<String, Object> a = act("이 일정 열어서 취소하기", (me.role == Role.ORG ? "/o/reqs/" : "/req/") + r.id, true);
                    a.put("kind", "cancel");
                    a.put("requestId", r.id);
                    actions.add(a);
                    if (list.size() > 1) actions.add(act("다른 일정 고르기", "/act", false));
                }
            }
            case "REGULAR" -> {
                List<Map<String, Object>> regs = me.role == Role.USER ? views.regularsOf(me.id, null) : List.of();
                if (regs.isEmpty()) { ans = sp = "아직 단골 봉사자가 없어요. 같은 봉사자와 세 번 활동하면 단골이 돼요."; }
                else {
                    List<String> names = regs.stream().limit(3).map(m -> m.get("name") + " 님").toList();
                    ans = "단골 봉사자는 " + String.join(", ", names) + (regs.size() > 3 ? " 외 " + (regs.size() - 3) + "명" : "") + "이에요.";
                    sp = KoreanSpeech.speakable(ans);
                    for (Map<String, Object> m : regs) items.add(Views.map("volunteerId", m.get("id"), "name", m.get("name"), "doneCount", m.get("doneCount")));
                }
                actions.add(act("단골에게 요청하기", "/regular", false));
            }
            case "NOTI" -> {
                List<Notification> list = notis.findByUserIdOrderByCreatedAtDescIdDesc(me.id);
                long unread = list.stream().filter(n -> !n.isRead).count();
                if (list.isEmpty()) { ans = sp = "받은 알림이 없어요."; }
                else {
                    List<Notification> top = list.stream().filter(n -> !n.isRead).limit(2).toList();
                    if (top.isEmpty()) top = list.subList(0, 1);
                    String t = String.join(" / ", top.stream().map(n -> n.title).toList());
                    ans = (unread > 0 ? "안 읽은 알림이 " + unread + "개 있어요. " : "새 알림은 없어요. 가장 최근 알림은 ") + t + (unread > 0 ? "" : "예요.");
                    sp = KoreanSpeech.speakable(ans);
                    for (Notification n : top) items.add(Views.map("notificationId", n.id, "title", n.title, "link", n.link));
                }
                actions.add(act("알림 보기", "/notif", false));
            }
            case "CREATE" -> {
                ans = sp = "새 도움 요청은 말로 요청하기에서 만들 수 있어요. 버튼을 누르면 화면이 열리고, 올리기 전에 한 번 더 확인해요.";
                actions.add(act("말로 요청하기", "/voice", true));
                actions.add(act("글로 봉사 요청 쓰기", "/write/need", true));
            }
            default -> {
                ans = sp = "이렇게 물어보세요. 내 시간 얼마 남았어? 내일 누가 와? 다음 일정 언제야? 단골 누구야?";
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", intent);
        out.put("date", in.date() == null ? null : in.date().toString());
        out.put("answer", ans);
        out.put("speech", sp);
        out.put("actions", actions);
        out.put("items", items);
        out.put("executed", false);
        out.put("source", in.source());
        return out;
    }
}
