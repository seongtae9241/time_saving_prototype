package com.timesavings.service;

import com.timesavings.domain.Elder;
import com.timesavings.domain.Notification;
import com.timesavings.domain.Request;
import com.timesavings.domain.User;
import com.timesavings.repo.ElderRepo;
import com.timesavings.repo.NotificationRepo;
import com.timesavings.repo.UserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 알림(A-07). 독거노인 건은 담당자에게 간다(C-08). 음성 알림(B-42)은 모의로 로그만 남긴다. */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final NotificationRepo repo;
    private final UserRepo users;
    private final ElderRepo elders;
    /** LLM 추가 A(7.12, B-42): 전화로 읽을 문장(생성자를 바꾸지 않으려고 필드 주입, 테스트에서 없으면 규칙 문장) */
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.timesavings.ai.VoiceTextAi voiceAi;

    public NotificationService(NotificationRepo repo, UserRepo users, ElderRepo elders) {
        this.repo = repo;
        this.users = users;
        this.elders = elders;
    }

    /** 41판 B-58: 알림 종류 → 설정 칸. 여기에 없는 종류(일정 확정·완료 확인·적립·지각 등)는 끌 수 없다. */
    private static final java.util.Map<String, String> CATEGORY = java.util.Map.ofEntries(
            java.util.Map.entry("NEW_REQUEST", "req"), java.util.Map.entry("NEW_PROPOSAL", "req"), java.util.Map.entry("NEW_REGULAR", "req"),
            java.util.Map.entry("MESSAGE", "msg"),
            java.util.Map.entry("VOL_ACCEPTED", "result"), java.util.Map.entry("REJECTED", "result"), java.util.Map.entry("DECLINED", "result"),
            java.util.Map.entry("AUTO_REJECTED", "result"), java.util.Map.entry("MANAGER_REJECTED", "result"),
            java.util.Map.entry("REMIND", "remind"),
            java.util.Map.entry("INTEREST", "interest"));

    /** 알림 종류의 설정 칸 이름(req·msg·result·remind·interest). 끌 수 없는 종류는 null. */
    public static String categoryOf(String type) { return type == null ? null : CATEGORY.get(type); }

    private static boolean on(Boolean v) { return v == null || v; }

    /** 이 사용자가 그 종류의 알림을 받는지(설정을 한 번도 안 바꿨으면 모두 받음). */
    public static boolean wants(User u, String type) {
        String c = categoryOf(type);
        if (u == null || c == null) return true;
        return switch (c) {
            case "req" -> on(u.notiReq);
            case "msg" -> on(u.notiMsg);
            case "result" -> on(u.notiResult);
            case "remind" -> on(u.notiRemind);
            default -> on(u.notiInterest);
        };
    }

    /** GET /api/me/noti-settings 모양. */
    public static java.util.Map<String, Object> settings(User u) {
        return Views.map("req", on(u.notiReq), "msg", on(u.notiMsg), "result", on(u.notiResult), "remind", on(u.notiRemind),
                "interest", on(u.notiInterest), "voiceAlert", u.voiceAlert, "smsAlert", Boolean.TRUE.equals(u.smsAlert));
    }

    public Notification send(Long userId, String type, String title, String body, String link) {
        if (userId == null) return null;
        User target = users.findById(userId).orElse(null);
        if (!wants(target, type)) return null; // B-58: 꺼 둔 종류는 알림을 만들지 않는다
        Notification n = new Notification();
        n.userId = userId;
        n.type = type;
        n.title = title;
        n.body = body;
        n.link = link;
        if (target != null && target.voiceAlert) n.voiceText = voiceText(title, body);
        repo.save(n);
        if (target != null && target.voiceAlert)
            log.info("[VOICE-MOCK] 음성 안내 발송(모의) userId={} type={} text=\"{}\"", target.id, type, n.voiceText);
        // B-43: 문자 병행(실제 발송 업체가 없어 모의로 기록만 남긴다. 내용은 로그에 남기지 않는다)
        if (target != null && Boolean.TRUE.equals(target.smsAlert))
            log.info("[SMS-MOCK] 문자 병행 발송(모의) userId={} type={}", target.id, type);
        return n;
    }

    /** 전화로 읽을 문장(LLM 다듬기 → 숫자는 코드가 한글 읽기). */
    public String voiceText(String title, String body) {
        return voiceAi != null ? voiceAi.make(title, body).text() : com.timesavings.ai.VoiceTextAi.rule(title, body);
    }

    /** 받는 쪽: 일반 사용자면 그 사람, 독거노인이면 담당자. */
    public Long recipientSideUser(Request r) {
        if (r.elderId != null) return elders.findById(r.elderId).map(e -> e.managerId).orElse(null);
        return r.recipientId;
    }

    public void toRecipientSide(Request r, String type, String title, String body) {
        send(recipientSideUser(r), type, title, body, "/requests/" + r.id);
    }

    public void toVolunteer(Request r, String type, String title, String body) {
        send(r.volunteerId, type, title, body, "/requests/" + r.id);
    }

    public String recipientLabel(Request r, boolean forManager) {
        if (r.elderId != null) {
            Elder e = elders.findById(r.elderId).orElse(null);
            if (e == null) return "도움 대상";
            return forManager ? e.alias + " 님" : Rules.maskAlias(e.alias);
        }
        return users.findById(r.recipientId).map(u -> u.name + " 님").orElse("상대");
    }

    public String name(Long userId) { return users.findById(userId).map(u -> u.name).orElse("상대"); }

    public User user(Long id) { return users.findById(id).orElse(null); }
}
