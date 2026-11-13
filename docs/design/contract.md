# 시간 적금 — 구현 계약서 (백엔드·프론트 공통 기준)

이 문서는 백엔드와 프론트가 **동시에** 개발할 때 서로 맞춰야 하는 약속이다. 경로·필드 이름·상태 값은 여기 적힌 대로 쓴다.
더 필요한 엔드포인트·필드는 추가해도 되지만, 추가한 것은 이 문서 맨 아래 "추가 기록"에 적는다(백엔드가 적고, 프론트는 읽는다).

- 기준 요구사항: `docs/requirements/요구사항명세서.txt` (A-01 ~ D-09, P-01 ~ P-07) — **2026-11-12부터 41판(A-01~A-10, B-01~B-67, C-01~C-16, D-01~D-11, 104개) 기준.** 41판으로 바뀐 약속은 7.17에 모았고, 아래 옛 문장에는 "41판" 표시를 붙였다. 요구사항 ID도 41판에서 번호가 바뀐 것이 있어(예: 단골 배지 B-41 → B-42) 7.16까지의 괄호 속 ID는 그 절을 쓴 때의 판 번호다.
- **2026-11-12 기능명세서(`docs/requirements/기능명세서.txt`, 73개 기능, ID C-·U-·O-·A-) 적용**: 바뀐 약속은 7.18에 모았다. 기능명세서 ID는 요구사항 명세서 ID와 **다른 체계**라(예: 기능 A-01 운영 대시보드 ≠ 요구사항 A-01 회원가입) 이 문서에서는 늘 "기능 U-09"처럼 "기능"을 붙여 적는다. 41판과 기능명세서가 부딪히는 곳은 **기능명세서(7.18)가 맞다** — 아래 옛 문장에는 "기능명세서에서 바뀜 → 7.18"을 붙였다.
- 채택 프로토타입(화면·흐름·문구·디자인의 기준): `docs/prototypes/adopted-황수빈-첫활동교육-동네클래스.html`
- 참고만 하는 프로토타입: `docs/prototypes/ref-*.html` (채택안에 없는 요구사항 화면만 참고)
- 요구사항과 프로토타입이 부딪히면 **요구사항명세서가 우선**한다(예: 독거노인은 실명 대신 별칭만 저장).

## 1. 기술 구성

| 영역 | 선택 |
|---|---|
| 백엔드 | Spring Boot 3.5.x, Java 21, Maven(`./mvnw`), spring-boot-starter-web / data-jpa / validation, spring-security-crypto(BCrypt만) |
| DB | H2 파일 DB (`./data/timesavings`, `MODE=MySQL`) 기본. `mysql` 프로필로 MySQL 8 전환 가능. DDL은 `db/schema.sql`, 데모 데이터는 서버 첫 기동 때 `DataSeeder`가 넣는다 |
| 프론트 | React 18 + Vite + react-router-dom 6, 순수 CSS(채택 프로토타입 CSS 변수·클래스 그대로 이식). 상태는 React state + context |
| AI | 백엔드 `AiClient` 한 곳에서만 호출. 환경변수 `ANTHROPIC_API_KEY`가 있으면 Claude API(Messages API, 모델 `claude-haiku-5-5`)를 부르고, 없거나 실패하면 **규칙 기반 대체 결과**를 돌려준다. 응답에 `source: "ai" | "rule"`을 넣는다. 키는 코드·파일·로그에 절대 적지 않는다. 테스트에서는 AI를 부르지 않는다 |
| 포트 | 백엔드 8080, 프론트 개발 서버 5173(`/api` → 8080 프록시) |

## 2. 공통 규칙

- 모든 API는 `/api` 아래. JSON. 날짜 `YYYY-MM-DD`, 일시 ISO-8601(`2026-11-14T14:00:00`).
- 시간(시각)은 **소수 시각**으로 주고받는다: `14.5` = 오후 2시 30분. 활동 시간도 소수(`1.5` = 1시간 30분). 모두 30분(0.5) 단위.
- 인증: 로그인하면 `token`을 준다. 이후 요청 헤더 `Authorization: Bearer <token>`. 토큰은 DB `auth_token` 테이블(만료 30일, "로그인 유지" 미선택 시 1일).
- 오류 응답: HTTP 4xx + `{ "error": "사람이 읽을 한국어 문장", "code": "SNAKE_CASE" }`. 프론트는 `error`를 토스트로 보여 준다.
- 목록은 배열 그대로 돌려준다(페이징 없음, 데모 규모).
- 역할: `USER`(사용자), `ORG`(기관 담당자), `ADMIN`(운영자). 권한 없는 접근은 403.
- 본인인증(휴대폰)은 **모의**: `POST /api/auth/phone/send` → `{ "demoCode": "123456" }`를 돌려주고, 화면에 "테스트용 인증번호"로 보여 준다.
- 파일(신분증·범죄경력 회보서·활동 사진·프로필 사진): `POST /api/files` (multipart `file`) → `{ "url": "/api/files/<id>" }`. `./data/uploads`에 저장.

## 3. 핵심 규칙 (서버가 강제)

### 3.1 카테고리·난이도·적립
| 카테고리 | 기본 등급 | 가중치 |
|---|---|---|
| 말동무·정서 | 기본(BASIC) | 1.0 |
| 디지털 도움 | 기본(BASIC) | 1.0 |
| 생활 정리 | 기본(BASIC) | 1.0 |
| 장보기·심부름 | 수고(EFFORT) | 1.2 |
| 가벼운 집안일 | 수고(EFFORT) | 1.2 |
| 동행 | 책임(RESPONSIBLE) | 1.5 *(7.21에서 바뀜: 기본(BASIC) 1.0)* |

- 등급 가중치: BASIC 1.0 / EFFORT 1.2 / RESPONSIBLE 1.5 (B-27) *(7.21에서 바뀜: 기본 1.0·수고 1.2 두 단계만. 책임 1.5는 예전 기록에만 남는다)*. AI(또는 규칙)가 요청 문장에서 등급과 근거를 **제안**하고, 가중치 값은 규칙으로 적용. *(기능명세서에서 바뀜 → 7.18 ⑧: 운영자가 건의를 승인하면 기본 6종 말고 카테고리가 더해진다 — `GET /api/categories`)*
- 적립 = **예정 시간**(시간 조정이 승인된 건은 조정 시간) × 가중치, 0.5 단위 반올림 (B-26 — 41판 문구: 실제 활동 시간이 아니라 예정 시간 기준). 예: 동행 2시간 → 3.0 *(7.21에서 바뀜: 2.0)*, 장보기 1.5시간 → 1.8 → 2.0.
- 복합 요청(B-29): 여러 카테고리가 섞이면 **최고 등급** 기준.
- 일정(B-10): 30분 단위, 1회 0.5~4시간, 요청·제안 시간은 공고 시간 범위 안. 공고 1개는 수락된 요청 1건만.

### 3.2 요청·제안 상태 (A-05, B-17~B-19, B-22~B-25, C-07, C-10)
`kind`: `REQUEST`(봉사받는 쪽이 봉사 가능 공고에 요청) / `PROPOSAL`(봉사하는 쪽이 도움 요청 공고에 제안) / `REGULAR`(단골에게 공고 없이 직접 요청)

```
REQUEST·REGULAR: SENT --(봉사자 수락)--> VOL_ACCEPTED --(받는 사람/담당자 최종 수락)--> CONFIRMED
PROPOSAL:        SENT --(받는 사람/담당자 수락 = 최종)--> CONFIRMED
CONFIRMED --(시작 체크인: 6자리 코드/QR)--> IN_PROGRESS --(종료 체크아웃)--> CHECKED_OUT
CHECKED_OUT --(봉사자 활동 일지 제출)--> LOG_SUBMITTED --(받는 사람 확인 / 담당자 대신 확인 / 24시간 뒤 자동 확정)--> DONE
   (기능명세서에서 바뀜 → 7.18 ③: 장보기·심부름은 영수증 사진, 가벼운 집안일은 전·후 사진이 있어야 일지를 낼 수 있다)
DONE --(평가)--> 평가는 별도 테이블, 상태는 DONE 유지
거절: REJECTED(봉사자 또는 받는 쪽 1차 거절, 사유 필수) / DECLINED(최종 거부) / EXPIRED(24시간 무응답) / CANCELLED(취소) / NOSHOW(봉사자 노쇼)
```
- 각 응답 단계 기한 24시간(`deadlineAt`). 스케줄러가 지나면 `EXPIRED` + 요청한 쪽 알림. 최종 거부·만료로 끝나도 **공고는 다시 열리지 않는다**(B-19).
- 공고의 요청 하나가 CONFIRMED가 되면 공고는 `MATCHED`, 같은 공고의 나머지 SENT/VOL_ACCEPTED는 자동 `REJECTED`(사유 "다른 요청이 수락됨") + 알림.
- 일반 사용자가 받는 쪽이면: CONFIRMED 때 받는 쪽 통장에서 예상 적립 시간만큼 **보류(HOLD)**, DONE 때 보류 해제 후 **예정 시간 × 가중치**(조정 승인 건은 조정 시간 × 가중치 = 봉사자 적립과 같은 값)만큼 **차감(USE)** (B-30 — 41판 글자는 "예정 시간만큼"이지만 총괄 결정으로 가중치를 곱한 값을 유지, 7.17 ⑨). 독거노인 건은 차감·보류 없음, 봉사자만 적립 (B-31).
- 본인 사용 조건(B-33): 받는 쪽 일반 사용자는 "본인이 적립한 누적 시간(선물받은 시간 제외)" 10시간 이상이어야 요청 가능. 단, 선물받은 시간 잔액은 이 조건 없이 사용 가능. 잔액 부족이면 요청 불가.
- 체크인: 받는 사람 화면에 **30초마다 바뀌는** 6자리 번호+QR을 띄우고(독거노인 건은 도움 대상별 현관 QR 카드), 봉사자가 QR 스캔 또는 번호 입력으로 시작/종료한다. 예정 시작 **10분** 뒤 지각 알림, **30분** 뒤 노쇼 의심 알림을 양쪽(독거노인 건은 담당자)에 보내고, 종료 기록이 없으면 예정 종료 30분 뒤 예정 종료 시각으로 자동 마감한다. *(41판에서 바뀜 — 예전: 확정 때 정한 고정 6자리 코드, 시작 15분 뒤 받는 쪽에만 알림 1번 → 7.17 ①)*
- 실제 시간이 예정과 다르면 봉사자가 시간 조정 요청(`adjust`) → 받는 쪽 승인 시 반영 (B-22). 기본 적립은 **예정 시간** 기준.
- 자동 확정(C-10): LOG_SUBMITTED 후 24시간 동안 받는 쪽이 확인하지 않으면 DONE. 일지가 없으면 자동 확정하지 않음. *(기능명세서에서 바뀜 → 7.18: 봉사 조건에 첫 활동 교육 이수가 더해졌다(기본 필수, 운영자 설정) ⑤ / 단체 봉사는 이 상태 흐름을 쓰지 않는 별도 표 ①)*
- 취소(B-25): 시작 24시간 전까지 무료 취소. 그 뒤 봉사자가 취소하거나 노쇼면 `penaltyCount+1`, 경고 알림. *(41판: 상대가 응답하기 전(SENT)의 취소는 보낸 쪽만 → 7.17 ①)*
- 메시지(A-08): 상대가 수락한 뒤(VOL_ACCEPTED 또는 CONFIRMED 이후)부터 DONE 전까지 작성 가능, 끝나면 읽기만. 연락처·계좌 패턴(전화번호, 카톡, 계좌)은 서버가 막지 않되 경고 문구를 붙이고 신고 버튼 제공.

### 3.3 시간 통장
- `ledger` 거래 종류: `EARN`(적립) / `HOLD`(보류) / `RELEASE`(보류 해제) / `USE`(사용·차감) / `GIFT_OUT` / `GIFT_IN` / `ADJUST`(운영자 조정) / `REFUND`.
- 잔액 = 합계(보류 제외), 보류 = 미해제 HOLD 합.
- 시간 선물(B-32): 승인된 가족 연결 상대에게만. 선물 외 양도·매매 없음(B-34).

### 3.4 독거노인(도움 대상) — 개인정보 최소
- 저장: `alias`(별칭), `dong`(동 단위), `ageGroup`(예: 80대), `needs`(카테고리), `caution`(방문 주의사항), 담당자 메모. 이름·주소·연락처는 **저장하지 않는다**(C-02). *(41판: 등록 때 서비스 이용 동의 확인이 필수, 현관 QR 카드 번호를 더 저장, `ageGroup`은 담당자에게만 응답 → 7.17 ①·⑥)*
- 봉사자에게는 별칭을 가린 표시(`displayName`: 첫 글자 + ○ + 끝 글자 + " 님", 예: "이○례 님")·동·도움 내용·시간·주의사항만. 담당자에게는 별칭 원문. *(기능명세서에서 바뀜 → 7.18 ④: **주의사항(방문 주의사항·공고의 관리 대상 이슈)은 일정이 확정된 봉사자와 담당자에게만** 준다. 확정 전 봉사자는 가린 이름·동·도움 내용·시간까지만)*
- 수락된 활동에 한해 담당자가 메시지로 방문 장소를 안내(C-02, B-13).
- 독거노인 건의 알림·메시지·최종 수락·확인·평가는 담당자가 대신(C-07~C-10, B-38).

### 3.5 단골(B-41)
같은 봉사자가 같은 받는 사람(사용자 또는 독거노인)을 DONE 3회 이상 → 단골. 프로필·소개 카드에 "○○ 님의 단골" 배지, `단골에게 요청하기` 목록에 사용. *(41판: 직접 등록은 활동이 없어도 되고 등록한 쪽·등록된 쪽 모두 해지할 수 있다. 받은 배지는 횟수와 함께 모아 본다 → 7.17 ④)* *(기능명세서에서 바뀜 → 7.18 ③: 완료 3회째에 양쪽 모두에게 `REGULAR` 알림, 봉사자 화면에도 단골 표시)*

## 4. 데이터 모델 (테이블 요약)

> **41판(2026-11-12)**: 더한 칸과 더 쓰지 않는 칸은 7.17 ⑩과 `db/schema.sql` 주석을 본다. 아래 요약의 `users.edu_done`, `posts.repeat_rule`·`series_id`는 41판부터 쓰지 않고, `requests.check_code`는 시작·종료 확인에 쓰지 않는다.
> **기능명세서(2026-11-12)**: 새 표 `group_posts`·`group_participants`·`recurring_posts`·`manager_applications`·`weather_alerts`, 새 칸 `requests.log_photo_url2`·`edu_progress.practice_json`·`notices.pinned`. 위에서 "쓰지 않는다"고 한 `users.edu_done`, `posts.repeat_rule`·`series_id`와 `org_events.capacity`, `edu_progress.completed_at`은 **다시 쓴다** → 7.18 ⑨.

`users`(id, login_id, password_hash, name, phone, role, status[ACTIVE/SUSPENDED/WITHDRAWN], area, age_group, intro, experience, personality, categories, time_slots, photo_url, org_id, id_status[NONE/PENDING/APPROVED/REJECTED], id_file_url, crime_status[NONE/PENDING/APPROVED/REJECTED], crime_file_url, edu_done, voice_alert, text_size, penalty_count, warning_count, created_at)
`organizations`(id, name, org_type, address, phone, manager_name, manager_phone, manager_email, status[PENDING/APPROVED/REJECTED], memo, created_at)
`elders`(id, org_id, manager_id, alias, dong, age_group, needs, caution, status[ACTIVE/ENDED], report_consent, created_at)
`elder_memos`(id, elder_id, text, created_at) · `elder_transfers`(id, elder_id, from_manager_id, to_manager_id, status[PENDING/ACCEPTED/REJECTED/ADMIN], created_at)
`posts`(id, type[OFFER/NEED], owner_id, elder_id, created_by, date, start_hour, end_hour, area, categories, title, detail, note, est_hours, difficulty, difficulty_reason, weight, repeat_rule, series_id, status[OPEN/MATCHED/CLOSED/DELETED], created_at)
`requests`(id, kind, post_id, sender_id, elder_id, volunteer_id, recipient_id, date, start_hour, hours, category, content, memo, difficulty, weight, est_earn, status, reject_reason, deadline_at, check_code, check_in_at, check_out_at, check_method, log_raw, log_text, log_photo_url, photo_consent, adjust_hours, adjust_status, earned, confirmed_at, done_at, created_at)
`messages`(id, request_id, sender_id, sender_label, system_msg, text, warn, created_at)
`ledger`(id, user_id, type, amount, base_hours, weight, request_id, label, created_at)
`ratings`(id, request_id, rater_id, target_user_id, stars, tags, comment, is_private, created_at)
`notifications`(id, user_id, type, title, body, link, is_read, created_at)
`interest_alerts`(id, user_id, area, categories, day_type, enabled)
`reports`(id, reporter_id, target_type[POST/REQUEST/MESSAGE/USER], target_id, target_label, type, content, status[RECEIVED/REVIEWING/DONE], action[NONE/WARNING/SUSPEND/BAN], result, created_at, handled_at)
`inquiries`(id, user_id, type, title, content, status[RECEIVED/ANSWERED], answer, answered_at, created_at)
`notices`(id, title, content, created_at)
`family_links`(id, requester_id, target_id, relation, status[PENDING/ACCEPTED/REJECTED], share_report, created_at)
`difficulty_appeals`(id, request_id, user_id, reason, requested_level, status[RECEIVED/ACCEPTED/REJECTED], result_level, admin_note, created_at)
`public_services`(id, name, target, content, apply_how, contact, region, keywords, created_at)
`life_reports`(id, subject_type[USER/ELDER], subject_id, month, stats_json, narrative, big_change, source, created_at)
`edu_progress`(user_id, diag_json, cards_done, quiz_score, roleplay_json, completed_at)
`roleplay_personas`(id, name, initial, age_label, fit_category, trait, goal, opening, turns_json, active)
`auth_token`(token, user_id, expires_at)

## 5. API 목록

표기: `메서드 경로 — 설명 [역할] (요구사항 ID)`. 요청/응답 필드는 4장의 열 이름을 camelCase로 쓴 것과 같다(예: `start_hour` → `startHour`).

### 5.1 인증·회원 (A-01~A-04, A-09, B-01, C-01)
- `POST /api/auth/phone/send` {phone} → {demoCode} — 모의 본인인증
- `POST /api/auth/phone/verify` {phone, code} → {verified:true, verifyToken}
- `POST /api/auth/signup` {loginId, password, name, phone, verifyToken, area, ageGroup, categories[], timeSlots[], intro} → {token, user} [공개] *(41판: `agreeTerms`·`agreePrivacy` 필수(없으면 400 `TERMS_REQUIRED`), `agreeLocation`·`agreeInterest` 선택 → 7.17 ②)*
- `POST /api/auth/login` {loginId, password, keep} → {token, user}
- `POST /api/auth/logout`
- `POST /api/auth/password/reset` {loginId, phone, verifyToken, newPassword}
- `GET /api/me` → user(본인) + `{balance, hold, earnedSelf, regularBadges[], ratingAvg, ratingCount, eduDone, verify:{idStatus, crimeStatus, eduDone}}` *(41판: `eduDone`·`verify.eduDone` 삭제, `accountNo`·`notiSettings`·`locConsent` 등 추가 → 7.17 ②)* *(기능명세서에서 바뀜 → 7.18 ⑤: `eduDone`·`verify.eduDone`이 되살아나고 `canVolunteer`·`volunteerBlock`·`verify.eduRequired`·`verify.eduOn` 추가)*
- `PUT /api/me` {name, phone, area, ageGroup, intro, experience, personality, categories[], timeSlots[], photoUrl, textSize, voiceAlert}
- `PUT /api/me/password` {currentPassword, newPassword}
- `GET /api/me/withdraw-check` → {activeRequests:[…], balance, hold, message} · `POST /api/me/withdraw` {password}
- `POST /api/me/verify/id` {fileUrl} · `POST /api/me/verify/crime` {fileUrl} — 운영자 승인 대기(PENDING)
- `POST /api/orgs/apply` {name, orgType, address, phone, managerName, managerPhone, managerEmail, memo} [공개] → {id, status} *(41판: 재직 증빙 파일 필수, multipart 또는 `proofFileUrl` → 7.17 ⑥)* *(기능명세서에서 바뀜 → 7.18 ⑥: 승인된 기관의 담당자 계정은 `POST /api/auth/manager-apply`로 신청하면 자동 승인)*
- `GET /api/users/{id}/profile` → 공개 프로필(이름, 나이대, 한 줄 소개, 경험, 평균 별점·횟수, 태그 상위, 범죄경력 배지, 교육 수료 배지, 단골 배지 목록) *(41판: 교육 수료 배지 없음 → 7.17 ⑤)* *(기능명세서에서 바뀜 → 7.18 ⑤: 프로필에 `eduDone`(안전 교육 이수 표시)이 다시 온다)*

### 5.2 공고 (B-02~B-11, B-20, B-21, C-04)
- `GET /api/posts?type=OFFER|NEED&area=&date=&cat=&sort=near|new` → [PostCard] (B-20). PostCard: {id,type,ownerId,ownerName,elderId,elderDisplayName,date,startHour,endHour,area,categories[],title,detail,note,estHours,difficulty,weight,estEarn,repeatRule,status,distanceKm,owner:{ratingAvg,ratingCount,ageGroup,crime,eduDone},mine} *(41판: `owner.eduDone` 삭제, `repeatRule`은 새 공고에서 늘 null, 쿼리 `lat`·`lng`·`dateFrom`·`dateTo` 추가 → 7.17 ③)* *(기능명세서에서 바뀜 → 7.18 ②·④: `sort=time`(활동 날짜·시작 시각 빠른 순) 추가, `repeatRule`·`seriesId`는 정기 요청 회차 공고면 값이 있다, `elderIssueLocked` 추가)*
- `GET /api/posts/{id}` → PostCard + `requests`(내가 공고 주인이면 받은 요청·제안 목록)
- `POST /api/posts` {type, date, startHour, endHour, area, categories[], title, detail, note, repeat:{weekly:true, weeks:4}?, elderId?} → 생성된 Post(반복이면 주마다 1개씩 생성, `seriesId` 공유) (B-03~B-07, C-04). 서버는 저장 전에 금지 필터(B-09)를 돌려 불가면 400 `FORBIDDEN_REQUEST`.  **(41판에서 바뀜: 자동 반복 등록은 없다. `repeat`은 받아도 무시하고 늘 1건만 만든다. 다시 올리기는 `POST /api/posts/{id}/repost` {date}, 가족 대신은 `forUserId` → 7.17 ③)** **(기능명세서에서 바뀜 → 7.18 ②: 이 주소의 `repeat`은 여전히 1건만 만들지만, 자동 회차 공고는 정기 요청 `POST /api/recurring`으로 다시 생겼다)**
- `PUT /api/posts/{id}` · `DELETE /api/posts/{id}` — 수락 전만 (B-11)
- `GET /api/posts/mine` → 내가 올린(담당자는 담당 대상) 공고
- `GET/POST/PUT/DELETE /api/interest-alerts` (B-21)

### 5.3 AI 기능 (B-02, B-08, B-09, B-24, P-01, P-03, 교육·추천)
모든 응답에 `source`(`ai`/`rule`).
- `POST /api/ai/post-chat` {type:'OFFER'|'NEED', messages:[{role:'user'|'assistant', text}], forElderId?} → {reply, done:boolean, draft:{date,startHour,endHour,area,categories[],title,detail,note,estHours}, missing:[…], filter:{level:'ALLOW'|'CAUTION'|'BLOCK', reason, alternatives:[PublicService]}, difficulty:{level,weight,reason,estEarn}} — 대화로 되묻고 JSON 공고 초안 정리
- `POST /api/ai/filter` {text} → {level, reason, alternatives[]} (B-09)
- `POST /api/ai/difficulty` {text, categories[], hours} → {level, weight, reason, factors:{physical,responsibility,risk,expertise,travel,access}, estEarn} (B-08, B-27)
- `POST /api/ai/log-tidy` {raw} → {text} (B-24) — 한 줄 기록을 읽기 쉬운 일지(2~3줄)로
- `POST /api/ai/public-services` {question, area?, ageGroup?} → {answer, services:[PublicService]} (P-01) — DB에 있는 서비스만, 자격 판정 금지
- `POST /api/ai/recommend` {question} → {answer, categories[], posts:[PostCard]} — "AI 도움 추천"(프로토타입 recommend)
- `POST /api/ai/roleplay` {personaId, history:[{who:'me'|'them', text}], text} → {quality:'good'|'ok'|'poor'|'bad', flag:null|'연락처'|'금전·선물'|'신체 돌봄'|'개인정보', next, done} — 교육 역할극 *(41판: 응답에 `feedback`·`options`·`total` 추가, `next`는 AI가 만든 어르신의 말 → 7.17 ⑤)*

### 5.4 요청·제안·활동 (B-12~B-19, B-22~B-25, B-28, C-05~C-10)
- `POST /api/requests` {kind, postId?, volunteerId?(REGULAR), elderId?(담당자 대리), date, startHour, hours, category, content, memo} → Request (B-14~B-16, C-05, C-06)
- `GET /api/requests?box=received|sent|active|done|all` → [RequestView]. RequestView: {id,kind,postId,postTitle,status,date,startHour,hours,category,content,memo,difficulty,weight,estEarn,deadlineAt,leftText,volunteer:{id,name,ageGroup,ratingAvg,ratingCount,intro,experience,tags[],crime,eduDone,isRegular},recipient:{id,name}|null,elder:{id,displayName,dong,caution}|null,sender:{id,name},myRole:'VOLUNTEER'|'RECIPIENT'|'MANAGER',actions:[…가능한 버튼 이름],checkCode?(받는 쪽에만),checkInAt,checkOutAt,logText,logPhotoUrl,earned,rated:boolean,adjust:{hours,status}} *(41판: `volunteer.eduDone` 삭제, `checkCode`는 30초 번호 또는 현관 카드 번호, `autoClosed`·`gapMinutes` 추가 → 7.17 ①)* *(기능명세서에서 바뀜 → 7.18 ③·④: `photoRule`·`logPhotoUrl2`·`elder.cautionLocked` 추가)*
- `GET /api/requests/{id}` → RequestView + `messages`
- `POST /api/requests/{id}/accept` (봉사자 수락 또는 받는 쪽 수락/최종 수락 — 서버가 상태로 판단)
- `POST /api/requests/{id}/reject` {reason} · `POST /api/requests/{id}/decline` {reason} (최종 거부)
- `POST /api/requests/{id}/cancel` {reason}
- `POST /api/requests/{id}/check-in` {code} · `POST /api/requests/{id}/check-out` {code} (코드 대신 `{method:'manual'}`이면 받는 쪽 확인 필요 표시) *(41판: `code`는 지금·직전 30초 구간의 번호 또는 현관 카드 번호. `GET /api/requests/{id}/qr` 추가 → 7.17 ①)*
- `POST /api/requests/{id}/log` {raw, text, photoUrl, photoConsent} (B-24) *(기능명세서에서 바뀜 → 7.18 ③: `photoUrl2` 추가, 장보기·집안일은 사진이 없으면 400 `PHOTO_REQUIRED`)*
- `POST /api/requests/{id}/confirm` (받는 쪽/담당자 확인 → DONE, 적립·차감) (B-23)
- `POST /api/requests/{id}/adjust` {hours, reason} · `POST /api/requests/{id}/adjust/answer` {accept:boolean}
- `POST /api/requests/{id}/rate` {stars, tags[], comment} (B-38, B-39 — 받는 쪽 평가는 is_private) *(기능명세서에서 바뀜 → 7.18 ③: 봉사자에게 가는 `RATED` 알림에서 별점을 뺐다)*
- `POST /api/requests/{id}/appeal` {reason, requestedLevel} (B-28)
- `GET /api/requests/{id}/messages` · `POST /api/requests/{id}/messages` {text} (A-08, C-09)
- `GET /api/regulars` → 내 단골 봉사자 목록 (B-14) · `GET /api/regulars?elderId=` (C-06)

### 5.5 시간 통장 (A-06, B-30~B-34, B-46)
- `GET /api/bank` → {balance, hold, earnedSelf, giftReceived, canUseSelf, monthIn, monthOut, monthly:[{month, in, out}], entries:[{id,type,amount,baseHours,weight,label,createdAt}]} *(기능명세서에서 바뀜 → 7.18 ③: `months`(거래가 있는 모든 달의 번 시간·쓴 시간 합계) 추가. 시간 플랜·월별 리포트 API 추가)*
- `POST /api/bank/gift` {toUserId, amount, message} — 연결된 가족만

### 5.6 알림·메시지·공지·문의·신고 (A-07, A-10, B-36, B-37, B-47~B-49)
- `GET /api/notifications` · `POST /api/notifications/{id}/read` · `POST /api/notifications/read-all` · `GET /api/notifications/unread-count`
- `GET /api/notices` · `GET /api/notices/{id}` *(기능명세서에서 바뀜 → 7.18 ⑧: 고정 공지(`pinned`)가 맨 위)*
- `GET /api/inquiries/mine` · `POST /api/inquiries` {type,title,content}
- `POST /api/reports` {targetType,targetId,targetLabel,type,content} · `GET /api/reports/mine` *(41판: `kind:"WELFARE"` 이상 징후(안부 확인) 접수, `targetType:"USER"` 확인 → 7.17 ⑦)*

### 5.7 마이페이지 (B-43~B-47, B-32, B-21)
- `GET /api/my/activities?seg=all|help|helped|posts` → 활동 내역 (B-45)
- `GET /api/family` → [{id, user:{id,name}, relation, status, direction:'out'|'in', shareReport}] · `POST /api/family` {loginIdOrPhone, relation} · `POST /api/family/{id}/answer` {accept} · `PUT /api/family/{id}/share` {share} (P-06) · `DELETE /api/family/{id}`
- `GET /api/family/{userId}/report` → 가족 생활 변화 리포트(공유 동의한 경우만) (P-04)
- `GET /api/certificate` → 봉사 확인서 데이터 {name, period, totalHours, count, byCategory:[…], items:[…]} *(41판: `from`·`to` 기간, `elderCount`, `summary` 추가 → 7.17 ②)* *(기능명세서에서 바뀜 → 7.18 ⑤: 화면이 이 값으로 실제 .pdf 파일을 만들어 내려 준다)*

### 5.8 첫 활동 교육 (B-01 안전 교육, 프로토타입 edu)
> **41판에서 바뀜**: 안전 교육 이수 조건이 없어지고 골라서 해 보는 "첫 활동 연습"(B-54 — 필수 아님, 수료·배지·활동 제한 없음)이 됐다. 아래의 `completed`·`passed`·`pass`와 `POST /api/edu/complete`는 없어졌다 → 7.17 ⑤.
> **기능명세서에서 다시 바뀜**: 첫 활동 교육(안전 교육) 이수 기록이 되살아났다. 연습 상황을 모두 안전 규칙에 맞게 답하면 `users.edu_done=true`이고, 운영자 설정이 필수(기본)이면 이수해야 봉사할 수 있다. `POST /api/edu/complete`는 여전히 없다(퀴즈·카드·긴 역할극은 이수 조건이 아니다) → 7.18 ⑤.
- `GET /api/edu` → {diag, cardsDone, quizScore, roleplay:{personaId:{passed, comm, emp, safe}}, completed, personas:[…], rules:[…], quiz:[…]}
- `PUT /api/edu/diag` {cats[], who, exp} · `POST /api/edu/cards-done` · `POST /api/edu/quiz` {answers:[번호…]} → {score, results:[…]}
- `POST /api/edu/roleplay/{personaId}/finish` {log:[…]} → {safe, comm, emp, pass, fixes[]}
- `POST /api/edu/complete` → 수료(조건: 카드 완료, 퀴즈 4/5 이상, 역할극 1명 이상 통과) → `users.edu_done=true` **(41판에서 삭제 — 이 API와 `users.edu_done` 갱신은 없다)**

