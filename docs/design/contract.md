# 시간 적금 — 구현 계약서 (백엔드·프론트 공통 기준)

이 문서는 백엔드와 프론트가 **동시에** 개발할 때 서로 맞춰야 하는 약속이다. 경로·필드 이름·상태 값은 여기 적힌 대로 쓴다.
더 필요한 엔드포인트·필드는 추가해도 되지만, 추가한 것은 이 문서 맨 아래 "추가 기록"에 적는다(백엔드가 적고, 프론트는 읽는다).

- 기준 요구사항: `docs/requirements/요구사항명세서.txt` (A-01 ~ D-09, P-01 ~ P-07)
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
| 동행 | 책임(RESPONSIBLE) | 1.5 |

- 등급 가중치: BASIC 1.0 / EFFORT 1.2 / RESPONSIBLE 1.5 (B-27). AI(또는 규칙)가 요청 문장에서 등급과 근거를 **제안**하고, 가중치 값은 규칙으로 적용.
- 적립 = 활동 시간 × 가중치, 0.5 단위 반올림 (B-26). 예: 동행 2시간 → 3.0, 장보기 1.5시간 → 1.8 → 2.0.
- 복합 요청(B-29): 여러 카테고리가 섞이면 **최고 등급** 기준.
- 일정(B-10): 30분 단위, 1회 0.5~4시간, 요청·제안 시간은 공고 시간 범위 안. 공고 1개는 수락된 요청 1건만.

### 3.2 요청·제안 상태 (A-05, B-17~B-19, B-22~B-25, C-07, C-10)
`kind`: `REQUEST`(봉사받는 쪽이 봉사 가능 공고에 요청) / `PROPOSAL`(봉사하는 쪽이 도움 요청 공고에 제안) / `REGULAR`(단골에게 공고 없이 직접 요청)

```
REQUEST·REGULAR: SENT --(봉사자 수락)--> VOL_ACCEPTED --(받는 사람/담당자 최종 수락)--> CONFIRMED
PROPOSAL:        SENT --(받는 사람/담당자 수락 = 최종)--> CONFIRMED
CONFIRMED --(시작 체크인: 6자리 코드/QR)--> IN_PROGRESS --(종료 체크아웃)--> CHECKED_OUT
CHECKED_OUT --(봉사자 활동 일지 제출)--> LOG_SUBMITTED --(받는 사람 확인 / 담당자 대신 확인 / 24시간 뒤 자동 확정)--> DONE
DONE --(평가)--> 평가는 별도 테이블, 상태는 DONE 유지
거절: REJECTED(봉사자 또는 받는 쪽 1차 거절, 사유 필수) / DECLINED(최종 거부) / EXPIRED(24시간 무응답) / CANCELLED(취소) / NOSHOW(봉사자 노쇼)
```
- 각 응답 단계 기한 24시간(`deadlineAt`). 스케줄러가 지나면 `EXPIRED` + 요청한 쪽 알림. 최종 거부·만료로 끝나도 **공고는 다시 열리지 않는다**(B-19).
- 공고의 요청 하나가 CONFIRMED가 되면 공고는 `MATCHED`, 같은 공고의 나머지 SENT/VOL_ACCEPTED는 자동 `REJECTED`(사유 "다른 요청이 수락됨") + 알림.
- 일반 사용자가 받는 쪽이면: CONFIRMED 때 받는 쪽 통장에서 예상 적립 시간만큼 **보류(HOLD)**, DONE 때 보류 해제 후 실제 시간만큼 **차감(USE)** (B-30). 독거노인 건은 차감·보류 없음, 봉사자만 적립 (B-31).
- 본인 사용 조건(B-33): 받는 쪽 일반 사용자는 "본인이 적립한 누적 시간(선물받은 시간 제외)" 10시간 이상이어야 요청 가능. 단, 선물받은 시간 잔액은 이 조건 없이 사용 가능. 잔액 부족이면 요청 불가.
- 체크인: 받는 사람 화면(또는 담당자 화면)에 6자리 코드+QR을 띄우고, 봉사자가 코드 입력(또는 QR 스캔 모의)으로 시작/종료. 시작 예정 15분이 지나도 체크인이 없으면 받는 쪽(독거노인은 담당자)에게 알림.
- 실제 시간이 예정과 다르면 봉사자가 시간 조정 요청(`adjust`) → 받는 쪽 승인 시 반영 (B-22). 기본 적립은 **예정 시간** 기준.
- 자동 확정(C-10): LOG_SUBMITTED 후 24시간 동안 받는 쪽이 확인하지 않으면 DONE. 일지가 없으면 자동 확정하지 않음.
- 취소(B-25): 시작 24시간 전까지 무료 취소. 그 뒤 봉사자가 취소하거나 노쇼면 `penaltyCount+1`, 경고 알림.
- 메시지(A-08): 상대가 수락한 뒤(VOL_ACCEPTED 또는 CONFIRMED 이후)부터 DONE 전까지 작성 가능, 끝나면 읽기만. 연락처·계좌 패턴(전화번호, 카톡, 계좌)은 서버가 막지 않되 경고 문구를 붙이고 신고 버튼 제공.

