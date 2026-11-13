# 시간 적금 — API 정의서 (백엔드)

- 작성일: 2026-11-13
- 기준 커밋: `9f78dc4 사용자 화면을 2026-11-13 대표 지시대로: 하단바·홈·공고·통장·플래너·마이` (백엔드는 바로 앞 `aeed99f 관심 공고(북마크) API, 통장 거래 건별 잔액·상대 이름, 동네 소식 최신순`까지 반영). 쓰기 시작할 때는 `c68c72e`였고, 쓰는 동안 사용자 화면 담당이 위 두 커밋을 올려서 끝에 다시 맞췄다. 사용자 화면 쪽은 더 바뀔 수 있다.
- 근거: `backend/src/main/java/com/timesavings/web/*.java`(컨트롤러 28개), `config/AuthInterceptor.java`·`WebConfig.java`·`ApiException.java`·`GlobalExceptionHandler.java`, 응답 조립 `service/Views.java`와 각 서비스. `docs/design/contract.md`는 참고만 했고, 다르면 **코드가 맞다**.
- 이 문서의 칸 이름은 컨트롤러의 `record`와 `Views.map(...)`에서 직접 확인한 것이다. 서비스 안쪽에서 만들어져 이번에 끝까지 읽지 못한 응답은 "(확인 필요)"로 적었다.
- **기관 담당자 영역(16장)은 모든 API를 상세로** 적었다. 담당자가 함께 쓰는 공용 API는 16.9에 모았다.

---

## 1. 개요

### 1.1 기본 주소·형식
- 모든 API는 `/api` 아래에 있다. 요청·응답은 JSON(`application/json`)이고, 파일을 보내는 API만 `multipart/form-data`다.
- CORS: `app.cors-origins` 설정값(기본 `http://localhost:*`, `http://127.0.0.1:*`), 메서드 GET·POST·PUT·DELETE·OPTIONS.
- `/api`가 아닌 주소는 `SpaController`가 React 화면(index.html)으로 돌려준다(API 아님).

### 1.2 인증
- 로그인(`POST /api/auth/login`)·가입 응답의 `token`을 모든 요청 머리에 넣는다: `Authorization: Bearer <token>`.
- 토큰은 표 `auth_token`에 저장된다(`keep=true`면 길게, 아니면 짧게 — 기간은 `AuthService`, db/README 기준 30일/1일).
- 로그인 없이 부를 수 있는 주소(`AuthInterceptor.isPublic`):
  - `OPTIONS` 전부
  - `/api/auth/**` (단, `/api/auth/logout`은 로그인 필요)
  - `/api/orgs/apply`
  - `GET /api/ai/status`
  - `/api/notices`로 시작하는 주소
- 그 밖의 `/api/**`는 토큰이 없거나 틀리면 **401 `UNAUTHORIZED`**.
- 올린 파일(`/api/files/**`)은 공개가 아니다. 로그인하고, 그 파일을 볼 수 있는 사람만 연다(`FileAccessService`).

### 1.3 역할과 경로 권한
| 역할 | 뜻 | 경로 규칙(인터셉터) |
|---|---|---|
| `USER` | 일반 사용자(봉사자이자 도움 받는 사람) | `/api/admin/**`, `/api/org/**`는 403 |
| `ORG` | 기관 담당자 | **`/api/org/**`는 ORG만**. 그 밖의 공용 API도 부를 수 있다 |
| `ADMIN` | 운영자 | **`/api/admin/**`는 ADMIN만** |

- 경로 규칙 외에 컨트롤러·서비스가 따로 막는 곳이 있다(예: `POST /api/events`는 경로가 `/api/org`가 아니지만 ORG만, `POST /api/public-referrals`는 USER만). 각 API의 "권한" 칸에 적었다.
- 담당자 API는 대부분 "내가 담당하는 대상"(`elders.manager_id = 내 id`)인지 한 번 더 확인하고 아니면 403이다.

### 1.4 공통 오류 응답
모든 오류는 같은 모양이다.

```json
{ "error": "사람이 읽는 한국어 안내 문장", "code": "ERROR_CODE" }
```

| HTTP | code | 언제 |
|---|---|---|
| 400 | `INVALID_INPUT` | JSON이 깨졌거나 형식이 틀림, 필수 쿼리 누락, 검증 실패 |
| 400 | 각 API의 코드 | 예: `CONSENT_REQUIRED`, `PII_NOT_ALLOWED`, `INVALID_DATE` … (API별 표 참고) |
| 400 | `FILE_TOO_LARGE` | 파일 10MB 초과 |
| 401 | `UNAUTHORIZED` | 로그인 필요 |
| 403 | `FORBIDDEN` | 역할이 다르거나 내 것이 아님 |
| 403 | `VOLUNTEER_BLOCKED` | 담당자가 "받지 않기"로 정한 봉사자가 그 대상 건에 요청·제안 |
| 404 | `NOT_FOUND` | 대상 없음, 없는 주소 |
| 405 | `METHOD_NOT_ALLOWED` | 주소는 있는데 그 방식(GET·POST 등)이 아님 |
| 409 | 각 API의 코드 | 상태가 맞지 않음. 예: `INVALID_STATE`, `DUPLICATE`, `ACTIVE_REQUESTS`, `POST_CLOSED`, `POST_LOCKED`, `ALREADY_SENT` |
| 413 | `AUDIO_TOO_LARGE` | 음성 인식 녹음이 너무 김 |
| 429 | `TOO_MANY_REQUESTS` | 로그인 전 신청(기관·담당자)을 같은 IP에서 10분에 5번 넘게 |
| 502·503 | `STT_FAILED`·`STT_BUSY`·`STT_UNAVAILABLE` | 서버 음성 인식 실패·꺼짐 |
| 500 | `INTERNAL` | 처리하지 못한 서버 오류 |

### 1.5 표기 규칙
- 날짜: `"YYYY-MM-DD"`. 달: `"YYYY-MM"`. 날짜·시각: ISO 형식(`"2026-11-13T09:30:00"`, 시간대 없음 = 서버 시각).
- 시각(hour): **소수 시(時)**. `9.5` = 오전 9시 30분. 30분 단위(0.5)만 받는다. `startHour`·`endHour`·`hours`·`estHours` 모두 같다.
- 시간 통장의 양: 시간(hour) 단위 실수, 0.5 단위.
- 목록형 칸(`categories`, `needs`, `tags`, `timeSlots` …)은 JSON 배열로 주고받는다(DB에는 쉼표로 이은 글자).
- enum은 이름 그대로 문자열(예: `"VOL_ACCEPTED"`). 값 목록은 `docs/산출물/ERD.md` 4장.
- 카테고리: 기본 6종(말동무·디지털·생활 정리·장보기·집안일·동행 — `Rules.CATEGORIES`) + 운영자가 더한 것. `GET /api/categories`로 받는다.
- 난이도 등급 `BASIC`/`EFFORT`/`RESPONSIBLE` = 가중치 1.0/1.2/1.5. 적립 = 시간 × 가중치.

### 1.6 AI 응답의 `source`
- AI가 문장을 만드는 API는 응답에 `source: "ai" | "rule"`을 넣는다. AI 키가 없거나 실패하면 같은 모양으로 **규칙 문장**(`rule`)이 온다. `ai`일 때는 `provider`(예: gemini)도 함께 올 수 있다.
- 숫자·순위·후보 찾기는 항상 코드가 계산한다. AI는 문장만 다듬는다.
- AI는 아무것도 실행하지 않는다(등록·발송·수락은 사람이 기존 API를 버튼으로 부른다).
- 한 번 만든 결과를 저장해 다시 쓰는 것은 `cached` 또는 `source: "cache"`로 표시한다.
- 현재 AI 상태: `GET /api/ai/status` → `{provider, model, stt, mode: "ai"|"rule"}` (공개).

### 1.7 자주 나오는 응답 모양
아래 세 가지는 여러 API가 그대로 돌려준다. 뒤에서는 이름만 적는다.

**UserBrief** `{id, name}`

**PostCard** (`Views.postCard`) — 공고 한 건
`id, type(OFFER|NEED), ownerId, ownerName, elderId, elderDisplayName, elderCaution, elderIssue, elderIssueLocked, date, startHour, endHour, area, categories[], title, detail, note, estHours, difficulty, difficultyReason, weight, estEarn, repeatRule, seriesId, status, distanceKm, createdAt, owner{id, name, ratingAvg, ratingCount, ageGroup, crime, intro, photoUrl}|null, manager{name, orgName, orgType}|null, mine`
- 관리 대상 공고는 `ownerName`·`elderDisplayName`이 담당자에게는 "별칭 님", 다른 사람에게는 가린 이름(`Rules.maskAlias`)이다.
- `elderCaution`·`elderIssue`(특이사항)는 **담당자와 일정이 확정된 봉사자에게만** 값이 있고, 나머지는 null + `elderIssueLocked: true`.
- 목록·상세에서는 `bookmarked`(관심 공고 여부)와 `PostService.decorate`가 붙이는 칸이 더 있다(decorate의 칸은 (확인 필요)).

**RequestView** (`Views.requestView`) — 요청·제안·활동 한 건
`id, kind(REQUEST|PROPOSAL|REGULAR), postId, postTitle, status, date, startHour, hours, category, content, memo, difficulty, weight, estEarn, deadlineAt, leftText, rejectReason, volunteer{id, name, ageGroup, photoUrl, ratingAvg, ratingCount, intro, experience, tags[], crime, isRegular, ratingPending, matchReason?}, recipient(UserBrief|null), elder{id, displayName, dong, caution, cautionLocked, baseCaution?, alias?, ageGroup?}|null, sender(UserBrief), myRole(VOLUNTEER|RECIPIENT|MANAGER|null), actions[], checkInAt, checkOutAt, checkMethod, logRaw, logText, logPhotoUrl, logSubmittedAt, earned, rated, anomalyHold, adjust{hours, status, reason}|null, createdAt, doneAt, careMemo(당사자만), canRejectVolunteer, autoClosed, gapMinutes`
- 받는 쪽(RECIPIENT·MANAGER)이고 상태가 `CONFIRMED`·`IN_PROGRESS`면 `checkCode, qrText`가 더 온다. 관리 대상 건은 현관 QR 카드(`elderCard: true, qrIssuedAt`), 일반 건은 30초마다 바뀌는 번호(`qrLeftSec`).
- `elder.alias`·`elder.ageGroup`은 담당자(`myRole=MANAGER`)에게만. `elder.caution`은 담당자와 확정된 봉사자에게만(아니면 null, `cautionLocked: true`).
- `actions`: 지금 누를 수 있는 버튼 이름 — `accept, reject, decline, cancel, message, checkIn, showCode, noshow, checkOut, adjust, log, confirm, rate, appeal, adjustAnswer, report`.
- 요청 상세(`GET /api/requests/{id}` 등 RequestController의 응답)는 여기에 `messages[], canMessage, photoRule(NONE|RECEIPT|BEFORE_AFTER), logPhotoUrl2`가 더 붙는다.

---

## 2. 인증·회원 (21개)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/api/auth/phone/send` | 공개 | 휴대폰 인증번호 보내기(모의: 실제 문자 없음) |
| GET | `/api/auth/phone/config` | 공개 | 쓸 수 있는 인증 방식 `{firebase, demo}` |
| POST | `/api/auth/phone/verify` | 공개 | 인증 확인 → `verifyToken` |
| POST | `/api/auth/signup` | 공개 | 사용자 가입 |
| POST | `/api/auth/login` | 공개 | 로그인 |
| POST | `/api/auth/logout` | 로그인 | 로그아웃(토큰 폐기) |
| POST | `/api/auth/password/reset` | 공개 | 본인인증 뒤 비밀번호 재설정 |
| GET | `/api/auth/manager-apply/orgs` | 공개 | 담당자 계정 신청 때 고를 승인 기관 목록 |
| POST | `/api/auth/manager-apply` | 공개(multipart) | 담당자 계정 신청 — 본인인증되면 **자동 승인** |
| POST | `/api/orgs/apply` | 공개(multipart 또는 JSON) | 기관 서비스 신청(재직 증빙 필수). 처리기 2개 |
| GET | `/api/me` | 로그인 | 내 정보(Me) |
| PUT | `/api/me` | 로그인 | 내 정보 고치기 |
| GET·PUT | `/api/me/noti-settings` | 로그인 | 알림 종류별 켜고 끄기 |
| PUT | `/api/me/password` | 로그인 | 비밀번호 바꾸기 |
| GET | `/api/me/withdraw-check` | 로그인 | 탈퇴할 수 있는지 미리 보기 |
| POST | `/api/me/withdraw` | 로그인 | 탈퇴 |
| POST | `/api/me/verify/id` | 로그인 | 신분증 사진 제출(운영자 승인 대기) |
| POST | `/api/me/verify/crime` | 로그인 | 범죄경력 회보서 제출 |
| GET | `/api/users/{id}/profile` | 로그인 | 다른 사용자 프로필 |