### 5.9 동네 클래스 — 삭제
- 2026-11-10 사용자 결정으로 동네 클래스(1:1 수업·여럿 수업·수업 열기 인터뷰·출석 QR·배움 노트·공간 배정)와 그 API(`classes`·`org/spaces`·`org/classes` 경로 전부)를 지웠다. 기관 행사·클래스 홍보는 동네 소식(7.11 "2장·O3")으로 남는다.

### 5.10 담당자 (C-02~C-10, P-05)
- `GET /api/org/home` → {todo:[…], counts:{elders, pendingFinal, logsToConfirm, active}, alerts:[…]} *(41판: `counts.monthDone` 추가 → 7.17 ⑥)*
- `GET /api/org/elders` · `POST /api/org/elders` · `GET /api/org/elders/{id}` (+memos, regulars, recent requests) · `PUT /api/org/elders/{id}` · `POST /api/org/elders/{id}/memos` {text} *(41판: 등록에 `consent:true` 필수, 메모에 정리본, 현관 QR 카드·변화 리포트 API 추가 → 7.17 ①·⑥)* *(기능명세서에서 바뀜 → 7.18 ④: 응답에 `serviceConsent`, 수정에서 `consent:true`로 동의 기록 남기기, 변화 리포트에 신청 내역, 돌봄 편성표 `/api/org/schedule` 추가)*
- `POST /api/org/elders/{id}/end` (진행 중 요청 있으면 409) · `POST /api/org/elders/{id}/transfer` {toManagerId} · `GET /api/org/transfers` · `POST /api/org/transfers/{id}/answer` {accept}
- `PUT /api/org/elders/{id}/consent` {reportConsent} (P-06 대리 입력)
- `GET /api/org/managers` → 같은 기관 담당자 목록(이관 대상)
- `GET /api/org/requests?box=` → 담당 대상 전체 요청·제안 (RequestView, myRole=MANAGER)
- `GET /api/org/reports?month=` · `POST /api/org/reports/{id}/flag` (P-05)

### 5.11 운영자 (D-01~D-09, P-02, P-07)
- `GET /api/admin/dashboard` → {supply, demand, elderUsage, matchRateByArea:[{area,rate,total}], timeBalance:{issued, consumed, elderFree, pool}, monthly:[…], pending:{orgs, ids, crimes, appeals, reports, inquiries}} *(기능명세서에서 바뀜 → 7.18 ⑦·⑧: 재정 추계·정책 시뮬레이션·봉사 유형/계절 리포트·모집 문구·특보·신고 관련 기록·담당자 계정 승인 내역 API 추가)*
- `GET /api/admin/orgs` · `POST /api/admin/orgs/{id}/approve` → {managerLoginId, tempPassword} (담당자 계정 발급) · `POST /api/admin/orgs/{id}/reject`
- `GET /api/admin/managers` · `POST /api/admin/managers/{id}/suspend|unsuspend` · `POST /api/admin/elders/{id}/transfer` {toManagerId}
- `GET /api/admin/verifications` → 신원(id)·범죄경력(crime) 대기 목록 · `POST /api/admin/verifications/{userId}/{kind}/approve|reject`
- `GET /api/admin/appeals` · `POST /api/admin/appeals/{id}/answer` {accept, resultLevel, note} (승인 시 적립 차액 ADJUST)
- `GET /api/admin/reports` · `POST /api/admin/reports/{id}/handle` {action, result} (D-02) *(41판: 이상 징후 접수는 제재 없이 `NONE`만 → 7.17 ⑦)*
- `GET /api/admin/anomalies` → 같은 쌍의 짧은 활동 반복 등 (D-06) · `POST /api/admin/anomalies/{requestId}/hold|release`
- `GET /api/admin/users?q=&role=` · `GET /api/admin/users/{id}` · `POST /api/admin/users/{id}/suspend|unsuspend` (D-07)
- `GET /api/admin/inquiries` · `POST /api/admin/inquiries/{id}/answer` {answer} (D-08) *(41판: `PUT /api/admin/inquiries/{id}/status` 추가 → 7.17 ⑧)*
- `POST/PUT/DELETE /api/admin/notices` (D-09) *(기능명세서에서 바뀜 → 7.18 ⑧: `POST /api/admin/notices/{id}/pin` 고정)*
- `GET/POST/PUT/DELETE /api/admin/public-services` (P-02)
- `GET/POST/PUT/DELETE /api/admin/personas` (가상 상대 관리)
- `POST /api/admin/reports/generate` {month?} → 생활 변화 리포트 즉시 생성 (P-07)
- `POST /api/admin/scheduler/run` → 만료·자동 확정·노쇼·공고 마감·리마인드를 즉시 한 번 실행(시연용) *(41판: 지각·노쇼 의심 알림과 종료 자동 마감도 실행, 리마인드는 시작 1시간 전 → 7.17 ①)*

## 6. 데모 계정 (DataSeeder)

| 역할 | 아이디 / 비밀번호 | 내용 |
|---|---|---|
| 사용자 | `yujin` / `1234` | 손유진, 행복동, 20대 — 프로토타입 `D.me` |
| 사용자 | `sky`, `mikyung`, `doyun`, `bok`, `mom` / `1234` | 김하늘·최미경·이도윤·김복남·이순자(손유진 가족) |
| 담당자 | `org1` / `1234` | 박지원 (행복동 주민센터) — 도움 대상 4명(별칭 이순례·정말순·한영자·오덕수) |
| 담당자 | `org2` / `1234` | 같은 기관 다른 담당자(이관 시연) |
| 운영자 | `admin` / `1234` | 김운영 |

시드 데이터는 프로토타입 `D.P`(공고), `D.R`/`D.RE`(요청), `D.L`(통장), `D.N`(알림), `RP`(역할극 상대), `QZ`(퀴즈), 공공 서비스 10개 안팎을 **오늘 날짜 기준 상대 날짜**로 옮긴다.

## 7. 추가 기록 (개발 중 계약에 더한 것)

- (백엔드가 여기에 추가)

### 7.1 말로 요청하기 — `POST /api/ai/post-chat` 확장 (음성 분석보고서 4.8, 2026-11-09)
- 요청에 `inputMode: 'text' | 'voice'` (기본 text). voice면 AI 프롬프트에 "받아쓰기라 띄어쓰기·오타·군말 섞임, 존댓말로 짧게 한 번에 하나만 되묻기, reply는 소리로 읽기 좋은 1~2문장"을 넣고, 규칙 모드도 되묻기 문장을 짧게 한다.
- 응답에 추가: `intent`(`NEW_REQUEST`|`REGULAR_REQUEST`|`CHANGE_CANCEL`|`CONFIRM`|`QUESTION`|`CHAT`|`EMERGENCY`), `confidence`{`date`,`time`,`area`,`category`: `high`|`medium`|`low`}, `safety`{`emergency`:boolean, `piiRemoved`:[`phone`|`account`|`address`]}, `inputMode`, `draft.caution`, `draft.repeat`(예 "매주 목요일" 또는 null), `draft.summarySpeech`(done일 때만, 예 "11월 17일 화요일 오후 2시부터 2시간, 병원 동행 요청서를 만들었어요. 올릴까요?"). *(41판: `draft.repeat` 삭제, `draft.repeatNotice` 추가 → 7.17 ③)*
- `missing` 값: `content`(도움 내용·카테고리) / `date` / `startHour` / `endHour`(OFFER만) / `estHours`(NEED만) / `area`. 한 번에 첫 항목 하나만 되묻는다.
- 위급 표현("쓰러졌", "숨이 안", "피가 나" 등)이면 `intent=EMERGENCY`, `safety.emergency=true`, `done=false`, reply로 119 안내. 전화번호·계좌·상세 주소(아파트 동·호수, 번지, 도로명 번지)는 draft 본문에서 지우고 "(수락 뒤 메시지로 안내돼요)"로 바꾼다(AI 결과에도 서버가 다시 적용). 주의사항·세부 내용에는 병명을 남기지 않고 방문에 필요한 문장("걸음이 불편하셔서 천천히 걸어 주세요")만 둔다.
- `CHANGE_CANCEL`/`CONFIRM`, 그리고 카테고리 없는 `QUESTION`/`CHAT`이면 요청서를 만들지 않고 reply로 해당 화면(내 활동, 공공 서비스 안내, 말동무 요청)을 안내한다.
- 시각에 오전·오후가 없으면 1~6시는 오후로 보고 `confidence.time=medium`. 장소를 말하지 않으면 프로필 지역(담당자 대리면 대상의 동)을 채우고 `confidence.area=medium`.
- `GET /api/ai/status` → `{mode: 'ai'|'rule'}` (키 유무 표시용). → 7.9에서 `provider`·`model`·`stt` 추가.

### 7.2 공고
- `POST/PUT /api/posts` 본문에 선택 `difficulty`(BASIC/EFFORT/RESPONSIBLE *— 7.21에서 바뀜: RESPONSIBLE로 오면 EFFORT로 받는다*), `difficultyReason`: 화면에서 AI 제안을 받아 확정한 등급. 카테고리 기본 등급보다 낮으면 기본 등급으로 올린다. 없으면 서버 규칙으로 정한다.
- `POST /api/posts` 응답 = 첫 공고 PostCard + `createdCount`, `createdIds`(반복이면 여러 개). *(41판: 반복이 없어 늘 1건, `createdCount` 1)*
- PostCard 추가 필드: `elderCaution`, `difficultyReason`, `seriesId`, `createdAt`, `owner.intro`, `owner.photoUrl`, `owner.id`, `owner.name`. `estEarn`은 NEED만(= estHours × weight), OFFER는 null(요청 때 시간으로 계산).
- `GET /api/posts/mine`: 각 카드에 `requestCount`(응답 대기 요청 수). `GET /api/posts/{id}`: 주인이 아니면 `requests: []`와 `myRequest`(내가 보낸 마지막 요청).
- **공고 닫히는 시점**(B-17 우선): 1차 수락 때 공고 `MATCHED` + 같은 공고 나머지 SENT/VOL_ACCEPTED 자동 `REJECTED`("다른 요청이 수락됨"). REQUEST는 봉사자 수락(VOL_ACCEPTED) 때, PROPOSAL은 받는 쪽 수락(=최종) 때. 그 요청이 최종 거부·만료·취소·노쇼로 끝나면 공고는 `CLOSED`(다시 열리지 않음, B-19).
- 관심 조건: `PUT /api/interest-alerts/{id}`, `DELETE /api/interest-alerts/{id}`. id 없는 `PUT /api/interest-alerts`는 첫 조건을 고치거나 새로 만든다. `dayType`: `평일`|`주말`|`상관없음`. *(41판: 날짜 기간 `dateFrom`·`dateTo` 추가 → 7.17 ③)*

### 7.3 요청·활동
- `POST /api/requests/{id}/noshow` — 받는 쪽/담당자, CONFIRMED이고 시작 **30분**이 지났을 때(41판에서 15분 → 30분, 그 전이면 409 `TOO_EARLY`) → `NOSHOW`, 봉사자 `penaltyCount+1`, 보류 해제.
- 체크인·체크아웃 `method`: `qr` | `code`(기본) | `manual`(코드 없이, `checkMethod=manual`로 남아 받는 쪽 확인 필요 표시). 체크인 코드는 CONFIRMED 이후 받는 쪽·담당자의 RequestView에만 `checkCode`로 나온다. *(41판: `checkCode`는 CONFIRMED·IN_PROGRESS일 때만 나오고 30초마다 바뀐다. 독거노인 건은 현관 카드 번호 → 7.17 ①)*
- RequestView 추가 필드: `rejectReason`, `checkMethod`, `logRaw`, `logSubmittedAt`, `anomalyHold`, `createdAt`, `doneAt`, `adjust.reason`, `volunteer.photoUrl`. 상세(`GET /api/requests/{id}`)에는 `messages`, `canMessage`도 들어간다. `GET /api/requests/{id}/messages` → `{messages, canMessage, notice}`.
- `actions` 이름: `accept`, `reject`, `decline`, `cancel`(SENT는 보낸 사람만), `message`, `checkIn`, `checkOut`, `showCode`, `noshow`, `log`, `adjust`, `adjustAnswer`, `confirm`, `rate`, `appeal`, `report`.
- `box`: `received`=내가 보내지 않은 응답 대기(SENT/VOL_ACCEPTED), `sent`=내가 보낸 것, `active`=VOL_ACCEPTED~LOG_SUBMITTED, `done`=끝난 상태 전부(DONE·거절·만료·취소·노쇼), `all`. 담당자 `GET /api/org/requests?box=received`는 내 결정이 필요한 것(제안·최종 수락·일지 확인).
- 봉사 자격(B-01): 신분증 인증(`idStatus=APPROVED`)이 끝난 사용자만 OFFER 공고·제안·봉사자 수락이 된다(`ID_NOT_VERIFIED`). 41판에서 안전 교육 이수 조건과 수료 배지는 없어졌다(7.17 ⑤).
- 단골 요청(REGULAR)은 같은 받는 사람에게 DONE 3회 이상인 봉사자에게만(`NOT_REGULAR`). *(41판: 직접 등록한 단골도 되고, 해지된 사이는 안 된다 → 7.17 ④)*
- 평가 태그에서 돈 관련 낱말(돈·용돈·현금·팁 등)은 서버가 뺀다.
- 정산: 받는 쪽 HOLD = `estEarn`(예정 시간 × 가중치), DONE 때 RELEASE 후 USE = 실제 적립 시간(조정 승인 시 조정 시간 × 가중치). 봉사자 EARN과 같은 값이라 시간 총량이 맞는다. *(41판: B-30 글자는 "예정 시간만큼 차감"이지만 총괄 결정으로 이 방식을 유지 → 7.17 ⑨)*

### 7.4 시간 통장
- `GET /api/bank`·`GET /api/me` 추가 필드: `available`(= balance − hold), `usable`(B-33 반영해 지금 쓸 수 있는 시간), `canUseSelf`, `selfUseMin`(10). `balance`는 보류를 빼지 않은 합계.
- 오류 코드: `SELF_USE_LOCKED`(직접 적립 10시간 미만, 선물받은 시간 부족), `INSUFFICIENT_BALANCE`.
- `ledger.elder_id` 칸 추가(user_id 없는 시스템 기록용). entries 항목에 `requestId`도 준다.
- (2026-11-10) 동네 클래스 삭제로 `ledger.class_id`·entries `classId`를 지웠다. `CLASS_FEE`/`CLASS_EARN`/`POOL`은 기존 DB 행을 읽기 위해 enum에만 남기고 새로 쓰지 않으며, 적립·사용 합계(직접 적립 10시간 판단, 운영자 발행·소비)에도 넣지 않는다.

### 7.5 마이페이지·가족
- `GET /api/family` 항목에 `theyShare`(상대가 나에게 리포트 공유에 동의했는지). `shareReport`는 내 동의. DB `family_links.target_share_report` 칸 추가(받은 쪽의 동의).
- `GET /api/family/{userId}/report` → `{user, reports:[LifeReport], latestMonth}`. 연결 + 상대 동의가 있어야 하고 본인은 403.
- `GET /api/my/activities` 항목에 `itemType`: `request`(RequestView) | `post`(PostCard, `seg=posts`일 때).
- `GET /api/certificate`에 `totalEarned`, `eduDone`, `issuedAt` 추가. *(41판: `eduDone` 삭제, 기간·요약 문장 추가 → 7.17 ②)*
- `GET /api/me/withdraw-check`에 `canWithdraw`. 진행 중인 요청이 있으면 `POST /api/me/withdraw`는 409 `ACTIVE_REQUESTS`.
- `GET /api/users/{id}/profile`에 `area`, `categories`, `timeSlots`, `idVerified`, `helpCount` 추가.

### 7.6 교육
> **41판에서 바뀜**: 수료·통과가 없다. `pass`·`passed`·`completed`는 응답에서 빠졌다 → 7.17 ⑤.
- `GET /api/edu`: `personas[].turns[].options`는 문장(`t`)만(판정 숨김), `quiz`는 정답 없이 `{no, q, options, card}`. `rules`는 진단 카테고리에 맞춤 카드 2장까지 더한 목록.
- `POST /api/ai/roleplay` 응답에 `turn`, `source` 추가. 대본 선택지와 같은 문장이면 대본 판정을 쓴다.
- `POST /api/edu/roleplay/{personaId}/finish` 응답: `{safe, comm, emp, pass, flags, fixes:[{said, better, flag}], good}`. log 항목 `{who:'me'|'them', text, quality?, flag?}`.
- (2026-11-10) 이 절에 있던 동네 클래스·1:1 클래스 기록(응답 필드, 상태, 정산, 금지 주제 `RuleEngine.classFilter`, 공간 배정)은 기능 삭제로 지웠다.

### 7.7 담당자·운영자
- 도움 대상 등록·수정·메모에 전화번호·계좌·상세 주소가 보이면 400 `PII_NOT_ALLOWED`. 동은 "○○동" 형식만.
- `GET /api/org/elders` 항목: `alias`, `displayName`, `maskedName`, `regularCount`, `activeCount`, `monthDone`, `doneTotal`, `flag`(최근 리포트 큰 변화). `GET /api/org/transfers` 항목에 `direction`('in'|'out'). `GET /api/org/home`의 `counts`에 `flaggedReports`, `transfersIn`.
- `GET /api/org/reports?month=` 기본은 지난달, 항목에 `reportConsent`.
- 운영자 대시보드 추가: `kpi{activities, prevActivities, matchRate, elderUsage, prevElderUsage}`, `weekly:[{label, supply, demand}]`, `timeBalance.useRatio`, `edu`, `pending.anomalies`. `monthly` 항목: `{month, activities, elderUsage, supply, demand, issued, consumed}`. *(41판: `edu`(교육 수료 수)는 응답에 남아 있지만 화면에서 쓰지 않는다)*
- `POST /api/admin/orgs/{id}/approve` → `{managerLoginId, tempPassword, managerId, ...}`(임시 비밀번호는 이 응답에서만).
- 이상 패턴(D-06): 최근 30일 같은 쌍의 1시간 이하 DONE 3회 이상. `hold`는 이미 적립된 건이면 ADJUST −적립, `release`는 ADJUST +적립(정산 전 보류면 정산 때 적립하지 않음).
- `POST /api/admin/reports/{id}/handle`의 `action`이 없으면 상태만 REVIEWING. WARNING은 대상 `warningCount+1`, SUSPEND/BAN은 계정 정지.
- `POST /api/admin/reports/generate` 본문 `{month?: 'YYYY-MM'}` 기본 지난달 → `{month, generated}`.

### 7.8 기타
- `GET /api/files/{id}`는 로그인 없이 열린다(`<img>` 태그가 헤더를 못 보내므로). 파일 이름은 추측할 수 없는 32자 무작위 값.
- 음성 알림(B-42)은 모의: `voiceAlert=true` 사용자에게 알림이 생기면 서버 로그에 `[VOICE-MOCK]`만 남긴다. 프론트가 필요하면 브라우저 음성 합성으로 읽어 준다. *(41판: 문자 병행 `smsAlert`도 서버에 저장하고 `[SMS-MOCK]` 로그만 남긴다 → 7.17 ②)*
- DB 열 이름: `life_reports.month`는 H2 예약어라 `report_month`(JSON 필드 이름은 그대로 `month`). `requests`에 `log_submitted_at`, `adjust_reason`, `anomaly_hold`, `reminded`, `late_notified` 추가.
- 알림(Notification) 필드: `{id, userId, type, title, body, link, isRead, createdAt}`. `link`는 프론트 경로 힌트(`/requests/{id}` 등).

### 7.9 실제 휴대폰 인증·QR 인식·Gemini·서버 음성 인식 (2026-11-09)
환경변수(값은 어디에도 적지 않는다):

| 이름 | 쪽 | 뜻 |
|---|---|---|
| `GEMINI_API_KEY` | 백엔드 | Gemini 키(헤더 `x-goog-api-key`로만 보냄) |
| `GEMINI_MODEL` | 백엔드 | 기본 `gemini-flash-latest` |
| `ANTHROPIC_API_KEY` | 백엔드 | Claude 키 |
| `AI_PROVIDER` | 백엔드 | `gemini`\|`anthropic`\|`rule`. 비우면 Gemini 키 → Anthropic 키 → 규칙. 고른 공급자에 키가 없으면 규칙 |
| `FIREBASE_WEB_API_KEY` | 백엔드 | Firebase 웹 API 키(ID 토큰 확인용) |
| `PHONE_DEMO` | 백엔드 | `false`면 데모 코드 123456 끔(`app.phone.demo-code-enabled`, 기본 true) |
| `VITE_FIREBASE_API_KEY`·`VITE_FIREBASE_AUTH_DOMAIN`·`VITE_FIREBASE_PROJECT_ID`·`VITE_FIREBASE_APP_ID` | 프론트 빌드 | 넷 다 있어야 Firebase 사용 |

**휴대폰 인증 (A-01 가입, A-03 비밀번호 찾기)**
- `GET /api/auth/phone/config` (로그인 불필요) → `{firebase: boolean(백엔드 키 있음), demo: boolean}`. 화면은 `firebase && VITE_FIREBASE_* 있음`이면 Firebase JS SDK(보이지 않는 reCAPTCHA + `signInWithPhoneNumber`, 번호는 +82로 바꿈)로 실제 문자를 보낸다.
- `POST /api/auth/phone/verify` 본문 둘 중 하나:
  - `{phone, firebaseIdToken}` → 서버가 `POST https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=<FIREBASE_WEB_API_KEY>` `{idToken}`으로 확인, 돌아온 `users[0].phoneNumber`(E.164)가 입력 번호(+82…)와 같아야 함. 확인 뒤 화면은 Firebase `signOut`.
  - `{phone, code:'123456'}` → 데모 코드(켜져 있을 때만).
  - 응답 `{verified:true, verifyToken, method:'firebase'|'demo'}`. 오류: `FIREBASE_DISABLED`(키 없음), `FIREBASE_INVALID`(토큰 만료·위조·번호 없음), `PHONE_MISMATCH`, `INVALID_CODE`, `DEMO_DISABLED`, `INVALID_PHONE`. 이후 `verifyToken` 쓰임은 그대로(10분, 번호 일치).
- `POST /api/auth/phone/send`는 데모용으로 남김(데모 꺼짐이면 400 `DEMO_DISABLED`).
- 화면: Firebase 켜짐이면 "인증번호 받기" = 실제 문자, 데모 켜짐이면 아래 작은 글씨 "프로토타입 테스트: 123456 입력으로도 인증돼요"(문자를 받지 않고 바로 123456을 넣어도 됨). 문자 발송 실패(할당량·결제 미설정·도메인 미승인 등)면 오류 문장 + 데모 코드 안내. Firebase 꺼짐이면 지금처럼 테스트 코드 안내.

**QR (요청 체크인·아웃)**
- QR 내용: `TS1|REQ|<요청 id>|<6자리 코드>`. RequestView에 `qrText`(받는 쪽·담당자에게만, `checkCode`와 함께). (클래스용 CLS·ONE 종류는 2026-11-10 삭제) **(41판에서 바뀜: 6자리 코드는 30초마다 바뀌고, 독거노인 건은 `TS1|ELD|<도움 대상 id>|<카드 번호>` → 7.17 ①)**
- 스캐너: 후면 카메라(`facingMode: environment`) 영상 → `BarcodeDetector`(있으면) / `jsqr`. 형식·종류·대상 id가 맞으면 `{code, method:'qr'}`로 해당 API 호출. 카메라 권한 거부·https 아님·카메라 없음이면 안내. 6자리 번호 입력칸은 항상 같이 보이고 `{code, method:'code'}`. 영상은 저장·전송하지 않는다.
- 요청 체크인·아웃 화면 표시 `qr`→"QR 확인", `code`→"번호 확인", `manual`→"버튼 확인".