### 3.3 시간 통장
- `ledger` 거래 종류: `EARN`(적립) / `HOLD`(보류) / `RELEASE`(보류 해제) / `USE`(사용·차감) / `GIFT_OUT` / `GIFT_IN` / `CLASS_FEE`(수강) / `CLASS_EARN`(선생님 적립) / `ADJUST`(운영자 조정) / `REFUND`.
- 잔액 = 합계(보류 제외), 보류 = 미해제 HOLD 합.
- 시간 선물(B-32): 승인된 가족 연결 상대에게만. 선물·수강 외 양도·매매 없음(B-34).
- 동네 클래스: 수강생은 `수업 시간 × 0.5`(0.5 단위)를 신청 때 보류, 출석 때 차감. 선생님은 `수업 시간 + 준비 1시간` 적립(어르신 선생님은 담당자 기관이 대신 받는 것으로 기록만). 정원이 다 차서 남는 시간은 "공동 적립"(community pool)으로 기록.

### 3.4 독거노인(도움 대상) — 개인정보 최소
- 저장: `alias`(별칭), `dong`(동 단위), `ageGroup`(예: 80대), `needs`(카테고리), `caution`(방문 주의사항), 담당자 메모. 이름·주소·연락처는 **저장하지 않는다**(C-02).
- 봉사자에게는 별칭을 가린 표시(`displayName`: 첫 글자 + ○ + 끝 글자 + " 님", 예: "이○례 님")·동·도움 내용·시간·주의사항만. 담당자에게는 별칭 원문.
- 수락된 활동에 한해 담당자가 메시지로 방문 장소를 안내(C-02, B-13).
- 독거노인 건의 알림·메시지·최종 수락·확인·평가는 담당자가 대신(C-07~C-10, B-38).

### 3.5 단골(B-41)
같은 봉사자가 같은 받는 사람(사용자 또는 독거노인)을 DONE 3회 이상 → 단골. 프로필·소개 카드에 "○○ 님의 단골" 배지, `단골에게 요청하기` 목록에 사용.

## 4. 데이터 모델 (테이블 요약)

`users`(id, login_id, password_hash, name, phone, role, status[ACTIVE/SUSPENDED/WITHDRAWN], area, age_group, intro, experience, personality, categories, time_slots, photo_url, org_id, id_status[NONE/PENDING/APPROVED/REJECTED], id_file_url, crime_status[NONE/PENDING/APPROVED/REJECTED], crime_file_url, edu_done, voice_alert, text_size, penalty_count, warning_count, created_at)
`organizations`(id, name, org_type, address, phone, manager_name, manager_phone, manager_email, status[PENDING/APPROVED/REJECTED], memo, created_at)
`elders`(id, org_id, manager_id, alias, dong, age_group, needs, caution, status[ACTIVE/ENDED], report_consent, created_at)
`elder_memos`(id, elder_id, text, created_at) · `elder_transfers`(id, elder_id, from_manager_id, to_manager_id, status[PENDING/ACCEPTED/REJECTED/ADMIN], created_at)
`posts`(id, type[OFFER/NEED], owner_id, elder_id, created_by, date, start_hour, end_hour, area, categories, title, detail, note, est_hours, difficulty, difficulty_reason, weight, repeat_rule, series_id, status[OPEN/MATCHED/CLOSED/DELETED], created_at)
`requests`(id, kind, post_id, sender_id, elder_id, volunteer_id, recipient_id, date, start_hour, hours, category, content, memo, difficulty, weight, est_earn, status, reject_reason, deadline_at, check_code, check_in_at, check_out_at, check_method, log_raw, log_text, log_photo_url, photo_consent, adjust_hours, adjust_status, earned, confirmed_at, done_at, created_at)
`messages`(id, request_id, sender_id, sender_label, system_msg, text, warn, created_at)
`ledger`(id, user_id, type, amount, base_hours, weight, request_id, class_id, label, created_at)
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
`classes`(id, teacher_id, teacher_elder_id, created_by, mode[ONE/GROUP], kind, title, intro, steps_json, items, caution, level, level_reason, date, start_hour, hours, place, space_id, capacity, status[SPACE_PENDING/OPEN/DONE/CANCELLED], note_json, bio, tags, met, weekly_cap, qs_json, slots_json, meet, teacher_label, teacher_age_label, created_at)
`class_enrollments`(id, class_id, user_id, status[APPLIED/ATTENDED/CANCELLED], review, one_status[WAIT/OK/MET/DONE/CANCELLED], slot_date, slot_hour, question, proposed, note, check_in_at, check_out_at, created_at)
`spaces`(id, org_id, name, capacity, equipment)
`auth_token`(token, user_id, expires_at)

## 5. API 목록

표기: `메서드 경로 — 설명 [역할] (요구사항 ID)`. 요청/응답 필드는 4장의 열 이름을 camelCase로 쓴 것과 같다(예: `start_hour` → `startHour`).

