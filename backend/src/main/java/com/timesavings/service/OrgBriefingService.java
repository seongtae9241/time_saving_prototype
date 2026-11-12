package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.ai.OrgAi;
import com.timesavings.config.ApiException;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * 7.14 C2·C3 담당자: 통화 메모 → 공고 초안, 이번 주 브리핑.
 * 브리핑 숫자는 매번 코드가 다시 세고, 요약 글(LLM)은 ai_results(ORG_BRIEFING)에 저장해 같은 주·같은 상황이면 다시 쓴다.
 * 주가 바뀌거나 큰 변화(확인 권장 대상, 새 받지 않기, 3일 안 미매칭 공고)가 생기거나 "다시 만들기"를 누르면 다시 만든다.
 */
@Service
@Transactional
public class OrgBriefingService {
    public static final String KIND = "ORG_BRIEFING";
    private final ElderRepo elders;
    private final RequestRepo requests;
    private final PostRepo posts;
    private final ElderBlockRepo blocks;
    private final AiResultRepo results;
    private final CareSignalService signals;
    private final OrgAi ai;

    public OrgBriefingService(ElderRepo elders, RequestRepo requests, PostRepo posts, ElderBlockRepo blocks, AiResultRepo results,
                              CareSignalService signals, OrgAi ai) {
        this.elders = elders; this.requests = requests; this.posts = posts; this.blocks = blocks; this.results = results;
        this.signals = signals; this.ai = ai;
    }

    private static void requireOrg(User u) { if (u.role != Role.ORG) throw ApiException.forbidden("담당자만 쓸 수 있어요"); }

    // ===================== C2 통화 메모 → 공고 초안 =====================
    @SuppressWarnings("unchecked")
    public Map<String, Object> memoToPosts(User mgr, Long elderId, String memo) {
        requireOrg(mgr);
        if (elderId == null) throw ApiException.bad("ELDER_REQUIRED", "어느 대상의 메모인지 골라 주세요");
        Elder e = elders.findById(elderId).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
        if (!mgr.id.equals(e.managerId)) throw ApiException.forbidden("내가 담당하는 대상만 쓸 수 있어요");
        if (e.status != ElderStatus.ACTIVE) throw ApiException.bad("ELDER_ENDED", "지원이 종료된 대상이에요");
        if (memo == null || memo.isBlank()) throw ApiException.bad("MEMO_REQUIRED", "통화 메모를 적어 주세요");
        if (memo.length() > 1000) throw ApiException.bad("MEMO_TOO_LONG", "메모는 1000자까지 적을 수 있어요");
        Map<String, Object> out = ai.memoToPosts(memo, e.alias, e.dong, e.caution, LocalDate.now());
        out.put("elderId", e.id);
        out.put("elder", Views.map("id", e.id, "alias", e.alias, "displayName", e.alias + " 님", "dong", e.dong));
        for (Object d : (List<?>) out.get("drafts")) if (d instanceof Map<?, ?> m) ((Map<String, Object>) m).put("elderId", e.id);
        return out;
    }

    // ===================== C3 이번 주 브리핑 =====================
    private static final EnumSet<ReqStatus> SCHEDULED = EnumSet.of(ReqStatus.CONFIRMED, ReqStatus.IN_PROGRESS, ReqStatus.CHECKED_OUT, ReqStatus.LOG_SUBMITTED, ReqStatus.DONE);

    static String hh(double h) {
        int H = (int) Math.floor(h);
        return (H < 12 ? "오전 " : "오후 ") + (H > 12 ? H - 12 : H) + "시" + (h - H >= 0.5 ? " 30분" : "");
    }

