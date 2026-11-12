package com.timesavings.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timesavings.ai.VoiceSafety;
import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.AppSetting;
import com.timesavings.domain.CategorySuggestion;
import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.User;
import com.timesavings.repo.AppSettingRepo;
import com.timesavings.repo.CategorySuggestionRepo;
import com.timesavings.repo.UserRepo;
import com.timesavings.service.NotificationService;
import com.timesavings.service.PlannerService;
import com.timesavings.service.Rules;
import com.timesavings.service.Views;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 2026-11-11 역할별 정리판(계약서 7.16):
 * 플래너(GET /api/me/planner, 계획 넣기·빼기), 생애 플랜(GET·POST /api/me/life-plan),
 * 카테고리 추가 건의(POST /api/category-suggestions, GET …/mine, 운영자 GET /api/admin/category-suggestions, POST …/{id}/answer),
 * 봉사 인증 항목 설정(GET /api/verify-items, PUT /api/admin/verify-items).
 */
@RestController
@RequestMapping("/api")
public class PlannerController {
    private static final ObjectMapper OM = new ObjectMapper();
    public static final List<String> SUG_STATUS = List.of("RECEIVED", "REVIEWING", "ADOPTED", "HELD");
    private static final Map<String, String> SUG_KO = Map.of("RECEIVED", "접수", "REVIEWING", "검토 중", "ADOPTED", "반영", "HELD", "보류");

    private final PlannerService planner;
    private final CategorySuggestionRepo suggestions;
    private final AppSettingRepo settings;
    private final UserRepo users;
    private final NotificationService noti;

    public PlannerController(PlannerService planner, CategorySuggestionRepo suggestions, AppSettingRepo settings, UserRepo users, NotificationService noti) {
        this.planner = planner; this.suggestions = suggestions; this.settings = settings; this.users = users; this.noti = noti;
    }

    public record PicksBody(List<Long> postIds) {}
    public record SuggestBody(String name, String reason) {}
    public record AnswerBody(String status, String answer) {}
    public record VerifyItem(String key, Boolean on, Boolean required) {}
    public record VerifyBody(List<VerifyItem> items) {}

    // ---------------- 플래너 ----------------
    @GetMapping("/me/planner")
    public Map<String, Object> planner(@CurrentUser User u, @RequestParam(required = false) String month) { return planner.planner(u, month); }

    /** 기능 U-26: 주 단위 캘린더. start 가 든 주(일~토)의 일정, mode(give|recv)를 주면 그 모드 일정만. */
    @GetMapping("/me/planner/week")
    public Map<String, Object> week(@CurrentUser User u, @RequestParam(required = false) String start, @RequestParam(required = false) String mode) {
        return planner.week(u, start, mode);
    }

    /** 41판 B-60: 홈 화면의 추천 봉사(내 활동 지역·가능 시간대·카테고리에 맞고 빈 시간에 들어가는 공고). AI 를 부르지 않는다. */
    @GetMapping("/me/recommends")
    public Map<String, Object> recommends(@CurrentUser User u) { return planner.recommends(u); }

    /** 41판 B-61 "계획 만들기": 고른 추천 봉사마다 AI 가 내 빈 시간에 맞춰 시작 시각을 정하고 서버가 겹침·범위를 검사해 계획에 넣는다. */
    @PostMapping("/me/planner/picks")
    public Map<String, Object> addPicks(@CurrentUser User u, @RequestBody PicksBody b) {
        Map<String, Object> made = planner.addPicks(u, b == null ? null : b.postIds());
        Map<String, Object> out = planner.planner(u, null);
        out.put("made", made.get("made"));
        out.put("skipped", made.get("skipped"));
        out.put("madeSource", made.get("source"));
        return out;
    }

    @DeleteMapping("/me/planner/picks/{postId}")
    public Map<String, Object> removePick(@CurrentUser User u, @PathVariable Long postId) {
        planner.removePick(u, postId);
        return Views.map("ok", true);
    }

    @GetMapping("/me/life-plan")
    public Map<String, Object> lifePlan(@CurrentUser User u) { return planner.lifePlan(u, null); }