### 상세
- **POST `/api/auth/phone/send`** 본문 `{phone}` → `{demoCode, message}`.
- **POST `/api/auth/phone/verify`** 본문 `{phone, code}`(데모 번호) 또는 `{phone, firebaseIdToken}` → `{verified: true, verifyToken, method: "demo"|"firebase"}`.
- **POST `/api/auth/signup`** 본문 `{loginId*(영문·숫자·_ 3~20자), password*(4자 이상), name*, phone*, verifyToken*, area, ageGroup, categories[], timeSlots[], intro, agreeTerms*(true), agreePrivacy*(true), agreeLocation, agreeInterest}` → `{token, user(Me)}`. 오류: `INVALID_LOGIN_ID`, `WEAK_PASSWORD`, `NAME_REQUIRED`, `TERMS_REQUIRED`, 409 `DUPLICATE_LOGIN_ID`, 본인인증 실패(코드는 `AuthService`, 확인 필요).
- **POST `/api/auth/login`** 본문 `{loginId, password, keep}` → `{token, user(Me)}`. 정지·퇴출·탈퇴 계정의 오류 코드는 `AuthService.login`(확인 필요).
- **POST `/api/auth/password/reset`** 본문 `{loginId, phone, verifyToken, newPassword}` → `{ok}`. 오류: 404, `PHONE_MISMATCH`, `WEAK_PASSWORD`. 성공하면 모든 로그인 토큰 폐기.
- **POST `/api/auth/manager-apply`** (multipart) 칸 `orgId*, name*, phone*, verifyToken*, loginId*, password*, proof*(파일)` → `{id, status: "APPROVED", orgId, orgName, loginId, token, user(Me), message}`. 오류: `ORG_NOT_APPROVED`, `NAME_REQUIRED`, `INVALID_LOGIN_ID`, `WEAK_PASSWORD`, `PROOF_REQUIRED`, 409 `DUPLICATE_LOGIN_ID`, 409 `ALREADY_MANAGER`, 429. 운영자와 그 기관 기존 담당자에게 알림이 간다.
- **POST `/api/orgs/apply`** multipart 칸 `name*, orgType, address*, phone, managerName*, managerPhone, managerEmail, memo, proof*(파일)` 또는 JSON `{…, proofFileUrl*("/api/files/…")}` → `{id, status: "PENDING"}`. 오류: `INVALID_INPUT`, `ADDRESS_REQUIRED`, `PROOF_REQUIRED`, 429.
- **Me** (`GET /api/me`, 가입·로그인의 `user`): `id, loginId, name, phone, role, status, area, ageGroup, intro, experience, personality, categories[], timeSlots[], photoUrl, orgId, orgName, idStatus, crimeStatus, voiceAlert, smsAlert, notiSettings{…}, accountNo, locConsent, interestConsent, termsAgreedAt, textSize, penaltyCount, warningCount, createdAt, balance, hold, available, usable, earnedSelf, canUseSelf, regularBadges[], ratingAvg, ratingCount, verify{idStatus, crimeStatus, eduDone, eduRequired, eduOn}, eduDone, canVolunteer, volunteerBlock{code, message}|null`.
- **PUT `/api/me`** 본문(보낸 칸만 바뀜) `{name, phone, area, ageGroup, intro, experience, personality, categories[], timeSlots[], photoUrl, textSize("BIG"|그 외=NORMAL), voiceAlert, smsAlert, locConsent}` → Me. 오류: `FILE_NOT_MINE`(남이 올린 파일을 사진으로).
- **PUT `/api/me/noti-settings`** 본문 `{req, msg, result, remind, interest, voiceAlert, smsAlert}`(보낸 칸만) → 설정 전체. 음성 전화·문자를 **켜는 것**은 USER만(`USER_ONLY`).
- **PUT `/api/me/password`** `{currentPassword, newPassword}` → `{ok}`. 오류 `WRONG_PASSWORD`, `WEAK_PASSWORD`.
- **GET `/api/me/withdraw-check`** → `{activeRequests[{id, status, date, category, content}], balance, hold, canWithdraw, message}`. **담당자는 담당 중(ACTIVE)인 대상이 남아 있으면 탈퇴 불가**, 운영자는 마지막 한 명이면 불가.
- **POST `/api/me/withdraw`** `{password}` → `{ok}`. 오류 `WRONG_PASSWORD`, 409 `ACTIVE_REQUESTS`, 409 `WITHDRAW_BLOCKED`.
- **POST `/api/me/verify/id`·`/crime`** `{fileUrl}` → `{idStatus}` / `{crimeStatus}`. 오류 `FILE_REQUIRED`, `FILE_NOT_MINE`.
- **GET `/api/users/{id}/profile`** → `id, name, ageGroup, area, intro, experience, personality, categories[], timeSlots[], photoUrl, ratingAvg, ratingCount, tags[], crime, idVerified, eduDone, regularBadges[], helpCount, ratingPending`. 공개 평가가 3건 미만이면 평균·칭찬은 가려진다(0, 빈 목록).

---

## 3. 공고 (17개)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/posts` | 로그인 | 공고 목록(필터·정렬) |
| GET | `/api/posts/mine` | 로그인 | 내가 올린 공고(지난 것 포함). 담당자는 담당 대상 공고도 |
| GET | `/api/posts/bookmarks` | 로그인 | 내 관심 공고 목록 |
| GET | `/api/posts/{id}` | 로그인 | 공고 상세 + 받은 요청 |
| POST | `/api/posts` | 로그인(운영자 제외는 서비스 규칙) | 공고 올리기(항상 1건) |
| POST | `/api/posts/{id}/repost` | 공고 주인·담당자 | 지난 공고를 날짜만 바꿔 다시 올리기 |
| PUT | `/api/posts/{id}` | 공고 주인·담당자 | 공고 고치기 |
| DELETE | `/api/posts/{id}` | 공고 주인·담당자 | 공고 삭제(상태 DELETED) |
| POST·DELETE | `/api/posts/{id}/bookmark` | 로그인 | 관심 공고 담기·빼기 |
| GET | `/api/posts/{id}/easy` | 로그인 | 쉬운 말로 읽어 주기 문장(공고별 1회 생성·저장) |
| GET | `/api/posts/{id}/fix-tips` | 공고 주인·담당자 | 안 맞춰지는 공고 고치기 제안 |
| GET·POST | `/api/interest-alerts` | 로그인 | 관심 조건 알림 목록·추가 |
| PUT | `/api/interest-alerts/{id}` · `/api/interest-alerts` | 본인 | 조건 고치기(id 없이 보내면 첫 조건을 고치거나 만든다) |
| DELETE | `/api/interest-alerts/{id}` | 본인 | 조건 삭제 |

### 상세
- **GET `/api/posts`** 쿼리 `type(OFFER|NEED), area, date, cat, sort, dateFrom, dateTo, lat, lng`(모두 선택). `lat·lng`는 거리 계산에만 쓰고 저장하지 않는다. → PostCard[] (+ 추천 이유, `bookmarked`). 오류 `INVALID_LOCATION`.
- **GET `/api/posts/mine`** 쿼리 `elderId`(선택 — 그 대상의 공고만) → PostCard[] + `requestCount`(응답 대기 요청 수).
- **GET `/api/posts/{id}`** → PostCard + `requests[]`(RequestView — 공고 주인·담당자·대신 올린 가족만), `myRequest`(그 밖의 사람에게는 내가 보낸 요청 1건), `fixTips`(내 열린 공고), `bookmarked`. 삭제된 공고는 404.
- **POST `/api/posts`** 본문:

| 칸 | 형식 | 필수 | 설명 |
|---|---|---|---|
| type | `OFFER`\|`NEED` | ✓ | 담당자·가족 대신은 `NEED`만 |
| date | 날짜 | ✓ | 오늘 이후 |
| startHour, endHour | 소수 시 | ✓ | 30분 단위 |
| area | 글자 | ✓ | 동 |
| categories | 배열 | ✓ | 1개 이상 |
| title, detail, note | 글자 | | |
| estHours | 실수 | NEED면 ✓ | 시간 범위보다 길 수 없음 |
| elderId | 숫자 | 담당자 대리면 ✓ | 담당하는 ACTIVE 대상 |
| elderIssue | 글자(300자) | | 관리 대상 이슈. `elderId`가 있을 때만. 연락처·상세 주소 금지 |
| difficulty, difficultyReason | 등급, 글자 | | |
| forUserId | 숫자 | | 연결된 가족 대신 올릴 때 |
| repeat | 아무 모양 | | 받아서 무시(반복은 `/api/recurring`). 보냈으면 응답에 `repeatNotice` |

  → PostCard + `createdCount: 1, createdIds[]`. 오류: `INVALID_TYPE`, `DATE_REQUIRED`, `INVALID_DATE`, `INVALID_TIME`, `AREA_REQUIRED`, `INVALID_CATEGORY`, `INVALID_HOURS`, `ELDER_REQUIRED`, `ELDER_ENDED`, `ID_NOT_VERIFIED`(봉사 가능 공고는 신분증 인증 뒤), `INVALID_FAMILY`, `ISSUE_ONLY_FOR_ELDER`, `ISSUE_TOO_LONG`, `PII_NOT_ALLOWED`.
- **POST `/api/posts/{id}/repost`** `{date}` → PostCard + `repostOf`. 오류 `INVALID_DATE`.
- **PUT `/api/posts/{id}`** 본문은 올리기와 같다. 수락된 요청이 있으면 409 `POST_LOCKED`.
- **bookmark**: → `{postId, bookmarked}`. 내 공고는 400 `OWN_POST`.
- **GET `/api/posts/{id}/easy`** → `{postId, text, cached, source(ai|rule|cache), lang: "ko-KR", rate: 0.9}`.
- **interest-alerts** 본문 `{area, categories[], dayType(평일|주말|상관없음), enabled, dateFrom, dateTo}`. 날짜 칸은 `""`나 `null`을 보내면 지워지고, 칸을 안 보내면 그대로다. 응답은 엔티티 그대로 `{id, userId, area, categories, dayType, dateFrom, dateTo, enabled}`. 오류 `INVALID`, `INVALID_DATE`.

---

## 4. 요청·진행 — QR·일지·평가 (22개)

상태 흐름: `SENT → VOL_ACCEPTED → CONFIRMED → IN_PROGRESS → CHECKED_OUT → LOG_SUBMITTED → DONE`. 끝나는 상태: `REJECTED, DECLINED, EXPIRED, CANCELLED, NOSHOW`(자세한 것은 ERD 4장).