    static String md(LocalDate d) {
        return Rules.md(d);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> briefing(User mgr, boolean refresh) {
        requireOrg(mgr);
        LocalDate today = LocalDate.now();
        LocalDate mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), sun = mon.plusDays(6);
        List<Elder> my = elders.findAll().stream().filter(e -> mgr.id.equals(e.managerId) && e.status == ElderStatus.ACTIVE)
                .sorted(Comparator.comparing(e -> e.id)).toList();
        Map<Long, Elder> byId = new LinkedHashMap<>();
        my.forEach(e -> byId.put(e.id, e));
        List<Request> reqs = requests.findAll().stream().filter(r -> r.elderId != null && byId.containsKey(r.elderId)).toList();

        // 대상별 이번 주 일정
        List<Map<String, Object>> sched = new ArrayList<>();
        int schedCount = 0;
        Map<Long, CareSignalService.Signal> sig = new HashMap<>();
        for (Elder e : my) {
            List<Map<String, Object>> items = reqs.stream().filter(r -> e.id.equals(r.elderId) && SCHEDULED.contains(r.status)
                            && !r.date.isBefore(mon) && !r.date.isAfter(sun))
                    .sorted(Comparator.comparing((Request r) -> r.date).thenComparing(r -> r.startHour))
                    .map(r -> Views.map("requestId", r.id, "date", r.date.toString(), "label", md(r.date) + " " + hh(r.startHour),
                            "category", r.category, "status", r.status, "done", r.status == ReqStatus.DONE)).toList();
            schedCount += items.size();
            CareSignalService.Signal s = signals.status(SubjectType.ELDER, e.id, null);
            sig.put(e.id, s);
            sched.add(Views.map("elderId", e.id, "alias", e.alias, "items", items, "flagged", s.flagged(), "signalLabels", s.labels()));
        }
        // 미매칭 공고
        List<Map<String, Object>> unmatched = posts.findAll().stream()
                .filter(p -> p.elderId != null && byId.containsKey(p.elderId) && p.status == PostStatus.OPEN && !p.date.isBefore(today))
                .sorted(Comparator.comparing((Post p) -> p.date).thenComparing(p -> p.id))
                .map(p -> {
                    long pending = reqs.stream().filter(r -> p.id.equals(r.postId) && r.status.pending()).count();
                    long days = p.date.toEpochDay() - today.toEpochDay();
                    return Views.map("postId", p.id, "elderId", p.elderId, "alias", byId.get(p.elderId).alias, "title", p.title,
                            "date", p.date.toString(), "label", md(p.date), "daysLeft", days, "pendingRequests", pending, "urgent", days <= 3);
                }).toList();
        // 일지 확인 대기 · 최종 수락 대기
        List<Map<String, Object>> logPending = reqs.stream().filter(r -> r.status == ReqStatus.LOG_SUBMITTED)
                .sorted(Comparator.comparing((Request r) -> r.logSubmittedAt == null ? LocalDateTime.MIN : r.logSubmittedAt))
                .map(r -> Views.map("requestId", r.id, "alias", byId.get(r.elderId).alias, "category", r.category, "label", md(r.date))).toList();
        List<Map<String, Object>> finalPending = reqs.stream()
                .filter(r -> r.status == ReqStatus.VOL_ACCEPTED || (r.kind == Kind.PROPOSAL && r.status == ReqStatus.SENT))
                .sorted(Comparator.comparing((Request r) -> r.deadlineAt == null ? LocalDateTime.MAX : r.deadlineAt))
                .map(r -> Views.map("requestId", r.id, "alias", byId.get(r.elderId).alias, "category", r.category, "label", md(r.date) + " " + hh(r.startHour),
                        "kind", r.kind, "deadlineAt", r.deadlineAt)).toList();
        // 확인 권장
        List<Map<String, Object>> flags = my.stream().filter(e -> sig.get(e.id).flagged())
                .map(e -> Views.map("elderId", e.id, "alias", e.alias, "labels", sig.get(e.id).labels(), "count", sig.get(e.id).count())).toList();
        // 받지 않기 변화(최근 7일)
        LocalDateTime weekAgo = today.minusDays(7).atStartOfDay();
        List<ElderBlock> allBlocks = blocks.findAll().stream().filter(b -> byId.containsKey(b.elderId)).toList();
        List<Map<String, Object>> newBlocks = allBlocks.stream().filter(b -> b.createdAt != null && b.createdAt.isAfter(weekAgo))
                .map(b -> Views.map("blockId", b.id, "elderId", b.elderId, "alias", byId.get(b.elderId).alias, "reason", b.reason,
                        "createdAt", b.createdAt)).toList();

        Map<String, Object> counts = Views.map("elders", my.size(), "schedule", schedCount, "unmatched", unmatched.size(),
                "unmatchedUrgent", unmatched.stream().filter(u -> Boolean.TRUE.equals(u.get("urgent"))).count(),
                "logPending", logPending.size(), "finalPending", finalPending.size(), "flagged", flags.size(),
                "blocksNew", newBlocks.size(), "blocksTotal", allBlocks.size());

        // 규칙 문장
        List<String> lines = new ArrayList<>(), todo = new ArrayList<>();
        lines.add("이번 주 일정 " + schedCount + "건" + (schedCount > 0 ? " (" + String.join(", ", sched.stream()
                .filter(s -> !((List<?>) s.get("items")).isEmpty()).map(s -> s.get("alias") + " 님 " + ((List<?>) s.get("items")).size() + "건").toList()) + ")" : ""));
        if (!unmatched.isEmpty()) lines.add("아직 봉사자가 없는 공고 " + unmatched.size() + "건 · 가장 가까운 날 " + unmatched.get(0).get("label") + " " + unmatched.get(0).get("title"));
        else lines.add("봉사자를 기다리는 공고가 없어요");
        lines.add("일지 확인 대기 " + logPending.size() + "건 · 최종 수락 대기 " + finalPending.size() + "건");
        if (!flags.isEmpty()) lines.add("확인 권장: " + String.join(", ", flags.stream().map(f -> f.get("alias") + " 님(" + String.join("·", (List<String>) f.get("labels")) + ")").toList()));
        if (!newBlocks.isEmpty()) lines.add("이번 주 받지 않기 " + newBlocks.size() + "명 추가 (전체 " + allBlocks.size() + "명)");
        if (!finalPending.isEmpty()) todo.add("최종 수락 대기 " + finalPending.size() + "건 수락·거절하기");
        if (!logPending.isEmpty()) todo.add("일지 " + logPending.size() + "건 확인하기 (24시간 뒤 자동 확정)");
        for (Map<String, Object> f : flags) todo.add(f.get("alias") + " 님 안부 전화하기");
        unmatched.stream().filter(u -> Boolean.TRUE.equals(u.get("urgent"))).limit(2)
                .forEach(u -> todo.add(u.get("label") + " " + u.get("alias") + " 님 공고 단골에게 요청하기"));
        if (todo.isEmpty()) todo.add("급한 일이 없어요. 다음 주 일정을 미리 살펴보기");

        // 요약 글: 저장한 것을 다시 쓰거나 새로 만든다
        String fp = mon + "|F" + flags.stream().map(f -> String.valueOf(f.get("elderId"))).toList()
                + "|B" + newBlocks.stream().map(b -> String.valueOf(b.get("blockId"))).toList()
                + "|U" + unmatched.stream().filter(u -> Boolean.TRUE.equals(u.get("urgent"))).map(u -> String.valueOf(u.get("postId"))).toList();
        AiResult saved = results.findByKindAndRefKey(KIND, String.valueOf(mgr.id)).orElse(null);
        String reason;
        Map<String, Object> summary;
        if (!refresh && saved != null && fp.equals(saved.fingerprint)) {
            summary = Views.parse(saved.payload, new TypeReference<Map<String, Object>>() {}, null);
            reason = "cached";
        } else {
            reason = refresh ? "refresh" : saved == null ? "first" : (saved.fingerprint != null && saved.fingerprint.startsWith(mon + "|")) ? "change" : "weekly";
            Map<String, Object> stats = Views.map("week", mon + "~" + sun, "counts", counts,
                    "schedule", sched.stream().map(s -> Views.map("alias", s.get("alias"), "count", ((List<?>) s.get("items")).size(), "flagged", s.get("flagged"))).toList(),
                    "unmatched", unmatched.stream().limit(5).map(u -> Views.map("alias", u.get("alias"), "title", u.get("title"), "daysLeft", u.get("daysLeft"))).toList(),
                    "flags", flags, "newBlocks", newBlocks.size());
            summary = ai.briefing(stats, lines, todo);
            if (saved == null) { saved = new AiResult(); saved.kind = KIND; saved.refKey = String.valueOf(mgr.id); }
            saved.fingerprint = fp;
            saved.payload = Views.json(summary);
            saved.source = (String) summary.get("source");
            saved.provider = (String) summary.get("provider");
            saved.createdAt = LocalDateTime.now();
            results.save(saved);
        }
        if (summary == null) summary = Views.map("lines", lines, "todo", todo, "source", "rule");
        return Views.map("week", Views.map("start", mon.toString(), "end", sun.toString()), "counts", counts, "elders", sched,
                "unmatched", unmatched, "logPending", logPending, "finalPending", finalPending, "flags", flags,
                "blocks", Views.map("recent", newBlocks, "total", allBlocks.size()), "summary", summary,
                "generatedAt", saved.createdAt, "cached", "cached".equals(reason), "reason", reason);
    }
}
