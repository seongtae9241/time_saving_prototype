package com.timesavings.service;

import com.timesavings.ai.CareMemoAi;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 7.14 C1 전달 메모 정리 + 생활 변화 신호.
 * 일지 제출 때(RequestService.submitLog 끝) 한 번 정리해 care_notes 에 저장하고, 같은 받는 사람의 최근 메모 WINDOW 건 중
 * 생활 변화 신호가 있는 메모가 2건 이상이면 '확인 권장'. 독거노인이면 처음 켜질 때 담당자에게 알림(CARE_SIGNAL).
 * 41판 B-36: 쓰러짐·넘어짐·식사 거름처럼 뚜렷한 표현(CareMemoAi.URGENT)은 일지 한 건에만 있어도 '확인 권장'이 켜지고,
 * 독거노인이면 그 일지로 바로 담당자에게 요약 알림을 보낸다(같은 일지를 고쳐 다시 내도 한 번만).
 * 일반 사용자는 알림 없이 공유 동의한 가족 리포트에만 보인다. 진단이 아니라 "확인 권장"이다.
 */
@Service
@Transactional
public class CareSignalService {
    public static final int WINDOW = 3;
    public static final int MIN_SIGNALS = 2;
    public static final int MAX_AGE_DAYS = 45;
    public static final String NOTICE = "진단이 아니에요. 최근 일지·메모에 생활 변화 표현이 있어 안부 확인을 권해요.";

    private final CareNoteRepo notes;
    private final ElderRepo elders;
    private final RequestRepo requests;
    private final NotificationService noti;
    private final CareMemoAi ai;

    public CareSignalService(CareNoteRepo notes, ElderRepo elders, RequestRepo requests, NotificationService noti, CareMemoAi ai) {
        this.notes = notes; this.elders = elders; this.requests = requests; this.noti = noti; this.ai = ai;
    }

    /** 일지·전달 메모 제출 뒤 한 번. 실패해도 일지 제출은 막지 않는다. */
    public CareNote onLog(Request r) {
        try {
            return process(r);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(CareSignalService.class).warn("전달 메모 정리 실패({})", e.getClass().getSimpleName());
            return null;
        }
    }

    CareNote process(Request r) {
        SubjectType type = r.elderId != null ? SubjectType.ELDER : SubjectType.USER;
        Long sid = r.elderId != null ? r.elderId : r.recipientId;
        if (sid == null) return null;
        Optional<CareNote> old = notes.findByRequestId(r.id);
        boolean hasMemo = r.careMemo != null && !r.careMemo.isBlank();
        CareMemoAi.Structured s = hasMemo || !CareMemoAi.ruleSignals(r.logRaw).isEmpty() ? ai.structure(r.careMemo, r.logRaw) : null;
        if (s == null || (!hasMemo && s.signals().isEmpty())) { old.ifPresent(notes::delete); return null; }
        boolean before = status(type, sid, r.id).flagged();
        CareNote n = old.orElseGet(CareNote::new);
        n.requestId = r.id; n.subjectType = type; n.subjectId = sid; n.activityDate = r.date;
        n.nextSteps = String.join("\n", s.next());
        n.hospital = s.hospital();
        n.lifeChanges = String.join("\n", s.life());
        n.signals = new ArrayList<>(s.signals());
        n.source = s.source(); n.provider = s.provider();
        n.updatedAt = LocalDateTime.now();
        notes.save(n);
        Signal after = status(type, sid, null);
        List<String> urgent = CareMemoAi.urgentOf(n.signals);
        boolean windowRaised = !before && after.flagged();
        if ((windowRaised || !urgent.isEmpty()) && !n.flagRaised) {
            n.flagRaised = true;
            if (type == SubjectType.ELDER) elders.findById(sid).ifPresent(e -> {
                if (e.managerId == null) return;
                // AI·규칙 모두 진단·건강 판단은 쓰지 않는다. 적힌 표현의 이름만 전하고 "확인해 보세요"까지만 말한다.
                String body = !urgent.isEmpty()
                        ? Rules.md(r.date) + " 활동 일지에 '" + String.join("·", urgent) + "' 표현이 있어요. 진단이 아니에요. 전화나 방문으로 안부를 확인해 보세요"
                        : "최근 메모에 '" + String.join("·", after.labels()) + "' 표현이 " + after.count() + "번 나왔어요. 진단이 아니라 안부 확인을 권해요";
                noti.send(e.managerId, "CARE_SIGNAL", e.alias + " 님 " + (urgent.isEmpty() ? "생활 변화 확인 권장" : "안부 확인 권장"), body, "/o/elders/" + e.id);
            });
        }
        return n;
    }