| 메서드 | 경로 | 누가 | 설명 |
|---|---|---|---|
| GET | `/api/requests?box=` | 당사자 | 내 요청 목록. `box`: `received`·`sent`·`active`·`done`·(없으면 전체) |
| POST | `/api/requests` | 로그인 | 요청(REQUEST)·제안(PROPOSAL)·단골 요청(REGULAR) 만들기 |
| GET | `/api/requests/{id}` | 당사자·담당자 | 상세(+메시지) |
| POST | `/api/requests/{id}/accept` | 응답할 쪽 | 수락(봉사자 수락 → 받는 쪽 최종 수락) |
| POST | `/api/requests/{id}/reject` | 응답할 쪽 | 거절 `{reason*}` |
| POST | `/api/requests/{id}/decline` | 받는 쪽 | 최종 거부 `{reason}` |
| POST | `/api/requests/{id}/cancel` | 당사자 | 취소 `{reason}` |
| POST | `/api/requests/{id}/noshow` | 받는 쪽 | 노쇼 처리(시작 뒤 일정 시간 지나야) |
| GET | `/api/requests/{id}/qr` | 받는 쪽·담당자 | 지금 보여 줄 QR·번호 |
| POST | `/api/requests/{id}/check-in` | 봉사자 | 시작 기록 `{code, method}` |
| POST | `/api/requests/{id}/check-out` | 봉사자 | 종료 기록 `{code, method}` |
| POST | `/api/requests/{id}/log` | 봉사자 | 활동 일지·전달 메모 제출 |
| POST | `/api/requests/{id}/confirm` | 받는 쪽·담당자 | 일지 확인 → 적립 확정(DONE) |
| POST | `/api/requests/{id}/adjust` | 봉사자 | 시간 조정 요청 `{hours, reason}` |
| POST | `/api/requests/{id}/adjust/answer` | 받는 쪽·담당자 | 조정에 답 `{accept}` |
| POST | `/api/requests/{id}/rate` | 당사자 | 평가 `{stars(1~5), tags[], comment}` |
| POST | `/api/requests/{id}/appeal` | 봉사자 | 난이도 이의 `{reason, requestedLevel}` |
| GET·POST | `/api/requests/{id}/messages` | 당사자·담당자 | 메시지 목록·보내기 `{text}` |
| GET | `/api/requests/{id}/briefing` | 당사자 | 방문 전 브리핑(AI/규칙, 저장·재사용) |
| GET | `/api/requests/{id}/message-assist` | 당사자 | 메시지 도우미(안전 감지·빠른 답장) |
| GET | `/api/judge-assist?kind=&id=` | 당사자·운영자 | 판단 보조. `kind=ADJUST`(요청 id) \| `APPEAL`(이의 id) |

### 상세
- **POST `/api/requests`** 본문 `{kind*, postId, volunteerId, elderId, date, startHour, hours, category, content, memo}`.
  - `REQUEST`: 봉사 가능 공고(OFFER)에 도움 요청. **담당자가 관리 대상을 대신해 보낼 때 `elderId` 필수.**
  - `PROPOSAL`: 도움 요청 공고(NEED)에 봉사 제안. 담당자는 보낼 수 없다(`INVALID_KIND`).
  - `REGULAR`: 공고 없이 단골 봉사자에게 직접. `volunteerId` 필수, 단골이 아니면 `NOT_REGULAR`.
  - → RequestView(+messages…). 오류: `INVALID_KIND`, `POST_REQUIRED`, `ELDER_REQUIRED`, `ELDER_ENDED`, `VOLUNTEER_REQUIRED`, `NOT_REGULAR`, `OWN_POST`, `OUT_OF_RANGE`, `PAST_DATE`, `INVALID_HOURS`, `INVALID_CATEGORY`, `CONTENT_REQUIRED`, 409 `DUPLICATE`, 409 `POST_CLOSED`, 403 `VOLUNTEER_BLOCKED`, 봉사 자격 미달(신분증·교육 — 코드는 `VolunteerGate`).
- 수락·거절·취소·확인 등 상태 전이 API는 모두 RequestView를 돌려주고, 지금 상태에서 할 수 없으면 409 `INVALID_STATE`.
- **GET `/api/requests/{id}/qr`** → `{status, elderCard, code, qrText, leftSec, stepSec}`. 관리 대상 건은 현관 QR 카드 번호(바뀌지 않음, `leftSec` null), 일반 건은 30초마다 바뀌는 번호. 상태가 `CONFIRMED`·`IN_PROGRESS`가 아니면 `code` null.
- **check-in / check-out** `{code, method("qr"|"code")}`. 오류: `WRONG_CODE`, `MANUAL_NOT_ALLOWED`(번호 없이 기록 불가), 409 `TOO_EARLY`.
- **POST `/api/requests/{id}/log`** `{raw*, text, photoUrl, photoConsent, careMemo, photoUrl2}`. 장보기·심부름은 영수증 사진, 가벼운 집안일은 하기 전·한 뒤 사진이 필수(`PHOTO_REQUIRED`). 오류 `LOG_REQUIRED`, `MEMO_TOO_LONG`, `FILE_NOT_MINE`. 제출하면 전달 메모가 정리돼 `care_notes`에 저장된다.
- **rate** → `{id, stars, tags, isPrivate}`. 오류 `INVALID_STARS`, `NO_ELDER_RATING`(봉사자는 관리 대상을 평가하지 않음), 409 `ALREADY_RATED`.
- **appeal** → `{id, status, requestedLevel}`. 오류 `INVALID_LEVEL`, `REASON_REQUIRED`, 409 `DUPLICATE`.
- **adjust** 오류 `INVALID_HOURS`, `REASON_REQUIRED`; **adjust/answer** 409 `NO_ADJUST`; **confirm** 409 `ADJUST_PENDING`.
- **messages GET** → `{messages[{id, senderId, senderLabel, systemMsg, text, warn, createdAt}], canMessage, notice}`. **POST** → `{id, text, warn, senderLabel, createdAt}`(연락처·계좌 패턴이면 `warn`). 오류 `TEXT_REQUIRED`.
- briefing·message-assist·judge-assist의 응답 칸은 `ActivityAiService`에서 만든다(확인 필요). 모두 `source: ai|rule`.

---

## 5. 단골·이웃 (7개)

| 메서드 | 경로 | 권한 | 설명 / 응답 |
|---|---|---|---|
| GET | `/api/regulars?elderId=` | 로그인. `elderId`를 주면 그 대상의 담당자만 | 내(또는 그 대상의) 단골 봉사자 목록. 소개 카드 + `doneCount, auto, manual` |
| GET | `/api/regulars/{volunteerId}/status` | 로그인 | `{volunteerId, doneCount, auto, manual, removed, removedByThem, regular, canMark}` |
| POST | `/api/regulars/{volunteerId}` | USER | 단골 직접 등록 → status. 오류 `SELF`, `INVALID_TARGET`, 409 `REMOVED_BY_VOLUNTEER` |
| DELETE | `/api/regulars/{volunteerId}` | 로그인 | 단골 해지(자동 3회 단골도 풀림) → status |
| GET | `/api/me/regular-badges` | 로그인 | 내가 받은 단골 배지 `[{key, kind(USER|ELDER), userId|elderId, name, label, count, auto, manual}]` |
| DELETE | `/api/me/regular-badges/{userId}` | 로그인 | 봉사하는 쪽에서 단골 해지 → 배지 목록 |
| GET | `/api/me/top-categories` | 로그인 | 내가 자주 받는 도움 `{top[{category, count, score}], order[], basis}` |

- 단골 규칙: 같은 사람(또는 관리 대상)을 완료(DONE) 3회 이상 도우면 자동 단골. 관리 대상의 단골은 직접 등록·해지가 없다(자동만).

---

## 6. 시간 통장·선물·시간 플랜·리포트 (12개)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/bank` | 로그인 | 통장 요약 + 거래 목록 |
| POST | `/api/bank/gift` | 로그인 | 연결된 가족에게 시간 선물 `{toUserId*, amount*(0.5 단위), message}` |
| GET | `/api/bank/month-note?month=` | 로그인 | 월별 결산 설명(AI/규칙) |
| GET | `/api/my/activities?seg=` | 로그인 | 내 활동. `seg`: `all`·`help`·`helped`·`posts` |
| GET | `/api/my/activity-summary?months=` | 로그인 | 월별 봉사·도움 받은 횟수(1~24개월, 기본 6) |
| GET | `/api/certificate?from=&to=` | 로그인 | 봉사 확인서 |
| POST | `/api/me/goal-plan` | 로그인 | 적립 목표 코치 `{category, perWeek, hoursPer, years, yearsLater, startAge}` |
| GET | `/api/me/time-plan?age=&startAge=` | 로그인 | 시간 플랜(숫자는 코드, 문장만 AI) |
| GET | `/api/me/month-report?month=` | 로그인 | 월별 리포트 |
| GET | `/api/reports/recent?subject=` | 아래 참고 | 최근 활동 리포트 |
| GET | `/api/me/insights` | USER | 개인 AI 분석 |
| POST | `/api/me/care-plan` | USER | 봉사 플랜 `{plans[{category, perWeek, hours}]}` |

### 상세
- **GET `/api/bank`** → `accountNo, totalEarned, totalUsed, balance, hold, available, usable, earnedSelf, giftReceived, canUseSelf, selfUseMin, monthIn, monthOut, monthly[], months[], entries[{id, type, amount, baseHours, weight, label, requestId, createdAt, balanceAfter, counterpart, counterpartRole(HELPER|HELPED)}]`. `balanceAfter`·`counterpart`는 2026-11-13에 더해졌다(계약서 7.20). 보류(HOLD)·보류 해제(RELEASE)는 잔액을 바꾸지 않는다.
- **POST `/api/bank/gift`** → `{ok, balance}`. 오류 `INVALID_INPUT`, `INVALID_AMOUNT`, 403(연결된 가족 아님), `INSUFFICIENT_BALANCE`.
- **GET `/api/certificate`** → `name, accountNo, period, from, to, totalHours, totalEarned, count, groupCount, elderCount, byCategory[{category, count, hours}], items[{date, category, hours, earned, recipient, elder, group?}], issuedAt, summary, summarySource(ai|rule)`. 오류 `INVALID_DATE`, `INVALID_PERIOD`.
- **GET `/api/my/activity-summary`** → `{months[{month, helpCount, helpHours, receivedCount, receivedHours}], totalHelpCount, totalReceivedCount, groupHelpCount}`.
- **GET `/api/reports/recent`** `subject` = `me`(USER 본인) | `elder:{id}`(**그 대상의 담당자**) | `family:{userId}`(연결되고 공유에 동의한 가족) → `{signal, subject, subjectType, subjectId, subjectLabel, items[{requestId, date, startHour, hours, category, content, volunteer, status, logText, careMemo, careNote{next[], hospital, life[], signals[], source, provider}}], total, handover, monthly[]}`. 오류 `INVALID_SUBJECT`, 403.
- month-note·goal-plan·time-plan·month-report·insights·care-plan의 응답 칸은 서비스에서 만든다(확인 필요).

---

## 7. 플래너·추천 (7개)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/me/planner?month=` | 로그인 | 달력 일정(내 요청·계획 + 단체 봉사) |
| GET | `/api/me/planner/week?start=&mode=` | 로그인 | 주 단위 일정. `mode`: `give`·`recv` |
| GET | `/api/me/recommends` | 로그인 | 홈 추천 봉사(AI 안 부름) |
| POST | `/api/me/planner/picks` | 로그인 | 추천 봉사를 계획에 넣기 `{postIds[]}` → 플래너 + `made, skipped, madeSource` |
| DELETE | `/api/me/planner/picks/{postId}` | 로그인 | 계획에서 빼기 → `{ok}` |
| GET | `/api/me/life-plan` | 로그인 | 저장된 생애 플랜 조건으로 계산 |
| POST | `/api/me/life-plan` | 로그인 | `{age, startAge, monthlyHours, needs{카테고리: 시간}, save}`로 계산(save면 저장) |

응답 칸은 `PlannerService`에서 만든다(확인 필요).

---

