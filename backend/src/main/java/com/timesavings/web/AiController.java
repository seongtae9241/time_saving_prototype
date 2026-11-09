package com.timesavings.web;

import com.timesavings.ai.AiClient;
import com.timesavings.ai.PostChatRule;
import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.Elder;
import com.timesavings.domain.Enums.PostType;
import com.timesavings.domain.User;
import com.timesavings.repo.ElderRepo;
import com.timesavings.service.EduService;
import com.timesavings.service.PostService;
import com.timesavings.service.Views;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/** AI 기능(계약서 5.3). 실제 호출은 모두 AiClient 가 한다. */
@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiClient ai;
    private final ElderRepo elders;
    private final PostService posts;
    private final EduService edu;

    public AiController(AiClient ai, ElderRepo elders, PostService posts, EduService edu) {
        this.ai = ai; this.elders = elders; this.posts = posts; this.edu = edu;
    }

    public record ChatMsg(String role, String text) {}
    public record PostChatBody(String type, List<ChatMsg> messages, Long forElderId, String inputMode) {}
    public record TextBody(String text) {}
    public record DiffBody(String text, List<String> categories, Double hours) {}
    public record RawBody(String raw) {}
    public record ServiceBody(String question, String area, String ageGroup) {}
    public record QuestionBody(String question) {}
    public record RoleplayBody(Long personaId, List<Map<String, Object>> history, String text) {}

    @GetMapping("/status")
    public Map<String, Object> status() { return Views.map("mode", ai.enabled() ? "ai" : "rule"); }

    @PostMapping("/post-chat")
    public Map<String, Object> postChat(@CurrentUser User u, @RequestBody PostChatBody b) {
        if (b.messages() == null || b.messages().isEmpty()) throw ApiException.bad("MESSAGES_REQUIRED", "하고 싶은 말을 입력해 주세요");
        String area = u.area;
        if (b.forElderId() != null) {
            Elder e = elders.findById(b.forElderId()).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
            if (!u.id.equals(e.managerId)) throw ApiException.forbidden("내가 담당하는 대상만 대신 작성할 수 있어요");
            area = e.dong;
        }
        // 최근 몇 턴만 보낸다(비용·개인정보 최소)
        List<ChatMsg> msgs = b.messages().size() > 12 ? b.messages().subList(b.messages().size() - 12, b.messages().size()) : b.messages();
        List<PostChatRule.Msg> list = msgs.stream().map(m -> new PostChatRule.Msg(m.role() == null ? "user" : m.role(), m.text())).toList();
        String type = "OFFER".equalsIgnoreCase(b.type()) ? "OFFER" : "NEED";
        return ai.postChat(type, list, LocalDate.now(), area, "voice".equalsIgnoreCase(b.inputMode()));
    }

    @PostMapping("/filter")
    public Map<String, Object> filter(@RequestBody TextBody b) {
        if (b.text() == null || b.text().isBlank()) throw ApiException.bad("TEXT_REQUIRED", "문장을 입력해 주세요");
        return ai.filter(b.text());
    }

    @PostMapping("/difficulty")
    public Map<String, Object> difficulty(@RequestBody DiffBody b) {
        return ai.difficulty(b.text() == null ? "" : b.text(), b.categories(), b.hours());
    }

    @PostMapping("/log-tidy")
    public Map<String, Object> logTidy(@RequestBody RawBody b) {
        if (b.raw() == null || b.raw().isBlank()) throw ApiException.bad("LOG_REQUIRED", "한 줄 기록을 입력해 주세요");
        return ai.logTidy(b.raw());
    }

    @PostMapping("/public-services")
    public Map<String, Object> publicServices(@CurrentUser User u, @RequestBody ServiceBody b) {
        if (b.question() == null || b.question().isBlank()) throw ApiException.bad("QUESTION_REQUIRED", "궁금한 것을 적어 주세요");
        return ai.publicServices(b.question(), b.area() != null ? b.area() : u.area, b.ageGroup() != null ? b.ageGroup() : u.ageGroup);
    }

    @PostMapping("/recommend")
    public Map<String, Object> recommend(@CurrentUser User u, @RequestBody QuestionBody b) {
        Map<String, Object> r = ai.recommend(b.question());
        @SuppressWarnings("unchecked") List<String> cats = (List<String>) r.get("categories");
        boolean helper = Boolean.TRUE.equals(r.get("helper"));
        List<Map<String, Object>> list = posts.list(u, helper ? PostType.NEED : PostType.OFFER, null, null, null, "near").stream()
                .filter(p -> !Boolean.TRUE.equals(p.get("mine")))
                .filter(p -> { @SuppressWarnings("unchecked") List<String> pc = (List<String>) p.get("categories"); return cats.isEmpty() || pc.stream().anyMatch(cats::contains); })
                .limit(5).toList();
        r.put("posts", list);
        return r;
    }

    @PostMapping("/roleplay")
    public Map<String, Object> roleplay(@RequestBody RoleplayBody b) {
        if (b.personaId() == null) throw ApiException.bad("PERSONA_REQUIRED", "가상 상대를 골라 주세요");
        return edu.roleplayTurn(b.personaId(), b.history(), b.text());
    }
}
