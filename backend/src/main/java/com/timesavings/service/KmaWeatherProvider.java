package com.timesavings.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 기상청 API허브 "특보 현황" 호출(기능 A-08). 환경변수 KMA_API_KEY 가 있을 때만 부른다. 키는 로그·응답에 남기지 않는다.
 * 주의: 이 호출부는 키 없이 검증하지 못했다(응답 형식은 줄마다 쉼표로 나뉜 글이라고 보고, 특보 종류·수준 낱말과 구역 이름이 든 줄만 읽는다).
 * 서비스의 동네(행복동 등)는 모두 KMA_REGION(기본 "서울") 구역에 있다고 본다.
 */
@Component
public class KmaWeatherProvider implements WeatherProvider {
    private static final Logger log = LoggerFactory.getLogger(KmaWeatherProvider.class);
    private final String key;
    private final String region;
    private final String url;

    public KmaWeatherProvider(@Value("${KMA_API_KEY:}") String key, @Value("${KMA_REGION:서울}") String region,
                              @Value("${KMA_API_URL:https://apihub.kma.go.kr/api/typ01/url/wrn_now_data.php}") String url) {
        this.key = key == null ? "" : key.trim();
        this.region = region;
        this.url = url;
    }

    @Override public boolean live() { return !key.isEmpty(); }

    public String region() { return region; }

    /** 응답 글에서 우리 구역의 특보만 뽑는다(순수 함수라 키 없이도 테스트한다). */
    public static List<Alert> parse(String body, String region) {
        LinkedHashSet<Alert> out = new LinkedHashSet<>();
        if (body == null) return new ArrayList<>(out);
        for (String line : body.split("\\r?\\n")) {
            if (line.startsWith("#") || !line.contains(region)) continue;
            String type = TYPES.stream().filter(line::contains).findFirst().orElse(null);
            String level = line.contains("경보") ? "경보" : line.contains("주의") ? "주의보" : null;
            if (type != null && level != null) out.add(new Alert(type, level, region));
        }
        return new ArrayList<>(out);
    }

    @Override
    public List<Alert> current() {
        if (!live()) return List.of();
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "?fe=f&tm=&disp=0&help=0&authKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) { log.warn("기상 특보 호출 실패(상태 {})", res.statusCode()); return List.of(); }
            String text = new String(res.body(), StandardCharsets.UTF_8);
            if (!text.contains("경보") && !text.contains("주의")) text = new String(res.body(), java.nio.charset.Charset.forName("EUC-KR"));
            return parse(text, region);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("기상 특보 호출 실패({})", e.getClass().getSimpleName());
            return List.of();
        }
    }
}
