package com.timesavings.service;

import com.timesavings.domain.Enums.LedgerType;
import com.timesavings.domain.GroupParticipant;
import com.timesavings.domain.GroupPost;
import com.timesavings.domain.Ledger;
import com.timesavings.domain.User;
import com.timesavings.repo.GroupParticipantRepo;
import com.timesavings.repo.GroupPostRepo;
import com.timesavings.repo.LedgerRepo;
import com.timesavings.repo.UserRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 데모 데이터(기능 U-10·U-11·O-07 단체 봉사). 데모 계정이 있고 단체 공고가 하나도 없을 때만 넣는다. */
@Component
@Order(110)
public class GroupSeeder implements ApplicationRunner {
    private final UserRepo users;
    private final GroupPostRepo posts;
    private final GroupParticipantRepo parts;
    private final LedgerRepo ledger;
    private final boolean enabled;

    public GroupSeeder(UserRepo users, GroupPostRepo posts, GroupParticipantRepo parts, LedgerRepo ledger, @Value("${app.seed.enabled:true}") boolean enabled) {
        this.users = users; this.posts = posts; this.parts = parts; this.ledger = ledger; this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || posts.count() > 0) return;
        User org1 = u("org1"), sky = u("sky");
        if (org1 == null || sky == null || org1.orgId == null) return;
        LocalDate today = LocalDate.now();
        // 모집형(기관): 오늘 활동 → 담당자가 참석 확인을 눌러 볼 수 있다
        GroupPost phone = post(GroupService.RECRUIT, org1, null, today, 14, 2, "행복동", "행복동 주민센터 2층 교육실", "디지털 도움",
                "어르신 스마트폰 교실 도우미", "어르신 열 분께 문자 보내기와 사진 찍기를 옆에서 알려 드려요. 한 분씩 천천히 도와주세요.", 4);
        join(phone, "yujin", "JOINED", null); join(phone, "doyun", "JOINED", null); join(phone, "mom", "JOINED", null);
        // 모집형(기관): 며칠 뒤, 자리 남음
        GroupPost clean = post(GroupService.RECRUIT, org1, null, today.plusDays(4), 10, 2, "행복동", "행복동 경로당", "가벼운 집안일",
                "경로당 창문 닦기와 바닥 청소", "경로당 겨울맞이 청소예요. 걸레와 장갑은 주민센터에서 준비해요.", 6);
        join(clean, "sky", "JOINED", null); join(clean, "mikyung", "JOINED", null);
        // 제공형(사용자 단체): 받을 기관을 기다리는 중
        GroupPost club = post(GroupService.OFFER, sky, "행복대 간호학과 봉사 동아리", today.plusDays(6), 15, 2, "행복동", null, "말동무·정서",
                "어르신 말벗·안부 나누기 함께 할게요", "동아리 친구들과 함께 가요. 경로당이나 복지관에서 말벗이 필요하시면 불러 주세요.", 5);
        join(club, "sky", "JOINED", null); join(club, "doyun", "JOINED", null);
        // 제공형(기관 봉사단)
        post(GroupService.OFFER, org1, "행복동 주민센터 청소년 봉사단", today.plusDays(9), 10, 3, "희망동", null, "생활 정리",
                "재활용 분리와 창고 정리 도와드려요", "주민센터에서 모집한 청소년 봉사단이에요. 인솔 담당자가 함께 가요.", 8);
        // 지난 단체 봉사(완료): 참석한 사람마다 적립된 모습
        GroupPost done = post(GroupService.RECRUIT, org1, null, today.minusDays(10), 10, 2, "행복동", "행복동 주민센터 앞마당", "장보기·심부름",
                "김장 재료 나르기와 나눔 상자 포장", "나눔 상자를 포장하고 주민센터 안으로 옮겼어요.", 5);
        done.status = "DONE"; done.doneBy = org1.id; done.doneAt = today.minusDays(10).atTime(12, 10);
        for (String id : new String[]{"sky", "mikyung"}) {
            User v = u(id);
            if (v == null) continue;
            join(done, id, "ATTENDED", 2.0);
            Ledger l = new Ledger();
            l.userId = v.id; l.type = LedgerType.EARN; l.amount = 2.0; l.baseHours = 2.0; l.weight = GroupService.WEIGHT;
            l.label = "단체 봉사 · " + done.title + " · 행복동 주민센터"; l.createdAt = done.doneAt;
            ledger.save(l);
        }
        join(done, "doyun", "ABSENT", null);
    }

    private User u(String loginId) { return users.findByLoginId(loginId).orElse(null); }

    private GroupPost post(String kind, User owner, String groupName, LocalDate date, double start, double hours, String area, String place,
                           String category, String title, String detail, int capacity) {
        GroupPost g = new GroupPost();
        g.kind = kind; g.ownerId = owner.id; g.orgId = owner.orgId; g.groupName = groupName; g.date = date; g.startHour = start; g.hours = hours;
        g.area = area; g.place = place; g.category = category; g.title = title; g.detail = detail; g.capacity = capacity;
        g.createdAt = LocalDateTime.now().minusDays(1);
        return posts.save(g);
    }

    private void join(GroupPost g, String loginId, String status, Double earned) {
        User v = u(loginId);
        if (v == null) return;
        GroupParticipant p = new GroupParticipant();
        p.groupPostId = g.id; p.userId = v.id; p.status = status; p.earned = earned;
        parts.save(p);
    }
}