## 8. 정기 요청 (7개) — 사용자·담당자 공용

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/recurring?elderId=` | 로그인 | 내 정기 요청 목록. **담당자는 `elderId`로 그 대상 것만** |
| GET | `/api/recurring/{id}` | 관리할 수 있는 사람 | 한 건 |
| POST | `/api/recurring` | 로그인 | 정기 요청 등록(7일 안 회차는 바로 공고 생성) |
| POST | `/api/recurring/{id}/pause` | 〃 | 일시정지 |
| POST | `/api/recurring/{id}/resume` | 〃 | 다시 시작 |
| POST | `/api/recurring/{id}/cancel` | 〃 | 해지 |
| DELETE | `/api/recurring/{id}` | 〃 | 해지(cancel과 같음) |

- **POST 본문** `{weekday(1=월…7=일, 없으면 startDate의 요일), startDate*, endDate 또는 weeks(2~26), startHour*, endHour*, estHours, area, categories[], title, detail, note, elderId, forUserId, elderIssue}`.
  - `elderId`: 담당자의 관리 대상 대리. `elderIssue`(300자, 개인정보 금지)는 확정된 봉사자에게만 보인다.
  - 대리 정기 요청의 `note`에 `"방문 시 주의:"` 뒤로 적은 글은 서버가 `elderIssue`로 옮긴다(공개 칸에 남지 않게).
- **응답(RecurringView)** `id, weekday, repeatLabel, startHour, endHour, estHours, area, categories, title, detail, note, startDate, endDate, status(ACTIVE|PAUSED|ENDED|CANCELLED), stopReason, totalCount, remainingCount, passedCount, nextDate, nextPostId, nextPostAt, posts[{id, date, status, requestCount}], estEarnPerVisit, elderId, elderDisplayName, ownerId, ownerName, proxy, proxyMine, createdByName, createdAt, actions[]` + 등록·다시 시작 때 `createdPostIds[], createdCount, firstPost`.
- 오류: `INVALID`, `ISSUE_ONLY_FOR_ELDER`, `ISSUE_TOO_LONG`, `PII_NOT_ALLOWED`, 그 밖의 검증 코드는 `RecurringService`(확인 필요).

---

## 9. 묶음 추천 (2개)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/api/bundles/recommend` | 로그인 | `{date, fromHour, toHour, lat, lng}` → 겹치지 않고 가까운 도움 요청 공고 2~3건 묶음(응답 칸은 `BundleService`, 확인 필요) |
| POST | `/api/bundles/propose` | USER | `{items[{postId, startHour, hours}](최대 3), memo}` → 공고마다 제안을 차례로 보냄 |

- propose 응답 `{results[{postId, title, ok, requestId, startHour, hours, deadlineAt | code, error}], okCount, failCount, message}`. 일부 실패해도 HTTP 200. 보내기 전 검사 오류: `ITEMS_REQUIRED`, `TOO_MANY`, `INVALID_ITEM`, `DUPLICATE`, `DIFFERENT_DAY`, `TIME_OVERLAP`.

---

## 10. 단체 봉사 (11개) — 사용자·담당자 공용 (운영자는 쓰기 불가)

| 메서드 | 경로 | 누가 | 설명 |
|---|---|---|---|
| GET | `/api/group-posts?kind=&area=&cat=` | 로그인 | 목록. `kind`: `RECRUIT`(단체 모집)·`OFFER`(단체 봉사 가능) |
| GET | `/api/group-posts/mine` | 로그인 | 사용자=올렸거나 참여한 것, **담당자=우리 기관이 올렸거나 받기로 한 것** |
| GET | `/api/group-posts/schedule?month=` | 로그인 | 내 단체 일정(달력용). 오류 `INVALID_MONTH` |
| GET | `/api/group-posts/{id}` | 로그인 | 상세(참여자 이름은 올린 쪽·확인하는 담당자에게만) |
| POST | `/api/group-posts` | USER(제공형만)·**ORG(모집형·제공형)** | 올리기 |
| DELETE | `/api/group-posts/{id}` | 올린 쪽 | 취소(상태 CANCELLED) |
| POST | `/api/group-posts/{id}/join` | USER(신분증 인증) | 참여 신청(선착순) |
| POST | `/api/group-posts/{id}/leave` | 참여자 | 참여 취소 |
| POST | `/api/group-posts/{id}/host` | **ORG** | 제공형 단체 봉사를 우리 기관에서 받기 |
| POST | `/api/group-posts/{id}/unhost` | **ORG**(받기로 한 기관) | 받기 취소 |
| POST | `/api/group-posts/{id}/complete` | **ORG**(확인하는 쪽) | 참석 확인·완료 → 참석자 통장에 적립 |

- **POST 본문** `{kind*, date*, startHour*, hours*, area, place, category, title, detail, capacity*(2명 이상), groupName}`.
- **응답(GroupView)** `id, kind, kindLabel, title, detail, category, date, startHour, hours, endHour, area, place, groupName, capacity, joined, left, full, status(OPEN|DONE|CANCELLED), past, ownerId, ownerName, byOrg, orgName, hosted, hostOrgName, weight, estEarn, mine, myStatus, myEarned, distanceKm, createdAt, canJoin, joinBlock, canLeave, canCancel, canHost, canUnhost, canComplete, confirmSide` + 상세에는 참여자 목록(`[{userId, name, status, earned, …}]`, 볼 수 있는 사람만).
- **complete** 본문 `{attended[사용자 id]}` → GroupView + `result{ok, attended, absent, earnedEach, totalEarned}`.
- 오류 코드는 `GroupService`(확인 필요). AI는 부르지 않는다.

---

## 11. 가족 (6개)

| 메서드 | 경로 | 설명 / 응답 |
|---|---|---|
| GET | `/api/family` | `[{id, user(UserBrief), relation, status, direction(out|in), shareReport, theyShare, createdAt}]` |
| POST | `/api/family` | `{loginIdOrPhone*, relation}` → `{id, status, user}`. 오류 404, `SELF`, `NOT_USER`, 409 `DUPLICATE` |
| POST | `/api/family/{id}/answer` | `{accept}` → `{id, status}`. 409 `INVALID_STATE` |
| PUT | `/api/family/{id}/share` | `{share}` — 내 리포트를 상대에게 공유할지 → `{id, shareReport}` |
| DELETE | `/api/family/{id}` | 연결 끊기(행 삭제) → `{ok}` |
| GET | `/api/family/{userId}/report` | 공유에 동의한 가족의 생활 변화 리포트 `{user, reports[], latestMonth}`. 아니면 403 |

---

## 12. 알림·문의·신고·공지·카테고리 (15개) — 모든 역할 공용

| 메서드 | 경로 | 권한 | 설명 / 응답 |
|---|---|---|---|
| GET | `/api/notifications` | 로그인 | 내 알림(최근 순). 엔티티 그대로 `{id, userId, type, title, body, link, isRead, voiceText, createdAt}` |
| GET | `/api/notifications/unread-count` | 로그인 | `{count}` |
| POST | `/api/notifications/{id}/read` | 본인 | `{ok}` |
| POST | `/api/notifications/read-all` | 로그인 | `{ok}` |
| GET | `/api/notices` | **공개** | 공지 목록(고정 공지 먼저, 그 안에서 최근 순) `{id, title, content, pinned, createdAt}` |
| GET | `/api/notices/{id}` | **공개** | 공지 한 건 |
| GET | `/api/inquiries/mine` | 로그인 | 내 1:1 문의 `{id, userId, type, title, content, status, stage, answer, answeredAt, createdAt}` |
| POST | `/api/inquiries` | 로그인 | `{type, title*, content*}` → 문의. 운영자에게 알림 |
| POST | `/api/reports` | 로그인 | 신고 또는 이상 징후(안부 확인) 접수 |
| GET | `/api/reports/mine` | 로그인 | 내가 낸 신고 |
| GET | `/api/categories` | 로그인 | 공고 카테고리 전체(기본 6종 + 운영자가 더한 것) |
| POST | `/api/category-suggestions` | USER·ORG | 카테고리 추가 건의 `{name*(2~20자), reason*(5~300자)}` |
| GET | `/api/category-suggestions/mine` | 로그인 | 내 건의 `[{id, name, reason, status, statusLabel, answer, answeredAt, createdAt}]` |
| GET | `/api/verify-items` | 로그인 | 봉사 인증 항목 설정 `{items[], updatedAt}` |
| GET | `/api/me/voice-preview` | 로그인 | 음성 전화 알림 미리 듣기 `{text, sample, notificationId, title?, voiceAlert}` |

- **POST `/api/reports`** 본문 `{targetType*(POST|REQUEST|MESSAGE|USER), targetId, targetLabel, type*, content, kind}`.
  - 일반 신고: 운영자에게 알림. `USER` 대상은 실제 다른 사용자여야 한다.
  - **`kind: "WELFARE"`(이상 징후·안부 확인)**: `targetType=REQUEST`이고 관리 대상의 활동이어야 한다(`NOT_ELDER_ACTIVITY`). 그 활동의 봉사자·담당자만(403). `content` 필수(`CONTENT_REQUIRED`). **그 대상의 담당자에게 알림**이 가고, 운영자 목록에는 제재 없는 기록으로 남는다. 응급은 여기서 처리하지 않는다(화면이 119·112 안내).
  - 응답은 엔티티 그대로 `{id, reporterId, targetType, targetId, targetLabel, type, content, status, action, result, kind, elderId, createdAt, handledAt}`.
- 건의 오류: `INVALID_NAME`, `INVALID_REASON`, `PII_NOT_ALLOWED`, `CATEGORY_EXISTS`, `ALREADY_SUGGESTED`.
- 알림의 `type` 값(코드에서 본 것): `TRANSFER, WELFARE, PUBLIC_REFERRAL, MANAGER_APPLY, MANAGER_REJECTED, WEATHER, CATEGORY_SUGGESTION, INQUIRY, REPORT, REPORT_RESULT, VERIFY, ORG_APPLY, GIFT, FAMILY, APPEAL, ANOMALY, WARNING` 등. `link`는 화면 경로.

---

## 13. 파일 (2개)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/api/files` | 로그인 (multipart `file`) | 사진(jpg·png 등)·PDF 올리기 → `{url: "/api/files/{id}"}`. 오류 `FILE_REQUIRED`, `FILE_TYPE`, `FILE_TOO_LARGE`(10MB) |
| GET | `/api/files/{id}` | 볼 수 있는 사람만 | 파일 내려받기. `Cache-Control: private, no-store`. 404·403 |

- 아직 어디에도 붙지 않은 파일은 올린 사람만 연다. 로그인 없이 올린 파일(기관·담당자 신청의 재직 증빙)은 **운영자만** 연다.

---

## 14. 교육 — 첫 활동 연습 (7개)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/edu` | 내 교육 진행 상태 |
| PUT | `/api/edu/diag` | 사전 진단 저장(본문 자유 JSON) → 진행 상태 |
| POST | `/api/edu/cards-done` | 카드 다 봄 → `{cardsDone: true}` |
| POST | `/api/edu/quiz` | `{answers[숫자]}` → 채점 결과 |
| POST | `/api/edu/roleplay/{personaId}/finish` | `{log[]}` → 역할극 결과 |
| GET | `/api/edu/situations` | 연습 상황 목록(정답 표시는 뺌) `{items[]}` |
| POST | `/api/edu/practice` | `{key, text}` → 위반 알림·바꿔 말하기 또는 칭찬. 상황을 모두 마치면 `users.edu_done = true` |

응답 칸은 `EduService`에서 만든다(확인 필요).

---

## 15. 기관 소식·공공 서비스·연계 (13개)와 AI (13개)

### 15.1 동네 기관 소식
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/events?area=&nearby=` | 로그인 | 열린 기관 소식. `nearby=true`면 내 동네 근처만 |
| GET | `/api/events/{id}` | 로그인 | 소식 한 건(EventView) |
| GET | `/api/news?area=&sort=` | 로그인 | 한 목록: 담당자가 올린 소식(ORG) + AI가 찾은 소식(AI) + 공공데이터 강좌(PUBLIC). `sort=new`면 올라온 순서가 최근인 것부터(없으면 내 동네 먼저·날짜 빠른 순) |
| POST | `/api/events` | **ORG** | 소식 올리기 → 16.7 |
| POST | `/api/events/{id}/close` | **ORG**(우리 기관) | 소식 내리기 → 16.7 |
| POST | `/api/link-preview` | USER·ORG | `{url}` → `{url, title, image, description, siteName}`(저장 안 함) |

- **EventView** `id, orgId, orgName, title, content, date, startHour, place, applyType("LINK" 고정), url, capacity(0이면 null), preview{title, image, description}|null, status(OPEN|CLOSED), owner(내 기관 것인지), createdAt, area, near, distanceKm`.
- **GET `/api/news`** → `{items[{key, kind(ORG|AI|PUBLIC), id, title, orgName, date, startHour, when, place, area, near, url, capacity, image, aiFound, notice, createdAt}], area, searchAvailable, aiNotice}`. 신청은 앱이 아니라 기관 페이지(`url`)에서 한다.

### 15.2 기관 클래스(공공 강좌) 추천
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/public-classes/status` | 로그인 | 검색 가능 여부·건수 |
| GET | `/api/public-classes/recommend` | USER | 내게 맞는 강좌(하루 캐시) |
| GET | `/api/public-classes/{id}` | 로그인 | 강좌 상세 |
| POST | `/api/public-classes/search` | USER·ORG | 내 위치로 다시 찾기 `{region, lat, lng}`. **담당자는 `region`으로 지역을 바꿔 찾을 수 있다.** 지역별 하루 1회. 오류 `INVALID_LOCATION` |

