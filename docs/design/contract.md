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
- `ledger` 거래 종류: `EARN`(적립) / `HOLD`(보류) / `RELEASE`(보류 해제) / `USE`(사용·차감) / `GIFT_OUT` / `GIFT_IN` / `ADJUST`(운영자 조정) / `REFUND`.
- 잔액 = 합계(보류 제외), 보류 = 미해제 HOLD 합.
- 시간 선물(B-32): 승인된 가족 연결 상대에게만. 선물 외 양도·매매 없음(B-34).

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

### 5.9 동네 클래스 — 삭제
- 2026-11-10 사용자 결정으로 동네 클래스(1:1 수업·여럿 수업·수업 열기 인터뷰·출석 QR·배움 노트·공간 배정)와 그 API(`classes`·`org/spaces`·`org/classes` 경로 전부)를 지웠다. 기관 행사·클래스 홍보는 동네 소식(7.11 "2장·O3")으로 남는다.

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
| 담당자 | `org1` / `1234` | 박지원 (행복동 주민센터) — 도움 대상 4명(별칭 이순례·정말순·한영자·오덕수) |
| 담당자 | `org2` / `1234` | 같은 기관 다른 담당자(이관 시연) |
| 운영자 | `admin` / `1234` | 김운영 |

시드 데이터는 프로토타입 `D.P`(공고), `D.R`/`D.RE`(요청), `D.L`(통장), `D.N`(알림), `RP`(역할극 상대), `QZ`(퀴즈), 공공 서비스 10개 안팎을 **오늘 날짜 기준 상대 날짜**로 옮긴다.

## 7. 추가 기록 (개발 중 계약에 더한 것)

- (백엔드가 여기에 추가)

### 7.1 말로 요청하기 — `POST /api/ai/post-chat` 확장 (음성 분석보고서 4.8, 2026-11-09)
- 요청에 `inputMode: 'text' | 'voice'` (기본 text). voice면 AI 프롬프트에 "받아쓰기라 띄어쓰기·오타·군말 섞임, 존댓말로 짧게 한 번에 하나만 되묻기, reply는 소리로 읽기 좋은 1~2문장"을 넣고, 규칙 모드도 되묻기 문장을 짧게 한다.
- 응답에 추가: `intent`(`NEW_REQUEST`|`REGULAR_REQUEST`|`CHANGE_CANCEL`|`CONFIRM`|`QUESTION`|`CHAT`|`EMERGENCY`), `confidence`{`date`,`time`,`area`,`category`: `high`|`medium`|`low`}, `safety`{`emergency`:boolean, `piiRemoved`:[`phone`|`account`|`address`]}, `inputMode`, `draft.caution`, `draft.repeat`(예 "매주 목요일" 또는 null), `draft.summarySpeech`(done일 때만, 예 "11월 17일 화요일 오후 2시부터 2시간, 병원 동행 요청서를 만들었어요. 올릴까요?").
- `missing` 값: `content`(도움 내용·카테고리) / `date` / `startHour` / `endHour`(OFFER만) / `estHours`(NEED만) / `area`. 한 번에 첫 항목 하나만 되묻는다.
- 위급 표현("쓰러졌", "숨이 안", "피가 나" 등)이면 `intent=EMERGENCY`, `safety.emergency=true`, `done=false`, reply로 119 안내. 전화번호·계좌·상세 주소(아파트 동·호수, 번지, 도로명 번지)는 draft 본문에서 지우고 "(수락 뒤 메시지로 안내돼요)"로 바꾼다(AI 결과에도 서버가 다시 적용). 주의사항·세부 내용에는 병명을 남기지 않고 방문에 필요한 문장("걸음이 불편하셔서 천천히 걸어 주세요")만 둔다.
- `CHANGE_CANCEL`/`CONFIRM`, 그리고 카테고리 없는 `QUESTION`/`CHAT`이면 요청서를 만들지 않고 reply로 해당 화면(내 활동, 공공 서비스 안내, 말동무 요청)을 안내한다.
- 시각에 오전·오후가 없으면 1~6시는 오후로 보고 `confidence.time=medium`. 장소를 말하지 않으면 프로필 지역(담당자 대리면 대상의 동)을 채우고 `confidence.area=medium`.
- `GET /api/ai/status` → `{mode: 'ai'|'rule'}` (키 유무 표시용). → 7.9에서 `provider`·`model`·`stt` 추가.

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
- `ledger.elder_id` 칸 추가(user_id 없는 시스템 기록용). entries 항목에 `requestId`도 준다.
- (2026-11-10) 동네 클래스 삭제로 `ledger.class_id`·entries `classId`를 지웠다. `CLASS_FEE`/`CLASS_EARN`/`POOL`은 기존 DB 행을 읽기 위해 enum에만 남기고 새로 쓰지 않으며, 적립·사용 합계(직접 적립 10시간 판단, 운영자 발행·소비)에도 넣지 않는다.

