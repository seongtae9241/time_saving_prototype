package com.timesavings;

import com.timesavings.domain.Enums.*;
import com.timesavings.domain.GroupPost;
import com.timesavings.domain.Organization;
import com.timesavings.domain.User;
import com.timesavings.repo.*;
import com.timesavings.service.AuthService;
import com.timesavings.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 단체 봉사 정원 경쟁(기능 U-10): 마지막 한 자리에 여러 사람이 같은 순간에 신청해도 한 사람만 들어간다.
 * 실제로 여러 트랜잭션이 겹쳐야 하므로 이 테스트는 @Transactional 을 쓰지 않고, 만든 자료는 끝나고 지운다.
 */
@SpringBootTest
class GroupJoinLockTest {
    @Autowired UserRepo users;
    @Autowired OrganizationRepo orgs;
    @Autowired GroupPostRepo posts;
    @Autowired GroupParticipantRepo parts;
    @Autowired NotificationRepo notis;
    @Autowired AuthService auth;
    @Autowired GroupService svc;

    final List<User> made = new ArrayList<>();
    Organization org;
    GroupPost post;

    User user(String id, Role role, Long orgId) {
        User u = new User();
        u.loginId = "lock" + id; u.name = id; u.passwordHash = auth.hash("1234"); u.role = role; u.area = "행복동"; u.ageGroup = "30대";
        u.idStatus = VerifyStatus.APPROVED; u.eduDone = true; u.orgId = orgId; u.phone = "010-9999-" + (1000 + made.size());
        made.add(users.save(u));
        return u;
    }

    @AfterEach
    void cleanUp() {
        if (post != null) {
            parts.deleteAll(parts.findByGroupPostId(post.id));
            posts.deleteById(post.id);
        }
        for (User u : made) {
            notis.deleteAll(notis.findAll().stream().filter(n -> u.id.equals(n.userId)).toList());
            users.deleteById(u.id);
        }
        if (org != null) orgs.deleteById(org.id);
    }

    @Test
    void 마지막_한_자리에_동시에_신청해도_정원을_넘지_않는다() throws Exception {
        org = new Organization();
        org.name = "잠금동 주민센터"; org.status = OrgStatus.APPROVED;
        orgs.save(org);
        User mgr = user("mgr", Role.ORG, org.id);
        post = svc.create(mgr, new GroupService.Cmd(GroupService.RECRUIT, LocalDate.now().plusDays(2), 10.0, 2.0, "행복동", null, "가벼운 집안일",
                "경로당 청소", null, 2, null));
        svc.join(user("first", Role.USER, null), post.id); // 한 자리 남음
        int n = 6;
        List<User> rivals = new ArrayList<>();
        for (int i = 0; i < n; i++) rivals.add(user("r" + i, Role.USER, null));
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n), go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (User u : rivals) results.add(pool.submit(() -> {
            ready.countDown();
            go.await();
            try { svc.join(u, post.id); return true; } catch (RuntimeException e) { return false; }
        }));
        ready.await();
        go.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdown();
        assertEquals(1, ok, "마지막 한 자리에는 한 사람만 들어가야 해요");
        long joined = parts.findByGroupPostId(post.id).stream().filter(p -> "JOINED".equals(p.status)).count();
        assertEquals(2, joined, "참여 인원이 정원(2명)을 넘으면 안 돼요");
    }
}