### 5.1 인증·회원 (A-01~A-04, A-09, B-01, C-01)
- `POST /api/auth/phone/send` {phone} → {demoCode} — 모의 본인인증
- `POST /api/auth/phone/verify` {phone, code} → {verified:true, verifyToken}
- `POST /api/auth/signup` {loginId, password, name, phone, verifyToken, area, ageGroup, categories[], timeSlots[], intro} → {token, user} [공개]
- `POST /api/auth/login` {loginId, password, keep} → {token, user}
- `POST /api/auth/logout`
- `POST /api/auth/password/reset` {loginId, phone, verifyToken, newPassword}
- `GET /api/me` → user(본인) + `{balance, hold, earnedSelf, regularBadges[], ratingAvg, ratingCount, eduDone, verify:{idStatus, crimeStatus, eduDone}}`
- `PUT /api/me` {name, phone, area, ageGroup, intro, experience, personality, categories[], timeSlots[], photoUrl, textSize, voiceAlert}
- `PUT /api/me/password` {currentPassword, newPassword}
- `GET /api/me/withdraw-check` → {activeRequests:[…], balance, hold, message} · `POST /api/me/withdraw` {password}
- `POST /api/me/verify/id` {fileUrl} · `POST /api/me/verify/crime` {fileUrl} — 운영자 승인 대기(PENDING)
- `POST /api/orgs/apply` {name, orgType, address, phone, managerName, managerPhone, managerEmail, memo} [공개] → {id, status}
- `GET /api/users/{id}/profile` → 공개 프로필(이름, 나이대, 한 줄 소개, 경험, 평균 별점·횟수, 태그 상위, 범죄경력 배지, 교육 수료 배지, 단골 배지 목록)

### 5.2 공고 (B-02~B-11, B-20, B-21, C-04)
- `GET /api/posts?type=OFFER|NEED&area=&date=&cat=&sort=near|new` → [PostCard] (B-20). PostCard: {id,type,ownerId,ownerName,elderId,elderDisplayName,date,startHour,endHour,area,categories[],title,detail,note,estHours,difficulty,weight,estEarn,repeatRule,status,distanceKm,owner:{ratingAvg,ratingCount,ageGroup,crime,eduDone},mine}
- `GET /api/posts/{id}` → PostCard + `requests`(내가 공고 주인이면 받은 요청·제안 목록)
- `POST /api/posts` {type, date, startHour, endHour, area, categories[], title, detail, note, repeat:{weekly:true, weeks:4}?, elderId?} → 생성된 Post(반복이면 주마다 1개씩 생성, `seriesId` 공유) (B-03~B-07, C-04). 서버는 저장 전에 금지 필터(B-09)를 돌려 불가면 400 `FORBIDDEN_REQUEST`.
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
- `POST /api/ai/roleplay` {personaId, history:[{who:'me'|'them', text}], text} → {quality:'good'|'ok'|'poor'|'bad', flag:null|'연락처'|'금전·선물'|'신체 돌봄'|'개인정보', next, done} — 교육 역할극

### 5.4 요청·제안·활동 (B-12~B-19, B-22~B-25, B-28, C-05~C-10)
- `POST /api/requests` {kind, postId?, volunteerId?(REGULAR), elderId?(담당자 대리), date, startHour, hours, category, content, memo} → Request (B-14~B-16, C-05, C-06)
- `GET /api/requests?box=received|sent|active|done|all` → [RequestView]. RequestView: {id,kind,postId,postTitle,status,date,startHour,hours,category,content,memo,difficulty,weight,estEarn,deadlineAt,leftText,volunteer:{id,name,ageGroup,ratingAvg,ratingCount,intro,experience,tags[],crime,eduDone,isRegular},recipient:{id,name}|null,elder:{id,displayName,dong,caution}|null,sender:{id,name},myRole:'VOLUNTEER'|'RECIPIENT'|'MANAGER',actions:[…가능한 버튼 이름],checkCode?(받는 쪽에만),checkInAt,checkOutAt,logText,logPhotoUrl,earned,rated:boolean,adjust:{hours,status}}
- `GET /api/requests/{id}` → RequestView + `messages`
- `POST /api/requests/{id}/accept` (봉사자 수락 또는 받는 쪽 수락/최종 수락 — 서버가 상태로 판단)
- `POST /api/requests/{id}/reject` {reason} · `POST /api/requests/{id}/decline` {reason} (최종 거부)
- `POST /api/requests/{id}/cancel` {reason}
- `POST /api/requests/{id}/check-in` {code} · `POST /api/requests/{id}/check-out` {code} (코드 대신 `{method:'manual'}`이면 받는 쪽 확인 필요 표시)
- `POST /api/requests/{id}/log` {raw, text, photoUrl, photoConsent} (B-24)
- `POST /api/requests/{id}/confirm` (받는 쪽/담당자 확인 → DONE, 적립·차감) (B-23)
- `POST /api/requests/{id}/adjust` {hours, reason} · `POST /api/requests/{id}/adjust/answer` {accept:boolean}
- `POST /api/requests/{id}/rate` {stars, tags[], comment} (B-38, B-39 — 받는 쪽 평가는 is_private)
- `POST /api/requests/{id}/appeal` {reason, requestedLevel} (B-28)
- `GET /api/requests/{id}/messages` · `POST /api/requests/{id}/messages` {text} (A-08, C-09)
- `GET /api/regulars` → 내 단골 봉사자 목록 (B-14) · `GET /api/regulars?elderId=` (C-06)