응답 칸은 `PublicClassService`(확인 필요). 담당자용 대상별 추천은 16.8.

### 15.3 공공 서비스 연계 (사용자 쪽)
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/public-referrals/orgs` | 로그인 | 전달할 수 있는 기관(승인 + 활동 중 담당자 있음) `[{id, name, orgType, address, near}]` |
| POST | `/api/public-referrals` | USER | 공공 서비스 안내 내용을 기관 담당자에게 전달 |
| GET | `/api/public-referrals/mine` | 로그인 | 내가 전달한 것(처리 상태 포함, 담당자 메모는 안 보임) |

- **POST 본문** `{orgId*, question*(1000자), answer(2000자), services[](최대 10), note(300자), consent*(true)}` → ReferralView(16.6). 오류 `CONSENT_REQUIRED`, `QUESTION_REQUIRED`, `ORG_REQUIRED`, `NO_MANAGER`, 409 `ALREADY_SENT`. **전달에 동의한 것만 저장되고**, 그 기관의 모든 활동 중 담당자에게 알림(`/org/public-referrals/{id}`)이 간다.

### 15.4 AI — 작성·판정·안내·음성
| 메서드 | 경로 | 권한 | 본문 → 응답 |
|---|---|---|---|
| GET | `/api/ai/status` | 공개 | `{provider, model, stt, mode}` |
| POST | `/api/ai/post-chat` | 로그인 | `{type(OFFER|NEED), messages[{role, text}]*, forElderId, inputMode("voice")}` → 말로 공고 쓰기 대화 한 턴(최근 12턴만 사용). **담당자는 `forElderId`로 담당 대상을 대신 작성** |
| POST | `/api/ai/filter` | 로그인 | `{text*}` → 금지 요청 판정 `{level(ALLOW|CAUTION|BLOCK), reason, alternatives…}` |
| POST | `/api/ai/difficulty` | 로그인 | `{text, categories[], hours}` → 난이도 등급 제안 |
| POST | `/api/ai/log-tidy` | 로그인 | `{raw*}` → 일지 문장 다듬기 |
| POST | `/api/ai/public-services` | 로그인 | `{question*, area, ageGroup}` → 공공 서비스 안내 + `volunteer`(봉사로 가능한 일인지) |
| POST | `/api/ai/public-services/nearby` | 로그인 | `{question, lat, lng, area}` → 위치 기반 공공 서비스 안내 + `volunteer`. 오류 `INVALID_LOCATION` |
| POST | `/api/ai/recommend` | 로그인 | `{question}` → `{categories, helper, regularPosts[], nearbyPosts[], posts[], help{categories[], level, blocked, reason, publicServices[], note}}` |
| POST | `/api/ai/roleplay` | 로그인 | `{personaId*, history[], text}` → 역할극 한 턴 |
| POST | `/api/ai/post-check` | 로그인 | `{draft{categories, title, detail, caution, note}*, elderId}` → 요청서 빠진 정보 질문(최대 2개). **담당자는 `elderId`** |
| POST | `/api/ai/assistant` | 로그인 | `{text*}`(300자) → 음성 비서 답(실행하지 않음, `executed: false`) |
| POST | `/api/ai/tidy-post` | 로그인 | `{text*, elderId}` → 대리 공고 정리 → 16.9 |
| POST | `/api/stt` | 로그인 (multipart `audio`, `lang`) | 서버 음성 인식. 녹음은 저장하지 않는다. 503 `STT_UNAVAILABLE`, 400 `AUDIO_REQUIRED`, 413 `AUDIO_TOO_LARGE`, 502 `STT_FAILED` |

오류 코드: `MESSAGES_REQUIRED`, `TEXT_REQUIRED`, `LOG_REQUIRED`, `QUESTION_REQUIRED`, `PERSONA_REQUIRED`, `DRAFT_REQUIRED`. 응답의 세부 칸은 `ai/*.java`에서 만든다(확인 필요).

---

## 16. 기관 담당자 (`/api/org/**` 32개 + 함께 쓰는 공용 API)

- `/api/org/**`는 **ORG 역할만**(아니면 403 "기관 담당자만 쓸 수 있어요").
- "내 대상" 검사: 대상의 `managerId`가 내 id가 아니면 **403 "내가 담당하는 대상이 아니에요"**, 없으면 404.
- **개인정보 규칙(C-02)**: 관리 대상에는 실명·주소·연락처를 저장하지 않는다. 별칭·동·주의사항·메모에 연락처·상세 주소가 보이면 서버가 **400 `PII_NOT_ALLOWED`**로 막는다(`VoiceSafety.scrub`).
- 다른 사람에게 대상 이름은 가린 이름(`Rules.maskAlias`)으로만 나간다.

### 16.1 홈
#### GET `/api/org/home`
담당자 홈: 지금 할 일, 숫자 요약, 읽지 않은 알림 5개.

응답
| 칸 | 설명 |
|---|---|
| `todo[]` | `{requestId, type, elder, volunteer, date, startHour, category, leftText}`. `type`은 "제안 수락/거절"·"최종 수락/거부 입력"·"활동 일지 대신 확인"·"시간 조정 답하기" |
| `counts.monthDone` | 이번 달 완료 건 |
| `counts.elders` | 담당 중(ACTIVE) 대상 수 |
| `counts.pendingFinal` | 내 응답이 필요한 건(봉사자가 수락한 요청 + 받은 제안) |
| `counts.logsToConfirm` | 일지 확인 대기 |
| `counts.active` | 진행 중(확정~일지 제출) |
| `counts.flaggedReports` | 지난달 리포트 가운데 "큰 변화" 표시된 내 대상 수 |
| `counts.transfersIn` | 내게 온 이관 신청(PENDING) |
| `alerts[]` | 읽지 않은 알림 최근 5개(Notification 엔티티) |

### 16.2 관리 대상
**ElderView** (목록·등록·수정·종료·동의 응답): `id, alias, displayName("별칭 님"), maskedName, dong, ageGroup, needs[], caution, status(ACTIVE|ENDED), reportConsent, serviceConsentAt, serviceConsent, managerId, createdAt, qrIssuedAt, regularCount, activeCount, monthDone, doneTotal, flag(최근 리포트가 큰 변화인지)`

#### GET `/api/org/elders`
내 담당 대상 목록(ACTIVE 먼저, id 순) → ElderView[].

#### POST `/api/org/elders`
대상 등록.
| 본문 칸 | 형식 | 필수 | 설명 |
|---|---|---|---|
| alias | 글자 1~10자 | ✓ | 별칭(실명 아님) |
| dong | 글자, "동"으로 끝남 | ✓ | 동 단위까지만 |
| ageGroup | 글자 | | 나이대 |
| needs | 배열 | | 필요한 도움(카테고리만 남김) |
| caution | 글자 | | 방문 주의사항(확정된 봉사자에게 보임) |
| memo | 글자 | | 첫 담당자 메모(담당자만 봄) |
| consent | true | ✓ | "대상에게 서비스 이용을 설명하고 동의를 받았음" 확인 → `serviceConsentAt`·`serviceConsentBy` 기록 |

응답 ElderView. 오류: `INVALID_INPUT`(별칭·동 없음), `CONSENT_REQUIRED`, `INVALID_ALIAS`, `INVALID_DONG`, `PII_NOT_ALLOWED`. 등록하면 `orgId`=내 기관, `managerId`=나.

#### GET `/api/org/elders/{id}`
대상 상세 → ElderView + `memos[]`(16.3), `regulars[]`(단골 봉사자 카드), `requests[]`(최근 20건 RequestView), `posts[]`(삭제 안 된 PostCard), `reports[]`(월별 생활 변화 리포트, 16.5).

#### PUT `/api/org/elders/{id}`
보낸 칸만 고친다. 본문은 등록과 같다(`memo`는 여기서 쓰지 않음). 동의 기록이 없는 예전 대상은 `consent: true`를 보내면 지금 시각으로 남긴다(이미 있는 기록은 바꾸지 않음). 오류는 등록과 같다.

#### POST `/api/org/elders/{id}/end`
지원 종료(`status=ENDED`). 열려 있는 그 대상의 공고는 마감(CLOSED)된다. 끝나지 않은 요청이 있으면 **409 `ACTIVE_REQUESTS`**. 응답 ElderView.

#### PUT `/api/org/elders/{id}/consent`
리포트 공유 동의를 담당자가 대신 입력. 본문 `{reportConsent: true|false}` → ElderView.

### 16.3 담당자 메모
#### POST `/api/org/elders/{id}/memos`
본문 `{text*}`(1000자). 원문은 그대로 저장하고, 읽기 쉬운 정리본을 AI(키 없으면 규칙)가 따로 만든다.
응답 메모 목록(최근 순) `[{id, elderId, text(원문), tidy(정리본), lines[](정리본 줄 목록), source(ai|rule), createdAt}]`.
오류 `TEXT_REQUIRED`, `TEXT_TOO_LONG`, `PII_NOT_ALLOWED`.
- 메모는 **담당자만** 본다. 봉사자에게 가는 주의사항(`caution`)과 따로다. 메모 수정·삭제 API는 없다.

### 16.4 현관 QR 카드
#### GET `/api/org/elders/{id}/qr-card`
카드 보기(없으면 처음 한 번 만든다). 응답 `{elderId, displayName, code(6자리), qrText, issuedAt, activeCount(확정·진행 중 건수)}`. 카드에는 이름·주소가 없고 번호만 담긴다.

#### POST `/api/org/elders/{id}/qr-card/reissue`
다시 발급. 번호가 바뀌어 이전 카드로는 시작·종료를 기록할 수 없다. 종료된 대상은 400 `ELDER_ENDED`. 응답은 위와 같다.
- 봉사자는 이 카드의 QR·번호로 `POST /api/requests/{id}/check-in`·`/check-out`을 부른다.

### 16.5 요청·리포트·변화 리포트
#### GET `/api/org/requests?box=`
내 담당 대상의 요청·제안 전체(최근 순) → RequestView[] (`myRole=MANAGER`).
| box | 뜻 |
|---|---|
| `received` | 내 응답이 필요한 것 + 일지 확인 대기 |
| `sent` | 내가 보내고 아직 응답 대기 |
| `active` | 진행 중 |
| `done` | 끝난 것 |
| 없음·`all` | 전체 |

#### POST `/api/org/requests/{id}/reject-volunteer` — 16.10 차단 참고

#### GET `/api/org/reports?month=YYYY-MM`
내 대상의 월별 생활 변화 리포트(기본: 지난달, 큰 변화 먼저).
응답 `[{id, subjectType: "ELDER", subjectId, subjectLabel, month, stats{…}, narrative, bigChange, source, createdAt, reportConsent}]`. `stats`는 저장된 JSON 그대로(`count, prevCount, receivedCount, helpCount …`).

#### POST `/api/org/reports/{id}/flag`
리포트의 "큰 변화" 표시를 켜고 끄기(토글) → `{id, bigChange}`. 내 대상의 리포트가 아니면 403.

#### GET `/api/org/change-report?range=`
대상 변화 리포트: 대상별 최근 활동 일지를 이전 기록과 비교해 **먼저 살펴볼 순서**를 준다. `range=today`면 오늘 일지가 들어온 대상만, 아니면 전체(최근 30일 기준).
응답
| 칸 | 설명 |
|---|---|
| `date`, `range`(today\|recent), `windowDays`(30) | |
| `items[]` | 아래 |
| `total` | 담당 중 대상 수 |
| `source` | 하나라도 AI 문장이면 `ai`, 아니면 `rule` |
| `notice` | 안내 문장 |

`items[]`: `elderId, alias, displayName, dong, rank, score, level(LOW 등), reasons[], stats{recentCount, prevCount, todayCount, daysSinceLast, lastDate, signalCount, signals, newSignals, flagged}, apply{total, matched, waiting, rejected, expired, cancelled, noshow, unmatchedPosts, failed}, applyText, checkFirst(level이 LOW가 아니면 true), summary, changes, source, provider`.
- 점수·순위·신청 내역 집계는 코드가 한다. AI 문장은 상위 몇 명만 만들고 서버 메모리에만 잠깐 둔다(**DB에 저장하지 않는다**).

#### GET `/api/org/care-signals`
대상별 생활 변화 "확인 권장" 상태. 응답 `[{elderId, alias, flagged, count, labels[], since, recent[{requestId, date, signals[]}], window(3), notice}]`. 최근 전달 메모 3건 중 신호가 2건 이상이거나 뚜렷한 표현이 있으면 `flagged`.

#### GET `/api/org/briefing?refresh=false`
이번 주 브리핑. `refresh=true`면 요약 글을 다시 만든다(아니면 저장된 것 재사용, 표 `ai_results` kind `ORG_BRIEFING`).
응답
| 칸 | 설명 |
|---|---|
| `week{start, end}` | 이번 주 월~일 |
| `counts` | `elders, schedule, unmatched, unmatchedUrgent, logPending, finalPending, flagged, blocksNew, blocksTotal` |
| `elders[]` | `{elderId, alias, items[{requestId, date, label, category, status, done}], flagged, signalLabels[]}` |
| `unmatched[]` | 아직 안 맞춰진 열린 공고 `{postId, elderId, alias, title, date, label, daysLeft, pendingRequests, urgent(3일 이내)}` |
| `logPending[]` | `{requestId, alias, category, label}` |
| `finalPending[]` | `{requestId, alias, category, label, kind, deadlineAt}` |
| `flags[]` | `{elderId, alias, labels[], count}` |
| `blocks{recent[{blockId, elderId, alias, reason, createdAt}], total}` | 최근 7일 받지 않기 |
| `summary{lines[], todo[], source}` | 요약 글(AI 또는 규칙) |
| `generatedAt, cached, reason` | |

### 16.6 공공 서비스 연계
#### GET `/api/org/public-referrals`
우리 기관(`orgId`)에 온 연계 요청 전체(최근 순). 응답 `{items[ReferralView], newCount, openCount}`.

**ReferralView**: `id, orgId, orgName, question, answer, services[](AI가 찾은 서비스 — name, target, summary, apply, phone …), note(사용자 한마디), status, statusLabel, memo(담당자 처리 메모 — 담당자에게만), createdAt, updatedAt, user{id, name, phone, area, ageGroup}`(전달에 동의한 사용자 정보 — 담당자에게만).

#### POST `/api/org/public-referrals/{id}/status`
본문 `{status*, memo(500자)}`. `status`: `CONTACTED`(연락함)·`HELPING`(신청 도움 중)·`DONE`(완료)·`NOT_ELIGIBLE`(대상이 아니에요). `NEW`로는 되돌릴 수 없다.
응답 ReferralView. 처리한 담당자(`handlerId`)가 기록되고 **사용자에게 알림**이 간다. 오류 `INVALID_STATUS`, 403(우리 기관 것이 아님), 404.

### 16.7 기관 소식(행사) — 경로는 `/api/events`지만 담당자만
#### GET `/api/org/events`
우리 기관이 올린 소식(최근 순) → EventView[] (15.1).

#### POST `/api/events`
| 본문 칸 | 형식 | 필수 | 설명 |
|---|---|---|---|
| title | 100자 | ✓ | |
| content | 2000자 | | |
| date | 날짜 | | 오늘 이후 |
| startHour | 소수 시 | | 30분 단위 |
| place | 글자 | | |
| url | 주소 | ✓ | 기관 페이지 링크(자세한 내용·신청은 그 페이지에서) |
| ogTitle, ogImage, ogDesc | 글자 | | 화면이 미리 받은 미리보기. 없으면 서버가 한 번 가져온다 |
| capacity | 0~9999 | | 정원(표시용, 0·비움 = 제한 없음) |

응답 EventView. 오류: 403(담당자 아님), `TITLE_REQUIRED`, `TITLE_TOO_LONG`, `CONTENT_TOO_LONG`, `INVALID_DATE`, `INVALID_TIME`, `INVALID_CAPACITY`, `URL_REQUIRED`, 주소 형식·내부 주소 오류(`LinkPreviewService`, 확인 필요).

#### POST `/api/events/{id}/close`
우리 기관 소식 내리기(`status=CLOSED`) → EventView. 다른 기관 것이면 403. 소식 수정·삭제 API는 없다.

### 16.8 편성표·특보·기관 클래스
#### GET `/api/org/schedule?week=YYYY-MM-DD&elderId=`
주간 돌봄 편성표(대상 × 요일). `week`는 그 주의 아무 날짜(비우면 이번 주), `elderId`를 주면 그 대상만.
응답
| 칸 | 설명 |
|---|---|
| `weekStart, weekEnd, prevWeek, nextWeek, today, thisWeek` | 주 이동용 |
| `days[]` | `{date, dow(월~일), label("11/13"), today, past}` |
| `elders[]` | `{elderId, alias, displayName, dong, needs[], cells[]}` |
| `elderId` | 요청한 필터 |
| `filter[]` | 필터에 넣을 대상 `{elderId, displayName}` |
| `counts` | `{fixed, pending, open, gaps, empty}` |

`cells[]`(요일마다 1개): `{date, items[], empty, past, selectable(비었고 지나지 않음), gap}`
- `items[]` 요청: `{kind: "REQUEST", state(PENDING|CONFIRMED|PROGRESS|DONE), requestId, status, startHour, hours, category, volunteerName}`
- `items[]` 열린 공고: `{kind: "POST", state: "OPEN", postId, startHour, endHour, category, title, pendingCount}`
- `gap`: 지난 4주 같은 요일에 2번 이상 방문이 있었는데 이번에 비어 있으면 `{weeks, category, startHour, hours, reason}`, 아니면 null.

#### POST `/api/org/schedule/suggest`
빈 칸 하나에 맞는 봉사자 후보를 찾는다. **아무것도 보내지 않는다**(`sent: false`).
본문 `{elderId*, date*(오늘 이후), startHour, hours, category*}`.
응답 `{elderId, displayName, dong, slot{date, dow, startHour, hours, category, label}, pattern{weeks, category, startHour, hours}|null, candidates[], alternatives[{date, dow, label, count, candidates[]}], postDraft{elderId, date, startHour, endHour, category}, message, source(ai|rule), provider?, notice, sent: false}`.
`candidates[]`(최대 5): `{volunteerId, name, area, ratingAvg, ratingCount, regular, doneCount, sameDong, timeFit, offerPostId, offerStartHour, offerEndHour, action, tier, facts[], score, reason}`.
- `action`: `REQUEST`(그 시간 봉사 가능 공고가 있음 → `POST /api/requests` kind=REQUEST, postId=offerPostId) · `REGULAR`(단골 → kind=REGULAR) · `POST`(공고를 올려 기다림 → `postDraft`로 `POST /api/posts`).
- 받지 않기로 정한 봉사자와 시간이 겹치는 봉사자는 후보에서 빠진다.
오류 `ELDER_REQUIRED`, `ELDER_ENDED`, `INVALID_DATE`, `INVALID_CATEGORY`, 403, 404.

#### POST `/api/org/schedule/bulk-posts`
빈 칸 여러 개에 대리 공고를 한꺼번에(최대 20칸). 칸마다 따로 저장해서 한 칸이 실패해도 나머지는 올라간다.
본문 `{items[{elderId*, date*, startHour*, endHour, category*, detail}]}`.
응답 `{created[{index, elderId, date, startHour, endHour, category, displayName, postId, title}], failed[{index, …, code, reason}], createdCount, failedCount}`.
전체 오류 `ITEMS_REQUIRED`, `TOO_MANY`. 칸별 실패 코드 `INVALID_CATEGORY`, `INVALID_DATE`, `INVALID_TIME`, `SLOT_TAKEN`(그 시간에 이미 일정·열린 공고), 그 밖에 공고 올리기 오류.

#### GET `/api/org/weather`
담당자 특보 알림: 날씨 특보와 운영자가 보낸 안내.
응답 `{alerts[](진행 중), past[](끝난 것, 30일 이내), guides[](운영자가 전달한 것), banner(홈 띠: 전달됐고 진행 중이며 내 대상이 있는 지역의 최근 것 | null), myElderTotal}`.
항목: `id, type(폭염·한파·호우·대설·강풍·태풍·건조), level(주의보·경보), areas[], from, to, active, mineArea(내 대상이 그 지역에 있는지), myElders, myElderNames[](최대 20), myActivities(기간 안 예정 활동 수), actions[](할 일 문장), source(MANUAL|SAMPLE|KMA), sent, sentAt, noticeId, guideTitle, guide(공지 내용)`.
- 다른 담당자의 정보는 주지 않는다. 운영자가 전달하면 담당자에게 `WEATHER` 알림(링크 `/notice/{id}`)이 온다.

#### GET `/api/org/elders/{id}/public-classes`
이 대상께 맞는 기관 클래스(대상의 동·나이대·필요 도움 기준). 응답 칸은 `PublicClassService.recommendForElder`(확인 필요). 내 대상이 아니면 403.

### 16.9 대리 공고·대리 요청 — 공용 API를 담당자가 쓰는 법
담당자는 `/api/org/**`가 아닌 공용 API에 `elderId`를 넣어 관리 대상을 대신한다.

| 하려는 일 | API | 담당자가 넣는 것 |
|---|---|---|
| 대리 공고 올리기 | `POST /api/posts` | `type: "NEED"`만, `elderId*`, `elderIssue`(이 공고에서 보일 주의사항) |
| 대리 공고 고치기·삭제 | `PUT`·`DELETE /api/posts/{id}` | 담당 대상 공고 |
| 지난 요청 다시 올리기 | `GET /api/posts/mine?elderId=` → `POST /api/posts/{id}/repost {date}` | |
| 봉사 가능 공고에 대신 요청 | `POST /api/requests` | `kind: "REQUEST"`, `postId`, `elderId*` |
| 단골에게 대신 직접 요청 | `GET /api/regulars?elderId=` → `POST /api/requests` | `kind: "REGULAR"`, `volunteerId`, `elderId*` |
| 받은 제안 수락·거절 | `POST /api/requests/{id}/accept`·`/reject` | |
| 봉사자가 수락한 요청 최종 수락·거부 | `POST /api/requests/{id}/accept`·`/decline` | |
| 일지 대신 확인(적립 확정) | `POST /api/requests/{id}/confirm` | |
| 시간 조정에 답하기 | `POST /api/requests/{id}/adjust/answer` | |
| 노쇼 처리·취소 | `POST /api/requests/{id}/noshow`·`/cancel` | |
| 메시지 | `GET`·`POST /api/requests/{id}/messages` | 보낸 사람 이름표는 서버가 붙임 |
| 봉사자 평가 | `POST /api/requests/{id}/rate` | |
| 대리 정기 요청 | `POST /api/recurring`, `GET /api/recurring?elderId=` | `elderId*`, `elderIssue` |
| 말로 대리 공고 쓰기 | `POST /api/ai/post-chat` | `forElderId` |
| 요청서 빠진 정보 점검 | `POST /api/ai/post-check` | `elderId` |
| 이상 징후 접수 | `POST /api/reports` | `kind: "WELFARE"` |
| 대상의 최근 활동 리포트 | `GET /api/reports/recent?subject=elder:{id}` | |
| 단체 봉사 모집·받기·참석 확인 | `/api/group-posts` (10장) | |
| 알림·공지·문의·카테고리 건의 | 12장 | 건의 답 알림 링크는 `/o/my` |
| 내 정보·알림 설정·탈퇴 | 2장 | 음성·문자 알림은 켤 수 없음 |

- 담당자는 봉사 제안(`PROPOSAL`)을 보낼 수 없고, 봉사 가능(`OFFER`) 공고도 올릴 수 없다.
- 이관 뒤에는 **지금 담당자만** 그 대상 건의 권한을 가진다(예전에 요청을 보낸 담당자는 권한 없음).

#### POST `/api/ai/tidy-post`
대리 공고 글을 AI로 정리(저장 안 함). 본문 `{text*, elderId}` → `{title, detail, caution, categories[], piiRemoved[], source, provider?}`. `elderId`를 주면 그 대상의 기본 주의사항을 참고한다(내 대상이 아니면 403). 오류 `TEXT_REQUIRED`.

#### POST `/api/org/ai/memo-to-posts`
통화 메모 → 공고 초안 여러 개(최대 8개, **저장 안 함**). 본문 `{elderId*, memo*(1000자)}`.
응답 `{elder{id, alias, displayName, dong}, drafts[{elderId, title, detail, note, categories[](최대 2), missing[], checked, filter{level, reason}, …}], source …}`(초안의 나머지 칸은 `OrgAi`, 확인 필요). 초안은 담당자가 확인한 뒤 `POST /api/posts`로 직접 올린다.
오류 `ELDER_REQUIRED`, `ELDER_ENDED`, `MEMO_REQUIRED`, `MEMO_TOO_LONG`, 403, 404.

### 16.10 이관
#### GET `/api/org/managers`
같은 기관의 다른 담당자(이관 받을 사람) `[{id, name, status, elderCount}]`.

#### POST `/api/org/elders/{id}/transfer`
이관 신청. 본문 `{toManagerId*}`. 같은 기관의 활동 중인 다른 담당자만(`INVALID_MANAGER`). 그 대상에 이미 신청 중이면 409 `DUPLICATE`. 받을 담당자에게 알림. 응답 TransferView.

**TransferView** `{id, elderId, elderAlias, from(UserBrief), to(UserBrief), status(PENDING|ACCEPTED|REJECTED|ADMIN), createdAt}`

#### GET `/api/org/transfers`
내가 보냈거나 받은 이관 신청(최근 순) → TransferView + `direction("in"|"out")`.

#### POST `/api/org/transfers/{id}/answer`
받은 신청에 답. 본문 `{accept}`. 수락하면 대상의 `managerId`가 나로 바뀐다. 보낸 담당자에게 알림. 받을 사람이 아니거나 이미 처리됐으면 409 `INVALID_STATE`.
- 운영자가 직접 이관하는 길은 `POST /api/admin/elders/{id}/transfer`(17장, 상태 `ADMIN`, 기관이 달라도 됨).

### 16.11 봉사자 거부·받지 않기(차단)
#### POST `/api/org/requests/{id}/reject-volunteer`
담당 대상 건의 봉사자를 거부. 요청·제안 단계나 확정된 일정에서만(아니면 409 `INVALID_STATE`).
본문 `{reason*(300자), block}`. `block: true`면 그 봉사자를 이 대상에게서 **받지 않는다**(표 `elder_blocks`).
- 상태 변화: 받은 제안(SENT+PROPOSAL) → `REJECTED`, 봉사자가 수락한 요청(VOL_ACCEPTED) → `DECLINED`, 그 밖(확정 등) → `CANCELLED`. `rejectReason = "담당자 거부: …"`. 맞춰졌던 공고는 날짜가 남았으면 다시 열린다. 시스템 메시지와 봉사자 알림(`MANAGER_REJECTED`)이 간다.
응답 RequestView + `blocked`. 오류 `REASON_REQUIRED`, `REASON_TOO_LONG`, 403, 404.

#### GET `/api/org/elders/{id}/blocks`
그 대상의 받지 않기 목록 `[{id, elderId, volunteer(UserBrief), reason, requestId, createdAt}]`.

#### DELETE `/api/org/blocks/{id}`
받지 않기 풀기(행 삭제) → `{ok: true}`.
- 받지 않기가 있으면 그 봉사자가 그 대상 건에 요청·제안할 때 403 `VOLUNTEER_BLOCKED`.

### 16.12 담당자 영역 API 한눈에 (32개)
| # | 메서드 | 경로 | 절 |
|---|---|---|---|
| 1 | GET | `/api/org/home` | 16.1 |
| 2 | GET | `/api/org/elders` | 16.2 |
| 3 | POST | `/api/org/elders` | 16.2 |
| 4 | GET | `/api/org/elders/{id}` | 16.2 |
| 5 | PUT | `/api/org/elders/{id}` | 16.2 |
| 6 | POST | `/api/org/elders/{id}/end` | 16.2 |
| 7 | PUT | `/api/org/elders/{id}/consent` | 16.2 |
| 8 | POST | `/api/org/elders/{id}/memos` | 16.3 |
| 9 | GET | `/api/org/elders/{id}/qr-card` | 16.4 |
| 10 | POST | `/api/org/elders/{id}/qr-card/reissue` | 16.4 |
| 11 | GET | `/api/org/requests` | 16.5 |
| 12 | GET | `/api/org/reports` | 16.5 |
| 13 | POST | `/api/org/reports/{id}/flag` | 16.5 |
| 14 | GET | `/api/org/change-report` | 16.5 |
| 15 | GET | `/api/org/care-signals` | 16.5 |
| 16 | GET | `/api/org/briefing` | 16.5 |
| 17 | GET | `/api/org/public-referrals` | 16.6 |
| 18 | POST | `/api/org/public-referrals/{id}/status` | 16.6 |
| 19 | GET | `/api/org/events` | 16.7 |
| 20 | GET | `/api/org/schedule` | 16.8 |
| 21 | POST | `/api/org/schedule/suggest` | 16.8 |
| 22 | POST | `/api/org/schedule/bulk-posts` | 16.8 |
| 23 | GET | `/api/org/weather` | 16.8 |
| 24 | GET | `/api/org/elders/{id}/public-classes` | 16.8 |
| 25 | POST | `/api/org/ai/memo-to-posts` | 16.9 |
| 26 | GET | `/api/org/managers` | 16.10 |
| 27 | POST | `/api/org/elders/{id}/transfer` | 16.10 |
| 28 | GET | `/api/org/transfers` | 16.10 |
| 29 | POST | `/api/org/transfers/{id}/answer` | 16.10 |
| 30 | POST | `/api/org/requests/{id}/reject-volunteer` | 16.11 |
| 31 | GET | `/api/org/elders/{id}/blocks` | 16.11 |
| 32 | DELETE | `/api/org/blocks/{id}` | 16.11 |

---

## 17. 운영자 (`/api/admin/**` 75개) — ADMIN만

### 17.1 대시보드·분석 (8개)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/dashboard` | `{supply, demand, elderUsage, kpi{activities, prevActivities, groupActivities, matchRate, elderUsage, prevElderUsage, newUsers, totalUsers, orgCount}, matchRateByArea[{area, rate, total, matched}], timeBalance{issued, consumed, elderFree, useRatio}, monthly[6개월], weekly[4주], edu{volunteers}, pending{orgs, ids, crimes, appeals, reports, welfare, inquiries, anomalies}}` |
| GET | `/api/admin/analytics` | 운영 분석(응답 칸 `AnalyticsService`, 확인 필요) |
| GET | `/api/admin/insights?refresh=` | 인사이트(1시간 캐시) + `categorySuggestions[{key, name, count, waiting}]` |
| GET | `/api/admin/stats/types` | 유형별 통계 |
| GET | `/api/admin/stats/season` | 계절별 통계 |
| GET | `/api/admin/stats/by-type?type=NEED\|OFFER` | 이용 리포트: 나이별 인기·계절별·지역별 |
| GET | `/api/admin/recruit-drafts` | 모집 글 초안 |
| POST | `/api/admin/scheduler/run` | 스케줄러 지금 한 번 돌리기(기한 만료·알림·정기 요청 회차 등) |

### 17.2 기관·담당자 승인과 관리 (9개)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/orgs` | 기관 신청 목록(Organization 엔티티 그대로, 대기 먼저) |
| POST | `/api/admin/orgs/{id}/approve` | 승인 → 담당자 계정 자동 생성 `{orgId, status, managerId, managerLoginId, tempPassword, message}`. 대기 중이 아니면 409 `INVALID_STATE` |
| POST | `/api/admin/orgs/{id}/reject` | 반려 → Organization |
| GET | `/api/admin/managers` | 담당자 목록 `[{id, loginId, name, status, banned, orgId, orgName, elders[{id, alias, status}]}]` |
| POST | `/api/admin/managers/{id}/suspend` · `/unsuspend` | 담당자 정지·해제 → `{id, status}`. 퇴출 계정은 409 `BANNED` |
| POST | `/api/admin/elders/{id}/transfer` | 운영자 대리 이관 `{toManagerId*}` → `{elderId, managerId, status: "ADMIN"}`. 대상의 `managerId`·`orgId`가 받는 담당자 쪽으로 바뀌고, 걸려 있던 이관 신청은 REJECTED. 오류 `INVALID_MANAGER` |
| GET | `/api/admin/manager-applications` | 담당자 계정 신청(자동 승인) 내역 `[{id, orgId, orgName, orgType, userId, name, loginId, phone, proofFileUrl, status, statusLabel, userStatus, existingManagers, otherManagersNow, revokeReason, revokedAt, createdAt}]` |
| POST | `/api/admin/manager-applications/{id}/revoke` | 잘못된 계정 취소 `{reason}` → 신청 `REVOKED`, 계정 정지, 로그인 끊김. 이미 취소면 409 |

### 17.3 신원 승인·난이도 이의 (4개)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/verifications` | 승인 대기 `[{userId, name, kind(id|crime), fileUrl, ageGroup, createdAt}]` |
| POST | `/api/admin/verifications/{userId}/{kind}/{decision}` | `kind`=`id`·`crime`, `decision`=`approve`·`reject` → `{userId, idStatus, crimeStatus}`. 오류 `INVALID` |
| GET | `/api/admin/appeals` | 난이도 이의 목록 |
| POST | `/api/admin/appeals/{id}/answer` | `{accept, resultLevel, note}` → `{id, status, resultLevel, adjust}`. 받아들이면 차액을 ADJUST로 적립 |

### 17.4 신고·이상 적립 (9개)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/reports` | 신고 목록(처리 전 먼저). 이상 징후 접수는 `welfare: true`, `elder{id, displayName, dong}`, `manager` |
| GET | `/api/admin/report-context/{id}` | 처리 전에 볼 관련 기록 `{reportId, target, pastReports[], betweenDone, reporterReportCount, request, messages[], post}` |
| POST | `/api/admin/reports/{id}/handle` | `{action(NONE|WARNING|SUSPEND|BAN), result}`. `action` 없이 보내면 검토 중(REVIEWING)으로만. 이상 징후 접수는 `NONE`만(`NO_SANCTION`) |
| GET | `/api/admin/ai/report-triage` | 신고 정리(요약·심각도 제안) |
| POST | `/api/admin/ai/report-triage/{id}` | 한 건 다시 정리 |
| GET | `/api/admin/anomalies` | 이상 적립 패턴(최근 30일 같은 쌍의 1시간 이하 활동 3회 이상) |
| POST | `/api/admin/anomalies/{requestId}/{op}` | `op`=`hold`·`release` → `{id, anomalyHold}` |
| GET | `/api/admin/ai/anomalies?refresh=` | 이상 패턴 설명 |
| POST | `/api/admin/reports/generate` | 생활 변화 리포트 즉시 생성 `{month}` → `{month, generated}` |

### 17.5 회원 (4개)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/users?q=&role=` | 회원 목록 `[{id, loginId, name, role, status, area, ageGroup, phone, idStatus, crimeStatus, banned, volunteerCount, recipientCount, balance, penaltyCount, warningCount, createdAt}]` |
| GET | `/api/admin/users/{id}` | 상세(+`intro, categories, ratingAvg, ratingCount, recentRequests[], reportsAgainst[]`) |
| POST | `/api/admin/users/{id}/suspend` · `/unsuspend` | 정지·해제. 운영자 계정은 403, 퇴출 계정은 409 `BANNED` |

### 17.6 문의·공지 (8개)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/inquiries` | 문의 목록 `[{id, user, type, title, content, status, stage, answer, answeredAt, createdAt}]` |
| PUT | `/api/admin/inquiries/{id}/status` | 처리 단계 `{status(RECEIVED|CHECKING|ANSWERED|CLOSED)}`. 오류 `INVALID_STATUS`, `ANSWER_REQUIRED` |
| POST | `/api/admin/inquiries/{id}/answer` | `{answer*}` → 문의(답변 완료). 문의한 사람에게 알림 |
| POST | `/api/admin/ai/inquiries/{id}/draft?refresh=` | 답변 초안(발송 안 함) |
| POST | `/api/admin/notices` | `{title*, content}` → 공지. 오류 `TITLE_REQUIRED` |
| PUT | `/api/admin/notices/{id}` | `{title, content}` |
| DELETE | `/api/admin/notices/{id}` | 삭제(행 삭제) |
| POST | `/api/admin/notices/{id}/pin` | 고정 공지 켜고 끄기 `{pinned}`(비우면 토글) |

### 17.7 카테고리·인증 항목·교육 상황·역할극 상대·공공 서비스 정보 (17개)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/category-suggestions` | 건의 묶음 `{groups[{key, name, count, waiting, status, items[]}], total, waiting, note}` |
| POST | `/api/admin/category-suggestions/{id}/answer` | `{status(RECEIVED|REVIEWING|ADOPTED|HELD), answer}` — 같은 이름 건의 전체에 적용. `ADOPTED`면 카테고리에 더함 |
| POST | `/api/admin/category-suggestions/{id}/adopt` | 등급을 골라 승인 `{level, answer}` → `{ok, updated, status, category}` |
| DELETE | `/api/admin/categories/{name}` | 운영자가 더한 카테고리 빼기 |
| PUT | `/api/admin/verify-items` | 봉사 인증 항목 설정 `{items[{key, on, required}]}`. 오류 `INVALID_ITEM` |
| GET·PUT | `/api/admin/edu/situations` | 연습 상황 목록(정답·이유 포함) 보기·저장 `{items[]}` |
| GET·POST | `/api/admin/personas` | 역할극 가상 상대 목록·추가 `{name*, initial, ageLabel, fitCategory, trait, goal, opening, turns[], active}` |
| PUT·DELETE | `/api/admin/personas/{id}` | 고치기·삭제 |
| GET | `/api/admin/ai/persona-cases` | 실제 사례 집계 |
| POST | `/api/admin/ai/persona-draft` | 역할극 상대 초안 `{caseKey}`(등록 안 함) |
| GET·POST | `/api/admin/public-services` | 공공 서비스 정보 목록·추가 `{name*, target, content, applyHow, contact, region, keywords[]}` |
| PUT·DELETE | `/api/admin/public-services/{id}` | 고치기·삭제 |

### 17.8 재정 추계·시뮬레이션·특보 (11개)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/forecast` | 시간 은행 재정 추계(숫자는 코드) |
| POST | `/api/admin/forecast/summary?refresh=` | 이번 달 월간 요약(저장·재사용) |
| GET | `/api/admin/forecast/summaries` | 지난 월간 요약 목록 |
| POST | `/api/admin/forecast/simulate` | 정책 시뮬레이션 `{text(300자) 또는 adjustments[{type, ageBand, area, value}]}`. 오류 `TEXT_REQUIRED`, `TEXT_TOO_LONG` |
| GET | `/api/admin/weather/alerts` | 특보 목록 `{live, types[], levels[], knownAreas[], alerts[]}` |
| POST | `/api/admin/weather/alerts` | 특보 직접 넣기 `{type*, level*, areas[]*, startDate, endDate, memo}`. 오류 `INVALID_TYPE`, `INVALID_LEVEL`, `AREA_REQUIRED` |
| POST | `/api/admin/weather/alerts/sample` | 시연용 표본 특보 |
| POST | `/api/admin/weather/fetch` | 기상청에서 받아 오기(키 없으면 `{live: false, created: 0, message}`) |
| POST | `/api/admin/weather/alerts/{id}/refresh` | 리포트 문장 다시 만들기 |
| POST | `/api/admin/weather/alerts/{id}/send` | **전달**: 공지 1건 생성 + 그 지역에 대상이 있는 담당자에게 알림. 이미 전달했으면 409 `ALREADY_SENT` |
| DELETE | `/api/admin/weather/alerts/{id}` | 삭제(전달한 것은 409) |

- **특보 한 건** `{id, type, level, areas[], startDate, endDate, source, memo, facts{type, level, areas[{area, elders, elders80, activities, managers[{managerId, name, orgName, elders}]}], from, to, totalElders, totalElders80, totalActivities, managerCount}, report{summary, actions[], source, provider}, noticeId, sentAt, sentManagers, createdAt}`.

### 17.9 기관 클래스 (5개)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/public-classes?source=` | 목록 `{items[], searchAvailable, counts}` |
| POST | `/api/admin/public-classes/import` | 공공데이터 CSV 올리기(multipart `file`). 오류 `FILE_REQUIRED`, `CSV_ONLY` |
| POST | `/api/admin/public-classes/search` | 지역으로 AI 검색 `{region*, force}`. 오류 `REGION_REQUIRED` |
| POST | `/api/admin/public-classes/{id}/hide` | 숨기기 `{hidden}` |
| DELETE | `/api/admin/public-classes/{id}` | 삭제 |

운영자 API 수: 17.1(8) + 17.2(9) + 17.3(4) + 17.4(9) + 17.5(4) + 17.6(8) + 17.7(17) + 17.8(11) + 17.9(5) = **75**. 컨트롤러로 보면 AdminController 36 + AdminReportController 19 + OpsAiController 6 + ManagerApplyController 2 + PlannerController 3 + EduController 2 + PublicClassController 5 + InsightController 2.

---

## 18. 끝

### 18.1 API 수 집계
처리기(`@GetMapping` 등) 기준 **269개**. `POST /api/orgs/apply`는 multipart·JSON 처리기가 2개라 2로 셌다.

| 영역 | 수 |
|---|---|
| 인증·회원 (기관·담당자 신청 포함) | 21 |
| 공고 (관심 조건·관심 공고·쉬운 말·고치기 제안 포함) | 17 |
| 요청·진행 (QR·일지·평가·메시지·브리핑·판단 보조) | 22 |
| 단골·이웃 | 7 |
| 시간 통장·선물·시간 플랜·리포트 | 12 |
| 플래너·추천 | 7 |
| 정기 요청 | 7 |
| 묶음 추천 | 2 |
| 단체 봉사 | 11 |
| 가족 | 6 |
| 알림·문의·신고·공지·카테고리 건의·인증 항목 | 15 |
| 파일 | 2 |
| 교육(첫 활동 연습) | 7 |
| 기관 소식·기관 클래스·공공 서비스 연계 | 13 |
| AI(작성·판정·안내·음성) | 13 |
| **기관 담당자 `/api/org/**`** | **32** |
| 운영자 `/api/admin/**` | 75 |
| **합계** | **269** |

컨트롤러별: Auth 21 · ManagerApply 4 · Post 15 · Assist 5 · Request 20 · ActivityAi 5 · Neighbor 6 · Bank 11 · TimePlan 2 · Planner 13 · Recurring 7 · Bundle 2 · Group 11 · Common 10 · Edu 9 · News 9 · PublicClass 10 · PublicReferral 5 · Ai 8 · Stt 1 · Insight 5 · Org 18 · OrgBlock 3 · OrgSchedule 3 · OrgWeather 1 · OpsAi 9 · Admin 36 · AdminReport 20.

로그인 없이 부를 수 있는 것: 13개(`/api/auth/**` 8, `/api/orgs/apply` 2, `/api/ai/status` 1, `/api/notices` 2).

### 18.2 모의(흉내) 상태인 연동
| 연동 | 지금 상태 |
|---|---|
| 휴대폰 문자 인증 | `POST /api/auth/phone/send`는 실제 문자를 보내지 않고 `demoCode`를 응답에 넣어 준다. 데모 번호 허용 여부는 `GET /api/auth/phone/config`의 `demo`. Firebase 키가 있으면(`firebase: true`) 화면이 Firebase로 실제 문자를 보내고 서버는 토큰만 확인한다 |
| 음성 전화·문자 알림 | 전화·문자를 실제로 걸거나 보내지 않는다. 읽을 문장(`notifications.voice_text`)만 만들고 `GET /api/me/voice-preview`로 미리 듣는다 |
| 기상청 특보 | 키가 없으면 `live: false`. 운영자가 직접 넣거나(`MANUAL`) 표본(`SAMPLE`)을 쓴다 |
| AI(LLM) | 키가 없으면 모든 AI API가 규칙 모드(`source: "rule"`)로 같은 모양의 응답을 준다. `GET /api/ai/status`의 `mode` |
| 서버 음성 인식(STT) | 꺼져 있으면 503 `STT_UNAVAILABLE`(화면은 직접 입력으로) |
| 기관 클래스 AI 웹 검색 | 검색을 쓸 수 없으면 `searchAvailable: false`. 공공데이터 CSV와 기관 소식만 보인다 |
| 거리 | 실제 지도가 아니라 동 이름 5개의 가상 좌표로 계산한 값(`distanceKm`) |
| 파일 저장 | 서버 로컬 폴더(무료 배포에서는 재시작 때 사라질 수 있음 — 배포 문서 확인 필요) |

### 18.3 문서(`docs/design/contract.md`)와 코드가 달랐던 곳
1. 관심 공고(북마크) API 3개, `GET /api/bank`의 `entries[].balanceAfter·counterpart·counterpartRole`, `GET /api/news?sort=new`는 쓰는 도중 계약서 **7.20**(커밋 `9f78dc4`)에 더해졌다. 코드와 같다(다른 곳 없음).
2. 계약서 5.x 절의 본문은 처음 판 그대로이고 바뀐 내용은 7장에 덧붙이는 식이라, **5장만 보면 지금 코드와 다르다**(아래 3·4가 그 예).
3. 계약서 5.10의 `GET /api/org/home` 본문 설명은 `counts{elders, pendingFinal, logsToConfirm, active}`만 적혀 있다. 코드는 `monthDone, flaggedReports, transfersIn`까지 7개를 준다(`monthDone`만 7.17에 덧붙여 있음).
4. 계약서 7.11의 `POST /api/events/{id}/apply`·`/cancel`은 코드에 없다(7.17에서 삭제로 정리됨 — 앞 절을 그대로 믿으면 안 된다). `applyType`은 응답에서 늘 `"LINK"`다.
5. 글자 크기: `PUT /api/me`는 `"BIG"`/`"NORMAL"`(대문자)로 저장하는데 새 계정의 기본값은 `"normal"`(소문자)이다. 화면은 대소문자를 가리지 않고 봐야 한다.
6. 공지 고정(`pinned`)은 `POST`·`PUT /api/admin/notices` 본문으로는 못 바꾸고 `POST /api/admin/notices/{id}/pin`으로만 바꾼다.
7. `POST /api/events`·`/api/events/{id}/close`는 담당자 전용인데 경로가 `/api/org/**`가 아니라서 인터셉터가 아니라 서비스가 403을 낸다(USER가 부르면 "기관 담당자만 동네 소식을 올릴 수 있어요").
8. `db/schema.sql`은 표 41개(`post_bookmarks`는 `aeed99f`에서 더해짐), `db/README.md`는 "테이블 40개"로 적혀 있고, 엔티티는 42개다. **`public_referrals`가 schema.sql에 없다** — ERD 문서 6장 참고.

### 18.4 "(확인 필요)"로 남긴 것
- 서비스 안에서 만드는 응답의 세부 칸: `PostService.decorate`, `ActivityAiService`(브리핑·메시지 도우미·판단 보조·목표 코치·월별 결산), `PlannerService`, `TimePlanService`, `PersonalInsightService`, `BundleService.recommend`, `EduService`, `PublicClassService`(추천·검색·대상별 추천), `AnalyticsService`·`AdminReportService`·`FinanceForecastService`·`OpsSafetyService`, `ai/*.java`의 AI 응답(post-chat·filter·difficulty·log-tidy·public-services·assistant·post-check), `OrgAi`의 초안 나머지 칸.
- 오류 코드: `AuthService`(로그인 실패·본인인증 실패), `VolunteerGate`(봉사 자격), `GroupService`, `RecurringService`, `LinkPreviewService`.
- 토큰 유효 기간의 정확한 값(`AuthService`).
