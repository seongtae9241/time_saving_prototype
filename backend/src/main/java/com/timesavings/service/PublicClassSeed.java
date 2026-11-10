package com.timesavings.service;

import com.timesavings.domain.PublicClass;
import com.timesavings.repo.PublicClassRepo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 기관 클래스 추천 데모 샘플(DataSeeder 가 부른다). 실제 강좌가 아니라는 뜻으로 기관명·제목에 "(예시)"를 붙이고 주소는 example.org 또는 비운다. */
public final class PublicClassSeed {
    private PublicClassSeed() {}

    public static void seed(PublicClassRepo repo, LocalDate today) {
        add(repo, today, "스마트폰 기초 교실 (예시)", "행복구 평생학습관 (예시)", "행복구 행복동", "행복동 주민센터 3층 강의실", 3, 24, "화·목 10:00~12:00",
                "50세 이상 구민", "무료", "15명", "https://example.org/class/smartphone", "디지털", "카카오톡 사진 보내기, 영상통화, 글자 크게 하기를 천천히 배워요.");
        add(repo, today, "키오스크 주문 연습 (예시)", "행복동 디지털배움터 (예시)", "행복구 행복동", "행복동 작은도서관", 6, 6, "14:00~16:00",
                "어르신 누구나", "무료", "10명", null, "디지털", "카페·병원 무인 주문기를 연습 기계로 직접 눌러 봐요. 전화 접수.");
        add(repo, today, "웃음 건강 체조 (예시)", "행복노인종합복지관 (예시)", "행복구 행복동", "행복노인종합복지관 2층 강당", 2, 30, "월·수 14:00~15:00",
                "60세 이상", "무료", "30명", "https://example.org/class/laugh", "정서·건강", "음악에 맞춰 가볍게 몸을 움직이고 함께 웃어요.");
        add(repo, today, "기억력 쑥쑥 치매 예방 교실 (예시)", "행복구 보건소 (예시)", "행복구 사랑동", "행복구 보건소 3층", 9, 9, "10:00~11:30",
                "65세 이상 구민", "무료", "20명", "https://example.org/class/memory", "정서·건강", "보건소 선생님과 기억력 놀이와 생활 습관을 배워요.");
        add(repo, today, "보이스피싱·금융 사기 예방 강좌 (예시)", "행복구 평생학습관 (예시)", "행복구 희망동", "희망동 주민센터 2층", 12, 12, "15:00~16:30",
                "누구나", "무료", "40명", null, "생활", "문자·전화 사기 수법과 대처법을 사례로 알려 드려요.");
        add(repo, today, "5060 건강 반찬 요리 (예시)", "희망동 주민센터 (예시)", "행복구 희망동", "희망동 주민센터 요리실", 15, 36, "금 10:00~12:30",
                "50~60대 구민", "재료비 10,000원", "12명", "https://example.org/class/cook", "생활", "저염 반찬 세 가지를 같이 만들어요.");
        add(repo, today, "어린이 코딩 교실 (예시)", "행복구립도서관 (예시)", "행복구 행복동", "행복구립도서관 멀티실", 5, 5, "토 10:00~12:00",
                "초등학생", "무료", "16명", "https://example.org/class/kids", "디지털", "초등학생 대상 강좌(나이대가 맞지 않으면 추천 순위가 낮아져요).");
    }

    private static void add(PublicClassRepo repo, LocalDate today, String title, String org, String region, String place, int inDays, int length,
                            String sched, String target, String fee, String cap, String url, String cat, String desc) {
        PublicClass c = new PublicClass();
        c.source = "PUBLIC_DATA"; c.title = title; c.orgName = org; c.region = region; c.place = place;
        c.startDate = today.plusDays(inDays); c.endDate = today.plusDays(inDays + length);
        c.scheduleText = sched; c.target = target; c.feeText = fee; c.capacityText = cap; c.applyUrl = url;
        c.applyHow = url == null ? "전화 접수 (예시 번호 없음)" : "기관 홈페이지 접수"; c.category = cat; c.description = desc;
        c.verified = true; c.verifiedAt = LocalDateTime.now(); c.fetchedAt = LocalDateTime.now(); c.expiresAt = c.endDate.plusDays(1).atStartOfDay();
        c.rawJson = "{\"sample\":true}";
        repo.save(c);
    }
}