**AI 공급자 (`AiClient` 한 곳, `LlmProvider` = `AnthropicProvider` | `GeminiProvider`)**
- Gemini: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`, 헤더 `x-goog-api-key`, 본문 `systemInstruction`·`contents[{role:'user',parts:[{text}]}]`·`generationConfig.responseMimeType:"application/json"`. 응답 `candidates[0].content.parts[*].text`(생각 part 제외)를 이어 JSON 객체로 읽는다. 429·HTTP 오류·형식 이상이면 그 호출만 규칙 결과 + 로그(공급자 이름과 상태 코드만, 키·본문 없음).
- 모든 AI 응답의 `source`는 그대로 `'ai'|'rule'`, `ai`면 `provider: 'gemini'|'anthropic'` 추가.
- `GET /api/ai/status` (로그인 불필요로 바꿈) → `{provider:'gemini'|'anthropic'|'rule', model(규칙이면 null), stt:boolean, mode:'ai'|'rule'}`.

**서버 음성 인식 (`SttClient` 한 곳)**
- `POST /api/stt` (로그인 필요, multipart `audio`, 선택 `lang=ko`) → `{text, source:'gemini'}`. 최대 5MB(화면은 1분에서 녹음 자동 정지). 받는 형식 `audio/webm`·`audio/ogg`·`audio/mp4`(+ mpeg·wav·aac·flac, `;codecs=` 무시).
- Gemini `generateContent`에 `inline_data{mime_type, data(base64)}` + "한국어 음성을 들리는 그대로 받아쓰기만, 설명 없이" 지시. **녹음은 저장하지 않고 메모리에서 처리 후 버림.**
- 오류: 키 없음(또는 `AI_PROVIDER=rule`, `app.ai.enabled=false`) 503 `STT_UNAVAILABLE`, 사용량 초과 503 `STT_BUSY`, 그 밖 실패 502 `STT_FAILED`, 400 `AUDIO_REQUIRED`·`AUDIO_TYPE`, 413 `AUDIO_TOO_LARGE`.
- 화면 공용 음성 입력(`ui/VoiceInput.jsx`): ① 브라우저 `SpeechRecognition` ② 없거나 설정(알림 설정 → 음성 입력, 말로 요청하기 화면 작은 토글)에서 "서버 음성 인식"을 고르면 `MediaRecorder` 녹음 → `/api/stt` ③ 둘 다 안 되면 직접 입력 안내. 마이크 권한은 버튼을 누를 때만 요청.
- 마이크 버튼(프로토타입 `.mic`)을 단 곳: 말로 요청하기, 대화형 공고 작성, 활동 일지 한 줄(B-24 → 기존 AI 일지 정리), 1:1 문의(B-48), 신고 내용(B-36), 요청·제안 내용·메모(A-08), 요청 메시지, AI 도움 추천·공공 서비스 질문, 수업 열기 인터뷰, 교육 역할극 대답, 담당자 대상 메모(C-02), 평가 한마디(B-38), 1:1 신청 "궁금한 점". 받은 글은 입력칸에 채워질 뿐이라 전화번호 경고·`PII_NOT_ALLOWED` 등 기존 규칙이 그대로 적용된다.


### 7.10 회의반영 U1·O1·리포트·운영 분석·AI 개인 분석 (2026-11-09, `docs/meetings/2026-11-09-회의반영.md` 1·3~6장)
**U1 시간 통장**
- `GET /api/bank` 추가 필드: `totalEarned`(보류 제외 + 거래 합), `totalUsed`(보류 제외 − 거래 합의 절댓값). `totalEarned − totalUsed = balance`. 화면은 맨 위에 "전체 적립 / 전체 사용 / 남은 시간"과 "내 봉사 플랜" 진입.

**O1 담당자의 봉사자 거부·받지 않기**
- `POST /api/org/requests/{id}/reject-volunteer` `{reason(필수, 300자), block?:boolean}` — 담당 대상 건만(아니면 403). 상태별: 제안 SENT → `REJECTED`, VOL_ACCEPTED → `DECLINED`, 담당자가 보낸 SENT·CONFIRMED → `CANCELLED`. 그 밖(IN_PROGRESS 이후)은 409 `INVALID_STATE`. 사유 없으면 400 `REASON_REQUIRED`. `rejectReason="담당자 거부: …"`, 시스템 메시지, 봉사자 알림 `MANAGER_REJECTED`. 확정·수락 단계에서 거부하면 아직 날짜가 남은 공고는 `OPEN`으로 다시 연다. 응답 = RequestView + `blocked`.
- `block=true`면 `elder_blocks`에 기록 → 그 봉사자는 그 대상의 공고에 제안·요청·단골 요청을 만들 수 없다(403 `VOLUNTEER_BLOCKED`, 담당자가 그 봉사자에게 보내는 요청도 막힘).
- `GET /api/org/elders/{id}/blocks` → `[{id, elderId, volunteer{id,name}, reason, requestId, createdAt}]`, `DELETE /api/org/blocks/{id}`(받지 않기 풀기).
- RequestView 추가: `canRejectVolunteer`(담당자이고 SENT·VOL_ACCEPTED·CONFIRMED일 때 true).

**4장 전달 메모·최근 활동 리포트**
- `POST /api/requests/{id}/log` 본문에 `careMemo`(선택, 500자 넘으면 400 `MEMO_TOO_LONG`, 빈 문자열이면 지움, 없으면 그대로). DB `requests.care_memo`.
- RequestView `careMemo`: 이 건의 당사자(봉사자 본인·받은 사람·담당자)에게만. 운영자 조회(역할 없음)에는 넣지 않는다.
- `GET /api/reports/recent?subject=me|elder:{id}|family:{userId}` → `{subject, subjectType, subjectId, subjectLabel, total, items:[{requestId, date, startHour, hours, category, content, volunteer, status, logText, careMemo}](최신순 30건, 일지 제출된 LOG_SUBMITTED·DONE), handover:{text, points[], count, source, provider?}, monthly:[LifeReport]}`.
  - 권한: `me`=사용자 본인(받은 도움), `elder:`=그 대상의 담당자만, `family:`=가족 연결 + 상대가 공유에 동의(`shareTarget`)한 경우만. 그 밖(봉사자 포함)은 403. **봉사자 화면에는 대상의 지난 메모가 나오지 않는다**(봉사자는 자기가 쓴 건의 메모만 그 요청 상세에서 본다).
  - 인수인계 요약은 최근 8건으로 AI(`InsightAi`)가 쓰고, 키가 없으면 규칙 문장(카테고리별 횟수·마지막 날짜·메모의 "다음에 챙길 것"). 건강·병원 낱말이 든 메모는 그대로 옮기지 않고 "병원 안내 사항 있음 (M/D 메모 확인)"으로 줄인다. AI 지시에도 진단 금지·병명/약 이름 옮기지 않기.
- 화면: 담당자 리포트(`/o/report`) = 대상 칩 → 최근 활동 리포트, 아래 "지난 월간 리포트". 사용자 마이 → "내가 받은 도움 리포트"(`/my/report`), 가족 리포트(`/my/family/{id}/report`, 기존 월간만 보던 화면은 `/my/family/{id}/monthly`).

**5장 운영자 분석**
- `GET /api/admin/analytics` → `{periodMonths:12, byRecipientAge:[{ageGroup,total,top[{category,count}],counts}], byVolunteerAge:[…], monthlyCategory:[{month, season, total, counts{카테고리:n}, top}](12개월, DONE 기준), seasons:[{season,total,top}], supplyDemand:[{category, demand, supply, gap, needPosts, requests, offerPosts, accepted, ratio}](최근 180일, gap 큰 순), supplyDemandDays}`. 수요 = NEED 공고 + 받는 쪽 요청(REQUEST·REGULAR), 공급 = OFFER 공고 + 수락된 건(VOL_ACCEPTED 이후). 지역 매칭률은 기존 `dashboard.matchRateByArea`.
- `GET /api/admin/insights?refresh=` → `{summary, shortages:[{category, demand, supply, gap, reason}], newCategories:[{name, reason, examples[]}], unmatched[], unmatchedCount, generatedAt, cached, source, provider?}`. 결과는 1시간 캐시(`refresh=true`로 다시 분석). 숫자(gap 등)는 코드 값을 덮어써 AI가 바꿀 수 없다. "카테고리에 안 맞는 문장" = 최근 1년 NEED 공고 제목·내용과 요청 내용 중 6개 카테고리 대표 낱말이 하나도 없는 것. 키 없으면 규칙(카테고리별 짐작 이유, 낱말 묶음 후보: 간단한 수리·반려동물 돌봄·화분·텃밭·행정·서류 대행·음식 나눔).

**6장 AI 개인 분석**
- `GET /api/me/insights`(USER만) → `{given:{count,hours,byCategory[{category,count,pct}],bySlot{오전,오후,저녁},topSlot,ratingAvg,ratingCount,topTags}, received:{…}, rating:{count,avg,distribution[★1..★5],topTags,recentComments,style(후한 편|꼼꼼한 편|균형 잡힌 편),tagRate}, activityText, ratingText, source, provider?}`.
- `POST /api/me/care-plan`(USER만) `{plans:[{category, perWeek(0.25 단위, 0.25=월 1회 ~ 7), hours(0.5 단위, 0.5~8)}](1~6개)}` → `{summary{usable, available, balance, hold, earnedSelf, canUseSelf, selfUseMin, needToUnlock, chosen, chosenWeeks, chosenSpan}, result{usable, plans[{category, perWeek, freq, hours, weight, costPerSession, weeklyCost, sessionsAffordable, weeksAlone, spanAlone}], weeklyCost, monthlyCost, weeks, months, span}, ifUnlocked(본인 사용 조건 전이면 잔액 기준 결과, 아니면 null), candidates[3]{label, plans, weeklyCost, weeks, span}, text, pick[], source, weights}`.
  - 1회 차감 = `Rules.earn(hours, 가중치)`(0.5 반올림), 주당 = Σ 1회 × perWeek, 기간 = floor(쓸 수 있는 시간 ÷ 주당) 주, 개월 = 주 ÷ 4.345. 쓸 수 있는 시간은 B-33(직접 적립 10시간 미만이면 선물받은 시간만) 반영. 오류 400 `PLAN_REQUIRED`·`TOO_MANY_PLANS`·`INVALID_CATEGORY`·`INVALID_FREQUENCY`·`INVALID_HOURS`.
- 화면: 마이 → "AI 분석"(`/my/insight`), 시간 통장 → "내 봉사 플랜"(`/bank/plan`).

**AI 공통**: 새 글쓰기는 `ai/InsightAi`가 `AiClient`가 고른 `LlmProvider`(gemini/anthropic)를 그대로 쓴다(`AiClient`는 바꾸지 않음). 실패·키 없음이면 호출한 쪽이 계산한 규칙 문장을 돌려주고 `source:"rule"`.

### 7.11 2026-11-09 회의 반영 — 홈·공고·단골·위치 공공 서비스·동네 소식 (`docs/meetings/2026-11-09-회의반영.md` 1·2장, O2·O3)
**클래스 규칙 변경** — (2026-11-10) 동네 클래스 삭제로 지웠다(주제 두 가지·4명·기관 장소·평등 정산 `Rules.CLASS_KINDS`/`classTeacherEarn` 모두 없음).

**U3 내가 자주 받는 도움** — `GET /api/me/top-categories` → `{top:[{category,count,score}], order:[6개 카테고리], basis}`. 내가 받는 쪽인 요청(거절·취소·만료 제외, DONE은 2점, 그 밖 1점)과 내가 올린 NEED 공고(1점)로 센다. 홈 "내가 자주 받는 도움", 공고(봉사 가능) 필터 칩 순서(★)와 목록 정렬(같은 순위 안에서는 기존 정렬 유지)에 쓴다. 공고 화면은 `?cat=` 쿼리로 칩을 미리 고를 수 있다.

**U7 수동 단골** — 테이블 `regular_marks(user_id, volunteer_id, created_at)`, 한 쌍에 하나.
- `GET /api/regulars/{volunteerId}/status` → `{volunteerId, doneCount, auto(DONE≥3), manual, regular, canMark}`. `canMark`=사용자 계정이고 그 봉사자와 끝낸 활동이 1~2번이며 아직 수동 등록 전. *(41판: `canMark`는 활동 횟수와 상관없다. `removed`·`removedByThem` 추가 → 7.17 ④)*
- `POST /api/regulars/{volunteerId}`(끝낸 활동 0번이면 409 `NO_DONE_ACTIVITY`, 나 자신 400 `SELF`) / `DELETE /api/regulars/{volunteerId}` → 위 status. **(41판에서 바뀜: 활동이 없어도 등록되고 `NO_DONE_ACTIVITY`는 없다. 해지는 양쪽 모두 → 7.17 ④)**
- `GET /api/regulars` 항목에 `auto`, `manual`, `doneCount` 추가(자동 3회 + 수동 합침). 수동 단골에게도 단골 요청(REGULAR)을 보낼 수 있다(`Views.isRegular`).

**U8 내 위치로 공공 서비스 찾기** — `POST /api/ai/public-services/nearby {question?, lat?, lng?, area?}`. 좌표가 있으면 소수 둘째 자리(약 1km)로 줄여 LLM 프롬프트에 넣고, 결과는 **DB에 저장하지 않는다**. 응답 `{answer, services:[{name,target,content,applyHow,contact,where,aiGenerated}], source:'ai'|'rule', aiGenerated, notice, usedLocation, saved:false}`. AI 결과면 `notice`="AI가 찾은 정보예요 · 꼭 기관에 확인하세요"를 화면에 표시. 키가 없거나 실패·좌표 없음이면 DB 공공 서비스(지역 + 질문, 질문 없으면 돌봄·안부·병원 등 기본 주제)로 대체(`source:'rule'`). 좌표 범위 밖이면 400 `INVALID_LOCATION`. 좌표·질문은 로그에 남기지 않는다. 화면은 버튼을 누를 때만 브라우저 위치 권한을 묻고, 거부하면 프로필 동네 기준으로 찾는다.

**O2 대리 공고 AI로 정리하기** — `POST /api/ai/tidy-post {text, elderId?}` → `{title, detail, caution, categories, piiRemoved, source}`. 입력에 있는 사실만 다듬고 전화번호·계좌·상세 주소는 지우며, 주의사항에는 병명 대신 행동 문장만(서버가 AI 결과에도 다시 적용). `elderId`는 내가 담당하는 대상만(403). 규칙 모드는 카테고리 + 장소 낱말로 짧은 제목(예 "병원 동행 (정형외과)"). 화면은 정리 결과를 고친 뒤 대화로 날짜·시간을 채우고, 올릴 때 정리한 제목·내용·주의사항을 쓴다(기존 대화형 작성 유지).

**2장·O3 동네 소식** — 테이블 `org_events`, `event_applications`. 아래 탭 "클래스" → "동네 소식"(`/news`, 기관 소식만 — 2026-11-10 사용자 클래스 탭 삭제, 예전 `/cls*` 주소는 `/news`로, `/o/space`는 담당자 소식 관리 `/o/events`로 넘김).
- `GET /api/events`(열린 소식, 날짜 지나지 않은 것, 날짜 순) · `GET /api/events/{id}` → `{id, orgId, orgName, title, content, date, startHour, place, capacity(0=제한 없음), applied, applyType:'APP'|'LINK', url, preview:{title,image,description}|null, status:'OPEN'|'CLOSED', myApplied, full, owner, applicants?(우리 기관 담당자에게만 [{userId,name,ageGroup,appliedAt}])}`. *(41판: `capacity, applied, myApplied, full, applicants` 삭제, `applyType`은 늘 `LINK`. 한 목록은 `GET /api/news` → 7.17 ⑧)*
- `POST /api/events`(담당자만) `{title, content, date, startHour, place, capacity, applyType, url?, ogTitle?, ogImage?, ogDesc?}`. LINK면 url 필수(`URL_REQUIRED`). 저장 전에 url 형식·내부 주소 검사(`URL_NOT_ALLOWED`). 화면에서 받은 미리보기를 같이 보내면 그대로 저장, 없으면 서버가 한 번 가져와 본다(실패해도 글은 올라감). *(41판: `url` 필수, `capacity`·`applyType`은 받지 않는다)*
- `POST /api/events/{id}/apply`(사용자, APP만 — LINK는 400 `EXTERNAL_APPLY`, 정원 409 `EVENT_FULL`, 중복 409 `DUPLICATE`, 마감 409 `EVENT_CLOSED`) · `/cancel` · `/close`(우리 기관 담당자) · `GET /api/org/events`(우리 기관 소식, 신청자 포함). 신청하면 올린 담당자에게 알림 `EVENT_APPLY`(링크 `/news/{id}`). **(41판에서 삭제: `/apply`·`/cancel`과 알림 `EVENT_APPLY`. 신청은 기관 페이지 링크로만 → 7.17 ⑧)**
- `POST /api/link-preview {url}` → `{url, title, image, description, siteName}`(저장 안 함, 사용자·담당자). **SSRF 방지**: http/https만, 80·443 포트만, 아이디·비밀번호 포함 주소 거부, localhost·*.local·*.internal 거부, 이름을 풀어 나온 **모든** 주소가 사설(10/8·172.16/12·192.168/16)·루프백·링크로컬(169.254 — 클라우드 메타데이터 포함)·0.0.0.0/8·CGNAT(100.64/10)·멀티캐스트·IPv6 ULA(fc00::/7)·fe80::/10·IPv4 매핑 주소면 거부(400 `URL_NOT_ALLOWED`). 리다이렉트는 자동으로 따르지 않고 직접 따라가며 매번 다시 검사(최대 3회), 연결·응답 3초, 본문 512KB까지만, HTML이 아니면 읽지 않음, 시스템 프록시 안 씀. `og:title`→없으면 `<title>`, `og:image`(상대 경로는 절대 경로로, http/https만), `og:description`/`description`. 남은 위험: 검사와 연결 사이 DNS 응답이 바뀌는 경우(DNS 리바인딩)는 완전히 막지 못한다 → 운영 때 나가는 요청을 프록시로 제한 권장.
- 시드: 행복동 주민센터 소식 4개(앱 신청 2, `https://example.org/…` 외부 링크 2 — 시드에서는 미리보기를 가져오지 않음). *(41판: 시드 소식은 모두 링크 방식)*

**화면(서버 계약 없음)** — U2 홈 교육 배너 → 홈 위 빠른 메뉴 아이콘(교육 n/4단계, 수료 전 빨간 점) · U4 "응답 필요"·"다가오는 일정(확정된 내 봉사/받는 일정)" 가로 슬라이드(스크롤 스냅·스와이프·점, 카드 누르면 상세) · U5 공고 화면 아래 "봉사 요청"(NEED 작성) / "도움 가능"(OFFER 작성) 버튼, 작성 화면 제목도 같게("공고 올리기" 문구 교체) · U6 홈 "도움 받기 / 봉사하기" 전환(브라우저 `localStorage` `ts-home-mode`, 막히면 기본 받기), 고른 쪽 카드가 먼저 · U9 QR 처음 쓸 때 한 줄 설명(`ts-qr-intro-seen`, 요청 시작·종료 QR 찍기 시트). *(41판: 교육 n/4단계·수료 전 빨간 점은 없다. 빠른 메뉴 "첫 활동 연습")*


### 7.12 LLM 추가 A — 요청·매칭·접근성 (2026-11-10)
**공통**: 숫자·순위·규칙 판정(빠진 항목, 추천 점수, 미매칭 근거, 의도별 답, 숫자 읽기)은 코드, LLM은 문장 다듬기·의도 분류만. 수락·취소·공고 수정 같은 결정·실행은 하지 않는다. 새 AI 클래스는 모두 `LlmJson`(→ `AiClient.provider()`)을 쓰고 `AiClient`는 바꾸지 않았다. 키가 없거나 실패·형식 이상이면 코드 결과(`source:'rule'`, 화면 "AI 키 없음 · 규칙 모드"). LLM 결과에 코드에 없는 숫자가 섞이면 버리고 코드 문장을 쓴다. **비용**: 화면을 열 때 부르지 않고 이벤트 때 1번 → DB 저장 후 재사용(아래 저장 칸).

| # | 엔드포인트 | 저장 칸 | 언제 LLM을 부르나 |
|---|---|---|---|
| 1 빠진 정보 점검 | `POST /api/ai/post-check` | 없음(응답만) | 요청서 카드가 나올 때 1번(빠진 항목이 있을 때만) |
| 2 추천 이유 | 요청 생성(`POST /api/requests`) 안에서 | `requests.match_reason` | 요청 생성 때 1번. 목록 카드는 코드 문장(LLM 없음) |
| 3 고치기 제안 | 스케줄러 + `GET /api/posts/{id}/fix-tips`, 공고 상세 `fixTips` | `posts.fix_tips_json`, `posts.fix_checked_at` | 공고당 1번(등록 24시간 뒤 요청·제안 0건) |
| 4 쉬운 말 | `GET /api/posts/{id}/easy` | `posts.easy_text` | 공고별 처음 들을 때 1번(공고 수정하면 지움) |
| 5 음성 비서 | `POST /api/ai/assistant` | 없음 | 질문마다 의도 분류 1번(답은 코드) |
| 6 전화 알림 문장 | 알림 생성 안에서, `GET /api/me/voice-preview` | `notifications.voice_text` | `voiceAlert` 사용자에게 알림이 생길 때 1번 |

**1. 요청서 빠진 정보 점검** — `POST /api/ai/post-check {draft:{categories[], title, detail, caution, note}, elderId?}` → `{questions:[{key, label, text, choices[], target:'detail'|'caution'}](최대 2개), covered[], skippable:true, source}`. 체크리스트(`PostCheckAi.CHECKLIST`, 중요한 순): 동행=만날 곳·이동 방법 / 장보기=무거운 짐·살 물건·계단 / 집안일=엘리베이터·계단·반려동물·준비물 / 생활 정리=무거운 짐·계단·반려동물 / 말동무=만날 곳·반려동물 / 디지털=기기 종류·만날 곳. 글(제목·내용·주의사항·메모, 담당자 대리면 대상 기본 주의사항 포함)에 해당 낱말이 있으면 묻지 않는다. LLM은 질문 문장만 바꾼다(항목 수·선택지는 코드). `elderId`는 내 담당만(403). 화면: 대화형 작성(`/write/need`, 담당자 대리 포함) 요청서 카드 아래, 말로 요청하기(`/voice`) 요청서 카드 아래(큰 글씨). 선택지·직접 적기로 답하면 `target`에 따라 "라벨: 답"을 내용(detail) 또는 주의사항(caution → note "방문 시 주의: …")에 붙여 올린다. "건너뛰기" 가능.

**2. "왜 이 봉사자?"** — 점수(`MatchReasonService.facts`): 단골 +4, 그 카테고리 DONE 횟수 ×0.5(최대 10회), 같은 동 +3(가까운 동 +1.5), 공개 평가 3개 이상·평균 4.0 이상이면 (평균−3.5)×2, 범죄경력 확인 +1, 안전 교육 수료 +1. 근거 문장은 중요한 순 3개(예 "단골 · 동행 11회 · 같은 동네"). 요청 생성 때 LLM이 한 줄로 다듬어(숫자 같아야 채택) `requests.match_reason`에 저장, RequestView `volunteer.matchReason`. 공고 목록·상세의 봉사자 공고(OFFER, 남의 공고, 사용자 계정)에는 `matchReason`(코드 문장)·`matchScore`(정렬은 바꾸지 않음). 서버가 켜질 때 이유가 빈 진행 중 요청은 코드 문장으로 채움(LLM 없음). 화면: 공고 카드·공고 상세 봉사자 줄, 받은 제안 목록(`ReqRow`, 봉사자 본인 제외), 요청 상세 봉사자 소개 카드(`request.jsx`에 `MatchWhy` 한 줄만 추가). *(41판: "안전 교육 수료 +1" 가점 삭제)*

**3. 안 맞춰지는 공고 고치기** — 스케줄러(`runAll` 결과 `unmatchedTips`): OPEN·날짜 안 지남·등록 24시간 지남·`fix_checked_at` 없음. 요청·제안이 1건이라도 있으면 `fix_checked_at`만 찍고 끝(다시 보지 않음). 근거(코드): 같은 카테고리 반대쪽 OPEN 공고 수(`oppositeOpen`)·같은 요일(`sameWeekday`)·시간 맞는 것(`overlapTime`)·최근 90일 확정 이후 연결(`recentMatched`)·시간대(오전/오후/저녁)·요일 분포(`bestSlot`, `bestWeekday`). 후보: TIME(많은 시간대로, 또는 범위 앞뒤 1시간 넓히기 — `suggest{startHour,endHour}`), SPLIT(카테고리 2개 이상 — `suggest{categories:[첫째], rest[]}`), DATE(많은 요일 — `suggest{date, weekday}`), EDIT(내용 15자 미만). LLM은 후보 중 1~3개를 골라 문장만 다듬고(후보 밖 행동·근거 밖 숫자는 버림) `suggest` 값은 코드 값. 저장 `{tips, evidence, source, createdAt}` → 공고 주인(독거노인 건은 담당자)에게 알림 `POST_TIPS`(링크 `/post/{id}`). `GET /api/posts/{id}`(주인·담당자, OPEN일 때) `fixTips`, `GET /api/posts/{id}/fix-tips`(남은 403). 공고를 고치면 `fix_tips_json`을 지워 카드를 숨기고 다시 만들지 않는다. 화면: 공고 상세 "24시간 동안 요청이 없어요 · 이렇게 바꿔 보세요" 카드, 버튼 → `/post/{id}/edit?tip=TIME&startHour=&endHour=` · `?tip=SPLIT&cats=&rest=` · `?tip=DATE&date=`(수정 화면이 값을 미리 넣고 "저장"을 눌러야 바뀜).

**4. 쉬운 말로 듣기** — `GET /api/posts/{id}/easy` → `{postId, text, cached, source:'cache'|'ai'|'rule', lang:'ko-KR', rate:0.9}`. 코드 문장(`EasyTextAi.base`): "{이름} 님이 {십일월 십팔일 수요일} {오후 한 시}부터 {다섯 시} 사이에 {쉬운 카테고리 말}을 도와줄 수 있어요."(도움 요청은 "…도와줄 이웃을 찾고 있어요. 두 시간 정도 걸려요."), 독거노인은 가린 이름, 열두 시는 "낮 열두 시". LLM이 말투만 다듬되 숫자를 쓰거나 시각·요일 표현이 빠지면 버림. 화면: 공고 카드(작은 버튼, 눌러도 상세로 안 넘어감)·공고 상세(문장 함께 표시) "🔊 쉬운 말로 듣기" → `speechSynthesis`(ko-KR, rate 0.9).

**5. 음성 비서 "물어보기"** — `POST /api/ai/assistant {text}` → `{intent, date, answer(화면), speech(숫자 한글 읽기), actions:[{label, link, confirm, kind?, requestId?}], items[], executed:false, source}`. 의도 `BALANCE|TOMORROW|NEXT|CANCEL|REGULAR|NOTI|CREATE|UNKNOWN`(LLM, 모르는 값이면 규칙 `AssistantAi.ruleIntent`), 날짜는 `KoreanParser.parseDate`("목요일", "내일", "11월 20일"). 답은 코드: 잔액(`BankService.summary` usable·hold·B-33), 다가오는 일정(VOL_ACCEPTED·CONFIRMED·IN_PROGRESS, 내가 당사자·담당자), 단골(`Views.regularsOf`), 알림(안 읽은 수·최근 제목). **CANCEL·CREATE는 실행하지 않고** 요청 상세(`/req/{id}`, 담당자 `/o/reqs/{id}`) 또는 `/voice`·`/write/need` 링크에 `confirm:true`만 준다(시작 24시간 안 봉사자면 경고 문구). 화면: `/ask`(사용자·담당자) — 마이크(공용 음성 입력)·글·예시 질문, 답을 소리로 읽기(rate 0.9), 확인 버튼은 확인 시트 뒤 화면만 연다. 진입: 홈 빠른 메뉴 아래 "물어보기" 줄, 말로 요청하기 화면 "궁금한 건 물어보기 ›".

**6. 음성 전화 알림 문장(B-42)** — `NotificationService.send`: 받는 사용자가 `voiceAlert`이면 `VoiceTextAi`가 "시간 적금입니다. …" 한두 문장(LLM은 말투만, 원문에 없는 숫자면 버림) → `KoreanSpeech.speakable`로 숫자를 한글 읽기("11/18 14:00 · 3.0시간" → "십일월 십팔일 오후 두 시, 세 시간")해 `notifications.voice_text`(Notification JSON `voiceText`)에 저장, `[VOICE-MOCK]` 로그에 `text="…"`로 남김(전화는 여전히 모의). `GET /api/me/voice-preview` → `{text, sample, notificationId, title, voiceAlert}`: 최근 알림의 전화 문장(없으면 그때 1번 만들어 그 알림에 저장, 알림이 없으면 예시). 화면: 마이 → 알림 설정 "AI 음성 안내 미리 듣기"가 이 문장을 읽는다.

### 7.13 LLM 추가 B — 활동·메시지·시간 통장 (2026-11-10)
**공통** — 새 클래스 `ai/ActivityAi`(AI 글쓰기, `AiClient`가 고른 `LlmProvider` 그대로 사용, `AiClient`는 바꾸지 않음) + `ai/ActivityRules`(코드 판정) + `service/ActivityAiService` + `web/ActivityAiController`. 숫자·규칙 판정은 코드, LLM은 정리·설명·제안만, 수락·제재·적립 조정 같은 결정은 하지 않는다. **이벤트 때 한 번** 부르고 테이블 `ai_notes`(kind·ref_id·ref_key 하나에 한 행, `body_json`, `source`, `provider`, `fingerprint`)에 저장해 다시 쓴다. 키가 없거나 실패하면 규칙 결과(`source:"rule"`, 화면은 기존 `.ai` 카드 + "AI 키 없음 · 규칙 모드"). 훅은 `RequestService`에 한 줄씩(확정·메시지 저장·시간 조정·난이도 이의) — 훅 오류는 본 흐름을 막지 않는다. 시드는 규칙 모드로만 돈다.

**B-1 방문 전 브리핑** — 요청이 CONFIRMED가 될 때 만든다(예전 확정 건은 처음 열 때 한 번). `GET /api/requests/{id}/briefing`(봉사자만, 403; 확정 전 409 `NOT_CONFIRMED`) → `{lines[3~6], basics:{alias, dong, content, caution, when}, category, source, createdAt}`. 재료는 기본 정보(가린 별칭·동·도움 내용·주의사항·일시)뿐, **지난 전달 메모·연락처·주소는 넣지 않는다**. 카테고리별 규칙 표(동행=진료 결정은 본인·부축까지, 장보기=영수증·잔돈, 디지털=비밀번호 직접 입력, 말동무=천천히·반복도 처음처럼, 생활 정리·집안일)와 교육 규칙(신체 돌봄·금전·연락처 금지)은 코드 표 `ActivityRules.CATEGORY_RULES`/`EDU_RULES`. AI는 이 재료로 체크리스트 문장만 쓰고, 전화번호·주소가 든 줄은 서버가 버린다. 화면: 요청 상세(봉사자, 확정 뒤) 카드, 홈 다가오는 일정 카드 "방문 전 브리핑" 버튼 → 시트.

**B-2 메시지 안전 감지 · B-3 빠른 답장·일정 변경** — 메시지 저장 때 **메시지당 1회** 코드(기존 `CONTACT` 정규식 + 돌려 말하는 연락처·금전/선물·앱 밖 만남·부적절 언행 낱말) + AI 분류를 한 번에 하고 저장한다. AI는 수준을 올릴 수는 있어도 코드가 잡은 경고를 내리지 못한다. **자동 차단·제재·경고 횟수 증가 없음**(표시만). 같은 호출에서 받은 사람용 답장 후보 2~3개(연락처·돈 문장, 40자 넘는 후보는 버림)와 일정 변경 감지(코드 정규식 우선: `DELAY`/`EARLY`(30분 이상)·`TIME`·`DATE`·`CANCEL`, 코드가 못 잡으면 AI 결과)를 같이 저장.
- `GET /api/requests/{id}/message-assist`(당사자) → `{warns:[{messageId, level:'caution'|'warn', reasons[], warnText, source}](내가 받은 메시지만), replyTo, replies[], schedule:{kind, text, deltaMin, newStartHour}|null(VOL_ACCEPTED·CONFIRMED에서만), source, autoChange:false}`. 마지막 메시지가 내 것이면 답장 후보 없음. 저장본이 없는 예전 메시지는 마지막 것만 AI, 나머지는 규칙으로 한 번 만들어 저장.
- 화면: 받은 메시지 아래 경고 띠 + "신고하기"(기존 신고 시트), 입력칸 위 "시간 변경 요청" 카드(일정 취소하기 = 기존 취소 흐름, 새 시간으로 다시 요청 = 받는 쪽만 `/req/new?to=봉사자`) — 시간은 자동으로 바뀌지 않음. 답장 버튼은 입력칸에 채우기만(보내기는 사용자).

**B-4 시간 조정·난이도 이의 판단 보조** — 시간 조정 요청(`POST /requests/{id}/adjust`)·난이도 이의(`POST /requests/{id}/appeal`) 때 1회 계산(조정 시간·사유가 바뀌면 다시). `GET /api/judge-assist?kind=ADJUST&id={요청 id}`(받는 쪽·담당자·운영자) / `kind=APPEAL&id={이의 신청 id}`(운영자) → `{table:[{item, planned, actual, flag:'ok'|'check', note}], pro[], con[], summary, reason, decision:"운영자(또는 받는 분)가 정해요", source}`. 표는 코드: 예정 시각·시간 vs 체크인/아웃, 조정 요청 시간과 체크 기록 차이(30분 기준), 체크 방법(버튼이면 check), 카테고리·등급, 일지·사유 단서 낱말, (이의) 카테고리 기본 등급·요청 등급·일지로 다시 본 `RuleEngine.difficulty` 등급·받아들이면 차액. AI는 "타당해 보이는 이유/다시 확인할 점" 요약만. 결정 버튼은 그대로. 화면 컴포넌트 `frontend/src/ui/ActivityAi.jsx`의 `JudgeAssist`를 요청 상세(받는 쪽 시간 조정 카드)와 운영자 승인 > 난이도 이의 카드에 끼운다.

**B-5 적립 목표 코치(거꾸로 플랜)** — `POST /api/me/goal-plan {category, perWeek(0.25~7), hoursPer(0.5~4, 0.5 단위), years(1~30), yearsLater(1~50) | startAge}` → `{plan:{category, weight, freq, perSession(=earn(hoursPer, 가중치)), sessions, totalNeeded(=perSession×perWeek×52×years, 0.5 단위), have(=쓸 수 있는 잔액 available), remaining, months, monthlyTarget(0.5 단위 올림), weeklyTarget, startAge, suggest:{topCategory, topSlot, avgHours, suggestCategory, suggestPerMonth …}}, text, tips[], posts:[PostCard 2~3], source}`. `startAge`는 프로필 나이대로 어림(예 60대 → 65살, 없으면 400 `AGE_UNKNOWN`). 근처 공고 후보(열린 NEED, 오늘 이후, 같은 동·잘하는 카테고리·시간대 순 5개)는 코드가 고르고 AI는 번호만 고른다. 같은 입력·같은 활동이면 저장본(`ai_notes` GOAL) 재사용. 화면: 시간 통장 → 내 봉사 플랜 탭 "나중에 받고 싶은 것"(`/bank/plan?tab=goal`, 계산 버튼을 누를 때만 호출).

**B-6 월별 결산 한 줄 설명** — `GET /api/bank/month-note?month=YYYY-MM`(기본 이번 달) → `{month, numbers:{earned, used, giftIn, bonus(가중치로 더 쌓인 시간), byCategory:[{category, earned, baseHours, count, weight, bonus}], topBonusCategory, topBonus, topBonusWeight}, text, suggestion, source, createdAt}`. 숫자는 통장 거래(HOLD/RELEASE 제외, `monthly()`와 같은 기준)에서 코드가 센다. 월 1회 생성 저장(`ref_key`=YYYY-MM), 그 달 거래 수·합·마지막 id가 바뀌면 다시. AI 문장에 코드에 없는 숫자가 있으면 규칙 문장을 쓴다. 화면: 시간 통장 월별 결산 카드 아래.

### 7.14 LLM 추가 C — 담당자·운영자·안전 (2026-11-10)
**공통**: 숫자·규칙 판정(날짜·시각·반복·심각도·신호 개수·집계)은 코드, LLM(`ai/CareMemoAi`·`OrgAi`·`AdminAi` → `LlmJson` → `AiClient.provider()`)은 정리·설명·제안·초안만. 키가 없거나 실패하면 규칙 결과(`source:'rule'`, AI면 `provider` 추가). 이벤트 때 한 번 만들어 저장(`care_notes`, `ai_results`)하고 다시 쓴다. 등록·발송·제재·보류는 하지 않는다(사람이 기존 API 버튼으로). 진단 문장 금지(`CareMemoAi.DIAGNOSIS`에 걸리는 문장은 버림), 병명·약 이름은 옮기지 않는다. LLM에 넘기는 글은 전화번호·계좌·주소를 지우고(운영자 쪽은 사용자 이름·대상 별칭도 "○○"로) 보낸다.

**C1 전달 메모 정리 + 생활 변화 신호**
- `RequestService.submitLog` 끝에서 `CareSignalService.onLog(r)` 한 번(실패해도 일지 제출은 그대로). 전달 메모가 있거나 일지에 신호 낱말이 있으면 `care_notes`에 저장(요청 한 건에 하나, 다시 내면 덮어씀): `next`(다음에 챙길 것) / `hospital`("병원 안내 있음 (다음 진료·검진 일정 · 약 먹는 시간 안내)"처럼 무엇에 대한 안내인지만) / `life`(생활 변화 문장) / `signals`.
- 신호 이름은 정해진 5개만: `식사 거름`·`외출 감소`·`기운 없음`·`잠 못 잠`·`말수·기분 변화`. 코드 키워드 ∪ LLM 분류(목록 밖 이름은 버림).
- **확인 권장**: 같은 받는 사람(대상 또는 사용자)의 최근 메모 3건(45일 안) 중 신호 있는 메모가 **2건 이상**. 처음 켜질 때 독거노인이면 담당자에게 알림 `CARE_SIGNAL`(링크 `/o/elders/{id}`, "진단이 아니라 안부 확인을 권해요"). 일반 사용자는 알림 없이 **공유 동의한 가족 리포트**(`subject=family:`)에만 보인다(본인 `me` 화면에는 넣지 않음).
- `GET /api/reports/recent` 추가: 항목 `careNote{next[], hospital, life[], signals[], source}`, 최상위 `signal{flagged, count, labels[], since, recent[{requestId,date,signals}], window, notice}`(대상·가족 리포트만, 그 밖은 null).
- `GET /api/org/care-signals`(ORG) → 담당 대상별 `[{elderId, alias, flagged, count, labels, since, recent, window, notice}]`.
- 화면: 담당자 홈 "살펴볼 대상"·도움 대상 목록에 노란 "확인 권장" 칩, 대상 상세 위 노란 카드(`.ai.amber`), 최근 활동 리포트 위 노란 카드 + 항목마다 "메모 정리" 상자(원문 전달 메모는 그 아래 그대로).