### 5.5 시간 통장 (A-06, B-30~B-34, B-46)
- `GET /api/bank` → {balance, hold, earnedSelf, giftReceived, canUseSelf, monthIn, monthOut, monthly:[{month, in, out}], entries:[{id,type,amount,baseHours,weight,label,createdAt}]}
- `POST /api/bank/gift` {toUserId, amount, message} — 연결된 가족만

### 5.6 알림·메시지·공지·문의·신고 (A-07, A-10, B-36, B-37, B-47~B-49)
- `GET /api/notifications` · `POST /api/notifications/{id}/read` · `POST /api/notifications/read-all` · `GET /api/notifications/unread-count`
- `GET /api/notices` · `GET /api/notices/{id}`
- `GET /api/inquiries/mine` · `POST /api/inquiries` {type,title,content}
- `POST /api/reports` {targetType,targetId,targetLabel,type,content} · `GET /api/reports/mine`

### 5.7 마이페이지 (B-43~B-47, B-32, B-21)
- `GET /api/my/activities?seg=all|help|helped|posts` → 활동 내역 (B-45)
- `GET /api/family` → [{id, user:{id,name}, relation, status, direction:'out'|'in', shareReport}] · `POST /api/family` {loginIdOrPhone, relation} · `POST /api/family/{id}/answer` {accept} · `PUT /api/family/{id}/share` {share} (P-06) · `DELETE /api/family/{id}`
- `GET /api/family/{userId}/report` → 가족 생활 변화 리포트(공유 동의한 경우만) (P-04)
- `GET /api/certificate` → 봉사 확인서 데이터 {name, period, totalHours, count, byCategory:[…], items:[…]}

### 5.8 첫 활동 교육 (B-01 안전 교육, 프로토타입 edu)
- `GET /api/edu` → {diag, cardsDone, quizScore, roleplay:{personaId:{passed, comm, emp, safe}}, completed, personas:[…], rules:[…], quiz:[…]}
- `PUT /api/edu/diag` {cats[], who, exp} · `POST /api/edu/cards-done` · `POST /api/edu/quiz` {answers:[번호…]} → {score, results:[…]}
- `POST /api/edu/roleplay/{personaId}/finish` {log:[…]} → {safe, comm, emp, pass, fixes[]}
- `POST /api/edu/complete` → 수료(조건: 카드 완료, 퀴즈 4/5 이상, 역할극 1명 이상 통과) → `users.edu_done=true`

### 5.9 동네 클래스 (프로토타입 cls)
- `GET /api/classes?kind=` → [Class] · `GET /api/classes/{id}` · `POST /api/classes` {kind,title,intro,steps:[{t,min}],items,caution,date,startHour,hours,capacity,teacherElderId?} → 상태 SPACE_PENDING (금지 필터·난이도(허용/주의) 함께)
- `POST /api/classes/{id}/apply` · `POST /api/classes/{id}/cancel` · `POST /api/classes/{id}/attend` {code}
- `GET /api/classes/{id}/qr` (선생님/담당자: 출석 코드) · `POST /api/classes/{id}/finish` {steps[], tip} → 배움 노트, 정산 · `POST /api/classes/{id}/review` {text}
- `GET /api/classes/mine` → {teaching:[…], applied:[…], past:[…]}
- 담당자: `GET /api/org/spaces` · `POST /api/org/spaces` · `POST /api/org/classes/{id}/assign-space` {spaceId, place}
- **1:1 클래스(황수빈 v2)**: `POST /api/classes` {mode:'ONE', kind, title, intro, steps, hours(기본 1), slots:[{date, hour}], meet, bio, tags[], qs[], weeklyCap(기본 2), teacherElderId?} → 공간 배정 없이 바로 OPEN
- `POST /api/classes/{id}/one/apply` {slotDate, slotHour, question} (질문 필수, 슬롯은 선생님이 정한 것 중) → 수강생 HOLD h · `POST /api/classes/{id}/one/cancel` · `POST /api/classes/{id}/one/accept-proposal`
- `POST /api/classes/one/enrollments/{eid}/accept` (선생님/담당자) · `POST /api/classes/one/enrollments/{eid}/propose` {date, hour}
- `POST /api/classes/{id}/one/start` {code} (만나서 시작 QR: 보류 해제·−h, 선생님 +h) · `POST /api/classes/{id}/one/end` {code} (마침 QR: 만남 끝, met+1) · `POST /api/classes/{id}/one/note` {text}
- `POST /api/classes/check-topic` {text} → {level, reason, category, source:'rule'} (수업 열기 인터뷰 금지 주제 확인)

