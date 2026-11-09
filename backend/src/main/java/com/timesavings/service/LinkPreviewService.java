package com.timesavings.service;

import com.timesavings.config.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 동네 소식 URL 미리보기: 그 페이지의 og:title / og:image / og:description / &lt;title&gt; 을 읽는다.
 * SSRF 방지: http/https 만, 80·443 포트만, 사설·루프백·링크로컬·멀티캐스트·CGNAT·IPv6 ULA 주소 차단(호스트 이름은 모든 주소를 확인),
 * 리다이렉트는 직접 따라가며 매번 다시 검사(최대 3회), 연결·응답 3초, 본문 512KB 까지만 읽는다. HTML이 아니면 읽지 않는다.
 * 남은 위험: 검사와 실제 연결 사이에 DNS 응답이 바뀌는 경우(DNS 리바인딩)는 완전히 막지 못한다 → 운영 시 외부 요청을 프록시로 제한 권장.
 */
@Service
public class LinkPreviewService {
    private static final Logger log = LoggerFactory.getLogger(LinkPreviewService.class);
    public static final int MAX_BYTES = 512 * 1024;
    public static final int MAX_REDIRECTS = 3;
    public static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .proxy(ProxySelector.of(null)) // 시스템 프록시를 쓰지 않는다(검사한 주소로 바로 연결)
            .build();

    public record Preview(String url, String title, String image, String description, String siteName) {}

