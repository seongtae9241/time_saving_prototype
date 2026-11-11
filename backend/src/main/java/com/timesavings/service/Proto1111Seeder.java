package com.timesavings.service;

import com.timesavings.domain.CategorySuggestion;
import com.timesavings.domain.User;
import com.timesavings.repo.CategorySuggestionRepo;
import com.timesavings.repo.UserRepo;
import com.timesavings.web.PlannerController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 데모 데이터(2026-11-11 역할별 정리판): 카테고리 건의 몇 건. 데모 계정이 있고 건의가 하나도 없을 때만 넣는다. */
@Component
@Order(100)
public class Proto1111Seeder implements ApplicationRunner {
    private final UserRepo users;
    private final CategorySuggestionRepo suggestions;
    private final boolean enabled;

    public Proto1111Seeder(UserRepo users, CategorySuggestionRepo suggestions, @Value("${app.seed.enabled:true}") boolean enabled) {
        this.users = users; this.suggestions = suggestions; this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || suggestions.count() > 0) return;
        add("yujin", "반려동물 돌봄", "입원하실 때 강아지 산책을 부탁하고 싶다는 어르신이 계셨어요", 3);
        add("mikyung", "반려동물돌봄", "고양이 밥 주기를 부탁받았는데 맞는 종류가 없었어요", 2);
        add("org1", "반려동물 돌봄", "대상 어르신 두 분이 병원 가는 날 반려견을 봐 달라고 하세요", 1);
        add("org1", "서류·민원 도움", "주민센터 서류 떼는 것을 같이 해 달라는 요청이 자주 와요", 4);
        add("yujin", "악기·취미 가르치기", "기타를 가르쳐 드리고 싶은데 올릴 종류가 없어요", 6);
    }

    private void add(String loginId, String name, String reason, int daysAgo) {
        User u = users.findAll().stream().filter(x -> loginId.equals(x.loginId)).findFirst().orElse(null);
        if (u == null) return;
        CategorySuggestion s = new CategorySuggestion();
        s.userId = u.id; s.name = name; s.nameKey = PlannerController.nameKey(name); s.reason = reason;
        s.createdAt = LocalDateTime.now().minusDays(daysAgo);
        suggestions.save(s);
    }
}