### 5.10 담당자 (C-02~C-10, P-05)
- `GET /api/org/home` → {todo:[…], counts:{elders, pendingFinal, logsToConfirm, active}, alerts:[…]}
- `GET /api/org/elders` · `POST /api/org/elders` · `GET /api/org/elders/{id}` (+memos, regulars, recent requests) · `PUT /api/org/elders/{id}` · `POST /api/org/elders/{id}/memos` {text}
- `POST /api/org/elders/{id}/end` (진행 중 요청 있으면 409) · `POST /api/org/elders/{id}/transfer` {toManagerId} · `GET /api/org/transfers` · `POST /api/org/transfers/{id}/answer` {accept}
- `PUT /api/org/elders/{id}/consent` {reportConsent} (P-06 대리 입력)
- `GET /api/org/managers` → 같은 기관 담당자 목록(이관 대상)
- `GET /api/org/requests?box=` → 담당 대상 전체 요청·제안 (RequestView, myRole=MANAGER)
- `GET /api/org/reports?month=` · `POST /api/org/reports/{id}/flag` (P-05)

### 5.11 운영자 (D-01~D-09, P-02, P-07)
- `GET /api/admin/dashboard` → {supply, demand, elderUsage, matchRateByArea:[{area,rate,total}], timeBalance:{issued, consumed, elderFree, pool}, monthly:[…], pending:{orgs, ids, crimes, appeals, reports, inquiries}}
- `GET /api/admin/orgs` · `POST /api/admin/orgs/{id}/approve` → {managerLoginId, tempPassword} (담당자 계정 발급) · `POST /api/admin/orgs/{id}/reject`
- `GET /api/admin/managers` · `POST /api/admin/managers/{id}/suspend|unsuspend` · `POST /api/admin/elders/{id}/transfer` {toManagerId}
- `GET /api/admin/verifications` → 신원(id)·범죄경력(crime) 대기 목록 · `POST /api/admin/verifications/{userId}/{kind}/approve|reject`
- `GET /api/admin/appeals` · `POST /api/admin/appeals/{id}/answer` {accept, resultLevel, note} (승인 시 적립 차액 ADJUST)
- `GET /api/admin/reports` · `POST /api/admin/reports/{id}/handle` {action, result} (D-02)
- `GET /api/admin/anomalies` → 같은 쌍의 짧은 활동 반복 등 (D-06) · `POST /api/admin/anomalies/{requestId}/hold|release`
- `GET /api/admin/users?q=&role=` · `GET /api/admin/users/{id}` · `POST /api/admin/users/{id}/suspend|unsuspend` (D-07)
- `GET /api/admin/inquiries` · `POST /api/admin/inquiries/{id}/answer` {answer} (D-08)
- `POST/PUT/DELETE /api/admin/notices` (D-09)
- `GET/POST/PUT/DELETE /api/admin/public-services` (P-02)
- `GET/POST/PUT/DELETE /api/admin/personas` (가상 상대 관리)
- `POST /api/admin/reports/generate` {month?} → 생활 변화 리포트 즉시 생성 (P-07)
- `POST /api/admin/scheduler/run` → 만료·자동 확정·노쇼·공고 마감·리마인드를 즉시 한 번 실행(시연용)

## 6. 데모 계정 (DataSeeder)

| 역할 | 아이디 / 비밀번호 | 내용 |
|---|---|---|
| 사용자 | `yujin` / `1234` | 손유진, 행복동, 20대 — 프로토타입 `D.me` |
| 사용자 | `sky`, `mikyung`, `doyun`, `bok`, `mom` / `1234` | 김하늘·최미경·이도윤·김복남·이순자(손유진 가족) |
| 사용자 | `youngho`, `miyoung` / `1234` | 박영호(70대)·이미영(60대) — 1:1 클래스 어르신 선생님(화면 표시는 박○호 72세·이○영 68세) |
| 담당자 | `org1` / `1234` | 박지원 (행복동 주민센터) — 도움 대상 4명(별칭 이순례·정말순·한영자·오덕수) |
| 담당자 | `org2` / `1234` | 같은 기관 다른 담당자(이관 시연) |
| 운영자 | `admin` / `1234` | 김운영 |

시드 데이터는 프로토타입 `D.P`(공고), `D.R`/`D.RE`(요청), `D.L`(통장), `D.N`(알림), `CLS`(클래스), `RP`(역할극 상대), `QZ`(퀴즈), 공공 서비스 10개 안팎을 **오늘 날짜 기준 상대 날짜**로 옮긴다.

## 7. 추가 기록 (개발 중 계약에 더한 것)

- (백엔드가 여기에 추가)