**C2 통화 메모 → 공고 여러 건** — `POST /api/org/ai/memo-to-posts {elderId, memo(1000자)}`(내 담당·지원 중 대상만, 403/400 `ELDER_REQUIRED`·`MEMO_REQUIRED`·`MEMO_TOO_LONG`·`ELDER_ENDED`) → `{drafts:[{segment, title, detail, note(주의사항), categories, date|null, startHour, endHour, estHours, area, repeat{weekly,weeks}, repeatLabel, confidence{date,time,category}, piiRemoved, filter{level,reason}, missing[], checked, elderId}], count, piiRemoved, source, notice, elder}`. **저장하지 않는다.**
- 규칙 모드: 쉼표·줄바꿈·"그리고"로 나누고 앞의 대상 이름을 뺀 뒤 구절마다 `KoreanParser`(날짜·시각·카테고리)·`VoiceSafety.repeat`(매주 → 4주 반복)·`TidyAi.ruleTitle`. 시각이 "오전/오후/저녁"뿐이면 범위(9–12/13–17/17–20), 활동 시간 기본 동행 2·말동무·디지털 1·그 밖 1.5. 예문 "이순례 님 매주 목요일 오전 말벗, 다음 주 화요일 오후 정형외과 동행, 냉장고 정리 한 번" → 3건(셋째는 날짜 없음 → `missing:['date']`, `checked:false`). *(41판: 초안의 `repeat`·`repeatLabel`은 남아 있지만 등록 때 서버가 무시해 1건만 올라간다)*
- AI 모드: LLM은 구절(segment)·제목·내용·주의 문장·카테고리만 주고, 날짜·시각·반복은 코드가 그 구절에서 다시 읽는다. 금지 필터(`RuleEngine.filter`)가 BLOCK이면 `checked:false` + 사유.
- 화면 `/o/memo-posts?elder=`(담당자 홈 버튼·대상 상세 버튼): 대상 칩 → 메모(마이크) → 초안 카드(제목·카테고리·날짜·시간·반복·할 일·주의사항 수정, 체크) → "체크한 N건 등록" = 카드마다 기존 `POST /api/posts`(금지 필터·검사 그대로, 실패 사유는 카드에).

**C3 담당자 주간 브리핑** — `GET /api/org/briefing?refresh=` → `{week{start,end}, counts{elders, schedule, unmatched, unmatchedUrgent, logPending, finalPending, flagged, blocksNew, blocksTotal}, elders[{elderId, alias, items[이번 주 일정], flagged, signalLabels}], unmatched[{postId, alias, title, date, label, daysLeft, pendingRequests, urgent(3일 안)}], logPending[], finalPending[](VOL_ACCEPTED 또는 SENT 제안), flags[], blocks{recent(7일), total}, summary{lines[3~5], todo[], source, provider?}, generatedAt, cached, reason('first'|'cached'|'weekly'|'change'|'refresh')}`.
- 숫자는 매번 코드가 센다. 요약 글만 `ai_results(ORG_BRIEFING, 담당자 id)`에 저장하고 지문(주 월요일 + 확인 권장 대상 + 7일 안 받지 않기 + 3일 안 미매칭 공고)이 같으면 다시 쓴다 → 주 1회 + 큰 변화 때 다시 생성, "다시 만들기"는 `refresh=true`. 할 일 순서(규칙): 최종 수락 → 일지 확인 → 확인 권장 대상 안부 전화 → 날짜 가까운 미매칭 공고 단골 요청.
- 화면: 담당자 홈 맨 위 `.ai` 카드(숫자 칩·요약·오늘 할 일·확인 권장 대상 바로가기·다시 만들기·규칙 모드 표시).

**C4 맞춤 역할극 상대** — `GET /api/admin/ai/persona-cases` → `{cases[{key(money|contact|body|privacy), label, flag, fitCategory, count, reports, messages, memos, samples[≤3, 이름·연락처 지움]}], period:'최근 180일', notice}`(신고 유형·내용, 경고 붙은 메시지, 전달 메모를 코드 낱말로 셈). `POST /api/admin/ai/persona-draft {caseKey?}` → `{draft{name(가명 "박○자 님" 강제), initial, ageLabel, fitCategory, trait, goal, opening, turns[3]{options[4]{t,q(good/ok/poor/bad),f?(bad만: 연락처|금전·선물|신체 돌봄|개인정보)}, next}, active:false}, case, cases, source, notice}`. LLM 결과가 형식(3턴·good·bad 포함)에 맞지 않으면 규칙 템플릿(money/contact → 장보기 "박○자 님", body → 동행 "최○식 님", privacy → 디지털 "윤○희 님"). **등록 안 함** — 화면 "가상 상대 관리 → 실제 사례로 만들기 → 초안 만들기" 후 기존 상대 폼에서 고쳐 `POST /api/admin/personas`.

**C5 신고 정리·우선순위** — 신고 접수(`POST /api/reports`) 직후 한 번 정리해 `ai_results(REPORT_TRIAGE)`에 저장. `GET /api/admin/ai/report-triage` → 처리 전 먼저 → 심각도(높음→보통→낮음) → 접수 최신순 `[{reportId, severity, severityLabel, rank, codeSeverity, aiSeverity, reasons[], summary, suggestion(참고), similar[{id,type,action,result,createdAt,sameTarget}](같은 유형·대상의 처리 끝난 건 ≤3), status, createdAt, notice, source}]`. `POST /api/admin/ai/report-triage/{id}` 다시 정리.
- 심각도 코드 규칙(바닥): 내용에 신체 접촉(만지·껴안·추행 등)·금전 요구(돈을 달라·빌려 달라·송금·계좌로)·위협(협박·가만 안 둬)이 있거나 유형 "신체 접촉"이면 **높음**, 연락처·카톡·노쇼·욕·반말 등 또는 유형 연락처·노쇼·부적절한 언행이면 보통, 그 밖 낮음(같은 대상 신고 2건 이상이면 낮음→보통). LLM 심각도는 **올리기만** 가능. 제재 버튼·상태는 그대로(자동 제재 없음).
- 화면: 운영자 신고·점검 목록을 이 순서로 정렬하고 심각도 칩·한 줄 요약, 처리 시트 위에 "신고 정리" 카드.

**C6 이상 적립 패턴 설명** — `GET /api/admin/ai/anomalies?refresh=` → D-06과 같은 묶음마다 `{key, requestIds[], evidence{count, avgHours, totalEarned, slots{오전,오후,저녁}, topSlot, avgLogLength, sameLogMax, checkMethods, spanDays, avgGapDays, category, held}, text, checks[], notice, source}`. 근거는 코드, 설명은 LLM(단정 금지). 묶음의 요청 id가 같으면 저장한 설명(`ANOMALY_NOTE`)을 다시 씀. 보류·해제는 기존 버튼(`/api/admin/anomalies/{id}/hold|release`).

**C7 문의 답변 초안** — `POST /api/admin/ai/inquiries/{id}/draft?refresh=` → `{draft, used[근거 번호], basis[{kind(rule|notice|bank|request), label, text}], autoSend:false, notice, source}`. 근거 = 이용 규칙 요약(계약서 3장, `OpsSafetyService.RULES` 코드 상수) 중 문의와 맞는 것 + 맞는 공지 + 그 사용자의 통장 최근 5건 + 관련 요청 상태. 규칙 초안은 적립 문의에서 "일지 미제출"/"확인 대기" 요청을 찾아 안내. **발송하지 않는다** — 화면 문의 답변 시트의 "답변 초안 만들기 → 답변 칸에 넣기" 후 운영자가 고쳐 기존 `POST /api/admin/inquiries/{id}/answer`.

**저장 칸**: `care_notes`, `ai_results`(db/README.md). **데모**: 시드에서 이순례 님 최근 두 건 메모에 식사 거름·기운 없음·외출 감소 표현을 넣어 '확인 권장'과 담당자 알림이 보이게 했다(시드는 규칙 모드).

### 7.15 기관 클래스 추천 (2026-11-10, 동네 클래스 삭제 뒤 대신)
공공기관(주민센터·평생학습관·복지관·도서관·보건소)이 여는 강좌를 모아 사용자에게 추천한다. **신청은 기관 사이트에서(외부 링크)**, 강좌는 시간 통장 시간을 쓰지 않는다(동행 봉사를 부르면 그 동행만 정산). 휴대폰·디지털, 정서·건강처럼 쉬운 강좌와 5060·7080 대상을 앞에 둔다. 환경변수 추가 없음(Gemini 키 재사용). 데이터 넣는 법: `docs/guides/기관클래스-데이터-넣기.md`.

**테이블 `public_classes`** — 세 경로가 한 테이블(`source`): `AI_SEARCH`(AI 웹 검색) · `PUBLIC_DATA`(공공데이터 CSV) · `ORG`(동네 소식 `org_events`를 옮겨 둔 것, `org_event_id`, 추천·목록을 열 때 맞춤 — 마감·삭제되면 `hidden`). 칸: title, org_name, region(시군구·동), place, start_date, end_date, schedule_text, target, fee_text, capacity_text, apply_url, apply_how, image_url, category(디지털/정서·건강/생활/기타 — 제목·내용 낱말로 코드가 분류), description, verified, verified_at, page_title, fetched_at, expires_at, hidden, search_key, lat, lng, raw_json.
- **사용자에게 보이는 조건**: `hidden=false` · `verified=true` · 끝나지 않음(end_date ≥ 오늘, 끝 날짜가 없으면 시작이 30일 안) · `expires_at` 전.

**① AI 웹 검색(Gemini 구글 검색 그라운딩)** — `ai/GeminiGrounding`(새 클래스, `generateContent` body에 `tools:[{google_search:{}}]`, JSON 응답 모드는 쓰지 않고 텍스트로 받음, `groundingMetadata.groundingChunks[].web.uri` 파싱)·`ai/PublicClassAi`(프롬프트·JSON 추출). `AiClient`는 바꾸지 않았다.
- 켜짐 = AI 켜짐 + `AiClient.provider()`가 Gemini + `GEMINI_API_KEY` 있음. Anthropic 공급자·규칙 모드면 꺼짐(`searchAvailable:false`).
- 프롬프트: 지역(시군구·동, 사용자가 동의하면 좌표를 소수 둘째 자리로 줄여 함께) 주변 공공기관 강좌를 `{"classes":[{title, orgName, place, startDate, endDate, schedule, target, fee, applyUrl(원래 페이지 주소), summary}]}`로, 모르는 값은 비우고 지어내지 말 것, 최대 8개.
- **검증(코드)**: `applyUrl` 없는 항목은 버림 → 서버가 `LinkPreviewService.fetch`(7.11의 SSRF 규칙 그대로: http/https·80/443·사설 주소 차단·리다이렉트마다 재검사·3초·512KB)로 그 주소를 실제로 열어 200이고 제목을 얻으면 `verified=true`(`page_title`, 최종 주소를 `apply_url`로), 아니면 `hidden=true`. 끝난 강좌도 숨김. 같은 `search_key`의 예전 AI 항목은 새 결과로 바꾼다. `expires_at`=끝 날짜 다음 날, 없으면 14일 뒤.
- **캐시**: 지역(`search_key`)별 하루 1회(결과 0건이어도). 운영자 "다시 찾기"는 `force`로 다시 부른다.
- 화면 배지: "AI가 찾은 정보 · 신청 전 기관에 확인".

**② 공공데이터 CSV** — `POST /api/admin/public-classes/import` (multipart `file`, 운영자) → `{encoding:'UTF-8'|'CP949', total, imported, updated, skippedEnded, skippedInvalid, mapped:{칸:원래 열 이름}, unmapped:[열 이름]}`.
- 글자 코드: UTF-8(BOM 허용)을 엄격하게 읽어 보고 깨지면 CP949. 따옴표·쉼표·줄바꿈 있는 CSV 지원, 최대 5000줄.
- 열 맞추기(공백·괄호 무시 → 정확한 이름 → 비슷한 이름 포함 규칙): 강좌명·강사명·교육시작일자·교육종료일자·교육시작시각·교육종료시각·교육요일·교육시간·교육대상·교육방법·수강정원·교육비(수강료)·교육장소·교육장도로명주소(주소)·운영기관명·운영기관전화번호·접수방법·홈페이지주소·위도·경도·강좌내용. 나머지 열은 `raw_json`. 강좌명 열이 없으면 400 `CSV_NO_TITLE`.
- 끝난 강좌(종료일 < 오늘, 종료일 없으면 시작 30일 지남) 제외, 강좌명 빈 줄 제외. 교육비 0 → "무료", 숫자 → "12,000원". 주소 → region("서울특별시 금천구 독산동 1" → "금천구 독산동"). `verified=true`. (강좌명+기관명+시작일)이 같으면 새로 넣지 않고 고친다.
- 시드: "(예시)"를 붙인 샘플 7건(행복구 평생학습관 (예시) 등, 주소는 `https://example.org/…` 또는 비움, 초등학생 대상 1건 포함).

**③ 기관 직접 등록** — 동네 소식(`POST /api/events`) 그대로. 추천에는 `source:'ORG'`로 들어가고, 화면은 `/news/{orgEventId}`(기존 동네 소식 상세·앱 신청)로 연다.

**추천** — `GET /api/public-classes/recommend` (USER) → `{items:[항목], source:'ai'|'rule', cached, date, basis:{area, ageGroup, interests}, searchAvailable, categories}`. 항목 = `{id, source, title, orgName, region, place, startDate, endDate, when("이번 주 목요일" 등), schedule, target, fee, capacity, applyUrl, applyHow, imageUrl, category, verified, aiFound, notice, orgEventId, sample, reason, reasonSource:'ai'|'rule', score}`.
- **코드 점수**(상위 12개): 같은 동 +40 · 같은 시군구 +20 · 디지털 +25 / 정서·건강 +20 / 생활 +8 · 관심 분야 +15(프로필 카테고리 + 최근 180일 받은·준 도움 카테고리: 디지털 도움→디지털, 말동무·정서·동행→정서·건강, 생활 정리·장보기·집안일→생활) · 50대 이상이고 대상이 어르신·5060 등 +10, 아동·청소년 대상 −40 · 시작까지 7일 안 +20 / 14일 +12 / 30일 +6 / 진행 중 +8 · 확인됨 +15 · 공공데이터 +5. 같으면 날짜 가까운 순.
- **LLM**: 고른 항목마다 추천 이유 한 줄(프롬프트에는 동네·나이대·관심 분야만, 이름·연락처 없음). 키 없음·실패면 규칙 문장 "같은 동네 · 휴대폰 사용법 · 이번 주 목요일 · 무료".
- **캐시**: 사용자별 하루. 데이터가 바뀌면(CSV·검색·숨김·삭제·기관 소식 추가/마감) 새로 만든다.
- `GET /api/public-classes/{id}` (로그인) → 항목 + `{description, pageTitle, easySummary(소리로 듣기용 쉬운 말 — 코드가 만든 문장), companionText("11월 20일 오후 2시부터 오후 4시까지 ○○에서 하는 '○○' 강좌에 같이 가 주세요 (동행)"), timeNote}`. 보이지 않는 항목은 404(운영자는 볼 수 있음, `hidden`·`searchKey`·`rawJson` 추가).
- `POST /api/public-classes/search {lat?, lng?}` (USER·ORG) — "내 위치로 다시 찾기": 프로필 동네(+좌표)로 ①을 부른다(하루 1회). → `{searched, cached, searchAvailable, region, found, dropped, verified, hidden, message?}`. 좌표 범위 밖 400 `INVALID_LOCATION`.
- `GET /api/public-classes/status` → `{searchAvailable, counts:{AI_SEARCH, PUBLIC_DATA, ORG, visible, hidden}}`.

**담당자** — `GET /api/org/elders/{id}/public-classes` (내가 담당하는 대상만, 아니면 403) → 추천과 같은 모양(대상의 동·나이대·필요 도움으로, 대상별 하루 캐시).

**운영자** — `GET /api/admin/public-classes?source=` → `{items:[항목 + hidden, visible, searchKey, pageTitle, fetchedAt], searchAvailable, counts}` · `POST /api/admin/public-classes/search {region, force?(기본 true)}` · `POST /api/admin/public-classes/{id}/hide {hidden}`(확인 못 한 AI 항목은 보이게 할 수 없음 409 `NOT_VERIFIED`) · `DELETE /api/admin/public-classes/{id}`(ORG는 400 `ORG_EVENT` — 숨기기만).

**화면** — 동네 소식(`/news`) 맨 위 "가까운 기관 클래스"(카테고리 칩 전체/디지털/정서·건강/생활, 카드: 기관명·제목·일정·장소·대상·수강료·추천 이유·출처 배지, 검색 가능할 때 "내 위치로 다시 찾기") · 상세 `/news/class/:id`(신청하기 → 기관 사이트 새 창, 쉬운 말 소리로 듣기, "같이 갈 봉사자 요청" → `/write/need?prefill=…`로 열어 미리 채운 문장으로 초안까지 만들고 등록은 사용자) · 사용자 홈 "이번 주 가까운 클래스" 한 줄 카드 · 담당자 대상 상세 "이 어르신께 맞는 기관 클래스"(카드 → "동행 요청 대신 만들기" `/write/need?elder={id}&prefill=…`) · 운영자 관리 → "기관 클래스"(`/a/classes`: 목록·출처·검증, 숨기기/삭제, CSV 가져오기, 지역 다시 찾기).

### 7.16 역할별 정리판 반영 (2026-11-11)

기준: `docs/prototypes/proto-2026-11-11-역할별정리판.html`, `docs/specs/2026-11-11-역할별-기능목록.md`. 화면별 대조표는 `docs/specs/2026-11-11-프로토타입-대조.md`.

**용어**: 공고 종류 이름이 바뀌었다 — OFFER = "도움 가능"(예전 봉사 가능), NEED = "봉사 요청"(예전 도움 요청). API 값(OFFER/NEED)은 그대로.