    public record Signal(boolean flagged, int count, List<String> labels, LocalDate since, List<Map<String, Object>> recent) {
        public Map<String, Object> view() {
            return Views.map("flagged", flagged, "count", count, "labels", labels, "since", since == null ? null : since.toString(),
                    "recent", recent, "window", WINDOW, "notice", NOTICE);
        }
    }

    /** 최근 WINDOW 건 메모 중 신호가 있는 것이 MIN_SIGNALS 건 이상이거나, 한 건이라도 뚜렷한 표현(URGENT)이 있으면 확인 권장. excludeRequestId 는 '바꾸기 전' 계산용. */
    @Transactional(readOnly = true)
    public Signal status(SubjectType type, Long sid, Long excludeRequestId) {
        LocalDate cut = LocalDate.now().minusDays(MAX_AGE_DAYS);
        List<CareNote> list = notes.findBySubjectTypeAndSubjectId(type, sid).stream()
                .filter(n -> excludeRequestId == null || !excludeRequestId.equals(n.requestId))
                .filter(n -> n.activityDate == null || !n.activityDate.isBefore(cut))
                .sorted(Comparator.comparing((CareNote n) -> n.activityDate == null ? LocalDate.MIN : n.activityDate).reversed()
                        .thenComparing(n -> n.id, Comparator.reverseOrder()))
                .limit(WINDOW).toList();
        List<CareNote> hit = list.stream().filter(n -> n.signals != null && !n.signals.isEmpty()).toList();
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        hit.forEach(n -> labels.addAll(n.signals));
        LocalDate since = hit.stream().map(n -> n.activityDate).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        List<Map<String, Object>> recent = hit.stream().map(n -> Views.map("requestId", n.requestId,
                "date", n.activityDate == null ? null : n.activityDate.toString(), "signals", n.signals)).toList();
        boolean urgent = hit.stream().anyMatch(n -> !CareMemoAi.urgentOf(n.signals).isEmpty());
        return new Signal(hit.size() >= MIN_SIGNALS || urgent, hit.size(), new ArrayList<>(labels), since, recent);
    }

    public static Map<String, Object> view(CareNote n) {
        if (n == null) return null;
        return Views.map("next", lines(n.nextSteps), "hospital", n.hospital, "life", lines(n.lifeChanges), "signals", n.signals,
                "source", n.source, "provider", n.provider);
    }

    private static List<String> lines(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.stream(s.split("\n")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, CareNote> byRequest(Collection<Long> ids) {
        Map<Long, CareNote> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        for (CareNote n : notes.findByRequestIdIn(ids)) m.put(n.requestId, n);
        return m;
    }

    /** 담당자의 대상별 확인 권장 상태. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forManager(User mgr) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Elder e : elders.findAll()) {
            if (!mgr.id.equals(e.managerId) || e.status != ElderStatus.ACTIVE) continue;
            Signal s = status(SubjectType.ELDER, e.id, null);
            Map<String, Object> m = s.view();
            m.put("elderId", e.id);
            m.put("alias", e.alias);
            out.add(m);
        }
        return out;
    }

    /** 데모 데이터: 이순례 님 최근 두 건 메모에 생활 변화 표현을 넣고 모든 전달 메모를 정리한다(시드는 규칙 모드). */
    public void seedDemo() {
        elders.findAll().stream().filter(e -> "이순례".equals(e.alias)).findFirst().ifPresent(e -> {
            List<Request> done = requests.findByElderId(e.id).stream().filter(r -> r.status == ReqStatus.DONE && r.logSubmittedAt != null)
                    .sorted(Comparator.comparing((Request r) -> r.date).reversed()).toList();
            if (done.size() >= 2) {
                Request a = done.get(0), b = done.get(1);
                a.careMemo = (a.careMemo == null ? "" : a.careMemo + " ") + "요즘 입맛이 없다며 점심을 자주 거르신대요. 다음 방문 때 같이 드실 죽을 사 가기로 했어요.";
                b.careMemo = "기운이 없다고 하셔서 산책은 다음에 하자고 하셨어요. 경로당에도 요즘 안 가신대요.";
            }
        });
        requests.findAll().stream().filter(r -> r.careMemo != null && !r.careMemo.isBlank() && r.logSubmittedAt != null)
                .sorted(Comparator.comparing((Request r) -> r.date)).forEach(this::process);
    }
}