### 7.5 마이페이지·가족
- `GET /api/family` 항목에 `theyShare`(상대가 나에게 리포트 공유에 동의했는지). `shareReport`는 내 동의. DB `family_links.target_share_report` 칸 추가(받은 쪽의 동의).
- `GET /api/family/{userId}/report` → `{user, reports:[LifeReport], latestMonth}`. 연결 + 상대 동의가 있어야 하고 본인은 403.
- `GET /api/my/activities` 항목에 `itemType`: `request`(RequestView) | `post`(PostCard, `seg=posts`일 때).
- `GET /api/certificate`에 `totalEarned`, `eduDone`, `issuedAt` 추가.
- `GET /api/me/withdraw-check`에 `canWithdraw`. 진행 중인 요청이 있으면 `POST /api/me/withdraw`는 409 `ACTIVE_REQUESTS`.
- `GET /api/users/{id}/profile`에 `area`, `categories`, `timeSlots`, `idVerified`, `helpCount` 추가.

### 7.6 교육
- `GET /api/edu`: `personas[].turns[].options`는 문장(`t`)만(판정 숨김), `quiz`는 정답 없이 `{no, q, options, card}`. `rules`는 진단 카테고리에 맞춤 카드 2장까지 더한 목록.
- `POST /api/ai/roleplay` 응답에 `turn`, `source` 추가. 대본 선택지와 같은 문장이면 대본 판정을 쓴다.
- `POST /api/edu/roleplay/{personaId}/finish` 응답: `{safe, comm, emp, pass, flags, fixes:[{said, better, flag}], good}`. log 항목 `{who:'me'|'them', text, quality?, flag?}`.
- (2026-11-10) 이 절에 있던 동네 클래스·1:1 클래스 기록(응답 필드, 상태, 정산, 금지 주제 `RuleEngine.classFilter`, 공간 배정)은 기능 삭제로 지웠다.

### 7.7 담당자·운영자
- 도움 대상 등록·수정·메모에 전화번호·계좌·상세 주소가 보이면 400 `PII_NOT_ALLOWED`. 동은 "○○동" 형식만.
- `GET /api/org/elders` 항목: `alias`, `displayName`, `maskedName`, `regularCount`, `activeCount`, `monthDone`, `doneTotal`, `flag`(최근 리포트 큰 변화). `GET /api/org/transfers` 항목에 `direction`('in'|'out'). `GET /api/org/home`의 `counts`에 `flaggedReports`, `transfersIn`.
- `GET /api/org/reports?month=` 기본은 지난달, 항목에 `reportConsent`.
- 운영자 대시보드 추가: `kpi{activities, prevActivities, matchRate, elderUsage, prevElderUsage}`, `weekly:[{label, supply, demand}]`, `timeBalance.useRatio`, `edu`, `pending.anomalies`. `monthly` 항목: `{month, activities, elderUsage, supply, demand, issued, consumed}`.
- `POST /api/admin/orgs/{id}/approve` → `{managerLoginId, tempPassword, managerId, ...}`(임시 비밀번호는 이 응답에서만).
- 이상 패턴(D-06): 최근 30일 같은 쌍의 1시간 이하 DONE 3회 이상. `hold`는 이미 적립된 건이면 ADJUST −적립, `release`는 ADJUST +적립(정산 전 보류면 정산 때 적립하지 않음).
- `POST /api/admin/reports/{id}/handle`의 `action`이 없으면 상태만 REVIEWING. WARNING은 대상 `warningCount+1`, SUSPEND/BAN은 계정 정지.
- `POST /api/admin/reports/generate` 본문 `{month?: 'YYYY-MM'}` 기본 지난달 → `{month, generated}`.