### 7.1 말로 요청하기 — `POST /api/ai/post-chat` 확장 (음성 분석보고서 4.8, 2026-11-09)
- 요청에 `inputMode: 'text' | 'voice'` (기본 text). voice면 AI 프롬프트에 "받아쓰기라 띄어쓰기·오타·군말 섞임, 존댓말로 짧게 한 번에 하나만 되묻기, reply는 소리로 읽기 좋은 1~2문장"을 넣고, 규칙 모드도 되묻기 문장을 짧게 한다.
- 응답에 추가: `intent`(`NEW_REQUEST`|`REGULAR_REQUEST`|`CHANGE_CANCEL`|`CONFIRM`|`QUESTION`|`CHAT`|`EMERGENCY`), `confidence`{`date`,`time`,`area`,`category`: `high`|`medium`|`low`}, `safety`{`emergency`:boolean, `piiRemoved`:[`phone`|`account`|`address`]}, `inputMode`, `draft.caution`, `draft.repeat`(예 "매주 목요일" 또는 null), `draft.summarySpeech`(done일 때만, 예 "11월 17일 화요일 오후 2시부터 2시간, 병원 동행 요청서를 만들었어요. 올릴까요?").
- `missing` 값: `content`(도움 내용·카테고리) / `date` / `startHour` / `endHour`(OFFER만) / `estHours`(NEED만) / `area`. 한 번에 첫 항목 하나만 되묻는다.
- 위급 표현("쓰러졌", "숨이 안", "피가 나" 등)이면 `intent=EMERGENCY`, `safety.emergency=true`, `done=false`, reply로 119 안내. 전화번호·계좌·상세 주소(아파트 동·호수, 번지, 도로명 번지)는 draft 본문에서 지우고 "(수락 뒤 메시지로 안내돼요)"로 바꾼다(AI 결과에도 서버가 다시 적용). 주의사항·세부 내용에는 병명을 남기지 않고 방문에 필요한 문장("걸음이 불편하셔서 천천히 걸어 주세요")만 둔다.
- `CHANGE_CANCEL`/`CONFIRM`, 그리고 카테고리 없는 `QUESTION`/`CHAT`이면 요청서를 만들지 않고 reply로 해당 화면(내 활동, 공공 서비스 안내, 말동무 요청)을 안내한다.
- 시각에 오전·오후가 없으면 1~6시는 오후로 보고 `confidence.time=medium`. 장소를 말하지 않으면 프로필 지역(담당자 대리면 대상의 동)을 채우고 `confidence.area=medium`.
- `GET /api/ai/status` → `{mode: 'ai'|'rule'}` (키 유무 표시용).

### 7.2 공고
- `POST/PUT /api/posts` 본문에 선택 `difficulty`(BASIC/EFFORT/RESPONSIBLE), `difficultyReason`: 화면에서 AI 제안을 받아 확정한 등급. 카테고리 기본 등급보다 낮으면 기본 등급으로 올린다. 없으면 서버 규칙으로 정한다.
- `POST /api/posts` 응답 = 첫 공고 PostCard + `createdCount`, `createdIds`(반복이면 여러 개).
- PostCard 추가 필드: `elderCaution`, `difficultyReason`, `seriesId`, `createdAt`, `owner.intro`, `owner.photoUrl`, `owner.id`, `owner.name`. `estEarn`은 NEED만(= estHours × weight), OFFER는 null(요청 때 시간으로 계산).
- `GET /api/posts/mine`: 각 카드에 `requestCount`(응답 대기 요청 수). `GET /api/posts/{id}`: 주인이 아니면 `requests: []`와 `myRequest`(내가 보낸 마지막 요청).
- **공고 닫히는 시점**(B-17 우선): 1차 수락 때 공고 `MATCHED` + 같은 공고 나머지 SENT/VOL_ACCEPTED 자동 `REJECTED`("다른 요청이 수락됨"). REQUEST는 봉사자 수락(VOL_ACCEPTED) 때, PROPOSAL은 받는 쪽 수락(=최종) 때. 그 요청이 최종 거부·만료·취소·노쇼로 끝나면 공고는 `CLOSED`(다시 열리지 않음, B-19).
- 관심 조건: `PUT /api/interest-alerts/{id}`, `DELETE /api/interest-alerts/{id}`. id 없는 `PUT /api/interest-alerts`는 첫 조건을 고치거나 새로 만든다. `dayType`: `평일`|`주말`|`상관없음`.

### 7.3 요청·활동
- `POST /api/requests/{id}/noshow` — 받는 쪽/담당자, CONFIRMED이고 시작 15분이 지났을 때 → `NOSHOW`, 봉사자 `penaltyCount+1`, 보류 해제.
- 체크인·체크아웃 `method`: `qr` | `code`(기본) | `manual`(코드 없이, `checkMethod=manual`로 남아 받는 쪽 확인 필요 표시). 체크인 코드는 CONFIRMED 이후 받는 쪽·담당자의 RequestView에만 `checkCode`로 나온다.
- RequestView 추가 필드: `rejectReason`, `checkMethod`, `logRaw`, `logSubmittedAt`, `anomalyHold`, `createdAt`, `doneAt`, `adjust.reason`, `volunteer.photoUrl`. 상세(`GET /api/requests/{id}`)에는 `messages`, `canMessage`도 들어간다. `GET /api/requests/{id}/messages` → `{messages, canMessage, notice}`.
- `actions` 이름: `accept`, `reject`, `decline`, `cancel`(SENT는 보낸 사람만), `message`, `checkIn`, `checkOut`, `showCode`, `noshow`, `log`, `adjust`, `adjustAnswer`, `confirm`, `rate`, `appeal`, `report`.
- `box`: `received`=내가 보내지 않은 응답 대기(SENT/VOL_ACCEPTED), `sent`=내가 보낸 것, `active`=VOL_ACCEPTED~LOG_SUBMITTED, `done`=끝난 상태 전부(DONE·거절·만료·취소·노쇼), `all`. 담당자 `GET /api/org/requests?box=received`는 내 결정이 필요한 것(제안·최종 수락·일지 확인).
- 봉사 자격(B-01): 신분증 인증(`idStatus=APPROVED`)이 끝난 사용자만 OFFER 공고·제안·봉사자 수락이 된다(`ID_NOT_VERIFIED`). 안전 교육 수료는 배지로만 보이고 서버가 막지 않는다.
- 단골 요청(REGULAR)은 같은 받는 사람에게 DONE 3회 이상인 봉사자에게만(`NOT_REGULAR`).
- 평가 태그에서 돈 관련 낱말(돈·용돈·현금·팁 등)은 서버가 뺀다.
- 정산: 받는 쪽 HOLD = `estEarn`(예정 시간 × 가중치), DONE 때 RELEASE 후 USE = 실제 적립 시간(조정 승인 시 조정 시간 × 가중치). 봉사자 EARN과 같은 값이라 시간 총량이 맞는다.

