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

    public Notification send(Long userId, String type, String title, String body, String link) {
        if (userId == null) return null;
        Notification n = new Notification();
        n.userId = userId;
        n.type = type;
        n.title = title;
        n.body = body;
        n.link = link;
        User target = users.findById(userId).orElse(null);
        if (target != null && target.voiceAlert) n.voiceText = voiceText(title, body);
        repo.save(n);
        if (target != null && target.voiceAlert)
            log.info("[VOICE-MOCK] 음성 안내 발송(모의) userId={} type={} text=\"{}\"", target.id, type, n.voiceText);
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