**헤더 모드(프론트)**: 사용자 헤더 칩이 "도움 받기 / 봉사하기"를 보여 주고, 누르면 "지금 할 일" 시트에서 바꾼다. 전역 상태(`ModeProvider`, localStorage `ts-mode` = recv|give, 예전 `ts-home-mode` 이어받음)이고 `body[data-mode]`로 색(받기 #EE9A7E *— 7.21에서 바뀜: #6F9CF0* / 하기 #6DB3A0)을 건다. 홈·공고(기본 종류)·내 활동(받는 쪽/하는 쪽 목록)·플래너·요청 상세(제목·머리 배너)가 따라 바뀐다. 서버는 모드를 모른다.

**새 API**

| 메서드·경로 | 권한 | 내용 |
|---|---|---|
| `GET /api/me/planner?month=YYYY-MM` | USER | 캘린더: `items[]`(kind give\|recv\|event\|plan, date, startHour, hours, category, title, who, amount, requestId\|eventId\|postId) — 확정 이후 요청(CONFIRMED~DONE), 신청한 기관 소식, 내 계획. `goal{target, confirmed, planned, left, fromLifePlan}`(target = 생애 플랜의 한 달 시간, 없으면 4). `recommends[]` = 추천 봉사: 열린 NEED 공고 중 오늘~30일, 내 것·이미 요청한 것·계획에 넣은 것 제외, **내 일정과 시간이 겹치지 않는 것만**, 순위 = 같은 동 3 + 잘하는 카테고리 2 + 가능 시간대 2(코드), 각 카드에 `why`. `plans[]`, `text`(안내문: LLM, 없으면 규칙), `source` *(41판: 기관 소식 앱 신청이 없어져 `event` 항목은 나오지 않는다. 계획 줄에 `endHour`·`postStartHour`·`postEndHour`·`planSource` → 7.17 ⑤)* |
| `POST /api/me/planner/picks` `{postIds[]}` | USER | 추천 봉사를 내 계획에 넣는다(열린 NEED 공고만, 내 공고 불가). 제안은 보내지 않는다 — 결정은 사람이 공고에서 버튼으로 *(41판: AI가 빈 시간 후보에서 시작 시각을 고르고 응답에 `made`·`skipped` → 7.17 ⑤)* |
| `DELETE /api/me/planner/picks/{postId}` | USER | 계획에서 뺀다 |
| `GET /api/me/life-plan` | USER | 저장된 조건(없으면 기본: 나이 = 나이대 가운데, 시작 72세, 한 달 4시간, 동행 2·장보기 2·말동무 1)으로 계산한 생애 플랜 |
| `POST /api/me/life-plan` `{age, startAge, monthlyHours, needs{카테고리: 한 달 시간}, save}` | USER | 계산(`save=true`면 조건 저장). 400: 나이 15~90, 시작 나이 > 나이·95 이하, 한 달 0~60 |
| `POST /api/category-suggestions` `{name, reason}` | USER·ORG | 카테고리 추가 건의. 400: 이름 2~20자, 이유 5~300자, 개인정보(`PII_NOT_ALLOWED`), 이미 있는 6종(`CATEGORY_EXISTS`), 같은 사람이 답변 전 같은 이름(`ALREADY_SUGGESTED`). ADMIN 403 |
| `GET /api/category-suggestions/mine` | USER·ORG | 내 건의와 상태·답변 |
| `GET /api/admin/category-suggestions` | ADMIN | `groups[]`(같은 이름 `key`로 묶음: name, count, waiting, status, items[]) 건수 많은 순, `total`, `waiting`, `note` |
| `POST /api/admin/category-suggestions/{id}/answer` `{status, answer}` | ADMIN | status RECEIVED\|REVIEWING\|ADOPTED\|HELD. 같은 이름 묶음 전체에 적용하고 건의자 모두에게 알림(type `CATEGORY_SUGGESTION`). ADOPTED·HELD 는 답변 필수. **카테고리 6종 자체는 바꾸지 않는다**(가중치·금지 규칙이 걸려 있어 운영자 결정 사항) |
| `GET /api/verify-items` | 로그인 | 봉사 인증 항목 5개(phone·id·edu·crime·orientation)의 on·required·locked *(41판: 항목은 3개 — phone·id·crime)* |
| `PUT /api/admin/verify-items` `{items[{key,on,required}]}` | ADMIN | 저장(`app_settings`의 `verify.items`). 휴대폰 본인인증은 항상 켜짐·필수. **지금은 설정 저장·표시만** 하고 실제 봉사 조건(신분증 승인·첫 활동 교육)은 기존 규칙 그대로다 |

**바뀐 API**

- `POST /api/ai/recommend`: 응답에 `regularPosts[]`(단골 추천)·`nearbyPosts[]`(가까운 사람 추천)·`help`가 더해졌다(`posts`는 호환용으로 남김). 받는 쪽 질문이면 단골 = 내 단골 봉사자(자동 3회·수동)가 올린 도움 가능 공고, 돕는 쪽이면 내가 3번 이상 도운 이웃(기관 대상 포함)의 봉사 요청 공고. 두 섹션 모두 순위 = 같은 동 → 가능 시간대(`users.time_slots`) → 거리(코드). 카드마다 `fit{regular, area, time, score}`·`fitText`. 매주 반복 공고는 가장 가까운 날짜 하나만. `help{categories[{category, level, levelLabel, weight}], level, blocked, reason, publicServices[], note}` = 받을 수 있는 도움(금지 요청이면 `blocked=true`와 대신 쓸 공공 서비스).
- `GET /api/events?area=`: 카드에 `area`(기관 이름 → 주소 → 장소에서 처음 나오는 "○○동"), `near`, `distanceKm`. `area`를 주면 그 동만, 안 주면 내 동네 → 가까운 동 → 날짜 순.
- `POST /api/posts`, `PUT /api/posts/{id}`: `elderIssue`(관리 대상 이슈, 300자) — 담당자 대리 공고(`elderId` 있음)에만. 연락처·상세 주소는 400 `PII_NOT_ALLOWED`, 병명은 규칙 문장으로 바꿔 저장. 공고 카드의 `elderCaution`은 이슈가 있으면 이슈, 없으면 대상 등록 때의 주의사항. `elderIssue`는 원문 칸. 그 공고로 생긴 요청 상세의 `elder.caution`도 이슈로 바뀌고 원래 값은 `elder.baseCaution`.
- `GET /api/admin/insights`: `categorySuggestions[{key, name, count, waiting}]`(사용자 건의 묶음 건수)가 더해졌다.

**생애 플랜 계산(코드, `PlannerService.lifeCompute`)**: 모은 시간 = 지금 쓸 수 있는 시간 + 한 달 시간 × 12 × (시작 나이 − 지금 나이). 한 달 사용 = Σ 받고 싶은 도움 시간 × 가중치(기본 1.0 / 수고 1.2 / 책임 1.5 *— 7.21에서 바뀜: 기본·수고만*). 끝 나이 = 시작 나이 + 모은 시간 ÷ (한 달 사용 × 12), 95세까지만. `okTo90`, `need90`(90세까지 모자란 시간), `need90Monthly`(90세까지 가려면 한 달 시간, 0.5 올림), `points[{age, hours}]`(나이별 통장 그래프). **상속은 없다**(`inherit=false`, 가족에게 선물만). 조언 문장만 LLM(없으면 규칙).

**새 테이블**: `category_suggestions`, `life_plans`, `planner_picks`, `app_settings`, 칸 `posts.issue_note` — `db/README.md`.

**데모 데이터**: 카테고리 건의 5건(반려동물 돌봄 3건 묶음 등) — `Proto1111Seeder`.

### 7.17 요구사항 명세서 41판 적용 (2026-11-12)

기준: `docs/requirements/요구사항명세서.txt` 41판(A-01~A-10, B-01~B-67, C-01~C-16, D-01~D-11, 104개), 프로토타입 `docs/prototypes/proto-2026-11-12-명세41판.html`.
대조·적용 기록은 `docs/specs/req41/`(대조 3개, 적용 4개, 재대조 `적용-결과.md`). 이 절은 `main`의 컨트롤러·서비스·엔티티 코드를 직접 확인해 적었다.
이 절과 앞 절(3~7.16)이 다르면 **이 절이 맞다**. 앞 절의 옛 문장에는 "41판" 표시를 붙였다. 앞 절 괄호 속 요구사항 ID는 그 절을 쓴 때의 판 번호라 41판 번호와 다를 수 있다(예: 단골 배지 B-41 → B-42, 평가 B-38·B-39 → B-39·B-40).
**기능명세서(2026-11-12)로 이 절에서 다시 바뀐 곳**에는 아래에 "기능명세서에서 바뀜 → 7.18"을 붙였다(③ 자동 반복, ⑤ 교육 이수·인증 항목, ⑥ 담당자 계정·대상 주의사항, ⑧ 행사 정원, ⑨ 확인서 PDF, ⑩ 쓰지 않는 칸). 이 절과 7.18이 다르면 **7.18이 맞다**.

**① 활동 진행 — QR 30초 갱신·지각/노쇼·자동 마감·현관 카드 (B-51~B-53, B-22, B-24, B-25, C-08, C-11)**

- 시간 기준(`Rules`): QR·번호 갱신 30초(`QR_STEP_SEC`), 지각 알림 10분(`LATE_MIN`), 노쇼 의심 알림·노쇼 신고 가능 30분(`NOSHOW_MIN`), 종료 자동 마감 30분(`AUTO_CLOSE_MIN`), 시간 차이 안내 30분(`GAP_MIN`).
- 시작·종료 번호: 일정이 확정(CONFIRMED)될 때 요청마다 비밀값 `requests.qr_secret`을 만들고, 6자리 번호 = HMAC-SHA256(비밀값, 30초 구간 번호)로 계산한다(`Rules.rotatingCode`). 서버는 **지금 구간과 직전 구간**의 번호만 받는다. 확정 때 만드는 고정 6자리 `check_code`는 확인에 쓰지 않는다(비밀값이 없는 예전 자료의 대용 값으로만 쓴다).
- 독거노인 건은 도움 대상별 **현관 QR 카드** 번호(`elders.qr_code`, 6자리)로만 시작·종료한다. 카드는 확정 때 없으면 자동으로 만들어지고, 다시 발급하면 번호가 바뀌어 이전 카드는 `WRONG_CODE`.
- QR 글: 일반 건 `TS1|REQ|<요청 id>|<6자리>`, 독거노인 건 `TS1|ELD|<도움 대상 id>|<6자리>`.
- `GET /api/requests/{id}/qr` [받는 사람·담당자] → `{status, elderCard, code, qrText, leftSec, stepSec:30}`. 일반 건은 지금 구간 번호와 남은 초, 독거노인 건은 현관 카드 번호(`leftSec` null). CONFIRMED·IN_PROGRESS가 아니면 `code`·`qrText`는 null. 받는 쪽 화면은 이 주소로 번호를 다시 받아 간다.
- `POST /api/requests/{id}/check-in`·`check-out` `{code, method}`: `method`는 `qr`|`code`. `code`가 틀리면 400 `WRONG_CODE`. **`method:'manual'`(번호 없이 기록)은 없앴다 — 400 `MANUAL_NOT_ALLOWED`**(⑪). 예전 기록의 `checkMethod:"manual"`은 읽기만 한다.
- RequestView: `checkCode`·`qrText`는 받는 쪽·담당자에게 CONFIRMED·IN_PROGRESS일 때만. 일반 건은 `qrLeftSec`, 독거노인 건은 `elderCard:true`·`qrIssuedAt`이 함께 온다. 모든 역할에 `autoClosed`, `gapMinutes`(기록 − 예정, 분. 시작·종료 기록이 다 있어야 값) 추가. `elder.ageGroup`·`elder.alias`는 담당자에게만(B-13). `volunteer.eduDone` 삭제. *(기능명세서에서 바뀜 → 7.18 ④: `elder.caution`도 담당자와 일정이 확정된 봉사자에게만(`cautionLocked`). `volunteer.eduDone`은 여전히 없다)*
- 지각·노쇼(스케줄러 `lateCheck`, 1분마다): 예정 시작 10분 뒤 시작 기록이 없으면 `LATE`, 30분 뒤 `NOSHOW_SUSPECT`를 봉사자와 받는 쪽(독거노인 건은 담당자)에 한 번씩 보내고 요청 메시지 창에 시스템 문장을 남긴다. 하루 넘게 지난 일정에는 뒤늦게 보내지 않는다. `POST /api/requests/{id}/noshow`와 `actions`의 `noshow`는 시작 30분 뒤부터(그 전 409 `TOO_EARLY`).
- 자동 마감(`autoCloseDue`): IN_PROGRESS인데 예정 종료 30분이 지나면 `checkOutAt` = 예정 종료 시각, 상태 CHECKED_OUT, `autoClosed=true`. 봉사자에게 일지 작성 알림 `AUTO_CLOSED`, 받는 쪽에도 같은 종류 알림.
- 시간 차이 안내: 종료가 기록될 때(QR·번호·자동 마감) 기록된 시간이 예정과 30분 이상 다르면 양쪽에 `TIME_GAP` 알림 1회 + 시스템 문장. **적립·차감은 예정 시간 기준 그대로**이고, 다르면 봉사자가 시간 조정(`adjust`)을 요청한다.
- `POST /api/requests/{id}/cancel`: 상태가 SENT(상대 응답 전)이면 **보낸 쪽만**(받은 쪽은 403 — 거절로 답한다).
- 리마인드 `REMIND`: 전날이 아니라 **시작 1시간 전부터 시작 전 사이에 한 번**(`remindAt`).
- `POST /api/admin/scheduler/run` 결과: `expired, postsClosed, autoConfirmed, lateAlerts, autoClosed, reminders, lifeReports, unmatchedTips, ranAt`. *(기능명세서에서 바뀜 → 7.18 ②: `recurringPosts`(이번에 만든 정기 요청 회차 공고 수) 추가)*
- 담당자 현관 카드: `GET /api/org/elders/{id}/qr-card` → `{elderId, displayName, code, qrText, issuedAt, activeCount}`(없으면 만든다) · `POST /api/org/elders/{id}/qr-card/reissue` → 같은 모양(번호 바뀜, 종료된 대상 400 `ELDER_ENDED`). `GET /api/org/elders`·`/{id}` 항목에 `qrIssuedAt`.

**② 계정·알림 설정·통장·확인서 (A-01, A-04, A-06, B-43, B-46, B-47, B-55, B-56, B-58)**

- `POST /api/auth/signup`: 본문에 `agreeTerms`·`agreePrivacy`(필수 — 없으면 400 `TERMS_REQUIRED`), `agreeLocation`·`agreeInterest`(선택). 동의 시각·값을 `users`에 저장하고, 관심 공고 알림에 동의하지 않으면 `noti_interest=false`로 시작한다.
- `GET /api/me`(가입·로그인 응답의 `user` 포함): `eduDone`·`verify.eduDone` 삭제. `accountNo`(`TS-` + 회원 번호 6자리), `smsAlert`, `notiSettings`, `locConsent`, `interestConsent`, `termsAgreedAt` 추가. *(기능명세서에서 바뀜 → 7.18 ⑤: `eduDone`·`verify.eduDone` 되살아남)* `PUT /api/me`: `smsAlert`·`locConsent`를 받고 `textSize`는 `BIG` 아니면 `NORMAL`로 저장(B-55 — 화면은 접속할 때 이 값을 적용).
- `GET /api/me/noti-settings` → `{req, msg, result, remind, interest, voiceAlert, smsAlert}` · `PUT /api/me/noti-settings`(보낸 칸만 바꿈) [모든 역할]. 꺼 둔 종류는 알림을 **만들지 않는다**(`NotificationService.wants`). 종류 묶음: `req` = NEW_REQUEST·NEW_PROPOSAL·NEW_REGULAR / `msg` = MESSAGE / `result` = VOL_ACCEPTED·REJECTED·DECLINED·AUTO_REJECTED·MANAGER_REJECTED / `remind` = REMIND / `interest` = INTEREST. 그 밖(일정 확정, 지각·노쇼 의심, 완료 확인, 적립, 신고 결과 등)은 끌 수 없다. 설정 칸이 null이면 켜짐.
- 음성 전화·문자(B-43)는 모의: `voiceAlert`면 `[VOICE-MOCK]`, `smsAlert`면 `[SMS-MOCK]` 로그만 남긴다(문자 내용은 남기지 않음).
- `GET /api/bank`: `accountNo`, `totalEarned`, `totalUsed` 포함, `monthly`는 6개월. *(기능명세서에서 바뀜 → 7.18 ③: `months` 추가)*
- `GET /api/my/activity-summary?months=6`(1~24) → `{months:[{month, helpCount, helpHours, receivedCount, receivedHours}], totalHelpCount, totalReceivedCount}` — 완료(DONE)된 활동만.
- `GET /api/certificate?from=&to=`(YYYY-MM-DD, 비우면 전체. 적립 확정 시각 `doneAt` 기준, 끝<시작 400 `INVALID_PERIOD`) → `{name, accountNo, period, from, to, totalHours, totalEarned, count, elderCount, byCategory[{category,count,hours}](횟수 많은 순), items[{date, category, hours, earned, recipient, elder}], issuedAt, summary, summarySource}`. 시간은 예정 시간(조정 승인 건은 조정 시간). `summary`는 자기소개용 요약 문장(AI, 키 없으면 규칙 문장). `eduDone` 삭제. PDF 파일은 만들지 않는다(⑨). *(기능명세서에서 바뀜 → 7.18 ⑤: 서버는 여전히 파일을 만들지 않지만 화면이 이 응답으로 .pdf 파일을 만들어 내려 준다. 응답의 `eduDone`은 여전히 없다)*
- 탈퇴(A-04): `GET /api/me/withdraw-check`·`POST /api/me/withdraw`는 모든 역할. 진행 중인 요청에 담당 독거노인 건을 넣고, 담당 중인 도움 대상(ACTIVE)이 남은 담당자와 마지막 운영자는 409 `WITHDRAW_BLOCKED`.

**③ 공고 — 자동 반복 삭제·다시 올리기·가족 대신·위치·관심 조건 (B-02, B-06, B-14, B-20, B-21, B-57, B-59)**

- **자동 반복 등록은 없다.** `POST /api/posts`의 `repeat`은 글자·객체·true 어떤 모양이 와도 받아서 무시하고 늘 1건만 만든다(`createdCount` 1, `createdIds` 1개). 반복을 바랐으면 응답에 `repeatNotice`(안내 문장). `repeatRule`·`seriesId`는 새 공고에서 늘 null. *(기능명세서에서 바뀜 → 7.18 ②: **정기 요청이 다시 생겼다.** `POST /api/posts`의 `repeat`은 여전히 1건만 만들지만 `POST /api/recurring`으로 등록하면 회차마다 공고가 자동으로 생기고, 그 회차 공고에는 `repeatRule`·`seriesId`가 들어간다. `repeatNotice` 문장도 정기 요청 안내로 바뀜)*
- `POST /api/posts/{id}/repost` `{date}` → 새 공고 카드 + `repostOf`. 지난 공고(마감·매칭 포함, 삭제 제외)를 같은 내용으로 날짜만 바꿔 1건 올린다. 쓸 수 있는 사람: 공고 주인, 그 독거노인의 담당자(가족 대신 올린 작성자는 못 한다 — ⑪). 독거노인 공고는 `issueNote`도 복사. 오류: 날짜 없음·지난 날짜 400 `INVALID_DATE`, 남의 공고 403, 삭제된 공고 404, 종료된 대상 400 `ELDER_ENDED`.
- 가족 대신 공고(B-57): `POST /api/posts`에 `forUserId`(연결 ACCEPTED인 가족의 회원 번호). 공고 `ownerId` = 가족, `createdBy` = 올린 사람, `type`은 NEED만(400 `INVALID_TYPE`). 연결 아님 403, 본인·정지 계정 400 `INVALID_FAMILY`. 봉사 제안의 받는 사람이 가족이라 **알림·최종 수락/거부·시간 보류/차감은 가족 본인**. 올린 사람은 공고 상세의 `requests`를 읽기만 한다(`myRole` null, `actions` 빈 목록). 가족에게 알림 `FAMILY_POST`(링크 `/posts/{id}`). **올린 사람은 읽기만 한다**: 그 공고의 수정·삭제·다시 올리기는 403, 그 공고에 직접 봉사 제안은 400 `OWN_POST`(⑪).
- PostCard 추가: `proxy`, `proxyByName`, `proxyMine`(목록·내 공고·상세·등록 응답), 목록에는 `distanceBy`(`location`|`profile`). `owner.eduDone` 삭제.
- `GET /api/posts`: 쿼리 `dateFrom`, `dateTo`, `lat`, `lng`(둘 다 있어야 함, 틀리면 400 `INVALID_LOCATION`). 좌표가 오면 `distanceKm` = 그 좌표에서 공고 동네 중심(`service/Geo.java`의 동별 좌표 표)까지 거리. **좌표는 저장하지 않는다.** 표에 없는 동은 프로필 동네 기준 거리. 화면은 가까운 순을 누르는 순간 브라우저 위치 권한을 묻고, 거절 뒤 다시 누르면 허용 방법을 안내한다(`ui/location.jsx`).
- `GET /api/posts/mine?elderId=`: 그 대상의 공고만. 내가 가족 대신 올린 공고도 포함.
- `POST /api/ai/post-chat` 응답 `draft`: `repeat` 삭제, `repeatNotice`(안내 문장 또는 null) 추가. *(기능명세서에서 바뀜 → 7.18 ②: "매주"면 `draft.repeat {weekly, weekday, label}`이 다시 온다)* 화면은 대화·말로 요청하기를 시작하기 전에 "새로운 사람 / 자주 만난 사람(단골)"을 먼저 묻는다(서버 계약 없음).
- 관심 조건: `POST·PUT /api/interest-alerts` 본문·응답에 `dateFrom`, `dateTo`(YYYY-MM-DD, **빈 글자 `""`나 `null`을 보내면 지움**, 칸을 아예 안 보내면 그대로 둠 — ⑪). 끝<시작·모양 오류 400 `INVALID_DATE`. 기간 밖 공고는 알리지 않는다.

**④ 단골 (B-42, B-65)**

- `POST /api/regulars/{volunteerId}`: 함께한 활동이 없어도 등록된다(`NO_DONE_ACTIVITY` 없음). 상대(봉사자)가 해지한 사이면 409 `REMOVED_BY_VOLUNTEER`. 내가 해지했던 사람은 다시 등록할 수 있다.
- `DELETE /api/regulars/{volunteerId}`: 등록한 쪽의 해지. 줄을 지우지 않고 `regular_marks.removed=true`, `removed_by`를 남긴다. 자동(3회) 단골도 해지된다.
- `GET /api/regulars/{volunteerId}/status` → `{volunteerId, doneCount, auto, manual, removed, removedByThem, regular, canMark}`. `canMark` = 사용자 계정이고 아직 단골이 아니며 상대가 해지하지 않았음.
- `GET /api/me/regular-badges` → `[{key, kind: USER|ELDER, userId|elderId, name, label, count, auto, manual}]`(내가 받은 단골 배지 — 누구를 몇 회 도왔는지, 횟수 많은 순) · `DELETE /api/me/regular-badges/{userId}`: 등록된 쪽(봉사자)의 해지, 응답은 남은 배지 목록(내 단골이 아니면 404).
- `GET /api/regulars`·단골 요청(REGULAR) 자격·`regularBadges`(글자 목록): 해지된 사이는 빠진다. 독거노인(별칭)의 단골은 자동(3회)만이고 직접 등록·해지는 없다. *(기능명세서에서 바뀜 → 7.18 ③: 완료 3회째에 양쪽 `REGULAR` 알림)*

**⑤ 첫 활동 연습·추천 봉사·플래너 (B-01, B-54, B-60~B-62)**

- 안전 교육 이수 조건·수료·수료증·수료 배지·매칭 가점은 없다. 봉사 조건은 신분증 인증(`ID_NOT_VERIFIED`)뿐이다. `POST /api/edu/complete` **삭제**. 프로필·소개 카드·공고 카드·확인서·`/api/me`에서 `eduDone` 삭제. *(기능명세서에서 바뀜 → 7.18 ⑤: **교육 이수 기록·표시가 되살아났다.** 봉사 조건 = 신분증 인증 + (운영자 설정이 필수이면, 기본 필수) 첫 활동 교육 이수(`EDU_REQUIRED`). `/api/me`·프로필에 `eduDone`. 소개 카드·공고 카드·확인서에는 여전히 없다. `POST /api/edu/complete`도 여전히 없다)*
- `GET /api/edu` → `{optional:true, diag, cardsDone, quizScore, roleplay, personas, situations[{key,title,line,options[]}], rules, quiz}`(`completed`·`completedAt` 없음). *(기능명세서에서 바뀜 → 7.18 ⑤: `education{done, completedAt, total, doneCount, doneKeys, required, on}` 추가, `optional`은 교육이 필수가 아닐 때만 true)* `POST /api/edu/quiz`는 점수만(`passed` 없음). `POST /api/edu/roleplay/{id}/finish`는 `pass` 없이 `note` 추가.
- `GET /api/edu/situations` → `{items:[{key, title, line, options[]}]}` · `POST /api/edu/practice` `{key, text}` → `{key, quality, flag, ok, praise, feedback:{violation, flag, message, reason, better}|null, reply, source}`. 위반 판정은 코드 규칙이 먼저다. *(기능명세서에서 바뀜 → 7.18 ⑤: 로그인 사용자의 답이 안전 규칙에 맞으면 그 상황을 끝낸 것으로 기록하고 응답에 `education` 추가)*
- 상황 목록은 서버 설정(`app_settings`의 `edu.situations`): `GET /api/admin/edu/situations`, `PUT /api/admin/edu/situations` `{items[]}`(운영자). 운영자 화면은 없다(API만).
- `POST /api/ai/roleplay`: 응답에 `feedback`, `options[]`, `total` 추가. `next`는 AI가 만든 어르신의 말(키 없으면 대본·상황 목록).
- `GET /api/me/recommends` → `{recommends:[공고 카드 + why, score], planCount, basis}`(AI 호출 없음, 홈 "추천 봉사"). *(기능명세서에서 바뀜 → 7.18 ⑤: 끝낸 봉사 기록 기준 `basis`·`basisText`·`whyBasis` 추가)*
- `POST /api/me/planner/picks` `{postIds[]}`: 고른 공고마다 서버가 빈 시간 후보(공고 범위 안, 내 일정·다른 계획과 안 겹침, 30분 단위)를 만들고 AI가 그 가운데 시작 시각을 고른다(후보에 없는 값이면 규칙). 날짜는 공고 날짜 그대로. 응답 = 플래너 + `made[{postId, title, date, startHour, endHour, hours, source}]`, `skipped[{postId, title, reason}]`, `madeSource`. `GET /api/me/planner`의 계획 줄에 `endHour`, `postStartHour`, `postEndHour`, `planSource`.
- `GET /api/verify-items`: 봉사 인증 항목은 3개(phone·id·crime). *(기능명세서에서 바뀜 → 7.18 ⑤: 4개(phone·id·edu·crime), edu 기본 켜짐·필수)*

**⑥ 담당자 (C-01, C-02, C-03, C-12~C-16)**

- `POST /api/orgs/apply` [공개]: multipart(`name, orgType, address, phone, managerName, managerPhone, managerEmail, memo` + 파일 `proof` 필수) 또는 JSON(같은 칸 + 실제 있는 `proofFileUrl`). 증빙이 없으면 400 `PROOF_REQUIRED`. `organizations.proof_file_url`에 저장하고 `GET /api/admin/orgs` 항목에 `proofFileUrl`. *(기능명세서에서 바뀜 → 7.18 ⑥: 승인된 기관의 **추가 담당자 계정**은 운영자 발급이 아니라 `POST /api/auth/manager-apply` 자동 승인으로도 생긴다)*
- `POST /api/org/elders`: `consent:true`가 없으면 400 `CONSENT_REQUIRED`. 확인 시각·담당자를 `elders.service_consent_at/by`에 기록(수정에는 필요 없음). 응답에 `serviceConsentAt`. *(기능명세서에서 바뀜 → 7.18 ④: `serviceConsent` 추가, 수정(`PUT`)에 `consent:true`를 보내면 동의 기록이 없는 대상에 남긴다)*
- `POST /api/org/elders/{id}/memos`, `GET /api/org/elders/{id}`의 `memos[]`: `{id, elderId, text(원문), tidy, lines[], source(ai|rule), createdAt}`. 1000자 넘으면 400 `TEXT_TOO_LONG`. 정리본은 저장할 때 만든다(`ManagerMemoAi`).
- `GET /api/org/home`: `counts.monthDone`(이번 달 완료 활동 건수).
- `GET /api/org/change-report?range=today|recent` → `{date, range, windowDays, total, source, notice, items[{elderId, alias, displayName, dong, rank, score, level, reasons[], stats{recentCount, prevCount, todayCount, daysSinceLast, lastDate, signalCount, signals[], newSignals[], flagged}, summary, changes[], source, provider}]}`. 최근 30일 일지를 그 앞 30일과 비교. 횟수·점수·순위는 코드, AI는 일지 내용 정리만(진단 금지). `today`는 오늘 일지가 들어온 대상만. *(기능명세서에서 바뀜 → 7.18 ④: 항목에 신청 내역 `apply`·`applyText`·`checkFirst` 추가, 점수에 반영)*
- 이관 뒤 독거노인 건의 담당자 권한은 **지금 담당자만**(`Views.roleOf`).

**⑦ 신고·안부 확인 (B-36, B-37, D-02, A-07)**

- `POST /api/reports`: `kind:"WELFARE"` 또는 `type:"이상 징후(안부 확인)"`이면 이상 징후 접수 — 대상은 독거노인 활동(`targetType:REQUEST`, 아니면 400 `NOT_ELDER_ACTIVITY`), 그 활동의 봉사자·담당자만(403), 내용 필수(400 `CONTENT_REQUIRED`). 그 대상의 담당자에게 알림 `WELFARE`, 운영자에게 접수 기록 알림. 응답 Report에 `kind`, `elderId`. `targetType:"USER"`(활동 상대)는 실제 있는 다른 사용자여야 한다(400).
- `GET /api/admin/reports` 항목에 `kind`, `welfare`, 이상 징후면 `elder{id, displayName, dong}`, `manager{id,name}`, `targetUser:null`. `POST /api/admin/reports/{id}/handle`: 이상 징후 접수는 `action:NONE`만(다른 값 400 `NO_SANCTION`).
- 응급은 사이트에서 받지 않는다: 긴급 시트는 119·112 전화 연결과 신고 양식 열기만.
- 새 알림 종류: `NOSHOW_SUSPECT`, `AUTO_CLOSED`, `TIME_GAP`, `WELFARE`, `FAMILY_POST`. 알림 `link`는 서버 글자 그대로(`/requests/N`, `/posts/N`, `/my/inquiries`, `/my/reports`, `/admin/…`, `/org/…`)이고 화면 `goLink`가 화면 경로로 바꾼다.

**⑧ 운영자·기관 소식 (D-04, D-08, D-11, B-64, C-15)**

- `GET /api/admin/analytics`: `areaShortage[{area, demand, supply, gap, shortTotal, short[], categories[]}]`, `seasonSupplyDemand[{season, demand, supply, gap, categories[{category, demand, supply, gap}]}]`.
- 문의: `GET /api/admin/inquiries`·`GET /api/inquiries/mine` 항목에 `stage`. `PUT /api/admin/inquiries/{id}/status` `{status: RECEIVED|CHECKING|ANSWERED|CLOSED}`(답변 없이 ANSWERED는 400 `ANSWER_REQUIRED`, 모르는 값 400 `INVALID_STATUS`), 바뀌면 문의한 사람에게 알림. `inquiries.status`(RECEIVED/ANSWERED)는 그대로 두고 글자 칸 `stage`를 따로 쓴다.
- 기관 소식: 앱 안 신청·정원·신청자 목록이 없다. `POST /api/events/{id}/apply`·`/cancel` **삭제**, `POST /api/events`는 `{title, content, date, startHour, place, url, ogTitle?, ogImage?, ogDesc?}`이고 `url` 필수(`URL_REQUIRED`). 소식 응답에서 `capacity, applied, myApplied, full, applicants` 삭제, `applyType`은 늘 `"LINK"`. *(기능명세서에서 바뀜 → 7.18 ④: **`capacity`(정원, 표시용)가 되살아났다.** `POST /api/events`가 `capacity`를 받고 소식 응답에 `capacity`가 온다. 앱 안 신청·`applied`·`full`·`applicants`는 여전히 없다. `GET /api/events?nearby=true` 추가)*
- `GET /api/news?area=` → `{items[{key, kind(ORG|AI|PUBLIC), id, title, orgName, date, startHour, when, place, area, near, url, image, aiFound, notice}], area, searchAvailable, aiNotice}` — 담당자가 올린 소식 + AI가 찾은 소식(서버가 출처 링크를 열어 확인한 것만) + 링크 있는 공공데이터 강좌를 한 목록으로(내 동네 먼저, 날짜 순).

**⑨ 명세와 다르게 둔 것(총괄 결정)**

- B-30 차감: 명세 글자는 "예정 시간만큼"이지만 **예정 시간 × 가중치**(= 봉사자 적립과 같은 값, 7.3 정산)로 보류·차감한다.
- B-51: QR 스캔 말고 **6자리 번호 입력**(30초마다 바뀜)으로 시작·종료하는 길을 남겼다. 번호 없이 기록하는 `method:'manual'`은 없앴다(⑪).
- B-56: 확인서 PDF는 서버가 파일을 만들지 않고 브라우저 인쇄(PDF로 저장)로 받는다. *(기능명세서에서 바뀜 → 7.18 ⑤: "PDF 파일로 받기"가 실제 .pdf 파일을 내려 준다 — 브라우저가 만든 그림 PDF. 인쇄하기도 남음)*
- B-54: 프로토타입에는 "연습 수료 배지"가 있지만 명세대로 만들지 않았다. *(기능명세서에서 바뀜 → 7.18 ⑤: 기능 U-34에 따라 "✓ 안전 교육" 표시가 마이·프로필에 생겼다)*
- 명세에 없는 기존 LLM 기능(7.12~7.15: 쉬운 말, 고치기 제안, 방문 전 브리핑, 메시지 안전 감지, 담당자 브리핑, 신고 정리 등)은 그대로 둔다.

**⑩ DB 변경** — 모두 null 허용 칸 추가(`ddl-auto: update`). 새 표는 ⑪의 `upload_files` 하나. 정의는 `db/schema.sql`.

| 표 | 더한 칸 |
|---|---|
| `users` | `terms_agreed_at`, `privacy_agreed_at`, `loc_consent`, `interest_consent`, `noti_req`, `noti_msg`, `noti_result`, `noti_remind`, `noti_interest`, `sms_alert` |
| `requests` | `qr_secret`, `noshow_notified`, `auto_closed`, `gap_notified` |
| `elders` | `qr_code`, `qr_issued_at`, `service_consent_at`, `service_consent_by` |
| `reports` | `kind`, `elder_id` |
| `inquiries` | `stage` |
| `interest_alerts` | `date_from`, `date_to` |
| `organizations` | `proof_file_url` |
| `elder_memos` | `tidy_text`, `source` |
| `regular_marks` | `removed`, `removed_by` |
| `planner_picks` | `start_hour`, `hours`, `plan_source` |
| `app_settings` | 새 줄 `edu.situations`(표 변경 없음) |

41판부터 쓰지 않는 것(지우지 않음): `users.edu_done`, `edu_progress.completed_at`, `posts.repeat_rule`·`series_id`, `org_events.capacity`·`apply_type`, 표 `event_applications`. `requests.check_code`는 값은 들어가지만 확인에 쓰지 않는다. *(기능명세서에서 바뀜 → 7.18 ⑨: 이 가운데 `users.edu_done`·`edu_progress.completed_at`·`posts.repeat_rule`·`series_id`·`org_events.capacity`는 **다시 쓴다.** 여전히 안 쓰는 것은 `org_events.apply_type`과 표 `event_applications`)*

**⑪ 재대조·화면 확인 뒤 남은 문제 고침 (2026-11-12, 테스트 `Req41FixTest`)**

- **번호 없이 시작·마치기 삭제**: `check-in`·`check-out`에 `method:'manual'`을 보내면 400 `MANUAL_NOT_ALLOWED`. 화면의 "번호 없이 시작/마치기" 버튼도 없앴다. QR 찍기·6자리 번호·독거노인 건의 현관 QR 카드는 그대로.
- **올린 파일 접근 권한**: `GET /api/files/{id}`는 공개가 아니다(로그인 없으면 401, 권한 없으면 403). 볼 수 있는 사람(`FileAccessService`, 위에서부터 처음 맞는 규칙 하나):
  1. 신분증·범죄경력 회보서(`users.id_file_url`·`crime_file_url`): 낸 사람 본인, 운영자
  2. 기관 신청의 재직 증빙(`organizations.proof_file_url`): 운영자
  3. 활동 일지 사진(`requests.log_photo_url`): 그 요청의 봉사자·받는 사람·보낸 사람, 독거노인 건의 지금 담당자, 운영자
  4. 프로필 사진(`users.photo_url`): 로그인한 사용자
  5. 아직 어디에도 붙지 않은 파일: 올린 사람, 운영자
  `POST /api/files`는 올린 사람을 `upload_files`에 남긴다. 서류·프로필 사진·일지 사진으로는 **직접 올린 파일만** 붙일 수 있다(`POST /api/me/verify/id`·`crime`, `PUT /api/me`의 `photoUrl`, `POST /api/requests/{id}/log`의 `photoUrl` — 아니면 400 `FILE_NOT_MINE`). 화면은 이미지를 `<img src>`로 바로 걸지 않고 인증 머리글을 붙여 받아 온 뒤 blob 주소로 보여 준다(`api.js` `fileBlobUrl`·`openFile`, `ui/bits.jsx` `AuthImg`).
- **기관 신청 횟수 제한**: `POST /api/orgs/apply`는 같은 IP(`X-Forwarded-For`의 첫 주소, 없으면 연결 주소)에서 10분에 5번까지. 넘으면 429 `TOO_MANY_REQUESTS`. 서버 메모리 기준(다시 띄우면 지워짐)이고, 내용이 모자라 400으로 돌아간 신청은 세지 않는다.
- **B-36 일지 기반 이상 징후 알림**: 신호 이름에 `쓰러짐`·`넘어짐`을 더했다. `쓰러짐`·`넘어짐`·`식사 거름`은 일지(또는 전달 메모) **한 건에만 있어도** 그 독거노인의 담당자에게 `CARE_SIGNAL` 알림을 보낸다(같은 일지는 한 번만, 링크 `/o/elders/{id}`). 나머지 신호(외출 감소·기운 없음·잠 못 잠·말수·기분 변화)는 전처럼 최근 3건 중 2건일 때. 알림 글은 표현 이름과 "확인해 보세요"까지만 쓰고 진단·건강 판단 문장은 쓰지 않는다. 일반 사용자의 일지는 담당자 알림이 없다.
- **B-67·B-09 금지 요청 → 근처 공공 서비스**: `POST /api/ai/filter`(및 불가 응답을 싣는 곳)의 BLOCK 결과에 `nearby{label, question, link}`가 붙는다. `link`는 `/public?near=1&q=…` — 공공 서비스 안내 화면이 열리면서 위치 기준 안내(`POST /api/ai/public-services/nearby`)를 먼저 하고, 위치를 못 쓰면 사는 동네 기준 안내로 대신한다. `alternatives`(공공 서비스 목록)는 그대로.
- **가족 대신 공고**: 올린 사람은 읽기만(③에 반영). `PostService.canEdit`는 주인·담당자만, `canSeeRequests`는 올린 사람도.
- **퇴출(D-02)**: 신고 처리 `action:BAN`은 정지와 다르다 — `users.banned=true`(상태 값은 `SUSPENDED` 그대로), 로그인 토큰 폐기, 다시 로그인 403, 그 사용자의 수락 대기·확정·진행 중 요청은 취소(보류 해제, 상대에게 `CANCELLED` 알림), 열린 공고는 마감. 종료 체크·일지 제출까지 간 활동은 상대의 적립이 걸려 있어 그대로 정산된다. `POST /api/admin/users/{id}/unsuspend`·`/managers/{id}/unsuspend`는 409 `BANNED`. `GET /api/admin/users`·`/{id}`·`/managers` 항목에 `banned`. 담당자 계정의 퇴출은 계정만 막는다(담당 대상은 운영자가 이관).
- **알림 설정**: `PUT /api/me/noti-settings`에서 `voiceAlert`·`smsAlert`를 **켜는 것**은 일반 사용자만(담당자·운영자는 400 `USER_ONLY`, 끄기와 다른 칸은 됨).
- **운영자 대시보드**: `pending.reports`는 일반 신고만, 안부 확인(이상 징후) 접수는 `pending.welfare`로 따로. `edu.eduDone`, 회원·인증 목록의 `eduDone` 삭제. *(기능명세서에서 바뀜 → 7.18: 운영자 대시보드·회원 목록에는 여전히 `eduDone`이 없다 — ⑩ 남은 것)*
- **알림 글·링크**: 관심 공고(`INTEREST`)·가족 대신 공고(`FAMILY_POST`) 알림의 날짜는 `11/20(금)` 모양(`Rules.md`), 링크는 `/posts/{id}`. 번호 없는 옛 링크 `/posts`는 화면이 공고 목록으로 보낸다. 조사는 받침에 맞게 붙인다(`Rules.josa`·`Rules.ro`, 화면 `josa`·`ro`).
- **방문 전 브리핑**: 받는 분이 기관 도움 대상이거나 60대 이상이면 어르신용 문장, 그 밖이거나 나이대를 모르면 중립 문장(`ActivityRules.categoryRules`). `basics.senior`가 함께 온다.
- **담당자 대상 상세**: `GET /api/org/elders/{id}`의 `requests`는 날짜 최근순(같은 날은 늦은 시각 먼저).
- **위치 동의**: 가입의 `agreeLocation`과 마이 > 설정 > 위치 정보(`PUT /api/me`의 `locConsent`)는 같은 값(`users.loc_consent`)이다. 사용자 계정에서 이 값이 켜져 있지 않으면 화면은 가까운 순·내 위치로 찾기를 눌러도 브라우저 권한을 묻지 않고 "마이 > 설정 > 위치 정보에서 켤 수 있어요"를 안내한다(서버 계약 없음).
- **통화 메모 초안**(`POST /api/org/ai/memo-to-posts`)의 `drafts[].repeat` 삭제(`repeatLabel`만 남음). *(기능명세서 뒤에도 그대로 — 통화 메모 초안은 정기 요청으로 이어지지 않는다)* 플래너·홈의 기관 소식 신청 일정(`myApplied`) 삭제.
- **시간대**: 애플리케이션 시작 때 JVM 기본 시간대를 `Asia/Seoul`로 정한다(`TimeSavingsApplication.useKoreaTime`).
- DB: 표 `upload_files(file_id PK, uploader_id, created_at)` 추가, `users.banned` 추가.

### 7.18 기능명세서 적용 (2026-11-12)

기준: `docs/requirements/기능명세서.txt`(73개 기능 — 공통 C-01~C-08, 사용자 U-01~U-35, 기관 담당자 O-01~O-13, 운영자 A-01~A-17). **기능명세서 ID는 요구사항 명세서 41판 ID와 다른 체계**라 이 절에서는 "기능 U-09"처럼 적는다.
적용 기록은 `docs/specs/func/`(구역별 적용 문서 6개, 재대조 `적용-결과.md`). 이 절은 `main`(커밋 `5e68fdc`)의 컨트롤러·서비스·엔티티를 직접 확인해 적었다.
이 절과 앞 절(3~7.17)이 다르면 **이 절이 맞다**. 앞 절의 옛 문장에는 "기능명세서에서 바뀜 → 7.18"을 붙였다. AI가 들어가는 곳은 모두 "순서·숫자·판정은 코드, 문장만 AI(키 없으면 규칙 문장, 응답에 `source: ai|rule`)"이다.

**① 단체 봉사 (기능 U-10, U-11, O-07) — 새 표 `group_posts`·`group_participants`, `web/GroupController`·`service/GroupService`**

- 1:1 공고(`posts`)·요청(`requests`)과 섞지 않는 별도 흐름이다. `kind`: `RECRUIT`(단체 모집 — **기관 담당자만**) / `OFFER`(단체 봉사 가능 — 사용자(신분증 인증)·담당자). 인원 `capacity`는 2~50명(2명 미만 400 `INVALID_CAPACITY` "혼자 하는 봉사는 일반 공고로"). 상태 `OPEN`·`DONE`·`CANCELLED`.
- 받는 쪽은 언제나 기관이라 **차감·보류가 없다.** 가중치는 카테고리와 관계없이 1.0, 활동 시간은 1:1과 같은 30분~4시간(`Rules.checkHours`). 금지 요청 판정(`FORBIDDEN_REQUEST`)과 연락처·상세 주소 거르기(`PII_NOT_ALLOWED`)는 1:1 공고와 같다.
- `GET /api/group-posts?kind=&area=&cat=` → 열려 있고 날짜가 지나지 않은 단체 공고 카드 목록(날짜·시각 순) [로그인한 모든 역할]
- `GET /api/group-posts/mine` → 사용자: 올렸거나 참여한 것 / 담당자: 우리 기관이 올렸거나 받기로 한 것(최근 날짜부터)
- `GET /api/group-posts/schedule?month=YYYY-MM` → 내 단체 일정(플래너 일정과 같은 모양: `kind:"give", group:true, date, startHour, hours, category, title("[단체] …"), who, amount, groupPostId, status(CONFIRMED|DONE)`). 달 모양이 틀리면 400 `INVALID_MONTH`
- `GET /api/group-posts/{id}` → 카드 + `participants[{userId, name, status, earned, owner}]`(올린 쪽과 참석을 확인하는 기관 담당자에게만. 다른 사람은 인원 수만)
- `POST /api/group-posts` `{kind, date, startHour, hours, area, place?, category, title?, detail?, capacity, groupName?}` → 상세 [사용자·담당자, 운영자 403]. 제공형은 `groupName` 필수(`GROUP_NAME_REQUIRED`). 사용자가 올린 제공형은 올린 사람도 참여자 1명으로 센다. 오류: `INVALID_KIND, INVALID_CAPACITY, INVALID_DATE, INVALID_TIME, INVALID_HOURS, INVALID_CATEGORY, AREA_REQUIRED, GROUP_NAME_REQUIRED, TOO_LONG, FORBIDDEN_REQUEST, PII_NOT_ALLOWED, ID_NOT_VERIFIED`(400), 모집형을 사용자가 올리면 403
- `DELETE /api/group-posts/{id}` → 공고 내리기(`CANCELLED`, 참여자에게 알림) [올린 사람, 담당자 공고는 같은 기관 담당자]
- `POST /api/group-posts/{id}/join` · `/leave` [사용자] → 참여 신청(정원까지 **선착순**, 신분증 인증 필요 `ID_NOT_VERIFIED`, 같은 날 시간이 겹치는 다른 단체 봉사가 있으면 409 `TIME_OVERLAP`, 그 밖에 못 하면 409 `CANNOT_JOIN`) · 취소(올린 사람은 409 `OWNER_CANNOT_LEAVE`)
- `POST /api/group-posts/{id}/host` · `/unhost` [담당자] → 제공형 단체의 봉사를 우리 기관에서 받기(한 기관만, 409 `ALREADY_HOSTED`) · 받기 취소
- `POST /api/group-posts/{id}/complete` `{attended:[사용자 id…]}` [담당자 — 모집형은 올린 기관, 제공형은 받은 기관] → 상세 + `result{ok, attended, absent, earnedEach, totalEarned}`. 활동 날부터 가능(그 전 409 `TOO_EARLY`), 받은 기관이 없는 제공형은 409 `NOT_HOSTED`. 참석자마다 통장에 `EARN`(시간 × 1.0, `request_id` 없음, 이름 "단체 봉사 · 제목 · 기관")을 넣고 나머지는 `ABSENT`, 공고는 `DONE`. **참석 확인·완료는 언제나 기관 담당자만 한다**(사용자가 스스로 시간을 만들 수 없다).
- 카드 칸: `id, kind, kindLabel, title, detail, category, date, startHour, hours, endHour, area, place, groupName, capacity, joined, left, full, status, past, ownerId, ownerName, byOrg, orgName, hosted, hostOrgName, weight(1.0), estEarn, mine, myStatus(JOINED|ATTENDED|ABSENT|null), myEarned, distanceKm, createdAt, canJoin, joinBlock, canLeave, canCancel, canHost, canUnhost, canComplete, confirmSide`
- `GET /api/me/planner`의 `items`에 그 달의 내 단체 일정이 더해진다(`groupPostId`로 구분).
- 알림 종류 `GROUP`(끌 수 없음): 참여 신청·취소(올린 사람에게), 받기·받기 취소, 공고 취소, 적립·불참. 링크 `/group/{id}` 또는 `/bank`.
- 커밋 `5e68fdc` 기준으로 단체 참여·제공형 올리기에는 **첫 활동 교육 검사가 없다**(신분증만 본다). 확인서·월별 횟수·월별 리포트·운영자 통계에도 단체 봉사가 들어가지 않는다 → ⑩.

**② 공고 — 정기 요청·묶음 추천·시간순 (기능 U-02, U-03, U-06, U-08, U-09, U-12)**

- **정기 요청이 다시 생겼다(기능 U-09).** 7.17 ③의 "자동 반복 없음"은 `POST /api/posts`의 `repeat` 칸에만 남았고(여전히 1건만 만들고 `repeatNotice` 안내 문장을 돌려준다), 매주 반복은 따로 등록한다. 새 표 `recurring_posts`.
- 규칙(`service/RecurringService`): **도움 요청(NEED) 공고만, 매주 한 요일만.** 기간은 회차 수 `weeks`(2~26) 또는 끝 날짜 `endDate`. 한 사람이 진행·일시정지로 둘 수 있는 것은 5개(`TOO_MANY_RECURRING`), 같은 요일·시간 중복은 409 `DUPLICATE_RECURRING`. **회차 공고는 한꺼번에 만들지 않는다** — 등록할 때와 1분마다 도는 스케줄러(`SchedulerService.runAll` → `generateDue`)가 "오늘부터 7일 안" 회차만 `PostService.create`와 같은 길로 만든다(검사·관심 알림·**공고 1개에 요청 1건** 그대로). 만든 공고에는 `seriesId="RC{정기 요청 id}"`, `repeatRule="매주 목요일"`. 수락·거절은 회차마다 사람이 누른다. 올릴 수 없게 되면(가족 연결 끊김·담당 종료·계정 정지) 자동으로 멈추고 `RECURRING_STOPPED` 알림.
- `GET /api/recurring[?elderId=]` → 내 정기 요청 목록(도움 받는 본인·대신 등록한 사람·그 대상의 담당자). 한 건: `id, weekday(1=월…7=일), repeatLabel, startHour, endHour, estHours, area, categories, title, detail, note, startDate, endDate, status(ACTIVE|PAUSED|ENDED|CANCELLED), stopReason, totalCount, remainingCount, passedCount, nextDate, nextPostId, nextPostAt, posts[{id, date, status, requestCount}](최근 6개), estEarnPerVisit, elderId, elderDisplayName, ownerId, ownerName, proxy, proxyMine, createdByName, createdAt, actions[pause|resume|cancel]`
- `GET /api/recurring/{id}` → 한 건(권한 없으면 403)
- `POST /api/recurring` `{weekday?, startDate?, weeks 또는 endDate, startHour, endHour, estHours?, area, categories[], title, detail?, note?, forUserId?(연결된 가족 대신), elderId?(담당자 대리), elderIssue?(관리 대상 이슈, 300자)}` → 한 건 + `createdCount, createdPostIds, firstPost`. 오류: `INVALID_PERIOD, INVALID_DATE, INVALID_WEEKDAY`(400), `DUPLICATE_RECURRING, TOO_MANY_RECURRING`(409), `ISSUE_ONLY_FOR_ELDER, ISSUE_TOO_LONG, PII_NOT_ALLOWED`(400) + 공고 등록과 같은 검사 오류
- `POST /api/recurring/{id}/pause` · `/resume`(→ `createdPostIds`) · `/cancel`, `DELETE /api/recurring/{id}`(= 해지). 일시정지: 새 회차를 만들지 않음(올라간 공고는 그대로). 해지: 더 만들지 않고, 올라간 앞으로의 회차 공고 가운데 수락되지 않은 것은 함께 내린다(응답 `removedPosts, keptPosts`). 상태가 안 맞으면 409 `INVALID_STATE`.
- `POST /api/ai/post-chat`: "매주"라고 하면(도움 요청일 때) `draft.repeat {weekly:true, weekday, label}`이 다시 오고 `draft.repeatNotice`가 정기 요청 안내 문장이 된다. 격주·매일·매달은 정기 요청이 안 되어 "한 번만 올리고 다시 올리기" 안내.
- `POST /api/admin/scheduler/run` 결과에 `recurringPosts` 추가. 새 알림 종류 `RECURRING_POST`(회차 공고가 올라감), `RECURRING_STOPPED`(자동으로 멈춤) — 끌 수 없음.
- **빈 시간 묶음 추천(기능 U-12, `web/BundleController`·`service/BundleService`)** [사용자]
  - `POST /api/bundles/recommend` `{date, fromHour, toHour, lat?, lng?}` → `{date, fromHour, toHour, startDong, startBy(profile|location), candidateCount, bundles[{key, count, items[{postId, post(PostCard), startHour, endHour, hours, estEarn, travelKm, travelMin, waitMin}], totalKm, travelMin, idleMin, totalHours, totalEarn, startHour, endHour, reason, facts[], source}], message, source, rule}`. 좌표는 출발 동을 정하는 데만 쓰고 저장하지 않는다. 오류 `INVALID_DATE, INVALID_TIME, RANGE_TOO_SHORT`(1시간 30분 미만)`, INVALID_LOCATION`
  - 묶음 고르기는 전부 코드: 그 날짜의 열린 도움 요청 공고 가운데 내 공고·이미 제안한 공고·받지 않기 대상의 공고를 빼고, 건 사이 간격 = 걷는 시간(시속 4km, **동 중심 좌표 사이 직선거리** `Geo`, 표에 없는 동은 2.5km) + 10분, 최소 30분. 묶음 2~3건, 최대 3묶음. LLM은 설명 문장만(코드에 없는 숫자가 섞이면 규칙 문장).
  - `POST /api/bundles/propose` `{items[{postId, startHour, hours}](1~3), memo?}` → `{results[{postId, title, ok, requestId?, startHour?, hours?, deadlineAt?, code?, error?}], okCount, failCount, message}`. 공고마다 보통 봉사 제안(`PROPOSAL`, 24시간 기한)을 시간 순서대로 만든다. 보내기 전 오류(아무것도 안 보냄): `ITEMS_REQUIRED, TOO_MANY, INVALID_ITEM, DUPLICATE, DIFFERENT_DAY, TIME_OVERLAP`. 일부만 실패하면 200에 건별 `ok/code/error`.
- `GET /api/posts?sort=time`: 활동 날짜·시작 시각이 빠른 순(기능 U-02). `sort`는 `new|near|time`.
- 가족 대신(`forUserId`)은 직접 적기·정기 요청 등록에서도 쓴다(서버 규칙은 7.17 ③ 그대로).

**③ 진행·일지·평가·단골·시간 통장 (기능 U-14~U-25)**

- **사진 인증(기능 U-16)**: `POST /api/requests/{id}/log` 본문에 `photoUrl2` 추가. 장보기·심부름 = 영수증 1장(`photoUrl`), 가벼운 집안일 = 하기 전(`photoUrl`)·한 뒤(`photoUrl2`) 2장이 **없으면 400 `PHOTO_REQUIRED`**. 이 두 종류의 인증 사진은 촬영 동의 칸(`photoConsent`)과 상관없이 저장한다(그 밖의 카테고리는 전처럼 동의했을 때만 저장). 고쳐서 다시 낼 때 사진을 안 보내면 먼저 올린 사진을 그대로 쓴다. 두 장 모두 직접 올린 파일이어야 한다(400 `FILE_NOT_MINE`). **사진 내용(정말 영수증인지, 얼굴·개인정보가 있는지)은 검사하지 않는다.**
- 요청 응답(`GET /api/requests/{id}` 등 컨트롤러가 주는 RequestView)에 `photoRule`(`NONE`|`RECEIPT`|`BEFORE_AFTER`), `logPhotoUrl2` 추가. `GET /api/files/{id}` 권한 규칙(7.17 ⑪의 3번)은 두 번째 사진에도 같다.
- **평가 비공개(기능 U-18)**: 봉사자에게 가는 `RATED` 알림 제목이 "활동 평가가 등록됐어요"로 바뀌고 **별점이 빠졌다.** 개별 평가(누가 몇 점·무슨 말)를 상대에게 주는 API는 없다. 여러 평가를 모은 평균 별점과 자주 받은 칭찬(받는 쪽이 봉사자를 평가한 것만)은 전처럼 프로필·소개 카드에 보인다.
- **단골 자동 지정(기능 U-19)**: 같은 상대와 완료(DONE) 3회째가 되는 순간 **양쪽 모두**에게 알림 `REGULAR`("○○ 님과 단골이 됐어요 ★", 끌 수 없음). 해지한 사이는 알리지 않는다.
- `GET /api/bank`: `months[{month, in(번 시간), out(쓴 시간), count}]` 추가 — 거래가 있는 모든 달, 최근 달부터. 보류·보류 해제는 뺀다(기능 U-22). `hold`(묶인 시간)·`totalEarned`·`totalUsed`는 7.17 ② 그대로이고 화면이 늘 보여 준다(기능 U-21).
- **시간 플랜(기능 U-24)** `GET /api/me/time-plan?age=&startAge=` [사용자] → `{age, startAge, saveYears, balance, earnRate, earnMonths, useMonthly, useYearly, saved, nowYears, years, helpMonths, endAge, targetAge(90), needTotal, shortfall, goalMonthly, goalExtra, onTrack, rows[{category, hoursPerMonth, weight, costPerMonth, totalHours}], pattern{basis: SAME_AGE|SENIOR|DEFAULT, label, peers, minPeers(5), usedDefault, windowMonths(12)}, notice, text, goalText, source}`. `age`·`startAge`는 바꿔 보기용이고 저장하지 않는다(15~90세 밖이면 400 `INVALID_AGE`).
  - 비슷한 사람들: ① 나와 같은 나이대 → ② 모자라면 60대 이상. 최근 12개월 완료 요청에서 사람마다 "카테고리별 받은 시간 ÷ 쓴 기간(달)"을 구해 평균. **기록이 있는 사람이 5명 미만이면 일반적인 가정값**(`TimePlanService.defaultPattern`)을 쓰고 `notice`에 "아직 기록이 적어 일반적인 가정으로 계산했어요". 본인·독거노인(계정 없음) 기록은 넣지 않고 응답에는 평균과 사람 수만 나간다.
  - 적립 속도 = 최근 6개월 직접 번 시간의 한 달 평균(선물받은 시간 제외). 한 달 사용량 = Σ 카테고리 시간 × 가중치. 모이는 시간 = 잔액 + 적립 속도 × 12 × (도움 받기 시작 나이 − 지금 나이). 받을 수 있는 기간 = 모이는 시간 ÷ 한 해 사용량(95세까지). 적립 목표 = 90세까지 받는 데 필요한 시간을 남은 기간에 나눈 한 달 시간(0.5 단위 올림).
- **월별 리포트(기능 U-25)** `GET /api/me/month-report?month=YYYY-MM`(비우면 이번 달) [사용자] → `{month, prevMonth, isCurrent, give{count, hours, earned, byCategory, top, topCount}, receive{count, hours, used, …}, prev{give, receive}, change{giveCount, receiveCount, giveHours, receiveHours}, text, source}`. 활동 날짜 기준, 완료된 활동만.
- 기존 생애 플랜(`/api/me/life-plan`)·내 봉사 플랜은 그대로 둔다.

**④ 기관 담당자 (기능 C-03, O-01~O-13)**

- **특이사항은 확정된 봉사자에게만(기능 O-04)** — 7.17 ①·3.4의 "봉사자에게 주의사항까지"를 바꿨다. 범위는 공고의 관리 대상 이슈(`posts.issue_note`)와 대상의 방문 주의사항(`elders.caution`) 둘 다. 볼 수 있는 사람: 담당자, 그리고 그 공고·요청의 봉사자 가운데 **요청 상태가 확정·진행 중·종료 기록·일지 제출·완료**인 사람(`Views.issueVisible`). 요청·수락 대기와 거절·취소·만료는 볼 수 없다.
  - 공고 카드(PostCard): `elderIssueLocked` 추가. 볼 수 없으면 `elderIssue`·`elderCaution`이 null.
  - 요청 응답(RequestView): `elder.cautionLocked` 추가. 볼 수 없으면 `elder.caution`이 null이고 `baseCaution`이 없다.
  - 새로 올리는 대리 공고(말로 만들기·AI 대화·통화 메모)는 주의사항을 공개 칸(`note`)이 아니라 `elderIssue`로 보낸다. 예전에 올린 공고의 `note`에 들어간 글은 그대로 남는다. **남은 구멍**: 담당자가 "매주" 도움을 말로 요청하기·AI 대화에서 정기 요청으로 넘길 때는 화면이 아직 주의사항을 `note`("방문 시 주의: …")로 보낸다 — 회차 공고의 공개 칸으로 나간다(`적용-결과.md` 7절 11번, 고쳐야 함).
- **돌봄 편성표(기능 O-11~O-13, `web/OrgScheduleController`·`service/CareScheduleService`)** [담당자]
  - `GET /api/org/schedule?week=YYYY-MM-DD&elderId=` → `{weekStart, weekEnd, prevWeek, nextWeek, today, thisWeek, days[{date, dow, label, today, past}], elders[{elderId, alias, displayName, dong, needs, cells[7]}], filter[{elderId, displayName}], counts{fixed, pending, open, gaps, empty}}`. 주는 월~일, 주간만 있다. 칸 `{date, items[], empty, past, selectable, gap}`. 항목 `kind`=`REQUEST`(`state`: CONFIRMED|PROGRESS|PENDING|DONE, `requestId, startHour, hours, category, volunteerName`) 또는 `POST`(`state`=OPEN, `postId, startHour, endHour, category, title, pendingCount`). 거절·취소·만료·노쇼는 일정으로 올리지 않는다. `gap`(빈 일정) = 오늘 이후 빈 칸 가운데 같은 요일의 지난 4번 중 2번 이상 방문이 있었던 칸 `{weeks, category, startHour, hours, reason}`.
  - `POST /api/org/schedule/suggest` `{elderId, date, startHour?, hours?, category?}` → `{slot, pattern, candidates[], alternatives[{date, dow, label, count, candidates[]}], postDraft{elderId, date, startHour, endHour, category}, message, source, provider?, notice, sent:false}`. 후보(최대 5명) `{volunteerId, name, area, ratingAvg, ratingCount, regular, doneCount, sameDong, timeFit, offerPostId, offerStartHour, offerEndHour, action(REQUEST|REGULAR), facts[], reason, score}`. 후보는 코드가 찾는다: **이 대상의 단골 + 그 날짜·시간을 덮는 열린 봉사 가능 공고(도움 종류 일치)의 주인**(받지 않기 목록·그 시간에 다른 일정이 있는 봉사자·정지·미인증 계정 제외). 점수 = 단골 50 + 이 대상 완료 횟수×3(최대 15) + 같은 동 20 + 그 시간 공고 30 + 평가×2. 후보가 없으면 앞뒤 6일 안에서 후보가 있는 날 최대 3일을 `alternatives`로, 공고 올리기는 `postDraft`로 준다. **아무것도 보내거나 만들지 않는다**(담당자가 눌러야 한다).
  - `POST /api/org/schedule/bulk-posts` `{items:[{elderId, date, startHour, endHour?, category, detail?}]}`(최대 20칸, 넘으면 400 `TOO_MANY`, 비면 `ITEMS_REQUIRED`) → `{created[{index, elderId, displayName, date, startHour, endHour, category, postId, title}], failed[{index, …, code, reason}], createdCount, failedCount}`. 칸마다 보통 공고 등록과 같은 검사를 거치고, 같은 대상·같은 시간에 일정이나 열린 공고가 있으면 `SLOT_TAKEN`. 한 칸이 실패해도 다른 칸은 올라간다.
- `GET /api/org/change-report`(기능 O-10): 항목마다 `apply{total, matched, waiting, rejected, expired, cancelled, noshow, unmatchedPosts, failed}`, `applyText`, `checkFirst` 추가. 점수에 신청 내역이 더해진다(맞춰지지 않은 신청 2건 이상 +15, 1건 +5, 노쇼 +10, 봉사자를 못 구하고 마감된 공고 +10). 담당자가 스스로 거둔 취소는 세지 않는다.
- **행사 정원(기능 O-09)** — 7.17 ⑧의 "정원 없음"을 바꿨다: `POST /api/events`가 `capacity`(정수, 비우면 제한 없음, 0~9999 밖이면 400 `INVALID_CAPACITY`)를 받고 `GET /api/events`·`/{id}`·`/api/org/events`·`/api/news`(ORG 항목) 응답에 `capacity`(없으면 null)가 온다. **표시용 숫자다** — 앱 안 신청·신청자 수·남은 자리는 없고 신청은 전처럼 기관 페이지 링크(`url` 필수)로만 한다.
- `GET /api/events?nearby=true`(기능 U-31): 내 동네 근처(같은 동 + 동 중심 사이 1.2km 이하의 옆 동, `EventService.nearDong`) 행사만. 파라미터가 없으면 예전과 같다. 좌표 표에 없는 동은 같은 동일 때만 근처.
- 관리 대상(기능 O-01): 응답에 `serviceConsent`(참/거짓) 추가. `PUT /api/org/elders/{id}`에 `consent:true`를 보내면 동의 기록이 없는 대상에 동의 시각·담당자를 남긴다(이미 있는 기록은 바꾸지 않음). "나이"는 정확한 나이가 아니라 나이대(60대~90대 이상)로 받는다. `GET /api/org/elders`·`/{id}`에 `monthDone`(이번 달 완료 횟수).
- 기관 서비스 신청(기능 C-03)의 주소 필수는 화면에서만 확인한다. 서버 필수는 기관명·담당자·재직 증빙이다(7.17 ⑥ 그대로).
- 정기 요청의 관리 대상 대리 등록은 `POST /api/recurring`의 `elderId`·`elderIssue`(②).

**⑤ 계정·인증·플래너·AI 안내·확인서 (기능 C-04, C-08, U-01, U-26~U-28, U-30, U-34, U-35)**

- **첫 활동 교육(안전 교육) 이수가 되살아났다(기능 U-34)** — 7.17 ⑤의 "이수 조건·수료·배지 없음"을 바꿨다.
  - 이수 조건: 첫 활동 연습의 **상황(운영자 설정 목록 `edu.situations`, 기본 4개)을 모두 안전 규칙에 맞게 답하기**. 어긋난 답은 끝낸 것으로 치지 않는다. 퀴즈·규칙 카드·긴 역할극은 이수 조건이 아니다(선택 연습). `POST /api/edu/complete`는 여전히 없다.
  - `POST /api/edu/practice`: 로그인 사용자의 답이 `ok`이면 그 상황 key를 `edu_progress.practice_json`에 적고, 모두 끝나면 `users.edu_done=true`·`edu_progress.completed_at`. 응답에 `education{done, completedAt, total, doneCount, doneKeys[], required, on}` 추가. `GET /api/edu`에도 `education`이 오고 `optional`은 교육이 필수가 아닐 때만 true.
  - 봉사 자격 검사는 한 곳(`service/VolunteerGate`): 신분증 인증(`ID_NOT_VERIFIED`) + 교육이 필수이고 미이수면 400 **`EDU_REQUIRED`**. 걸리는 곳: 봉사 제안 보내기·요청 수락(`RequestService`), 도움 가능 공고 등록(`PostService`), 요청을 받는 봉사자가 미이수일 때 보내는 쪽(문장만 다름).
  - **기본 필수, 운영자가 바꾼다**: 인증 항목은 4개(순서 `phone, id, edu, crime`). `GET /api/verify-items`·`PUT /api/admin/verify-items`의 `edu` 항목 `on`·`required`. 운영자가 한 번도 바꾸지 않았으면 설정값 `app.verify.edu-required-default`(기본 `true`, 테스트 설정에서는 `false`)를 따른다. 기본이 필수라 **이미 가입해 있던 봉사자도 교육을 마쳐야 봉사할 수 있다**(시연 계정은 시드에서 이수 처리).
  - 표시: `GET /api/me`(로그인·가입 응답의 `user` 포함)에 `eduDone`, `canVolunteer`, `volunteerBlock{code, message}|null`, `verify.eduDone`·`verify.eduRequired`·`verify.eduOn`. `GET /api/users/{id}/profile`에 `eduDone`. 소개 카드(volunteerCard)·공고 카드·확인서·운영자 목록에는 없다.
- **담당자 계정 신청 자동 승인(기능 C-04, `web/ManagerApplyController`)** — 새 표 `manager_applications`
  - `GET /api/auth/manager-apply/orgs` [공개] → 승인된 기관 `[{id, name, orgType, address}]`(이름 순)
  - `POST /api/auth/manager-apply` [공개, multipart] 칸 `orgId, name, phone, verifyToken, loginId, password, proof(파일)`. 승인된 기관이고 휴대폰 본인인증(`/api/auth/phone/verify`의 `verifyToken`)이 맞으면 **바로 담당자 계정(역할 ORG)을 만들고 로그인시킨다.** 응답 `{id, status:"APPROVED", orgId, orgName, loginId, token, user, message}`. 오류: `ORG_NOT_APPROVED, NAME_REQUIRED, INVALID_LOGIN_ID, WEAK_PASSWORD, PHONE_NOT_VERIFIED, PROOF_REQUIRED`(400), `DUPLICATE_LOGIN_ID, ALREADY_MANAGER`(같은 번호로 그 기관에 이미 담당자 계정)(409), `TOO_MANY_REQUESTS`(429, 같은 IP 10분에 5번). **재직 증빙은 받아서 저장만 하고 내용은 검사하지 않는다**(운영자만 열 수 있다). 운영자와 같은 기관의 기존 담당자에게 알림 `MANAGER_APPLY`.
  - `GET /api/admin/manager-applications` [운영자] → 최근 순 `[{id, orgId, orgName, orgType, userId, name, loginId, phone, proofFileUrl, status(APPROVED|REVOKED), statusLabel, userStatus, existingManagers, otherManagersNow, revokeReason, revokedAt, createdAt}]` (기능 A-11)
  - `POST /api/admin/manager-applications/{id}/revoke` `{reason?}` [운영자] → 신청을 `REVOKED`로 바꾸고 그 담당자 계정을 정지·로그아웃. 이미 취소면 409 `INVALID_STATE`.
  - 기관 신청을 운영자가 승인할 때 첫 담당자 계정을 발급하는 길(5.11 `POST /api/admin/orgs/{id}/approve`)은 그대로다.
- **플래너(기능 U-01, U-26~U-28)**
  - `GET /api/me/planner/week?start=YYYY-MM-DD&mode=give|recv` [사용자] → `{start, end, today, mode, days[{date, items[]}], items[]}`. 그 날짜가 든 주(일~토), 모드에 따라 봉사할 일정(+내 계획) 또는 도움 받을 일정만. AI를 부르지 않는다.
  - `GET /api/me/planner`: `basis{doneCount, topCategory, topCategoryCount, topArea, topAreaCount, topSlot, topSlotCount}`, `basisText` 추가. `recommends[].why`가 끝낸 봉사 기록 기준 이유를 담고 `whyBasis{category, area, slot}` 추가. `GET /api/me/recommends`도 `basis`에 기록 값이 더해지고 `basisText` 추가. 추천 점수 = 같은 동 3·카테고리 2·시간대 2(프로필 또는 기록 기준) + 가장 많이 한 카테고리와 맞으면 1.
  - 화면: 헤더 모드(봉사하기/도움 받기)에 따라 홈·공고·플래너 달력·활동 내역이 걸러진다(서버 계약 없음, 플래너 주 보기만 `mode`).
- `POST /api/ai/public-services`, `POST /api/ai/public-services/nearby`(기능 U-30): `volunteer{possible(YES|CAUTION|NO|UNKNOWN), label, message, reason, categories[{category, level, levelLabel, weight}], criteria[], writeLink|null, source}` 추가 — 봉사로 가능한 일인지와 봉사 기준. 금지 요청 필터와 6가지 도움 찾기(코드) 결과이고, 6가지에 들지 않는 말은 `UNKNOWN`.
- `POST /api/ai/recommend`(기능 U-27)는 7.16의 모양 그대로(`regularPosts`·`nearbyPosts`·`help`). 도움 받기 모드 플래너에 입구가 생겼다(`/recommend?q=`).
- **봉사 확인서 PDF(기능 U-35)** — 7.17 ⑨의 "인쇄로만"을 바꿨다: `GET /api/certificate` 응답은 그대로이고, 화면(`frontend/src/lib/certPdf.js`)이 그 값을 그림으로 그려 **실제 .pdf 파일(A4, 파일 이름 `봉사활동확인서_이름_발급일.pdf`)을 내려 준다.** 서버는 파일을 만들지 않는다. 글자 선택·검색은 안 되고 직인·진위 확인 코드는 없다. 인쇄하기도 남아 있다.

**⑥ 기관 신청·담당자 승인 흐름 정리 (기능 C-03, C-04, A-10, A-11)**

| 하는 일 | 주소 | 승인 |
|---|---|---|
| 기관 서비스 신청 | `POST /api/orgs/apply`(7.17 ⑥) | 운영자가 서류를 보고 승인·반려(`/api/admin/orgs/{id}/approve|reject`). 승인하면 첫 담당자 계정 발급 |
| 승인된 기관의 담당자 계정 신청 | `POST /api/auth/manager-apply`(⑤) | **자동 승인**(승인된 기관 + 휴대폰 본인인증). 운영자는 내역을 보고 취소만 한다 |

**⑦ 운영자 리포트·추계·특보 (기능 A-02, A-04~A-09, `web/AdminReportController`)** [운영자]

- `GET /api/admin/forecast`(기능 A-05) → `{baseYear, offsets[0,1,3,5,10], areas[{area, risk, rows[{offset, year, supply, demand, gap, ratio, status, members, elders, seniorShare}], reasons[], joinsPerMonth, youngJoinsPerMonth, churnYear}], total[rows], assumptions{bands[{band, users, elders, supplyPer, supplySource(기록|가정), demandPer, demandSource}], joinsPerMonth, joinsSource, churnYear, churnSource, churnSample, notes[]}}`
  - 모형(`service/FinanceModel`, 코호트): 나이대 8개 × 동네. **가정값**: 1인당 월 공급·수요 시간(`DEFAULT_SUPPLY`·`DEFAULT_DEMAND`, 한 나이대 기록이 3명 미만이면 가정), 이탈률 연 15%(가입 90일 넘은 회원이 5명 미만이면 가정. 기록이 있으면 "탈퇴했거나 최근 90일 활동이 없는 비율"), 월 가입 수(최근 6개월 가입이 3명 미만이면 회원의 2%), 가입자 나이 분포(`JOIN_SHARE`), 한 해에 나이대의 10%가 다음 나이대로. 관리 대상은 수를 그대로 두고 나이만 올린다. 사망·전출·동네 이동은 없다. 응답 `assumptions`가 값마다 "기록"인지 "가정"인지 밝힌다.
- `POST /api/admin/forecast/summary?refresh=` → 이번 달 요약 `{month, headline, areas[{area, risk, reasons[], text}], total, source, savedAt}`. 저장된 것이 있으면 그대로, 없거나 `refresh=true`면 다시 만들어 `ai_results`(kind `FIN_MONTHLY`, ref_key 연-월)에 저장. `GET /api/admin/forecast/summaries` → 저장된 요약 목록(최근 달 먼저).
- `POST /api/admin/forecast/simulate`(기능 A-06) `{text}`(300자) 또는 `{adjustments:[{type, ageBand, area, value}]}` → `{adjustments[{…, label}], understood, source(ai|rule|manual), before, after(알아들었을 때만), areas, bands, assumptions}`. `type` = `JOIN_ADD`(월 가입 ±명)·`CHURN_MULT`(이탈률 ×배)·`SUPPLY_ADD`·`DEMAND_ADD`(1인당 월 시간 ±). 값 범위: 가입 ±1000명, 배수 0~5, 시간 ±20. 자연어는 LLM이 조정 값 JSON으로 바꾸고(형식 검사 `ForecastAi.valid`), 키가 없으면 규칙이 가입 수·이탈률(절반·배·%)·봉사/도움 시간 문장만 알아듣는다. 오류 `TEXT_REQUIRED, TEXT_TOO_LONG`.
- `GET /api/admin/stats/types`(기능 A-02) → `{categories[{category, givers, receivers, giverPct, receiverPct, count, hours}], userTypes[{key, label, count, pct}], members, elders, interpretation{text, source}}`
- `GET /api/admin/stats/season`(기능 A-04) → `{periodMonths, monthly[12], seasons[4], nextSeason{season, currentSeason, growth, basis, categories[{category, base, expected}], total, …}, interpretation{text, source}}`. 다음 계절 예상 = 지난 12개월 가운데 그 계절의 카테고리별 수요(그 계절 기록이 없으면 12개월 수요 ÷ 4) × 추세(최근 90일 수요 ÷ 그 앞 90일 수요, 0.5~2.0으로 자름) — 코드 계산.
- `GET /api/admin/recruit-drafts`(기능 A-07) → `{drafts[{area, category, timeSlot, demand, supply, gap, title, body}], source, days(180)}` — 부족이 큰 동네·카테고리 5개, 시간대는 요청이 가장 많은 "평일/주말 × 오전/오후/저녁" 한 칸.
- **특보(기능 A-08, A-09)** — 새 표 `weather_alerts`
  - `GET /api/admin/weather/alerts` → `{live, types, levels, knownAreas, alerts[{id, type, level, areas, startDate, endDate, source(MANUAL|SAMPLE|KMA), memo, facts{areas[{area, elders, elders80, activities, managers[]}], totalElders, …}, report{summary, actions[], source}, noticeId, sentAt, sentManagers, createdAt}]}`
  - `POST /api/admin/weather/alerts` `{type, level, areas[], startDate?, endDate?, memo?}`(직접 입력) · `POST …/alerts/sample`(시연용 표본 — 실제 기상 정보 아님) · `POST /api/admin/weather/fetch`(기상청에서 받아 오기) · `POST …/alerts/{id}/refresh`(리포트 문장 다시) · `DELETE …/alerts/{id}`(전달한 것은 409 `ALREADY_SENT`)
  - `POST /api/admin/weather/alerts/{id}/send`(기능 A-09) → 공지 게시 + 해당 지역에 담당 대상이 있는 담당자에게 알림(type `WEATHER`, 링크 `/notice/{id}`). 한 번만(두 번째 409 `ALREADY_SENT`). 문자·푸시 같은 바깥 발송은 없다.
  - **기상청 호출은 모의에 가깝다**: `service/KmaWeatherProvider`는 환경변수 `KMA_API_KEY`가 있을 때만 기상청 API허브 특보 현황 주소(`KMA_API_URL`)를 부르고 응답 줄에서 구역 이름(`KMA_REGION`, 기본 "서울")·특보 종류·수준 낱말을 찾는다. **응답 형식은 짐작이고 실제 호출로 확인하지 못했다.** 키가 없으면 `live:false`이고 직접 입력·표본만 쓴다. 서비스의 동네는 모두 그 구역 하나에 있다고 본다.

**⑧ 운영자 관리 (기능 A-13, A-15, A-16) · 공지 (기능 C-08)**

- `GET /api/admin/report-context/{id}`(기능 A-13) → `{reportId, target{id, name, role, status, banned, warningCount, penaltyCount, createdAt, doneAsVolunteer}|null, pastReports[](최근 10), betweenDone, reporterReportCount, request{id, date, category, status, hours, content, logText, checkInAt, checkOutAt}?, messages[{id, sender, byTarget, text, warn, createdAt, reported}](최근 10)?, post{…}?}`. 이상 징후(안부 확인) 접수는 대상 계정을 싣지 않는다.
- **카테고리 건의 승인 → 카테고리에 추가(기능 A-15)** — 3.1의 "6종 고정"을 바꿨다.
  - `GET /api/categories` [로그인한 누구나] → 기본 6종 + 더한 것 `[{name, level, levelLabel, weight, extra}]`. 더한 카테고리는 `app_settings`의 `categories.extra`(JSON `{이름: 등급}`).
  - `POST /api/admin/category-suggestions/{id}/adopt` `{level?(기본 BASIC), answer?}` → `{ok, updated, status:"ADOPTED", category}`. 같은 이름의 건의를 모두 반영으로 바꾸고 카테고리에 더하고 건의자에게 알림. 기존 `POST …/{id}/answer`도 상태가 `ADOPTED`면 기본 등급으로 더한다.
  - `DELETE /api/admin/categories/{name}` → 더한 카테고리 빼기(그 종류의 공고·요청이 있으면 409 `CATEGORY_IN_USE`).
  - 더한 카테고리는 공고·요청·등급(가중치)에 바로 쓰이지만 **운영자 통계의 기본 집계와 AI 난이도·글 검사 낱말 사전에는 들어가지 않는다**(기본 6종 기준).
- **공지 고정(기능 A-16, C-08)**: `POST /api/admin/notices/{id}/pin` `{pinned?}`(없으면 뒤집기) → Notice. `GET /api/notices`는 고정 공지(`pinned=true`)를 맨 위에, 그 안에서 최근 순으로 준다. 항목에 `pinned`.

**⑨ DB 변경** — `ddl-auto: update`로 생긴다. 정의는 `db/schema.sql`, 설명은 `db/README.md`.

| 표 | 내용 |
|---|---|
| `group_posts` (새 표) | 단체 봉사 공고: `id, kind, owner_id, org_id, group_name, date, start_hour, hours, area, place, category, title, detail, capacity, status, host_org_id, host_by, hosted_at, done_by, done_at, created_at` |
| `group_participants` (새 표) | 단체 봉사 참여자: `id, group_post_id, user_id, status(JOINED/LEFT/ATTENDED/ABSENT), earned, created_at` |
| `recurring_posts` (새 표) | 정기 요청: `id, owner_id, elder_id, created_by, weekday, start_hour, end_hour, est_hours, area, categories, title, detail, note, issue_note, start_date, end_date, status, stop_reason, last_generated, created_at, updated_at` |
| `manager_applications` (새 표) | 담당자 계정 신청 내역: `id, org_id, user_id, name, phone, login_id, proof_file_url, status(APPROVED/REVOKED), existing_managers, revoke_reason, revoked_at, created_at` |
| `weather_alerts` (새 표) | 기상 특보: `id, type, level, areas, start_date, end_date, source, memo, report_json, notice_id, sent_at, sent_managers, created_at` |
| `requests` | `log_photo_url2` 추가(집안일 "한 뒤" 사진) |
| `edu_progress` | `practice_json` 추가(끝낸 연습 상황 key 목록) |
| `notices` | `pinned` 추가 |
| `ai_results` | 새 kind `FIN_MONTHLY`(표 변경 없음) |
| `app_settings` | 새 줄 `categories.extra`, `verify.items` 값에 `edu` 항목(표 변경 없음) |

41판에서 "쓰지 않음"이던 것 가운데 **다시 쓰는 것**: `users.edu_done`, `edu_progress.completed_at`(교육 이수), `posts.repeat_rule`·`series_id`(정기 요청 회차 공고), `org_events.capacity`(행사 정원, 표시용). 여전히 쓰지 않는 것: `org_events.apply_type`(늘 LINK), 표 `event_applications`, `requests.check_code`(확인에는 안 씀). 단체 봉사 적립은 기존 `ledger`에 `EARN`(`request_id` null)으로 들어간다.

**⑩ 남은 것·모의 상태** — 자세한 것은 `docs/specs/func/적용-결과.md`.

- 모의·가정값: 휴대폰 본인인증(Firebase 키가 없으면 데모 번호 123456, 통신사 실명 대조 아님 — 담당자 계정 자동 승인도 이 인증을 쓴다), 음성 전화·문자(로그만), 기상청 호출(⑦), 재정 추계의 가정값(⑦), 묶음 추천·근처 판정의 동 좌표(`Geo`의 시범 지역 가상 좌표, 직선거리·걷는 속도), 시간 플랜의 일반 가정값(③), 재직 증빙·인증 사진 내용 미검사.
- 실제 AI(키 있음)로는 한 번도 확인하지 않았다. ✨ 기능 17개 모두 키 없이 규칙 모드로 돈다.
- 커밋 `5e68fdc` 기준으로 운영자가 더한 카테고리의 공고·요청이 생기면 `GET /api/admin/analytics`가 오류가 난다(`AnalyticsService.add`). 통합 담당이 고치는 중.
- 커밋 `5e68fdc` 기준으로 단체 봉사가 빠진 곳: 첫 활동 교육 검사(참여·제공형 올리기), 봉사 확인서, 월별 횟수 요약, 월별 리포트, 플래너 목표·주 보기, 운영자 통계. 통합 담당이 고치는 중이며, 고쳐지면 이 줄과 ①의 마지막 줄을 지운다.

### 7.19 담당자·운영자 화면 변경 (2026-11-13 대표 지시)

- **담당자 특보 알림**: `GET /api/org/weather` → `{alerts[], past[], guides[], banner, myElderTotal}`. 항목마다 `type, level, areas, from, to, active, mineArea, myElders, myElderNames, myActivities, actions, sent, sentAt, guideTitle, guide`. `guides`는 운영자가 전달한 특보(공지 내용 포함), `banner`는 그 가운데 진행 중이고 내 담당 대상이 있는 지역의 최근 것(담당자 홈 배너). 화면 `/o/weather`, 담당자 홈·마이에 진입점.
- **공공 서비스 연계**: 사용자가 공공 서비스 안내에서 "담당자에게 전달하기"를 누르고 동의했을 때만 기록이 만들어진다(표 `public_referrals`). 누르지 않은 질문·답은 저장하지 않는다.
  - `GET /api/public-referrals/orgs`(전달할 수 있는 승인 기관), `POST /api/public-referrals {orgId, question, answer, services[], note, consent:true}`(동의 없으면 400 `CONSENT_REQUIRED`), `GET /api/public-referrals/mine`.
  - 담당자: `GET /api/org/public-referrals`(우리 기관에 온 것만, 전달한 사용자의 이름·전화·동네·나이대 포함), `POST /api/org/public-referrals/{id}/status {status: CONTACTED|HELPING|DONE|NOT_ELIGIBLE, memo}` → 사용자에게 알림. 화면 `/o/public`, 사용자 `/public-referrals`.
- **운영자 대시보드 상단 네 칸**: 이번 달 활동 · 매칭률 · 신규 가입/총 가입자 · 등록 기관 수. `GET /api/admin/dashboard`의 `kpi`에 `newUsers, totalUsers, orgCount` 추가. (시간 발행·소비 칸은 상단에서 뺌)
- **이용 리포트 토글**: `GET /api/admin/stats/by-type?type=NEED|OFFER` → 그 종류 공고(최근 12개월)의 `byAge[]`(나이별 인기), `monthly[]`·`seasons[]`(계절별), `byArea[]`(지역별). 숫자는 모두 코드 집계.
- **공고 상세**: `GET /api/posts/{id}` 응답에 `manager {name, orgName, orgType}`(관리 대상 공고만, 연락처 없음) 추가.

### 7.20 사용자 화면 변경 (2026-11-13)

대표 지시(`docs/specs/2026-11-13-대표-화면-지시.md`)와 프로토타입 `docs/prototypes/proto-2026-11-13-사용자.html`의 "11/13 수정" 묶음을 사용자(모바일) 화면에 옮겼다. 담당자·운영자 화면은 그대로다.

**새·바뀐 API**

- **관심 공고(북마크)** — 새 표 `post_bookmarks (id, user_id, post_id, created_at)`, `(user_id, post_id)` 유일.
  - `POST /api/posts/{id}/bookmark` → `{postId, bookmarked:true}` (두 번 눌러도 한 줄, 내가 올린 공고는 400 `OWN_POST`, 삭제된 공고는 404)
  - `DELETE /api/posts/{id}/bookmark` → `{postId, bookmarked:false}`
  - `GET /api/posts/bookmarks` → 공고 카드 목록(담은 순서가 최근인 것부터, 삭제된 공고 제외, `bookmarkedAt` 포함)
  - `GET /api/posts`·`GET /api/posts/{id}` 응답에 `bookmarked`(내가 담았는지) 추가.
- **시간 통장** `GET /api/bank`의 `entries[]`에 `balanceAfter`(그 거래 뒤 잔액 — 원장을 시간순으로 더한 값, 보류·보류 해제는 잔액을 바꾸지 않음), `counterpart`(상대 이름 — 요청 기록에서 찾음, 기관 대상은 가린 이름, 선물·조정은 null), `counterpartRole`(`HELPED` 내가 도운 사람 · `HELPER` 나를 도와준 사람) 추가.
- **동네 기관 소식** `GET /api/news?sort=new` → 올라온 순서가 최근인 것부터. 항목마다 `createdAt` 추가(정렬값을 안 주면 예전처럼 내 동네 먼저·날짜 빠른 순).

**화면**

- 하단바: 홈 · 공고 · 시간 통장 · 플래너 · 마이.
- 공지 확성기: 고정 공지(`notices.pinned`)가 있으면 하단바 다섯 화면 위쪽에 한 줄로 흐르고, 누르면 그 공지(`/notice/{id}`)로 간다.
- 홈: 통장 요약 → 내 다음 일정(확정된 것 2개) → (도움 받기) "어떤 도움이 필요하신가요?" AI 도움 찾기 / (봉사하기) "도움 가능" 한 메뉴 → 나를 기다리는 요청 → 이웃 → 기관 소식 → (봉사하기) 봉사 확인서 한 메뉴. 작은 사각형 빠른 메뉴는 없앴다(공고 올리기 = 공고 화면, 단골 = 마이·공고 올리기, 공공 서비스 = 마이·플래너 AI 도움 추천).
- 공고(`/board`): 탭 1:1 공고 / 단체 봉사(`?tab=group`) / 동네 기관 소식(`?tab=news`). 1:1 공고는 관심 공고 구역이 먼저(없으면 구역 없음), 그 아래 나머지 공고. 공고 카드·상세에 ☆ 버튼. 동네 기관 소식은 최신순 카드, 누르면 기관 페이지(새 창). 세 탭 아래쪽에 "내가 도와줄 수 있는 이웃"(1:1 탭에서 봉사 요청 공고를 보고 있을 때는 목록이 곧 그것이라 뺀다).
- 공고 올리기(`/write`): 헤더 모드에 맞는 공고 한 종류 + (도움 받기) "누구의 도움인가요?"에서 가족 대신 올리기(`familyId`로 기존 B-57 흐름).
- 시간 통장: 통장 번호 · 예금주 · 쓸 수 있는 시간 · 총 모은 시간 · 총 사용 시간 · [가족에게 선물하기] · [플래너]. 거래 내역은 월 넘기기(‹ ›), 최신순, 건마다 ±시간 · 그 뒤 잔액 · 도운 분/도와준 분, 누르면 거래 상세(영수증).
- 플래너: 탭 생애 플랜(꺾은선 그래프 먼저) → 캘린더 → AI 봉사 추천/AI 도움 추천. 관심 공고는 캘린더 아래 목록(달력 칸에는 표시하지 않음).
- 공고 상세: 시간 → 필요한 도움 → 내용 → 특이사항(요청자 · 담당자 — 담당자 소속은 담당자 줄에 함께).
- 끝난 활동 상세: 봉사 일지 상자(날짜 · 작성자 · 확인 여부, 일지 줄, QR 시작·종료 시각, 적립·차감 시간).
- 마이: "가족 대신 봉사 요청"·"가족 최근 기록"·"내 리포트 공유 동의" 줄을 뺐다(서버 기능과 `/my/family` 화면은 그대로). "단골에게 요청" 줄 추가.

**프로토타입에는 있으나 만들지 않은 것**: 담당 기관 연결·권한 위임(마이 · 프로필 · 가입의 담당 기관 칸, 홈의 담당자 띠, 공공 서비스 "담당자에게 전달" 위임 연동), 책임 가중치 삭제(동행 1.5 → 1.0 — 서버 적립 규칙 변경이라 결정이 필요 *→ 2026-11-13 대표 결정으로 7.21에서 만듦*), 단체 봉사 북마크, 봉사하기 홈의 "내 활동 리포트" 카드, PC 폭에서 하단바를 왼쪽 메뉴로 바꾸는 배치(대표 지시는 PC에서도 모바일 모양).

### 7.21 가중치·색 변경 (2026-11-13 대표 결정)

대표 결정 "팀원 색으로 맞추고 가중치도 없애기로 했어" — 사용자 프로토타입 `docs/prototypes/proto-2026-11-13-사용자.html`의 "11/13 수정" 묶음(책임 가중치 삭제: 동행도 기본 1.0, 수고 1.2는 그대로)과 PC 프로토타입(`--recv:#6F9CF0`)을 따른다. 앞 절(3.1, 7.16 등)에서 1.5·책임 등급·#EE9A7E를 말하는 문장은 이 절이 대신한다.

**가중치 규칙**
- 등급은 **기본(BASIC) 1.0 · 수고(EFFORT) 1.2 두 단계만** 쓴다. 동행의 카테고리 기본 등급은 기본 1.0 (동행 2시간 → 적립 2.0).
- `Level` enum 의 `RESPONSIBLE`은 예전 기록 때문에 지우지 않는다. `Rules.weight(RESPONSIBLE)=1.5`, `levelKo="책임"`은 **예전 기록을 보여 주고 다시 셈할 때만** 쓴다. 새로 고를 수 있는 등급은 `Rules.SELECTABLE`(기본·수고), 예전 값은 `Rules.current()`가 수고로 바꾼다.
- 책임 등급이 들어오는 곳은 모두 수고로 바꿔 받는다: 공고 `difficulty`(`POST/PUT /api/posts`), 난이도 이의 신청 `requestedLevel`(바꾼 뒤에도 지금 등급보다 높지 않으면 400 `INVALID_LEVEL`), 이의 신청 답(`resultLevel`·예전 신청의 책임 등급 — 지금 등급보다 올릴 수 없으면 400 `INVALID_LEVEL`, 반려로 답한다), 카테고리 건의 승인 `level`, 저장돼 있던 추가 카테고리 등급, AI 난이도 제안 응답.
- 난이도 제안(`RuleEngine.difficulty`): 책임 요소(병원·동행 등)만으로는 등급이 오르지 않는다. 책임 요소에 다른 부담(신체·위험·전문성·이동·접근성 합 2 이상)이 겹치거나 무거운 짐·계단 등이면 수고까지만 오른다. 동행만 있는 요청의 근거 문장은 "신체 부담·위험·전문성이 낮아 기본 등급(×1.0)을 제안해요." AI 프롬프트도 BASIC·EFFORT 두 값만 받는다.
- 플래너·시간 플랜·내 분석·목표 플랜은 `Rules`를 그대로 쓰므로 동행을 1.0으로 계산한다. `POST /api/me/care-plan` 응답의 `weights`는 `{BASIC:1.0, EFFORT:1.2}`. 안내 문장의 "동행(×1.5)" 류는 "장보기·집안일(×1.2)"로 바꿨다. 교육 규칙 카드 예시는 "병원 동행 2시간 → +2.0시간".

**이미 있는 기록 (`WeightRuleMigration`, 서버가 뜰 때 한 번 · 여러 번 돌아도 같음)**
- **바꾸지 않는 것**: 통장 원장(Ledger)의 가중치·시간, 끝난 요청(DONE·거절·취소 등)의 등급·가중치·적립 시간, 닫히거나 지워진 공고. 화면에는 저장된 값("책임 1.5")이 그대로 보인다.
- **새 규칙으로 옮기는 것**: 열려 있는 공고(OPEN·MATCHED)와 끝나지 않은 요청(SENT ~ LOG_SUBMITTED) 가운데 책임 등급인 것 → `RuleEngine.difficulty`로 다시 정한 등급(기본 또는 수고), 가중치·예상 적립(`estEarn`)·공고의 근거 문장을 다시 쓴다. 정산은 정산 시점의 요청 가중치를 쓰므로 새 규칙대로 적립·차감된다.
- 받는 사람 통장에 묶인 보류가 새 예상 시간보다 많으면 그 차이만 `RELEASE`("… 보류 일부 해제(가중치 변경)")로 먼저 푼다. 그래서 보류 해제(`RequestService.releaseHold`)는 "보류 합 − 이미 푼 합"만큼 푼다(전에는 예상 적립 전체).
- 옮긴 요청의 봉사자와 받는 사람(일반 사용자)에게 알림 `RULE_CHANGE`("책임 1.5 → 기본 1.0 · 예상 3.0시간 → 2.0시간")를 보낸다.
- 시연 자료(DataSeeder 등)는 `Rules`로 등급을 정하므로 동행 기록·공고가 새 규칙값(1.0)으로 들어간다(시연 계정 yujin 잔액 24.5 → 20.5).

**화면**
- `lib/format.js` 카테고리 표: 동행 `w 1.0 · 기본`. `LEVEL.RESPONSIBLE`은 예전 기록 표시용으로만 남긴다.
- 고르는 곳에서 책임 1.5를 뺐다: 난이도 이의 신청(지금 등급이 기본일 때만 버튼이 보이고 "수고 1.2로 조정 요청" 하나), 운영자 카테고리 건의 승인(기본 1.0 · 수고 1.2).
- 문구: "기본 1.0 · 수고 1.2 · 책임 1.5" → "기본 1.0 · 수고 1.2"(플래너·내 분석·목표·통장 규칙·봉사 안내), 카테고리 설명 동행 "기본 1.0", 플래너 도움말 "장보기(1.2배)를 하나 섞으면…", 공고 난이도 근거의 "책임 등급" 문장 삭제.

**봉사받기(도움 받기) 색 — 프로토타입 짝 그대로**

| 쓰임 | 전 | 후 |
|---|---|---|
| 대표색(`--recv`, 받기 모드 `--brand`·`--pri`, 버튼·탭·점·범례) | `#EE9A7E` | `#6F9CF0` |
| 연한 배경(`--recv-soft`, `--pri-soft`, 배너·선택 칸) | `#FDF0EA` `#FFF1EB` `#FFEDE5` | `#E8F0FE` |
| 진한 글자(배너·칩·선택 글자, `--pri-2`) | `#D9775A` `#DC7754` `#B55E1C` | `#3A6FD8` |
| 테두리(모드 선택 칸) | `#F6C4B1` | `#BED3F8` |
| 그라데이션 짝(`--recv2`) | `#F7C2AE` | `#9DBDF5` |
| 연한 그라데이션 | `#FFE9DF → #FFF4EE` | `#E8F0FE → #F2F6FF` |
| 그림자 | `rgba(232,137,106,…)` `rgba(238,154,126,…)` | `rgba(111,156,240,…)` |
| 2색 아이콘 `recv` | `#F2996B / #FFD9C4 / #FFF1E9` | `#6F9CF0 / #CFE0FC / #EDF3FE` |

- 공고 종류 배지도 프로토타입대로: `.kind.offer`(도움 가능) `#E8F0FE / #3A6FD8`, `.kind.need`(봉사 요청) `#E2F3EC / #3F8F7B`.
- 달력·일정의 행사 점은 받기 색과 겹치지 않게 프로토타입 값 `#E3B25A`로(전 `#8DB4F7`).
- 그대로 둔 것(프로토타입도 주황): 홈 "받은 제안" 카드(`t-offer`·`f-offer`), 마감 임박 배지(`w-dl`), 통장 "총 사용 시간" 아이콘, 달력 아이콘의 장식 점, 장보기·요청 아이콘, 아바타 색. 봉사하기(초록)·담당자·운영자 색은 건드리지 않았다.
- 흰 글자가 올라가는 버튼은 프로토타입과 같이 `#6F9CF0` 그대로 쓴다(프로토타입에 따로 진한 버튼색이 없다). 글자색으로 쓸 때는 `#3A6FD8`.

### 7.22 담당자 PC 틀(5판) 1차 (2026-11-13)
기준: `docs/prototypes/proto-2026-11-13-기관운영자-PC-5.html`, 명세 `docs/specs/2026-11-13-프로토타입-5판-분석.md` 2절. 화면 사진 `docs/specs/pc5/shots/`.
서버·DB·API는 바꾸지 않았다(프런트만).

**바뀐 것**
- 역할이 ORG이고 화면 폭이 901px 이상이면 `ui/Frame.jsx`의 `Screen`이 모바일 틀 대신 PC 틀(`ui/PcFrame.jsx`의 `PcShell`)을 그린다. 900px 이하는 지금까지의 모바일 틀·아래 탭·모바일 화면 그대로다. 사용자·운영자 틀은 그대로.
- PC 틀: 흰 왼쪽 메뉴 236px(로고 줄 · 프로필 카드 · 숫자 3줄 `관리 대상 / 처리할 일 / 이번 주 돌봄` · 그룹 메뉴 · 계정 바꾸기 · 로그아웃) + 위 막대 64px `#3E9E85`(뒤로 · 제목 · 날짜 · 알림 종(안 읽은 알림 빨간 점) · 프로필 알약) + 본문(여백 22/32/40, 바탕 `#F3F5F8`). 카드 반지름 22, 선 `#EDF0F4`, 그림자 `0 6px 22px rgba(40,60,90,.06)`. 색 변수 `--acc #4FAE96 / --acc-s #E2F3EC / --acc-k #2F8069 / --band #3E9E85`.
- 스타일은 `styles/pc.css` 한 파일에, 모든 규칙을 `.pcw`(틀) 또는 `body.pcw-on`(틀이 떠 있을 때의 시트·토스트) 아래에만 둔다. PC 틀에서는 시트가 가운데 창(폭 560, 반지름 24)으로 뜬다.
- 프로필 숫자 3줄은 `/org/home`(`counts.elders`, `todo` 개수)과 `/org/schedule`(`counts.fixed`)을 1분 동안 나눠 쓴다.
- 화면 3개를 5판 배치로 새로 짰다(`screens/orgPc.jsx`, `screens/schedulePc.jsx`): 홈, 요청 관리 보드, 돌봄 편성표 캘린더. 나머지 담당자 화면은 모바일 본문을 가운데 흰 카드(최대 폭 760px)에 담아 보여 준다.
- 실명·나이·전화·주소는 쓰지 않는다. 5판에서 `이순례 님 · 82세 · 행복동`인 자리는 `별칭 님 · 나이대 · 동`이다.

**메뉴**
| 그룹 | 항목 | 경로 | 5판 |
|---|---|---|---|
| 돌봄 | 홈 | `/o/home` | O-HOME-01 |
| 돌봄 | 특보 알림 | `/o/weather` | O-ALERT-01 |
| 돌봄 | 관리 대상 | `/o/elders` | O-CARE-01 |
| 돌봄 | 돌봄 편성표 | `/o/schedule` | O-SCHED-01 |
| 돌봄 | 요청 관리 | `/o/reqs` | O-POST-01 |
| 돌봄 | 공공 서비스 연계 | `/o/public` | O-PUB-01 |
| 기관 공고 | 단체 봉사 공고 | `/board?tab=group&kind=mine` | O-POST-05 자리(기관 공고 3종은 미정이라 지금 있는 단체 봉사로) |
| 기관 공고 | 기관 행사 | `/o/events` | O-POST-06 |
| 관리 | 리포트 | `/o/report` | O-RPT-01 |
| 관리 | 정기 요청 | `/recurring` | 5판에 없음(앱에만 있는 화면) |
| 관리 | 내 정보·설정 | `/o/my` | O-HOME-03 자리(프로필 카드·알약으로도 감). 알림 설정·문의·공지·카테고리 건의·탈퇴가 이 안에 있음 |
| (맨 아래) | 계정 바꾸기 | 데모 계정 시트 | 5판에 없음(모바일 틀의 역할 칩 자리) |
| (맨 아래) | 로그아웃 | `/auth/logout` → `/login` | 로그아웃 |
| (위 막대) | 알림 종 | `/notif` | O-HOME-02 자리 |

메뉴에 없는 하위 화면은 가까운 메뉴에 불이 켜진다: `/req/…`·`/write/…`·`/regular`·`/voice`·`/o/memo-posts`·`/board`·`/post/…` → 요청 관리, `/group/…` → 단체 봉사 공고, `/news/…` → 기관 행사, `/my/…`·`/qna`·`/notice` → 내 정보·설정.

**홈(`/o/home`) 구역과 자료**
| 5판 구역 | 넣은 것 | 자료 |
|---|---|---|
| 인사 줄 | `OOO 님, 오늘 처리할 일이 n건 있어요` + 아래 줄(진행 중 특보가 있으면 특보와 담당 대상 수, 없으면 이번 달 활동·진행 중 건수) | `/org/requests?box=all`(처리할 일), `/org/weather` `banner`, `/org/home` `counts` |
| 숫자 칩 4개 | 관리 대상 · 이번 주 돌봄 · 편성 빈칸 · **공공 서비스 연계**(5판은 "새 신청") | `/org/home`, `/org/schedule` `counts.fixed·gaps`, `/org/public-referrals` `openCount` |
| 공지 띠 | 진행 중이고 내 담당 지역인 특보가 있을 때만 | `/org/weather` `banner` |
| 오늘 처리할 일 | 상태 배지 · `별칭 님 · 도움` · 할 일 · 남은 시간, 3줄 + 더 보기. 줄을 누르면 상세(대신 수락·거절·확인은 상세에서) | `/org/requests?box=all` |
| 새로 온 요청 | 특보 알림 · 공공 서비스 연계 · **관리 대상 이관 요청**(5판은 "기관 공고 신청") | `/org/weather`, `/org/public-referrals` `newCount`, `/org/home` `counts.transfersIn` |
| 먼저 안부 물어볼 분 | 순위 3명(앞 2명 `우선`), 누르면 그 대상 리포트 | `/org/change-report` |
| 최근 도착한 일지 | `봉사자 → 별칭 님` + 일지 앞 30자 | `/org/requests?box=all`의 `logText` |
| 이번 주 돌봄 | 날짜 띠(월~금, 토·일은 일정이 있거나 오늘일 때만) + 그날 일정·빈칸 | `/org/schedule` |
| 봉사자 현황 | **넣지 않음** → 그 자리에 **바로 하기**(관리 대상 등록 · 공고 대신 등록 · 봉사 공고 찾기 · 단골에게 요청 · 말로 공고 만들기 · 통화 메모로 여러 건) | - |
| 기관 공고 진행 | **넣지 않음** → 그 자리에 **이번 주 브리핑** | `/org/briefing` |

**요청 관리 보드(`/o/reqs`) 열 ↔ 요청 상태**
| 5판 열 | 요청 상태값 | 카드 배지 |
|---|---|---|
| 새 제안·수락 | `SENT`, `VOL_ACCEPTED` | 요청(제안이면 `새 제안`) · 수락 |
| 확정 | `CONFIRMED` | 확정 |
| 진행 중 | `IN_PROGRESS`, `CHECKED_OUT` | 진행 중 · 일지 대기 |
| 일지 확인 | `LOG_SUBMITTED` | 일지 확인 |
| 완료 | `DONE`, `REJECTED`, `DECLINED`, `EXPIRED`, `CANCELLED`, `NOSHOW` | 완료 · 거절됨 · 거부됨 · 만료 · 취소 · 노쇼 (최근 12건, 나머지는 목록에서) |

- 끌어 옮기기는 없다. 카드를 누르면 `/o/reqs/:id`이고 상태는 거기 버튼으로만 바뀐다.
- `[보드 | 목록]`: 목록은 `?view=list&seg=need|going|past`의 표(처리할 일 · 진행 중 · 완료). 위 오른쪽 `[정기 요청]` `[도움 찾기]` `[+ 대리 요청]`(대상을 고른 뒤 기존 화면으로).
- 카드의 노란 메모 띠는 공고에 적은 "이번 요청 특이사항"이 있을 때만 나온다(대상 기본 주의사항은 상세에서).

**돌봄 편성표(`/o/schedule`)**
- `[편성표 | 일 | 주 | 월]`, 처음 값 주. 주소: `?view=t|d|m`(주는 없음) `&week=` `&elder=` `&day=`.
- 주·일: 08~20시 격자(한 시간 62px, 시간 열 60px). 겹치는 일정은 칸을 나눠 나란히. 월~금 + 일정이 있는 토·일. 오늘 열 표시와 "지금" 줄은 브라우저 시계.
- 블록: 확정·진행 중·응답 대기·완료(회색)·열린 공고(점선)·빈칸(빗금). 일정을 누르면 그 요청·공고, 빈칸을 누르면 오른쪽 "빈칸 상세"(`봉사자 추천 보기` = 기존 추천 시트, `공고로 올리기` = 기존 한꺼번에 올리기 시트).
- 월: 그 달에 걸친 주만큼 `/org/schedule?week=`를 불러 그린다. 날짜를 누르면 그날 일 보기.
- 오른쪽: 빈칸 상세 · 이번 주 빈칸 · 요일별 편성 · 이번 주 봉사자(편성 많은 순 6명) — 모두 주간 응답으로 프런트에서 센다.
- "편성표" 보기는 지금까지의 대상 × 요일 표(빈 칸 골라 추천·한꺼번에 올리기 포함) 그대로다.

**5판에 있지만 넣지 않은 것**
| 항목 | 이유 |
|---|---|
| AI 도우미 떠 있는 패널 | 대표 결정 전 |
| 권한 위임 메뉴·화면 | 기능·서버 없음, 결정 전 |
| 기관 공고 3종, 홈 "기관 공고 진행"·"새 신청"·"기관 공고 신청" | 결정 전(단체 봉사만 있음) |
| 홈 "봉사자 현황" | 자료·기준 없음 |
| 홈 인사 줄의 "혼자 사는 분 n명" | 가구 형태를 저장하지 않음 |
| 실명·나이(숫자)·전화·주소, 보드 카드의 `우선` | 저장하지 않음 |
| 캘린더 블록의 `단골` 표시 | 주간 응답에 단골 여부가 없음 |
| 알림 드롭다운(420px), 담당자 정보 패널(480px) | 다음 차수(지금은 `/notif`, `/o/my` 화면으로 이동) |
| 이모지 → 선 아이콘 바꾸기(전 화면) | 새로 짠 화면과 틀만 선 아이콘. 나머지 화면은 다음 차수 |
| 마우스 올림 말풍선(`#ctip`) | 브라우저 기본 `title`로 대신 |
| 화면 ID 표시, 사용자(모바일) 보기 | 시연용 |

**다음 차수에 할 화면**: 관리 대상(목록 340 + 상세) · 등록·수정 패널, 요청 상세(목록 + 상세 두 칸), 특보 알림, 공공 서비스 연계, 리포트, 기관 행사, 대리 요청(두 칸), 도움 찾기, 알림 드롭다운, 담당자 정보 패널, 운영자 틀·대시보드.

### 7.23 담당자 PC 화면(5판) 2차 (2026-11-13)
기준·명세는 7.22와 같다. 화면 사진 `docs/specs/pc5/shots/2차-…png`. 서버·DB·API는 바꾸지 않았다(프런트만). 폭 900px 이하·사용자·운영자 화면은 그대로다.

**틀에서 바뀐 것**
- 틀(왼쪽 메뉴·위 막대)을 화면마다 그리지 않고 `App.jsx`에서 한 번만 그린다(`ui/PcLayout.jsx`). 화면(`Screen`)은 본문만 그리고 제목·뒤로 버튼 여부만 틀로 올린다 → 메뉴를 눌러 옮겨도 왼쪽 메뉴가 다시 만들어지지 않는다. 틀이 꺼져 있을 때(좁은 폭·다른 역할·로그인 화면)는 같은 자리에 `display:contents` 요소만 둔다.
- 위 막대 종을 누르면 **알림 드롭다운**(420px, 반지름 24: 알림 목록 · `모두 읽음` · 줄을 누르면 읽음 처리 뒤 그 화면으로 · `알림 화면에서 전체 보기`)이 뜬다. 자료·동작은 `/notif`와 같다.
- 프로필 숫자 3줄: 자료를 바꾸는 API 호출이 끝나면 `api.js`가 `ts-mutated` 이벤트를 알리고, 틀이 캐시를 비워 다시 받는다(요청 처리 직후 바로 바뀜). 알림 읽음·로그인·AI·추천 보기 같은 호출은 빼 두었다.
- 본문 배치 세 가지: ① `wide`(PC용으로 새로 짠 화면) ② `PcSideCtx`를 준 화면 = 위 줄 + 왼쪽 목록 340px + 오른쪽에 기존 본문 ③ 그 밖 = 가운데 카드 760px.
- 오른쪽 패널(`PcDrawer`, 480px, 왼쪽 모서리 24)과 5판 부품(`.split` `.lrow` `.ptag` `.warnbox` `.notebox` `.kpi` `.steps` `.li`)을 `pc.css`에 더했다.
- PC 틀 안에 들어온 모바일 부품(가운데 카드·시트)은 5판 모양에 맞췄다: 버튼 높이 44(작은 것 36)·반지름 12·그림자 없음, 아바타·파란 바탕 → 역할 색, 고른 칩 = 역할 색 연한 바탕, 입력 칸 높이 42, 떠 있는 버튼(단체 공고 올리기) 역할 색.

**화면별 배치**
| 화면 · 경로 | PC 배치 | 파일 |
|---|---|---|
| 관리 대상 `/o/elders` `/o/elders/:id` | 목록 340(별칭 검색 · 칩 `전체 / 동 / 살펴볼 분 / 지원 종료` · 줄 · `관리 대상 등록` · 이관 요청 수락·거절·대기) + 상세. 상세: 머리 카드(별칭·나이대·동·등록일 + 필요한 도움 + `정보 수정` `도움 찾기` `대신 요청 올리기` + 아래 줄 `단골에게 요청` `지난 요청 다시 올리기` `정기 요청` `말로 공고` `통화 메모로 여러 건` `메모 쓰기`) → 확인 권장 카드 → [방문 주의사항(추가) · 단골 봉사자] → 이번 주 돌봄(요일 칸, 빈칸 빨간 글씨) → [받은 도움 기록 표 · 담당자 메모(AI 정리·음성·추가)] → [변화 리포트 · 동의·관리(서비스 동의 기록, 리포트 공유 동의, 현관 QR 카드, 이관, 지원 종료)] → 받지 않기로 한 봉사자 · 맞는 기관 클래스 | `screens/eldersPc.jsx` |
| 등록·수정 `/o/elders/new` `/:id/edit` | 목록+상세 위에 오른쪽 패널 480. 폼 칸·검사·저장은 모바일과 같은 본문(`ElderFormBody`) | 〃, `org.jsx` |
| 요청 상세 `/o/reqs/:id` | 위 줄(`처리할 일 / 진행 중 / 완료` + `보드로 보기` `도움 찾기` `+ 대리 요청`) + 목록 340(상태 배지·날짜·`별칭 님 · 도움`·봉사자·할 일) + 오른쪽 상세(기존 `RequestDetail` 그대로) | `screens/orgPc2.jsx` |
| 대신 요청 올리기 `/write/need?elder=` | 왼쪽 340(관리 대상 고르기 칩 · 대상 정보·주의사항 · 단골 `바로 요청` · 다른 방법 5가지) + 오른쪽 기존 대화형 작성 | 〃 |
| 특보 알림 `/o/weather` | 탭 `기상 특보 / 운영자 안내` + 목록 340 + 상세(특보 상자 · 숫자 3칸 · 이 지역의 담당 대상 · 운영자 안내 보기 · 권하는 조치 · 이어 가기 버튼). 주소 `?tab=wx|guide&id=` | 〃 |
| 공공 서비스 연계 `/o/public` | 숫자 4칸 + `진행 중 / 완료` + 목록 340 + 상세(머리 카드·진행 막대 → [전달한 AI 대화 · 연락처] → AI가 찾은 서비스 표 → 처리 메모 + 상태 버튼 4개) | 〃 |
| 리포트 `/o/report` | 왼쪽 400(리포트 AI 안내 · 먼저 안부를 물어볼 순서 · `최근 30일 / 오늘` · 다른 담당 대상) + 오른쪽(대상 머리 카드 · 동향 요약 · 최근 활동 기록 · 지난 월간 리포트 2칸 + `변화 큰 사례로 표시`) | 〃 |
| 기관 행사 `/o/events` `/o/events/new` | 왼쪽 올린 행사 카드 + 오른쪽 새 행사 폼(모바일과 같은 본문 `EventFormBody`). 올린 뒤에는 목록을 다시 받고 폼을 비운다 | 〃, `news.jsx` |
| 내 정보·설정 `/o/my` | 프로필 카드 + 묶음 카드 5개(돌봄 · 기관 · 알림·화면 · 도움말 · 계정) | 〃 |
| 알림 `/notif`, 정기 요청, 단골 요청, 통화 메모 여러 건, 단체 봉사, 알림 설정 등 | 가운데 카드 760 그대로, 색·버튼·아바타만 위 규칙으로 | `styles/pc.css` |

**5판과 다르게 채운 구역**
| 5판 | 넣은 것 | 이유 |
|---|---|---|
| 관리 대상 목록 필터 `우선순위 높음 / 혼자 사는 분`, 줄의 `우선` | `살펴볼 분`(변화 큼·확인 권장) / `지원 종료`, 줄의 `변화 큼`·`확인 권장` | 우선순위·가구 형태를 저장하지 않음 |
| 상세 머리의 `82세 · 혼자`, 연락처·안부 확인 카드, 권한 위임 카드 | 나이대·동·등록일, 그 자리에 `변화 리포트`와 `동의·관리` | 실명·나이·전화·주소·위임 자료 없음 |
| 등록·수정의 이름·나이·가구 형태 칸 | 별칭·나이대·동·필요한 도움·주의사항·동의(지금 폼 그대로) | 〃 |
| 요청 상세의 두 칸 카드(봉사자 · 이슈 메모 · 평가) | 오른쪽은 기존 상세 본문 그대로(카드 순서는 앱 것) | 사용자와 같이 쓰는 화면이라 동작을 지키려고 |
| 대신 요청의 `전화 내용 + AI로 정리` · `정리 결과` 두 칸 | 왼쪽 대상·단골, 오른쪽 대화형 작성(그 안에 "AI로 정리하기"가 있음) | 앱 작성 흐름이 대화형 |
| 특보의 `혼자 사는 관리 대상`·`안부 확인 완료` 숫자, 안부 전화 표(전화·가족·안부 확인 기록), `확인했어요`·`했어요`, 예정 돌봄 목록, `봉사자에게 특보 알리기` | 숫자 `이 지역 내 담당 대상 / 특보 기간 예정 활동 / 운영자 안내`, 담당 대상 별칭 목록, 권하는 조치(읽기만), 편성표·관리 대상·공공 서비스 연계로 가는 버튼 | 안부 확인 기록·전화번호·가구 형태 자료 없음 |
| 공공 연계의 `자격 미리 보기`(AI), 대상 나이·가구·`관리 대상 정보` | `연락처` 카드(전달에 동의한 사용자의 전화)와 안내 문구 | 자격 판단 AI 없음. 요청한 사람은 관리 대상이 아니라 앱 사용자 |
| 공공 연계 숫자 `이번 달 완료` | `완료`(전체) | 달 단위 집계 없음 |
| 리포트의 `이번 주 요약`, 월별 활동 막대, `도움과 요청 변화`, `추천 조치`·`안부 확인함` | 리포트 AI 안내, 동향 요약(변화·특이사항·신청 내역·이유), 최근 활동 기록, 지난 월간 리포트 | 안부 확인 기록·조치 기록 없음 |
| 기관 행사의 종류·대상·이미지·노출 범위 칸, `조회 n` | 지금 폼(링크·제목·내용·날짜·시각·장소·정원) | 그런 칸이 서버에 없음 |
| 담당자 정보 패널 480(직무·전화·알림 스위치 3개) | `/o/my` 화면(묶음 카드) | 메뉴 17개가 패널에 들어가지 않음. 알림 스위치는 `/my/noti` |

**기존 동작 대조**(모바일 화면에 있던 것이 PC에 모두 있는지)
- 관리 대상 목록: 등록 · 줄 → 상세 · 이관 수락/거절 · 보낸 이관 대기 표시 · 종료 목록 · 안내 문구 — 모두 있음(이관 수락 뒤 새로 고침 대신 목록만 다시 받음).
- 관리 대상 상세: 수정 · 공고 대신 등록 · 봉사 공고에 요청 · 단골에게 요청 · 메모 쓰기 · 지난 요청 다시 올리기 · 정기 요청 · 말로 공고 · 통화 메모 · 서비스 동의 기록 · 주의사항 추가 · AI 정리 · 메모 음성/추가 · 요청 줄 → 요청 상세 · 받지 않기 풀기 · 기관 클래스 · 변화 카드 · 리포트 보기 · 월간 리포트 · 리포트 공유 동의 · 현관 QR · 이관 · 지원 종료 — 모두 있음.
- 등록·수정: 같은 본문을 쓴다(취소 버튼만 더함).
- 요청 상세 · 대신 요청 올리기: 본문이 기존 컴포넌트 그대로다.
- 특보: 특보 카드(지금·지난) · 운영자 안내(PC는 전체 글을 바로 보여 줌) · 담당 대상 보기 · 119 안내 — 모두 있음.
- 공공 연계: 진행 중/완료 · 요청 고르기 · 본인이 한 말·한마디·AI 안내·찾은 서비스·연락처 · 처리 메모 · 상태 4가지 — 모두 있음.
- 리포트: 기간 바꾸기 · 순위 카드 → (PC는 오른쪽 상세, `관리 대상 정보` 버튼으로 대상 상세) · 대상 고르기 · 최근 활동 기록 · 월간 리포트 · 변화 큰 사례로 표시 — 모두 있음.
- 기관 행사: 목록 → 행사 상세 · 올리기(링크 불러오기 포함) — 있음. 올린 뒤 행사 상세로 가지 않고 목록에 남는 것만 다르다.
- 내 정보·설정: 메뉴 17개 모두 있음.

**못 한 것 · 남은 것**
- 요청 상세 오른쪽, 대신 요청 작성, 가운데 카드 화면들은 본문이 모바일 구성 그대로다(색·크기만 맞춤). 이모지 → 선 아이콘 바꾸기도 이 화면들에는 하지 않았다.
- 도움 찾기(`/board?elder=`)의 왼쪽 필터 칸 배치, 단체 봉사 두 칸 배치, 행사 상세(`/news/:id`)는 하지 않았다.
- 5판에 있지만 넣지 않은 것은 7.22 표와 같다(AI 도우미, 권한 위임, 기관 공고 3종, 봉사자 현황, 실명·전화·주소, 안부 확인 기록).
- 운영자 틀·대시보드는 다음 차수.

### 7.24 운영자 PC 화면(5판)과 담당자 버튼 확인 (2026-11-13)
기준·명세는 7.22와 같다(명세 3절 "PC 5판 운영자 화면 구현 명세"). 화면 사진 `docs/specs/pc5/shots/3차-…png`. 서버·DB·API는 바꾸지 않았다(프런트만). 폭 900px 이하의 운영자 화면, 사용자 화면은 그대로다.

**틀**
- 역할이 ADMIN이고 폭이 901px 이상이면 담당자와 같은 PC 틀(`ui/PcFrame.jsx` `PcNav`·`PcTop`, `ui/PcLayout.jsx`)을 쓴다. 색만 운영자 값이다: `--acc #E0A23E / --acc-s #FCF0D9 / --acc-k #A6700F / --ai-bg #FFF9EE / --ai-bd #F3DFB8 / --band #DD9424`(`.pcw.adm`, 시트·토스트는 `body.pcw-on.pcw-adm`).
- 프로필 카드(`운영자` + 이름, 누르면 `/a/manage`) + 숫자 3줄 `처리 대기 n건 / 이번 달 활동 n건 / 등록 기관 n곳` — 모두 `/admin/dashboard`(`pending` 합, `kpi.activities`, `kpi.orgCount`). 처리 대기 합은 기관 신청·신분증·범죄경력·난이도 이의·신고·안부 확인 접수·문의를 더한 값이다(이상 적립 패턴은 묶음 수라 뺀다). 승인·답변처럼 자료를 바꾸는 호출 뒤에는 담당자와 같이 바로 다시 받는다.
- 위 막대에는 뒤로 · 제목 · 날짜만 있다(5판대로 종·프로필 알약 없음 — 운영자는 알림이 없다).

**운영자 메뉴**
| 그룹 | 항목 | 경로 | 5판 |
|---|---|---|---|
| 운영 | 대시보드 | `/a/dash` | A-01 |
| 운영 | 이용 리포트 | `/a/stats` | A-02 |
| 운영 | 재정 추계·시뮬레이션 | `/a/forecast` (`/a/sim`도 여기 불) | A-03(+A-04) |
| 운영 | 특보 알림 | `/a/weather` | A-05 |
| 운영 | 대응 추천 | `/a/recruit` | 5판에 없음(앱에만 있는 화면) |
| 관리 | 승인 | `/a/approve` (`/a/mgr-apps`는 PC에서 `?seg=mgr`로 넘김) | A-06 |
| 관리 | 신고 | `/a/safety` | A-07 |
| 관리 | 문의 | `/a/inquiries` | A-08 |
| 관리 | 공지사항 | `/a/notices` (`/notice…`도 여기 불) | A-09 |
| 관리 | 카테고리 건의 | `/a/suggest` | A-10 |
| 관리 | 공공 서비스 정보 | `/a/pub` | A-11 |
| 설정 | 회원 관리 | `/a/members` | 5판에 없음 |
| 설정 | 담당자 계정 | `/a/managers` | 5판에 없음 |
| 설정 | 봉사 인증 항목 | `/a/verify` | 5판에 없음 |
| 설정 | 기관 클래스 | `/a/classes` | 5판에 없음 |
| 설정 | 가상 상대(역할극) | `/a/persona` | 5판에 없음 |
| (맨 아래) | 계정 바꾸기 · 로그아웃 | 데모 계정 시트 · `/login` | 로그아웃 |
| (메뉴 밖) | 관리(전체 메뉴 · 회원 탈퇴) | `/a/manage` — 프로필 카드와 대시보드 "운영 도구"에서 감 | 5판에 없음 |

**화면별 배치** (`screens/adminPc.jsx`. 좁으면 `admin.jsx`·`adminReports.jsx`의 모바일 화면)
| 화면 · 경로 | PC 배치 |
|---|---|
| 대시보드 `/a/dash` | 인사 줄(`OOO 님, 처리 대기가 n건 있어요` + 이번 달 요청·가능 공고 수와 가장 부족한 지역) + 숫자 칩 4개 → [큰 패널: 특보 띠 + 2×2(처리 대기 · 최근 신고 3 · 새 기관 신청 3 · 미답변 문의 3) \| 오른쪽 360: 카테고리 건의 · 담당자 계정 신청 · 지역별 수요·공급] → [주별 수요·공급 꺾은선 + 작은 숫자 4칸 + 지역별 매칭 성공률 + 시간 균형 \| 카테고리별 수요·공급 카드 2×2 + AI 인사이트] → 운영 도구 |
| 이용 리포트 `/a/stats?tab=&type=` | 탭 `봉사 유형 / 나이별 인기 / 계절별 / 지역별 / 수요·공급` + 오른쪽 `[도움 요청 \| 봉사 가능]` 토글(나이별·계절별·지역별에만 걸림). 탭마다 두 칸 카드. 그래프 색은 5판 값(요청 `#5B8DEF` · 봉사 `#2F9C7F` · `#E0A23E` · `#D9705A`, 주는 쪽 `#6DB3A0` · 받는 쪽 `#6F9CF0`), 막대는 알약 모양 |
| 승인 `/a/approve?seg=org\|mgr\|vol\|diff` | 탭 `기관 신청 / 담당자 계정 / 봉사자 신원 / 난이도 이의` + 표 + 오른쪽 상세(정보 줄 · 증빙 열기 · `반려` `승인`). 기관 신청 표에는 처리한 신청도 상태와 함께 남는다 |
| 신고 `/a/safety?tab=open\|wf\|ab\|done` | 탭 `신고 / 안부 확인 접수 / 이상 적립 / 처리 완료` + 표 + 오른쪽 상세(신고 정리 AI · 관련 기록 · 처리 4가지 · 결과 메모 — 모바일 처리 시트와 같은 본문) |
| 문의 `/a/inquiries` | 표 + 오른쪽(처리 상태 칩 4개 · 문의 내용 · 답변 초안 만들기 · 답변 등록) |
| 공지사항 `/a/notices` | 왼쪽(전달 전 특보가 있으면 "특보로 공지 쓰기" 카드 + 올린 공지 표: 고정·수정·삭제) · 오른쪽(새 공지 / 수정 폼) |
| 카테고리 건의 `/a/suggest` | 숫자 2칸 + 표(이름 · 설명 · 건의자 · 같은 건의 · 상태) + 오른쪽(건의 글 목록 · 상태 · 등급 `기본 1.0 / 수고 1.2` · 답변) |
| 공공 서비스 정보 `/a/pub` | 표 + 오른쪽 패널 480(등록·수정 폼) |
| 회원 관리 `/a/members` | 검색 · 역할 칩 + 표 + 오른쪽 상세(정지·해제) |
| 담당자 계정 `/a/managers` | 표(정지·해제 · 대신 이관 시트) |
| 재정 추계 `/a/forecast` · 정책 시뮬레이션 `/a/sim` · 특보 알림 `/a/weather` · 대응 추천 `/a/recruit` · 봉사 인증 항목 · 기관 클래스 · 가상 상대 · 관리 | 가운데 카드 760(모바일 본문 그대로, 색·버튼만 운영자 값) |

**대시보드 위 네 칸**: 대표 지시대로 `이번 달 활동 · 매칭률 · 신규 가입 / 총 가입자 · 등록 기관 수`. 5판은 셋째 칸이 "총 회원"이지만 신규 가입을 지웠을 뿐이라 대표 지시를 따랐다.

**꺾은선 그래프**(`LineChart5`, 라이브러리 없이 SVG): 선 2.5px, 첫 계열 아래 면 투명도 .08, 끝점 반지름 5 흰 테두리, 가로 눈금 점선 `2 4`, 눈금 글자 11px `#A3ACBA`. 마우스를 올리거나 키보드로 옮기면 세로선·점과 값 말풍선(5판 `#ctip`)이 뜬다. 눈금 한 칸은 1·2·5·10·25… 가운데 값을 다 담는 가장 작은 값.

**5판과 다르게 채운 구역**
| 5판 | 넣은 것 | 이유 |
|---|---|---|
| 대시보드 오른쪽 "이번 주 운영 일정"(날짜 띠 + 일정) | **카테고리 건의**(답변 기다리는 것 3줄) · **담당자 계정 신청**(최근 3줄) | 운영 일정 자료가 없다 |
| 대시보드 인사 아래 한 줄(AI 요약 문장) | 이번 달 도움 요청·봉사 가능 공고 수 + 가장 부족한 지역(`/admin/analytics` `areaShortage`) | AI 문장 대신 코드가 센 값 |
| 처리 대기 4줄(기관·담당자 계정·신고·문의) | 기관 가입 승인 · 봉사자 신원 · 신고 검토 · 미답변 문의 + (있을 때만) 난이도 이의 · 안부 확인 접수 · 이상 적립 패턴 | 담당자 계정 신청은 자동 승인이라 대기가 없고, 앱에는 신원·이의·안부 접수가 있다 |
| 주별 꺾은선 8주 | 4주 | `/admin/dashboard` `weekly`가 4주 |
| 작은 숫자 `평균 매칭 4.1시간`, 증감 `+3%` | `이번 달 공고(요청 / 봉사 가능)`, 지난주와의 차(건수) | 평균 매칭 시간·증감률 집계 없음 |
| "카테고리별 이번 달"(건수 + 지난달 대비 %) | "카테고리별 수요·공급"(요청 건수 · 봉사 가능 · 부족 건수, 최근 n일) | 지난달 대비 집계 없음 |
| "이번 달 한 줄 요약" AI 카드 | 기존 AI 인사이트 카드(공급 부족 · 사용자 건의 · 새 카테고리 후보 · 다시 분석) | 지금 있는 기능을 그대로 |
| 지역별 수요·공급(동마다 두 줄 막대 + 배수) | 같음. 값은 `/admin/stats/by-type`의 도움 요청·봉사 가능 공고 수 | - |
| (5판에 없음) | 지역별 매칭 성공률 · 시간 균형 · 운영 도구(가상 상대 · 정책 시뮬레이션 · 운영자 계정 · 스케줄러 실행) | 모바일 대시보드에 있던 것 |
| 이용 리포트의 지역 칩 | 없음(지역별 탭으로) | 지역으로 거르는 API 없음 |
| 승인 3탭 · 서류 미리 보기 · 반려 사유 칸 | 4탭(난이도 이의 더함) · 증빙 `열어 보기` 링크 · 반려 사유 칸 없음 | 앱에 난이도 이의가 있고, 앱의 반려는 사유 칸 없이 처리한다 |
| 신고 상세의 QR·메시지 기록 줄, 버튼 `기각 / 경고 / 이용 정지` | 관련 기록 카드(지난 신고 · 관련 활동 · 메시지) + 처리 `경고 / 이용 정지 / 퇴출 / 신고 기각` | 앱 처리 규칙 그대로 |
| 공지사항의 말투 고르기 · `AI로 문장 다듬기` · 상단 고정 스위치 | 없음. 고정은 표의 `고정` 링크로 | 공지 다듬기 AI 없음 |
| 공지사항 "특보로 공지 쓰기 → 공지 초안 만들기" | 전달 전 특보 줄 + `특보 알림에서 전달 ›` | 앱은 특보 알림에서 공지 게시와 담당자 알림을 한 번에 보낸다 |
| 카테고리 건의 표 안의 `×1.0 \| ×1.2` 선택과 승인·반려 버튼 | 오른쪽 답변 칸에서 상태(접수·검토 중·반영·보류)와 등급을 고른다 | 앱은 답변 글을 함께 보낸다 |
| AI 도우미(운영 도우미) | 없음 | 대표 결정 전(7.22와 같음) |

**모바일 대시보드에 있던 것이 PC에서 간 곳**: 처리할 일 태그 → 처리 대기 줄 · 리포트 메뉴 5개 → 왼쪽 메뉴 · 수요 vs 공급(주별 막대) → 꺾은선 · 이번 달 공고 수 → 인사 줄과 작은 숫자 칸 · 지역별 매칭 성공률·시간 균형·AI 인사이트·스케줄러·가상 상대 관리 → 대시보드 · 나이대별 인기 봉사·월별 인기 봉사·카테고리별 수요 vs 공급·계절별 요청·가능·지역별 부족한 도움 → 이용 리포트의 탭(나이별 인기 · 계절별 · 수요·공급 · 지역별).

**담당자 PC 버튼 확인** (새로 만든 확인용 DB에서 org1로 실제로 눌러 봄)
| 항목 | 결과 | 고친 것 |
|---|---|---|
| 관리 대상 등록(패널: 별칭·도움·주의사항·동의 → 등록하기) | 됨 — 상세로 가고 목록·프로필 숫자 4 → 5명 | - |
| 관리 대상 수정(별칭·나이대 → 저장) | 됨 — 패널이 닫히고 상세·목록에 바로 반영 | - |
| 메모 쓰기(칩 → 메모 칸으로 이동 → 추가) | 됨 | - |
| 주의사항 추가 | 됨(`기존 / 새 것`으로 붙음) | - |
| 대신 요청 올리기(새로운 사람에게 → 예시 문장 → 시각 → 시간 → 공고 등록) | 됨 — 공고 화면으로 감. 1시간 × 수고 1.2 = +1.0시간(0.5 단위) | - |
| 요청 보드 카드 → 상세 → 대신 최종 수락 | 됨 — 확인 시트 뒤 확정, 처리할 일 3 → 2건 | - |
| 요청 보드 카드 → 상세 → 대신 확인·평가(일지 확인) | 됨 — 처리할 일 2 → 1건 | - |
| 편성표 빈칸 → 봉사자 추천 보기 | 됨 — 추천 시트(5명 · 단골 요청하러 가기 · 이 칸에 공고 올리기) | - |
| 공공 서비스 연계 상태 바꾸기·처리 메모 | 됨 — `연락했어요` + 메모, 새로 열어도 남아 있음(연계 요청은 사용자 계정으로 하나 만들어 확인) | - |
| 기관 행사 올리기 | 됨 — 왼쪽 목록에 바로 나오고 폼이 비워짐(링크·제목이 없으면 안내 문구) | - |
| 알림 드롭다운에서 항목 누르기 | 됨 — 읽음 처리 뒤 그 화면으로 | - |
| 처리 뒤 왼쪽 프로필 숫자 | 됨 — 새로 고침 없이 바뀜 | - |
| 요청 상세 목록·대신 요청 왼쪽 칸이 위 막대에 붙음(2차에서 남은 것) | 고침 | `.sticky` `top:0` → `16px` |
| 요청 상세 봉사자 카드의 파란 기(2차에서 남은 것) | 고침 | `.intro`·`.reg` 바탕을 역할 색 연한 바탕으로 |

**못 한 것 · 남은 것**
- 재정 추계·정책 시뮬레이션을 한 화면으로 합치기와 5판 꺾은선(역전 표시), 특보 알림 두 칸 배치, 대응 추천·봉사 인증 항목·기관 클래스·가상 상대의 표 배치는 하지 않았다(가운데 카드에 모바일 본문 그대로).
- 5판에 있지만 넣지 않은 것: AI 도우미, 이번 주 운영 일정, 공지 문장 다듬기, 평균 매칭 시간·증감률.
- 대시보드 왼쪽 아래 칸은 오른쪽 AI 인사이트가 길 때 아래가 빈다.
- 개발 모드(React StrictMode)에서 재정 추계 화면을 열면 `POST /admin/forecast/summary`가 두 번 겹쳐 가서 한 번이 500(같은 달 요약 중복 저장)으로 떨어진다. 빌드한 화면에서는 한 번만 부른다. 서버를 고치지 않아 그대로 두었다.