### 7.4 시간 통장
- `GET /api/bank`·`GET /api/me` 추가 필드: `available`(= balance − hold), `usable`(B-33 반영해 지금 쓸 수 있는 시간), `canUseSelf`, `selfUseMin`(10). `balance`는 보류를 빼지 않은 합계.
- 오류 코드: `SELF_USE_LOCKED`(직접 적립 10시간 미만, 선물받은 시간 부족), `INSUFFICIENT_BALANCE`.
- ledger 거래 종류에 `POOL`(공동 적립, user_id 없음) 추가. `ledger.elder_id` 칸 추가: 어르신 선생님(도움 대상) 적립을 "기관이 대신 받음"으로 기록(user_id 없음).
- entries 항목에 `requestId`, `classId`도 준다.

### 7.5 마이페이지·가족
- `GET /api/family` 항목에 `theyShare`(상대가 나에게 리포트 공유에 동의했는지). `shareReport`는 내 동의. DB `family_links.target_share_report` 칸 추가(받은 쪽의 동의).
- `GET /api/family/{userId}/report` → `{user, reports:[LifeReport], latestMonth}`. 연결 + 상대 동의가 있어야 하고 본인은 403.
- `GET /api/my/activities` 항목에 `itemType`: `request`(RequestView) | `post`(PostCard, `seg=posts`일 때).
- `GET /api/certificate`에 `totalEarned`, `eduDone`, `issuedAt` 추가.
- `GET /api/me/withdraw-check`에 `canWithdraw`. 진행 중인 요청이 있으면 `POST /api/me/withdraw`는 409 `ACTIVE_REQUESTS`.
- `GET /api/users/{id}/profile`에 `area`, `categories`, `timeSlots`, `idVerified`, `helpCount` 추가.