### 7.8 기타
- `GET /api/files/{id}`는 로그인 없이 열린다(`<img>` 태그가 헤더를 못 보내므로). 파일 이름은 추측할 수 없는 32자 무작위 값.
- 음성 알림(B-42)은 모의: `voiceAlert=true` 사용자에게 알림이 생기면 서버 로그에 `[VOICE-MOCK]`만 남긴다. 프론트가 필요하면 브라우저 음성 합성으로 읽어 준다.
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
- QR 내용: `TS1|REQ|<요청 id>|<6자리 코드>`. RequestView에 `qrText`(받는 쪽·담당자에게만, `checkCode`와 함께). (클래스용 CLS·ONE 종류는 2026-11-10 삭제)
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
- `GET /api/regulars/{volunteerId}/status` → `{volunteerId, doneCount, auto(DONE≥3), manual, regular, canMark}`. `canMark`=사용자 계정이고 그 봉사자와 끝낸 활동이 1~2번이며 아직 수동 등록 전.
- `POST /api/regulars/{volunteerId}`(끝낸 활동 0번이면 409 `NO_DONE_ACTIVITY`, 나 자신 400 `SELF`) / `DELETE /api/regulars/{volunteerId}` → 위 status.
- `GET /api/regulars` 항목에 `auto`, `manual`, `doneCount` 추가(자동 3회 + 수동 합침). 수동 단골에게도 단골 요청(REGULAR)을 보낼 수 있다(`Views.isRegular`).

**U8 내 위치로 공공 서비스 찾기** — `POST /api/ai/public-services/nearby {question?, lat?, lng?, area?}`. 좌표가 있으면 소수 둘째 자리(약 1km)로 줄여 LLM 프롬프트에 넣고, 결과는 **DB에 저장하지 않는다**. 응답 `{answer, services:[{name,target,content,applyHow,contact,where,aiGenerated}], source:'ai'|'rule', aiGenerated, notice, usedLocation, saved:false}`. AI 결과면 `notice`="AI가 찾은 정보예요 · 꼭 기관에 확인하세요"를 화면에 표시. 키가 없거나 실패·좌표 없음이면 DB 공공 서비스(지역 + 질문, 질문 없으면 돌봄·안부·병원 등 기본 주제)로 대체(`source:'rule'`). 좌표 범위 밖이면 400 `INVALID_LOCATION`. 좌표·질문은 로그에 남기지 않는다. 화면은 버튼을 누를 때만 브라우저 위치 권한을 묻고, 거부하면 프로필 동네 기준으로 찾는다.

**O2 대리 공고 AI로 정리하기** — `POST /api/ai/tidy-post {text, elderId?}` → `{title, detail, caution, categories, piiRemoved, source}`. 입력에 있는 사실만 다듬고 전화번호·계좌·상세 주소는 지우며, 주의사항에는 병명 대신 행동 문장만(서버가 AI 결과에도 다시 적용). `elderId`는 내가 담당하는 대상만(403). 규칙 모드는 카테고리 + 장소 낱말로 짧은 제목(예 "병원 동행 (정형외과)"). 화면은 정리 결과를 고친 뒤 대화로 날짜·시간을 채우고, 올릴 때 정리한 제목·내용·주의사항을 쓴다(기존 대화형 작성 유지).

