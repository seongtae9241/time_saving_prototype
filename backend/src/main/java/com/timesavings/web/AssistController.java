package com.timesavings.web;

import com.timesavings.ai.EasyTextAi;
import com.timesavings.ai.PostCheckAi;
import com.timesavings.ai.VoiceTextAi;
import com.timesavings.config.ApiException;
import com.timesavings.config.CurrentUser;
import com.timesavings.domain.*;
import com.timesavings.domain.Enums.*;
import com.timesavings.repo.*;
import com.timesavings.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * LLM 추가 A(계약서 7.12): 요청서 빠진 정보 점검, 쉬운 말 공고 읽기, 안 맞춰지는 공고 고치기 제안 조회, 음성 비서, 음성 전화 알림 미리 듣기.
 * 화면을 열 때마다 AI 를 부르지 않는다: 쉬운 말·고치기 제안·전화 문장은 한 번 만들어 DB 에 저장해 다시 쓴다.
 */
@RestController
@RequestMapping("/api")
@Transactional
public class AssistController {
    private final PostCheckAi postCheck;
    private final EasyTextAi easy;
    private final AssistantService assistant;
    private final UnmatchedPostService unmatched;
    private final PostService posts;
    private final PostRepo postRepo;
    private final ElderRepo elders;
    private final NotificationRepo notis;
    private final NotificationService noti;
    private final Views views;

    public AssistController(PostCheckAi postCheck, EasyTextAi easy, AssistantService assistant, UnmatchedPostService unmatched, PostService posts,
                            PostRepo postRepo, ElderRepo elders, NotificationRepo notis, NotificationService noti, Views views) {
        this.postCheck = postCheck; this.easy = easy; this.assistant = assistant; this.unmatched = unmatched; this.posts = posts;
        this.postRepo = postRepo; this.elders = elders; this.notis = notis; this.noti = noti; this.views = views;
    }

    public record Draft(List<String> categories, String title, String detail, String caution, String note) {}
    public record CheckBody(Draft draft, Long elderId) {}
    public record AskBody(String text) {}

    /** 1. 요청서 빠진 정보 점검(올리기 직전). 질문은 최대 2개, 건너뛰기 가능. */
    @PostMapping("/ai/post-check")
    public Map<String, Object> postCheck(@CurrentUser User u, @RequestBody CheckBody b) {
        if (b == null || b.draft() == null) throw ApiException.bad("DRAFT_REQUIRED", "점검할 요청서가 없어요");
        Draft d = b.draft();
        List<String> cats = d.categories() == null ? List.of() : d.categories().stream().filter(Rules::isCategory).distinct().toList();
        String elderCaution = null;
        if (b.elderId() != null) {
            Elder e = elders.findById(b.elderId()).orElseThrow(() -> ApiException.notFound("도움 대상을 찾을 수 없어요"));
            if (!u.id.equals(e.managerId)) throw ApiException.forbidden("내가 담당하는 대상만 점검할 수 있어요");
            elderCaution = e.caution;
        }
        return postCheck.check(cats, clip(d.title(), 200), clip(d.detail(), 1000), clip(d.caution(), 300), clip(d.note(), 500), elderCaution);
    }

    /** 4. 쉬운 말로 듣기: 공고별 1회 생성 후 저장(공고를 고치면 다시 만든다). */
    @GetMapping("/posts/{id}/easy")
    public Map<String, Object> easy(@CurrentUser User u, @PathVariable Long id) {
        Post p = posts.get(id);
        if (p.status == PostStatus.DELETED) throw ApiException.notFound("삭제된 공고예요");
        boolean cached = p.easyText != null && !p.easyText.isBlank();
        String source = "cache";
        if (!cached) {
            Map<String, Object> card = views.postCard(p, null); // 보는 사람과 상관없이 가린 이름(독거노인)으로 만든다
            String who = card.get("ownerName") == null ? null : String.valueOf(card.get("ownerName"));
            String base = EasyTextAi.base(p.type == PostType.OFFER, who, p.date, p.startHour, p.endHour, p.categories, p.estHours, p.type == PostType.OFFER ? p.area : null);
            EasyTextAi.Easy e = easy.make(base);
            p.easyText = e.text().length() > 300 ? e.text().substring(0, 300) : e.text();
            source = e.source();
            postRepo.save(p);
        }
        return Views.map("postId", p.id, "text", p.easyText, "cached", cached, "source", source, "lang", "ko-KR", "rate", 0.9);
    }

    /** 3. 안 맞춰지는 공고 고치기 제안(공고 주인·담당자만). 없으면 tips 빈 목록. */
    @GetMapping("/posts/{id}/fix-tips")
    public Map<String, Object> fixTips(@CurrentUser User u, @PathVariable Long id) {
        Post p = posts.get(id);
        if (!posts.canEdit(p, u)) throw ApiException.forbidden("내 공고만 볼 수 있어요");
        Map<String, Object> t = unmatched.tipsOf(p);
        return t != null ? t : Views.map("tips", List.of(), "source", "rule");
    }

    /** 5. 음성 비서: 의도만 분류하고 답은 코드가 내 데이터로. 실행은 하지 않는다(executed:false). */
    @PostMapping("/ai/assistant")
    public Map<String, Object> assistant(@CurrentUser User u, @RequestBody AskBody b) {
        if (b == null || b.text() == null || b.text().isBlank()) throw ApiException.bad("TEXT_REQUIRED", "물어볼 말을 해 주세요");
        String t = b.text().trim();
        return assistant.answer(u, t.length() > 300 ? t.substring(0, 300) : t, LocalDate.now());
    }

    /** 6. 음성 전화 알림 미리 듣기: 최근 알림의 전화 문장(없으면 그때 한 번 만들어 그 알림에 저장). */
    @GetMapping("/me/voice-preview")
    public Map<String, Object> voicePreview(@CurrentUser User u) {
        List<Notification> list = notis.findByUserIdOrderByCreatedAtDescIdDesc(u.id);
        if (list.isEmpty()) {
            String sample = VoiceTextAi.rule("김하늘 님이 병원 동행 요청을 수락했어요", "내일 오후 2시 · 앱에서 최종 수락을 눌러 주세요");
            return Views.map("text", sample, "sample", true, "notificationId", null, "voiceAlert", u.voiceAlert);
        }
        Notification n = list.get(0);
        if (n.voiceText == null || n.voiceText.isBlank()) {
            n.voiceText = noti.voiceText(n.title, n.body);
            notis.save(n);
        }
        return Views.map("text", n.voiceText, "sample", false, "notificationId", n.id, "title", n.title, "voiceAlert", u.voiceAlert);
    }

    private static String clip(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }
}