### 7.6 교육·클래스
- `GET /api/edu`: `personas[].turns[].options`는 문장(`t`)만(판정 숨김), `quiz`는 정답 없이 `{no, q, options, card}`. `rules`는 진단 카테고리에 맞춤 카드 2장까지 더한 목록.
- `POST /api/ai/roleplay` 응답에 `turn`, `source` 추가. 대본 선택지와 같은 문장이면 대본 판정을 쓴다.
- `POST /api/edu/roleplay/{personaId}/finish` 응답: `{safe, comm, emp, pass, flags, fixes:[{said, better, flag}], good}`. log 항목 `{who:'me'|'them', text, quality?, flag?}`.
- 클래스 응답 필드: `teacherName`(도움 대상 선생님은 담당자에게만 별칭 원문, 그 외 "한○자"), `teacherAgeGroup`, `elderTeacher`(60대 이상 또는 도움 대상), `byOrg`, `steps:[{t,min}]`, `joined`, `fee`, `teacherEarn`, `poolIfFull`, `myEnrollment`, `host`, `note`, `reviews`.
- `GET /api/classes?status=DONE`으로 지난 수업(배움 노트) 목록. 기본은 OPEN, 어르신 선생님 먼저.
- `GET /api/org/spaces` → `{spaces, pendingClasses}`. 클래스 생성 시 담당자 전원에게 공간 배정 알림.
- 수업을 마치면(`finish`) 오지 않은 수강생은 보류만 풀고 차감하지 않는다. 공동 적립 = 출석 수 × 수강료 − (수업 + 1시간), 0보다 클 때만.
- DB `classes.check_code`(출석 코드) 추가.
- **1:1 클래스(황수빈 v2, 2026-11-09)**: 클래스 `mode` ONE/GROUP(비면 GROUP). 같이 해 보기 정원은 1~4명(`INVALID_CAPACITY`), 수강료 = 시간 × 0.5. 1:1은 1시간 = 1시간(가중치 없음), 새 종류 `일·경험`.
  - 1:1 응답 추가 필드: `mode`, `bio`, `tags`, `met`, `weeklyCap`, `left`(= 주당 정원 − (WAIT/OK/MET 신청 + 최근 7일 안에 끝난 만남)), `qs`, `slots:[{date,hour}]`, `meet`, `teacherAge`(표시 나이, 없으면 나이대), `managerName`(기관 어르신 선생님일 때), `myOne`{id,status,slotDate,slotHour,slotText,question,proposed,note,checkInAt,checkOutAt}(내 마지막 신청), 선생님/담당자에게만 `requests`[…+userName,userAgeGroup]·`newRequests`. `fee`=h, `teacherEarn`=h.
  - 상태: WAIT(수락 대기) → OK(확정) → MET(시작 QR, 정산) → DONE(마침 QR). 다른 시간 제안 = WAIT + `proposed=true`, 학생이 `accept-proposal`하면 OK. 취소는 WAIT 언제든, OK는 약속 전날까지(당일 `LATE_CANCEL`), MET 이후 불가. 진행 중 신청이 있으면 `DUPLICATE`, 자리 없으면 `CLASS_FULL`, 질문 없으면 `QUESTION_REQUIRED`, 정하지 않은 시간이면 `INVALID_SLOT`.
  - 정산: 신청 HOLD h → 시작 QR 때 RELEASE h + CLASS_FEE −h, 선생님 CLASS_EARN +h(어르신 선생님은 `ledger.elder_id`로 "기관이 대신 받음"). 만남 장소에 집·자택·호수 등 닫힌 곳이면 `MEET_NOT_PUBLIC`.
  - 알림: 신청(선생님, 기관 어르신이면 담당자 → `/o/space`), 수락·시간 제안(학생), 제안 수락·취소·만남 끝·오늘 배운 한 줄(선생님/담당자). 유형 `CLASS_ONE_APPLY|ACCEPT|PROPOSE|CANCEL|DONE|NOTE`.
  - 금지 주제(`RuleEngine.classFilter`): 침·뜸·마사지·지압·민간요법·약초 / 투자·주식·코인·보험·대출 / 전도·교회·포교·판매 → 400 `FORBIDDEN_REQUEST`(이유 문장). 그다음 봉사 금지 필터를 그대로 적용. 같이 해 보기 생성에도 쓴다.
  - `GET /api/classes?mode=ONE|GROUP` 선택 필터. OPEN 목록은 1:1 먼저(연 순서), 같이 해 보기는 어르신 선생님 먼저. `GET /api/org/spaces`에 `oneClasses`(내가 맡은 어르신의 1:1 수업, `requests` 포함) 추가. 기존 `/apply`·`/attend`·`/finish`는 1:1 수업이면 400 `ONE_CLASS`, `/cancel`은 1:1 취소로 넘긴다.

### 7.7 담당자·운영자
- 도움 대상 등록·수정·메모에 전화번호·계좌·상세 주소가 보이면 400 `PII_NOT_ALLOWED`. 동은 "○○동" 형식만.
- `GET /api/org/elders` 항목: `alias`, `displayName`, `maskedName`, `regularCount`, `activeCount`, `monthDone`, `doneTotal`, `flag`(최근 리포트 큰 변화). `GET /api/org/transfers` 항목에 `direction`('in'|'out'). `GET /api/org/home`의 `counts`에 `spacePending`, `flaggedReports`, `transfersIn`.
- `GET /api/org/reports?month=` 기본은 지난달, 항목에 `reportConsent`.
- 운영자 대시보드 추가: `kpi{activities, prevActivities, matchRate, elderUsage, prevElderUsage}`, `weekly:[{label, supply, demand}]`, `timeBalance.useRatio`, `edu`, `classes`, `pending.anomalies`. `monthly` 항목: `{month, activities, elderUsage, supply, demand, issued, consumed}`.
- `POST /api/admin/orgs/{id}/approve` → `{managerLoginId, tempPassword, managerId, ...}`(임시 비밀번호는 이 응답에서만).
- 이상 패턴(D-06): 최근 30일 같은 쌍의 1시간 이하 DONE 3회 이상. `hold`는 이미 적립된 건이면 ADJUST −적립, `release`는 ADJUST +적립(정산 전 보류면 정산 때 적립하지 않음).
- `POST /api/admin/reports/{id}/handle`의 `action`이 없으면 상태만 REVIEWING. WARNING은 대상 `warningCount+1`, SUSPEND/BAN은 계정 정지.
- `POST /api/admin/reports/generate` 본문 `{month?: 'YYYY-MM'}` 기본 지난달 → `{month, generated}`.

### 7.8 기타
- `GET /api/files/{id}`는 로그인 없이 열린다(`<img>` 태그가 헤더를 못 보내므로). 파일 이름은 추측할 수 없는 32자 무작위 값.
- 음성 알림(B-42)은 모의: `voiceAlert=true` 사용자에게 알림이 생기면 서버 로그에 `[VOICE-MOCK]`만 남긴다. 프론트가 필요하면 브라우저 음성 합성으로 읽어 준다.
- DB 열 이름: `life_reports.month`는 H2 예약어라 `report_month`(JSON 필드 이름은 그대로 `month`). `requests`에 `log_submitted_at`, `adjust_reason`, `anomaly_hold`, `reminded`, `late_notified` 추가.
- 알림(Notification) 필드: `{id, userId, type, title, body, link, isRead, createdAt}`. `link`는 프론트 경로 힌트(`/requests/{id}` 등).