    @PostMapping("/me/life-plan")
    public Map<String, Object> lifePlanCalc(@CurrentUser User u, @RequestBody PlannerService.LifeCmd b) { return planner.lifePlan(u, b); }

    // ---------------- 카테고리 추가 건의 ----------------
    public static String nameKey(String name) {
        return name == null ? "" : name.replaceAll("[\\s·ㆍ・.,/\\-]+", "").toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> view(CategorySuggestion s, boolean admin) {
        Map<String, Object> m = Views.map("id", s.id, "name", s.name, "reason", s.reason, "status", s.status, "statusLabel", SUG_KO.get(s.status),
                "answer", s.answer, "answeredAt", s.answeredAt == null ? null : s.answeredAt.toString(), "createdAt", s.createdAt.toString());
        if (admin) {
            User w = users.findById(s.userId).orElse(null);
            m.put("userId", s.userId);
            m.put("userName", w == null ? null : w.name);
            m.put("userRole", w == null ? null : w.role);
        }
        return m;
    }

    @PostMapping("/category-suggestions")
    @Transactional
    public Map<String, Object> suggest(@CurrentUser User u, @RequestBody SuggestBody b) {
        if (u.role == Role.ADMIN) throw ApiException.forbidden("사용자·담당자만 건의할 수 있어요");
        String name = b == null || b.name() == null ? "" : b.name().trim();
        String reason = b == null || b.reason() == null ? "" : b.reason().trim();
        if (name.length() < 2 || name.length() > 20) throw ApiException.bad("INVALID_NAME", "카테고리 이름은 2~20자로 적어 주세요");
        if (reason.length() < 5 || reason.length() > 300) throw ApiException.bad("INVALID_REASON", "필요한 이유를 5~300자로 적어 주세요");
        if (!VoiceSafety.scrub(name + " " + reason).removed().isEmpty())
            throw ApiException.bad("PII_NOT_ALLOWED", "연락처·주소 같은 개인정보는 적지 않아요");
        String key = nameKey(name);
        if (Rules.CATEGORIES.stream().anyMatch(c -> nameKey(c).equals(key)))
            throw ApiException.bad("CATEGORY_EXISTS", "이미 있는 종류예요. 그 종류를 골라 주세요");
        boolean dup = suggestions.findByUserIdOrderByIdDesc(u.id).stream()
                .anyMatch(s -> s.nameKey.equals(key) && (s.status.equals("RECEIVED") || s.status.equals("REVIEWING")));
        if (dup) throw ApiException.bad("ALREADY_SUGGESTED", "이미 건의한 종류예요. 답변을 기다려 주세요");
        CategorySuggestion s = new CategorySuggestion();
        s.userId = u.id; s.name = name; s.nameKey = key; s.reason = reason;
        suggestions.save(s);
        return view(s, false);
    }

    @GetMapping("/category-suggestions/mine")
    public List<Map<String, Object>> mine(@CurrentUser User u) {
        return suggestions.findByUserIdOrderByIdDesc(u.id).stream().map(s -> view(s, false)).toList();
    }

    /** 같은 이름(nameKey)끼리 묶어 건수와 함께. 건수 많은 순, 같으면 최근 순. /api/admin/** 는 ADMIN 만. */
    @GetMapping("/admin/category-suggestions")
    public Map<String, Object> adminList() {
        Map<String, List<CategorySuggestion>> by = new LinkedHashMap<>();
        suggestions.findAll().stream().sorted(Comparator.comparing((CategorySuggestion s) -> -s.id)).forEach(s -> by.computeIfAbsent(s.nameKey, k -> new ArrayList<>()).add(s));
        List<Map<String, Object>> groups = new ArrayList<>();
        int open = 0;
        for (var e : by.entrySet()) {
            List<CategorySuggestion> l = e.getValue();
            long waiting = l.stream().filter(s -> s.status.equals("RECEIVED") || s.status.equals("REVIEWING")).count();
            open += waiting;
            groups.add(Views.map("key", e.getKey(), "name", l.get(0).name, "count", l.size(), "waiting", waiting,
                    "status", l.get(0).status, "items", l.stream().map(s -> view(s, true)).toList()));
        }
        groups.sort(Comparator.<Map<String, Object>>comparingInt(g -> -(int) g.get("count")));
        return Views.map("groups", groups, "total", by.values().stream().mapToInt(List::size).sum(), "waiting", open,
                "note", "카테고리 6종 자체를 바꾸는 것은 가중치·금지 규칙이 걸려 있어 운영자 결정 사항이에요. 여기서는 건의에 답변만 해요.");
    }

    /** 건의 하나에 답하면 같은 이름의 건의 전체에 같은 상태·답변을 적용하고 건의자에게 알린다. */
    @PostMapping("/admin/category-suggestions/{id}/answer")
    @Transactional
    public Map<String, Object> answer(@CurrentUser User admin, @PathVariable Long id, @RequestBody AnswerBody b) {
        CategorySuggestion s = suggestions.findById(id).orElseThrow(() -> ApiException.notFound("건의를 찾을 수 없어요"));
        String st = b == null || b.status() == null ? "" : b.status();
        if (!SUG_STATUS.contains(st)) throw ApiException.bad("INVALID_STATUS", "상태는 접수·검토 중·반영·보류 중에서 골라 주세요");
        String ans = b.answer() == null ? "" : b.answer().trim();
        if (!st.equals("RECEIVED") && !st.equals("REVIEWING") && ans.isBlank()) throw ApiException.bad("ANSWER_REQUIRED", "건의자에게 보낼 답변을 적어 주세요");
        if (ans.length() > 500) throw ApiException.bad("ANSWER_TOO_LONG", "답변은 500자 안으로 적어 주세요");
        int n = 0;
        for (CategorySuggestion x : suggestions.findByNameKey(s.nameKey)) {
            x.status = st;
            if (!ans.isBlank()) { x.answer = ans; x.answeredBy = admin.id; x.answeredAt = LocalDateTime.now(); }
            suggestions.save(x);
            noti.send(x.userId, "CATEGORY_SUGGESTION", "카테고리 건의 '" + x.name + "' · " + SUG_KO.get(st),
                    ans.isBlank() ? "운영자가 건의를 " + SUG_KO.get(st) + " 상태로 바꿨어요." : ans, users.findById(x.userId).map(w -> w.role == Role.ORG ? "/o/my" : "/my/suggest").orElse("/my/suggest"));
            n++;
        }
        return Views.map("ok", true, "updated", n, "status", st);
    }

    // ---------------- 봉사 인증 항목 설정 ----------------
    /** 항목 목록·기본값·읽기는 VolunteerGate(봉사 자격 검사)에 있다. 기능 U-34: 첫 활동 교육(edu) 항목 포함. */
    @org.springframework.beans.factory.annotation.Autowired private com.timesavings.service.VolunteerGate gate;

    private List<Map<String, Object>> verifyItems() { return gate.items(); }

    @GetMapping("/verify-items")
    public Map<String, Object> verifyGet() {
        return Views.map("items", verifyItems(), "updatedAt", settings.findById("verify.items").map(s -> s.updatedAt.toString()).orElse(null));
    }

    @PutMapping("/admin/verify-items")
    @Transactional
    public Map<String, Object> verifyPut(@RequestBody VerifyBody b) {
        Map<String, Map<String, Boolean>> m = new LinkedHashMap<>();
        if (b != null && b.items() != null) for (VerifyItem i : b.items()) {
            if (!com.timesavings.service.VolunteerGate.knownKey(i.key())) throw ApiException.bad("INVALID_ITEM", "알 수 없는 인증 항목이에요");
            Map<String, Boolean> v = new LinkedHashMap<>();
            v.put("on", Boolean.TRUE.equals(i.on()));
            v.put("required", Boolean.TRUE.equals(i.on()) && Boolean.TRUE.equals(i.required()));
            m.put(i.key(), v);
        }
        AppSetting s = settings.findById("verify.items").orElseGet(() -> { AppSetting x = new AppSetting(); x.key = "verify.items"; return x; });
        s.value = Views.json(m);
        s.updatedAt = LocalDateTime.now();
        settings.save(s);
        return verifyGet();
    }
}