    /** 미리보기를 가져온다. 주소가 막히면 400 URL_NOT_ALLOWED, 읽지 못하면 400 PREVIEW_FAILED. */
    public Preview fetch(String raw) {
        URI uri = check(raw);
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET()
                        .header("User-Agent", "TimeSavingsLinkPreview/1.0")
                        .header("Accept", "text/html,application/xhtml+xml").build();
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int sc = res.statusCode();
                if (sc >= 300 && sc < 400) {
                    try (InputStream ignored = res.body()) { /* 닫기 */ }
                    String loc = res.headers().firstValue("location").orElse(null);
                    if (loc == null || hop == MAX_REDIRECTS) throw ApiException.bad("PREVIEW_FAILED", "링크가 너무 여러 번 다른 곳으로 넘어가요");
                    uri = check(uri.resolve(loc.trim()).toString());
                    continue;
                }
                try (InputStream in = res.body()) {
                    if (sc != 200) throw ApiException.bad("PREVIEW_FAILED", "페이지를 열 수 없어요 (" + sc + ")");
                    String ct = res.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
                    if (!ct.isEmpty() && !ct.contains("html")) throw ApiException.bad("PREVIEW_FAILED", "웹 페이지 주소가 아니에요");
                    byte[] body = readLimited(in, MAX_BYTES);
                    Charset cs = charsetOf(ct);
                    return parse(new String(body, cs), uri);
                }
            }
            throw ApiException.bad("PREVIEW_FAILED", "페이지를 열 수 없어요");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.info("링크 미리보기 실패({})", e.getClass().getSimpleName());
            throw ApiException.bad("PREVIEW_FAILED", "페이지를 읽지 못했어요. 주소를 확인하거나 미리보기 없이 올려 주세요");
        }
    }

    static byte[] readLimited(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            int room = max - out.size();
            if (room <= 0) break;
            out.write(buf, 0, Math.min(n, room));
        }
        return out.toByteArray();
    }

    private static Charset charsetOf(String ct) {
        Matcher m = Pattern.compile("charset=([\\w-]+)").matcher(ct);
        if (m.find()) { try { return Charset.forName(m.group(1)); } catch (Exception ignored) { /* 기본값 */ } }
        return StandardCharsets.UTF_8;
    }

    /** 주소 검사(SSRF, 연결 직전). 이름 풀이(DNS)까지 해서 모든 주소를 확인한다. 통과하면 URI, 아니면 400 URL_NOT_ALLOWED. */
    public static URI check(String raw) { return check(raw, true); }

    /** 저장할 때 검사: 형식·스킴·포트·내부 이름·IP 직접 입력만 본다(DNS를 못 풀어도 저장은 된다. 실제로 가져올 때 check 로 다시 검사). */
    public static URI checkStatic(String raw) { return check(raw, false); }

    private static URI check(String raw, boolean resolve) {
        if (raw == null || raw.isBlank()) throw ApiException.bad("URL_REQUIRED", "링크 주소를 넣어 주세요");
        if (raw.length() > 500) throw ApiException.bad("URL_NOT_ALLOWED", "주소가 너무 길어요");
        URI u;
        try { u = new URI(raw.trim()); } catch (URISyntaxException e) { throw ApiException.bad("URL_NOT_ALLOWED", "주소 형식이 맞지 않아요"); }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw ApiException.bad("URL_NOT_ALLOWED", "http 또는 https 주소만 넣을 수 있어요");
        if (u.getRawUserInfo() != null) throw ApiException.bad("URL_NOT_ALLOWED", "주소에 아이디·비밀번호를 넣을 수 없어요");
        String host = u.getHost();
        if (host == null || host.isBlank()) throw ApiException.bad("URL_NOT_ALLOWED", "주소 형식이 맞지 않아요");
        int port = u.getPort();
        if (port != -1 && port != 80 && port != 443) throw ApiException.bad("URL_NOT_ALLOWED", "80·443 포트 주소만 넣을 수 있어요");
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        String hl = h.toLowerCase(Locale.ROOT);
        if (hl.equals("localhost") || hl.endsWith(".localhost") || hl.endsWith(".local") || hl.endsWith(".internal") || hl.equals("metadata.google.internal"))
            throw ApiException.bad("URL_NOT_ALLOWED", "내부 주소는 넣을 수 없어요");
        boolean literal = h.contains(":") || h.matches("^[0-9.]+$") || h.matches("^0x[0-9a-fA-F.x]+$");
        if (!resolve && !literal) return u;
        InetAddress[] addrs;
        try { addrs = InetAddress.getAllByName(h); } catch (UnknownHostException e) { throw ApiException.bad("URL_NOT_ALLOWED", "주소를 찾을 수 없어요"); }
        if (addrs.length == 0) throw ApiException.bad("URL_NOT_ALLOWED", "주소를 찾을 수 없어요");
        for (InetAddress a : addrs) if (blocked(a)) throw ApiException.bad("URL_NOT_ALLOWED", "내부·사설 주소는 넣을 수 없어요");
        return u;
    }

    /** 사설·루프백·링크로컬·멀티캐스트·0.0.0.0/8·CGNAT(100.64/10)·IPv6 ULA(fc00::/7)·IPv4 매핑 주소 차단 */
    static boolean blocked(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0 || b0 == 127 || b0 == 10) return true;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;   // CGNAT
            if (b0 == 169 && b1 == 254) return true;               // 링크로컬·클라우드 메타데이터
            if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
            if (b0 == 192 && b1 == 168) return true;
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return true;
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;  // 벤치마크용
            return b0 >= 224;                                      // 멀티캐스트·예약
        }
        if (b.length == 16) {
            if ((b[0] & 0xfe) == 0xfc) return true;                // fc00::/7 ULA
            if ((b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0x80) return true; // fe80::/10
            boolean mapped = true;                                  // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try { return blocked(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16))); } catch (UnknownHostException e) { return true; }
            }
        }
        return false;
    }

    private static final Pattern META = Pattern.compile("<meta\\s+[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE);

    /** HTML 에서 og 메타·title 을 뽑는다(네트워크 없음, 테스트용으로 공개). */
    public static Preview parse(String html, URI base) {
        Map<String, String> og = new HashMap<>();
        Matcher m = META.matcher(html);
        while (m.find()) {
            Map<String, String> at = new HashMap<>();
            Matcher a = ATTR.matcher(m.group());
            while (a.find()) at.put(a.group(1).toLowerCase(Locale.ROOT), a.group(3) != null ? a.group(3) : a.group(4));
            String key = at.getOrDefault("property", at.get("name"));
            String content = at.get("content");
            if (key != null && content != null) og.putIfAbsent(key.toLowerCase(Locale.ROOT), content);
        }
        String title = og.get("og:title");
        if (title == null || title.isBlank()) { Matcher t = TITLE.matcher(html); if (t.find()) title = t.group(1); }
        String desc = og.getOrDefault("og:description", og.get("description"));
        String img = og.getOrDefault("og:image", og.get("twitter:image"));
        String image = null;
        if (img != null && !img.isBlank()) {
            try {
                URI iu = base.resolve(unescape(img).trim());
                String sch = iu.getScheme() == null ? "" : iu.getScheme().toLowerCase(Locale.ROOT);
                if (sch.equals("http") || sch.equals("https")) image = iu.toString();
            } catch (Exception ignored) { /* 이미지 없이 */ }
        }
        return new Preview(base.toString(), clip(unescape(title), 200), image == null ? null : clip(image, 500), clip(unescape(desc), 300),
                clip(unescape(og.get("og:site_name")), 100));
    }

    static String unescape(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'").replace("&nbsp;", " ");
        return t.replaceAll("<[^>]*>", "");
    }

    private static String clip(String s, int n) {
        if (s == null || s.isBlank()) return null;
        return s.length() > n ? s.substring(0, n) : s;
    }
}