**2장·O3 동네 소식** — 테이블 `org_events`, `event_applications`. 아래 탭 "클래스" → "동네 소식"(`/news`, 기관 소식만 — 2026-11-10 사용자 클래스 탭 삭제, 예전 `/cls*` 주소는 `/news`로, `/o/space`는 담당자 소식 관리 `/o/events`로 넘김).
- `GET /api/events`(열린 소식, 날짜 지나지 않은 것, 날짜 순) · `GET /api/events/{id}` → `{id, orgId, orgName, title, content, date, startHour, place, capacity(0=제한 없음), applied, applyType:'APP'|'LINK', url, preview:{title,image,description}|null, status:'OPEN'|'CLOSED', myApplied, full, owner, applicants?(우리 기관 담당자에게만 [{userId,name,ageGroup,appliedAt}])}`.
- `POST /api/events`(담당자만) `{title, content, date, startHour, place, capacity, applyType, url?, ogTitle?, ogImage?, ogDesc?}`. LINK면 url 필수(`URL_REQUIRED`). 저장 전에 url 형식·내부 주소 검사(`URL_NOT_ALLOWED`). 화면에서 받은 미리보기를 같이 보내면 그대로 저장, 없으면 서버가 한 번 가져와 본다(실패해도 글은 올라감).
- `POST /api/events/{id}/apply`(사용자, APP만 — LINK는 400 `EXTERNAL_APPLY`, 정원 409 `EVENT_FULL`, 중복 409 `DUPLICATE`, 마감 409 `EVENT_CLOSED`) · `/cancel` · `/close`(우리 기관 담당자) · `GET /api/org/events`(우리 기관 소식, 신청자 포함). 신청하면 올린 담당자에게 알림 `EVENT_APPLY`(링크 `/news/{id}`).
- `POST /api/link-preview {url}` → `{url, title, image, description, siteName}`(저장 안 함, 사용자·담당자). **SSRF 방지**: http/https만, 80·443 포트만, 아이디·비밀번호 포함 주소 거부, localhost·*.local·*.internal 거부, 이름을 풀어 나온 **모든** 주소가 사설(10/8·172.16/12·192.168/16)·루프백·링크로컬(169.254 — 클라우드 메타데이터 포함)·0.0.0.0/8·CGNAT(100.64/10)·멀티캐스트·IPv6 ULA(fc00::/7)·fe80::/10·IPv4 매핑 주소면 거부(400 `URL_NOT_ALLOWED`). 리다이렉트는 자동으로 따르지 않고 직접 따라가며 매번 다시 검사(최대 3회), 연결·응답 3초, 본문 512KB까지만, HTML이 아니면 읽지 않음, 시스템 프록시 안 씀. `og:title`→없으면 `<title>`, `og:image`(상대 경로는 절대 경로로, http/https만), `og:description`/`description`. 남은 위험: 검사와 연결 사이 DNS 응답이 바뀌는 경우(DNS 리바인딩)는 완전히 막지 못한다 → 운영 때 나가는 요청을 프록시로 제한 권장.
- 시드: 행복동 주민센터 소식 4개(앱 신청 2, `https://example.org/…` 외부 링크 2 — 시드에서는 미리보기를 가져오지 않음).

**화면(서버 계약 없음)** — U2 홈 교육 배너 → 홈 위 빠른 메뉴 아이콘(교육 n/4단계, 수료 전 빨간 점) · U4 "응답 필요"·"다가오는 일정(확정된 내 봉사/받는 일정)" 가로 슬라이드(스크롤 스냅·스와이프·점, 카드 누르면 상세) · U5 공고 화면 아래 "봉사 요청"(NEED 작성) / "도움 가능"(OFFER 작성) 버튼, 작성 화면 제목도 같게("공고 올리기" 문구 교체) · U6 홈 "도움 받기 / 봉사하기" 전환(브라우저 `localStorage` `ts-home-mode`, 막히면 기본 받기), 고른 쪽 카드가 먼저 · U9 QR 처음 쓸 때 한 줄 설명(`ts-qr-intro-seen`, 요청 시작·종료 QR 찍기 시트).

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
