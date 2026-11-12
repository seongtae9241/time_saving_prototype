package com.timesavings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.timesavings.config.ApiException;
import com.timesavings.domain.AppSetting;
import com.timesavings.domain.Enums.Level;
import com.timesavings.repo.AppSettingRepo;
import com.timesavings.repo.PostRepo;
import com.timesavings.repo.RequestRepo;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 기능 A-15: 카테고리 건의를 승인하면 공고 카테고리에 더한다.
 * 기본 6종(Rules.CATEGORIES)은 코드에 그대로 두고, 더한 카테고리(이름 → 등급)는 app_settings 의 categories.extra 에 JSON 으로 저장한다.
 * 서버가 뜰 때 읽어 {@link Rules} 에 올려 두므로 공고·요청의 카테고리 검사와 적립 가중치(등급)가 똑같이 적용된다.
 */
@Service
@Transactional
public class CategoryRegistry {
    public static final String KEY = "categories.extra";
    private final AppSettingRepo settings;
    private final PostRepo posts;
    private final RequestRepo requests;

    public CategoryRegistry(AppSettingRepo settings, PostRepo posts, RequestRepo requests) {
        this.settings = settings; this.posts = posts; this.requests = requests;
    }

    private Map<String, String> saved() {
        return settings.findById(KEY).map(s -> Views.parse(s.value, new TypeReference<LinkedHashMap<String, String>>() {}, new LinkedHashMap<String, String>()))
                .orElseGet(LinkedHashMap::new);
    }

    private void save(Map<String, String> m) {
        AppSetting s = settings.findById(KEY).orElseGet(() -> { AppSetting x = new AppSetting(); x.key = KEY; return x; });
        s.value = Views.json(m);
        s.updatedAt = LocalDateTime.now();
        settings.save(s);
    }

    /** DB 에 저장된 것을 Rules 에 다시 올린다(서버 시작 때). */
    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        Rules.clearExtraCategories();
        saved().forEach((name, lv) -> {
            try { Rules.putExtraCategory(name, Level.valueOf(lv)); } catch (IllegalArgumentException e) { Rules.putExtraCategory(name, Level.BASIC); }
        });
    }

    private static Map<String, Object> row(String name, Level lv, boolean extra) {
        return Views.map("name", name, "level", lv, "levelLabel", Rules.levelKo(lv), "weight", Rules.weight(lv), "extra", extra);
    }

    /** 기본 6종 + 더한 카테고리. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : Rules.CATEGORIES) out.add(row(c, Rules.categoryLevel(c), false));
        saved().forEach((name, lv) -> { if (Rules.CATEGORIES.contains(name)) return; Level l; try { l = Level.valueOf(lv); } catch (IllegalArgumentException e) { l = Level.BASIC; } out.add(row(name, l, true)); });
        return out;
    }

    public Map<String, Object> add(String rawName, Level level) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() < 2 || name.length() > 20) throw ApiException.bad("INVALID_NAME", "카테고리 이름은 2~20자로 적어 주세요");
        if (name.contains(",") || name.contains("|")) throw ApiException.bad("INVALID_NAME", "카테고리 이름에 쉼표나 | 는 쓸 수 없어요");
        if (Rules.CATEGORIES.contains(name)) throw ApiException.bad("CATEGORY_EXISTS", "이미 있는 종류예요");
        Level lv = level == null ? Level.BASIC : level;
        Map<String, String> m = saved();
        m.put(name, lv.name());
        save(m);
        Rules.putExtraCategory(name, lv);
        return row(name, lv, true);
    }

    /** 더한 카테고리 빼기. 그 카테고리로 올라온 공고·요청이 있으면 뺄 수 없다(등급 계산이 깨지지 않게). */
    public void remove(String name) {
        Map<String, String> m = saved();
        if (!m.containsKey(name)) throw ApiException.notFound("더한 카테고리가 아니에요");
        boolean used = posts.findAll().stream().anyMatch(p -> p.categories != null && p.categories.contains(name))
                || requests.findAll().stream().anyMatch(r -> name.equals(r.category));
        if (used) throw ApiException.conflict("CATEGORY_IN_USE", "이 종류로 올라온 공고·요청이 있어 뺄 수 없어요");
        m.remove(name);
        save(m);
        load();
    }
}
