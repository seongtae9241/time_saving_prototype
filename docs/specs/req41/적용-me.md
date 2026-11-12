# 요구사항 41판 적용 — 계정·마이페이지·통장·알림 설정·연습·플래너·홈 (가지 `req41-me`)

작업 폴더 `/home/user/ts-wt/me`. 경로 줄임: `BE` = `backend/src/main/java/com/timesavings`, `FE` = `frontend/src`.
`docs/design/contract.md`와 `backend/src/main/resources/db/schema.sql`은 고치지 않았다. 아래 ②·③을 총괄이 합친다.

## 먼저 볼 것 (합칠 때 꼭 확인)

1. **`/api/me`에서 `eduDone`이 빠졌다(B-54).** 다른 담당 파일인 `FE/screens/board.jsx:126`은 `!me.eduDone`이면 봉사 제안을 막는다. 그 줄이 남은 채 합치면 **모든 사용자의 봉사 제안이 막힌다.** `FE/screens/write.jsx:40, 137`의 "교육을 마친 뒤" 안내도 모든 사용자에게 뜬다. 공고 담당 가지에서 이 세 줄이 없어졌는지 확인이 필요하다(나는 그 파일들을 고치지 않았다).
2. 아래 탭을 바꾸느라 `FE/ui/Frame.jsx`의 사용자 탭 한 줄을 고쳤다. 바로 아랫줄(담당자 탭 "최근 기록" → "변화 리포트")을 담당자 쪽에서 고치면 합칠 때 그 두 줄이 부딪힐 수 있다.
3. 봉사 인증 항목의 "안전 교육 이수" 줄(`BE/web/PlannerController.java` `VERIFY_DEFAULT`)은 운영자 화면 범위(프로토타입 21번)라 그대로 뒀다.

## ① 고친 항목

| ID | 무엇을 어떻게 |
|---|---|
| A-01 | 가입 요청에 동의 4항목(`agreeTerms`·`agreePrivacy`·`agreeLocation`·`agreeInterest`)을 싣고 서버가 저장한다. 필수 2개가 없으면 400 `TERMS_REQUIRED`. 관심 공고 알림에 동의하지 않으면 그 알림이 꺼진 채 시작한다. 화면은 [필수] 2개·[선택] 2개, "보기"를 누르면 내용 요약 시트, 안내문·환영 문구를 프로토타입대로. 위치 정보 동의는 마이 › 설정 › 위치 정보에서 바꾼다. |
| A-04 | 일반 사용자 쪽 확인만 했다. 진행 중인 요청·활동 건수, 남은 시간·보류 시간, "탈퇴하면 사라진다·가족에게 먼저 선물" 안내 뒤 비밀번호 확인으로 탈퇴 — 명세대로라 고치지 않았다. |
| A-06 · B-47 | 통장 번호(`accountNo`, `TS-000123`)를 서버가 `/api/bank`·`/api/me`·확인서에 내려 준다. 화면은 그 값을 쓴다. 월별 결산을 6개월 모두 보여 준다. 이용 규칙 문구를 예정 시간 기준으로 바꿨다. 마이의 통장 요약에 통장 번호를 붙였다. |
| B-26 · B-30 | 계산은 그대로(예정 시간 × 가중치). 이용 규칙·활동 결산·확인서 문구만 "예정 시간 기준"으로. |
| B-46 | 내 활동을 마이 안으로 옮기고(아래 탭 표시는 마이) 맨 위 [도움 받은 활동] [봉사한 활동] 토글, 그 아래 [응답 필요] [진행 중] [내 공고] [지난 활동]. 월별 봉사·도움 받은 횟수 요약(최근 6개월) 표를 붙였다. |
| B-56 | 확인서에 기간 선택(전체·최근 1/3/6개월·올해·직접 고르기), 독거노인 대상 활동 횟수, 자기소개용 요약 문장(AI, 키 없으면 규칙 문장, 횟수를 바꿔 쓴 AI 문장은 버림), 요약 복사, 인쇄(PDF로 저장). 기간은 적립 확정 시각(`doneAt`) 기준. `eduDone` 제거. |
| B-42 | 받은 단골 배지를 "누구를 몇 회"로 모아 본다(`GET /api/me/regular-badges`). 마이 › 단골 관리 시트의 "나를 단골로 등록한 사람". |
| B-65 | 활동이 없어도 프로필·공고·평가 화면에서 직접 등록. 등록한 쪽·등록된 쪽 모두 해지(자동 3회 단골도 해지됨). 마이 › 단골 관리에서 내 단골 목록·해지·"+ 단골 직접 등록". 등록된 쪽이 해지하면 상대는 다시 등록할 수 없다(409 `REMOVED_BY_VOLUNTEER`). 내가 해지한 사람은 다시 등록할 수 있다. |
| B-54 · B-01 | 수료 API(`POST /api/edu/complete`)·수료증 화면·수료 배지·매칭 가점(+1, "안전 교육 수료" 문구)·"교육이 필요해요" 안내를 없앴다. `/api/me`·프로필·소개 카드·공고 카드·확인서 응답에서 `eduDone` 제거. 연습은 ① 상황 하나 연습(프로토타입 시트: 어르신 말 → 내 답 → 초록 칭찬 또는 "이렇게 바꿔 말해 보세요" + 이유 + 바꿔 말할 문장) ② AI 어르신과 길게 대화(차례마다 위반 알림·바꿔 말하기, 끝나면 소통·공감 피드백). AI가 어르신의 다음 말을 만들고(키 없으면 대본·상황 목록), 위반 판정은 코드 규칙이 먼저라 AI가 뒤집지 못한다. 상황 목록은 서버 설정(`app_settings`의 `edu.situations`)으로 관리. 규칙 카드·퀴즈는 "읽을거리(선택)"로 남겼고 통과 조건이 없다. |
| B-58 | 알림 종류 5가지를 계정에 저장하고(`GET/PUT /api/me/noti-settings`) 꺼 둔 종류는 알림을 만들지 않는다. 담당자도 같은 API·같은 화면(`/my/noti` 경로를 담당자에게 열었다). 리마인드를 시작 1시간 전으로 바꿨다. |
| B-43 | AI 음성 전화 안내·문자 병행 켜고 끄기를 서버에 저장. 문자는 음성과 같이 모의(로그 `[SMS-MOCK]`, 내용은 남기지 않음). |
| B-55 | 큰 글씨를 바꾸면 서버(`users.text_size`, `BIG`/`NORMAL`)에 저장하고, 접속할 때마다 서버 값을 화면에 적용한다. 담당자 마이도 같은 줄(`TextSizeRow`)을 쓴다. |
| B-32 | 선물 화면에서 연결된 가족이 없으면 "연결된 가족이 없어요" + [가족 연결 신청하러 가기]만 보인다. |
| B-21 | 관심 조건 화면에 날짜 시작·끝 칸. `dateFrom`·`dateTo`로 보낸다(서버가 아직 안 받으면 무시됨). |
| B-60 | 홈(봉사하기)에 "추천 봉사" 구역 3건. 고르고 "계획 만들기"를 누르면 플래너로 간다. AI를 부르지 않는 `GET /api/me/recommends`. |
| B-61 | 계획 만들기: 고른 공고마다 서버가 빈 시간 후보(공고 범위 안, 내 일정·다른 계획과 안 겹침, 30분 단위)를 만들고 AI가 그 가운데 시작 시각을 고른다. 후보에 없는 값이면 서버가 규칙으로 정한다. 빈 자리가 없는 공고는 넣지 않고 `skipped`로 알린다. 날짜는 공고 날짜 그대로다(공고가 하루짜리라 날짜는 고를 여지가 없다). |
| B-62 | 플래너 맨 앞에 생애 플랜, 그 아래 "시간 플래너 · n월". "플랜 설명 AI — 숫자는 시스템이 계산하고…" 한 줄. |

추천 봉사 규칙이 하나 바뀌었다: 전에는 공고 시간 범위가 내 일정과 조금이라도 겹치면 추천에서 뺐고, 지금은 범위 안에 예정 시간만큼 들어갈 빈 자리가 있으면 추천한다.

## ② 새 API · 바뀐 API

새로 생긴 것
- `GET /api/me/noti-settings` → `{req, msg, result, remind, interest, voiceAlert, smsAlert}` (모든 역할)
- `PUT /api/me/noti-settings` — 보낸 칸만 바꾼다. 응답은 위와 같다.
- `GET /api/my/activity-summary?months=6` → `{months:[{month, helpCount, helpHours, receivedCount, receivedHours}], totalHelpCount, totalReceivedCount}` (완료된 활동만)
- `GET /api/me/regular-badges` → `[{key, kind: USER|ELDER, userId|elderId, name, label, count, auto, manual}]`
- `DELETE /api/me/regular-badges/{userId}` — 등록된 쪽(봉사자)의 해지. 응답은 남은 배지 목록. 내 단골이 아니면 404.
- `GET /api/edu/situations` → `{items:[{key, title, line, options[]}]}` (어느 답이 맞는지는 뺌)
- `GET /api/admin/edu/situations` (운영자) → `{items:[{key, title, line, bad, good, reason, flag}]}`
- `PUT /api/admin/edu/situations` (운영자) — `{items:[{title, line, flag?, bad?, good?, reason?, key?}]}` 1~8개. `flag`는 연락처·금전·선물·신체 돌봄·개인정보 중 하나.
- `POST /api/edu/practice` `{key, text}` → `{quality, flag, ok, praise, feedback:{violation, flag, message, reason, better}|null, reply, source}`
- `GET /api/me/recommends` → `{recommends:[공고 카드 + why, score], planCount, basis}`

바뀐 것
- `POST /api/auth/signup`: 본문에 `agreeTerms`·`agreePrivacy`(필수)·`agreeLocation`·`agreeInterest` 추가. 필수가 없으면 400 `TERMS_REQUIRED`.
- `GET /api/me`(로그인·가입 응답의 `user` 포함): `eduDone`, `verify.eduDone` 삭제. `accountNo`, `smsAlert`, `notiSettings`, `locConsent`, `interestConsent`, `termsAgreedAt` 추가.
- `PUT /api/me`: `smsAlert`, `locConsent`를 받는다. `textSize`는 `BIG` 아니면 `NORMAL`로 저장.
- `GET /api/users/{id}/profile`, 소개 카드(`volunteer`), 공고 카드의 `owner`: `eduDone` 삭제.
- `GET /api/bank`: `accountNo` 추가.
- `GET /api/certificate?from=&to=`: 기간 칸 추가(없으면 전체). 응답에 `accountNo`, `from`, `to`, `elderCount`, `summary`, `summarySource`, 항목마다 `elder` 추가. `eduDone` 삭제. `byCategory`는 횟수 많은 순.
- `GET /api/regulars/{volunteerId}/status`: `removed`, `removedByThem` 추가. `canMark`는 활동 횟수와 상관없이 "아직 단골이 아니고 상대가 해지하지 않았으면" true. `auto`는 해지되면 false.
- `POST /api/regulars/{volunteerId}`: 활동이 없어도 등록(409 `NO_DONE_ACTIVITY` 없어짐). 상대가 해지했으면 409 `REMOVED_BY_VOLUNTEER`.
- `DELETE /api/regulars/{volunteerId}`: 줄을 지우지 않고 해지 기록을 남긴다. 자동(3회) 단골도 해지된다.
- `GET /api/regulars`: 해지한 사람은 빠진다. `regularBadges`(글자 목록)는 그대로지만 해지된 사이는 빠진다.
- `GET /api/edu`: `completed`, `completedAt` 삭제. `optional: true`, `situations:[{key, title, line, options[]}]` 추가.
- `POST /api/edu/quiz`: `passed` 삭제(점수만).
- `POST /api/edu/complete`: **삭제**.
- `POST /api/ai/roleplay`: 응답에 `feedback:{violation, flag, message, better}|null`, `options[]`(다음 차례에 고를 답), `total` 추가. `next`는 AI가 만든 어르신의 말(키 없으면 대본 → 상황 목록 → 맺는 말). 대본이 없는 가상 상대는 3번 주고받는다.
- `POST /api/edu/roleplay/{id}/finish`: `pass` 삭제, `note` 추가, `fixes[]`에 `violation` 추가. 기록은 `{practiced, comm, emp, safe, at}`.
- `POST /api/me/planner/picks`: 응답(플래너)에 `made:[{postId, title, date, startHour, endHour, hours, source}]`, `skipped:[{postId, title, reason}]`, `madeSource` 추가.
- `GET /api/me/planner`: 계획 줄(`kind: plan`)의 `startHour`가 정해진 시각이 되고 `endHour`, `postStartHour`, `postEndHour`, `planSource` 추가.
- 리마인드 알림(`REMIND`): 전날이 아니라 시작 1시간 전부터 시작 전 사이에 한 번. 제목 "곧 14:00 봉사 일정이 있어요 (1시간 전 알림)".

알림 종류와 설정 칸: `req` = NEW_REQUEST·NEW_PROPOSAL·NEW_REGULAR / `msg` = MESSAGE / `result` = VOL_ACCEPTED·REJECTED·DECLINED·AUTO_REJECTED·MANAGER_REJECTED / `remind` = REMIND / `interest` = INTEREST. 그 밖(일정 확정, 지각, 노쇼, 완료 확인, 적립, 신고 결과 등)은 끌 수 없다.

## ③ DB 변경 (모두 null 허용, `ddl-auto: update`로 생김)

- `users`: `terms_agreed_at`(시각), `privacy_agreed_at`(시각), `loc_consent`(참/거짓), `interest_consent`(참/거짓), `noti_req`, `noti_msg`, `noti_result`, `noti_remind`, `noti_interest`(참/거짓, null = 켜짐), `sms_alert`(참/거짓)
- `regular_marks`: `removed`(참/거짓), `removed_by`(해지한 사용자 번호)
- `planner_picks`: `start_hour`(실수), `hours`(실수), `plan_source`(글자 10, `ai`|`rule`)
- `app_settings`: 새 줄 `edu.situations`(표 변경 없음, 값은 2000자 안)
- 더 쓰지 않는 칸(지우지 않음): `users.edu_done`, `edu_progress.completed_at`

## ④ 못 한 것 · 남은 것

- 실제 AI는 한 번도 부르지 않았다. 역할극·확인서 요약·계획 만들기의 AI 쪽은 가짜 응답(mock)으로 응답 읽기만 확인했다. "AI가 고른 시각이 후보에 없으면 규칙으로" 가는 길은 코드에 있지만 AI 응답을 넣어 끝까지 돌려 본 테스트는 없다.
- 다른 담당 파일에 남은 교육 흔적: `board.jsx:126`, `write.jsx:40, 137`(위 "먼저 볼 것"), `request.jsx:55, 517`의 "✓ 교육 수료" 배지(서버가 값을 안 줘서 안 보이지만 줄은 남음), 운영자 대시보드의 "첫 활동 교육 수료 수"와 회원 상세의 "교육 수료/미수료"(`AdminController.java:116, 125, 364`, `admin.jsx:172, 334`), 봉사 인증 항목의 "안전 교육 이수".
- 상황 목록을 고치는 운영자 화면은 만들지 않았다(API만 있음). 가상 상대 관리 화면(`/a/persona`)은 그대로다.
- 담당자 마이에 알림 설정으로 가는 메뉴는 넣지 않았다(`org.jsx`는 다른 담당). 경로 `/my/noti`는 담당자에게 열려 있고, 담당자에게는 관심 공고 줄과 음성·문자 구역이 안 보인다.
- 관심 조건의 날짜는 화면만 있다. 서버 `interest-alerts`가 `dateFrom`·`dateTo`를 받기 전에는 저장되지 않는다.
- 위치 정보는 동의 값 저장과 바꾸는 시트까지만 했다. 실제 위치 권한 요청·가까운 순(B-59)은 공고 담당 범위다.
- 음성 전화·문자는 실제로 보내지 않는다(모의 로그). 확인서 PDF는 브라우저 인쇄다(총괄 결정).
- 독거노인(별칭)의 단골은 자동(3회)만이고, 담당자가 직접 등록하거나 해지하는 길은 없다.
- 예전 가입자는 동의 칸이 비어 있다(위치 정보는 "동의 안 함"으로 보이고 마이에서 켤 수 있다. 알림은 모두 켜짐으로 본다).
- 시드 자료(`DataSeeder`)가 예전 교육 진행 기록(`completedAt`, `passed`)을 여전히 넣는다. 화면·응답에는 쓰이지 않는다.

## ⑤ 확인 방법

- 서버 테스트: `cd backend && sh ./mvnw -q test` — 154건 통과(전 142건 + 새 12건). 새 테스트: `Req41MeTest`(7건), `ai/Req41AiMockTest`(4건), `MeetingChangesTest`의 단골 양쪽 해지 1건. 고친 테스트: `MeetingChangesTest`(활동 없이 등록), `Proto1111Test`(계획 만들기 시각·홈 추천), `LlmAssistFlowTest:114`(추천 이유에서 "안전 교육 수료" 빠짐).
- 프런트 빌드: `cd frontend && npm run build` 성공.
- 화면: 키 없는 서버(별도 포트·별도 작업 폴더, 시드 계정 `yujin`)와 크롬으로 홈(봉사하기)·플래너·마이(단골 관리·위치 정보·큰 글씨)·내 활동·시간 통장·선물·첫 활동 연습(상황 연습의 틀린 답·맞는 답, 어르신 대화)·알림 설정·확인서(기간 바꾸기)·신원 인증·관심 조건·탈퇴·가입 첫 단계를 열고 눌러 봤다. 콘솔 오류 없음. 가입 2~3단계(약관 화면)와 담당자 계정의 알림 설정, 평가 화면의 단골 버튼은 화면으로 눌러 보지 못했다(서버 테스트와 빌드만).
- 손으로 볼 곳: 가입 3단계에서 필수 2개만 체크하고 가입 → 마이 › 설정 › 위치 정보가 "동의 안 함". 알림 설정에서 "새 메시지"를 끄고 상대가 메시지를 보내면 알림이 안 온다. 홈(봉사하기) 추천 봉사를 골라 계획 만들기 → 플래너 달력에 정해진 시각으로 들어간다.

## 프로토타입 대조 (2026-11-12 명세 41판)

맞춘 것
- 1번 아래 탭: 홈 · 공고 · 플래너 · 시간 통장 · 마이. 내 활동은 마이 안(탭 표시 마이).
- 2번 내 활동: 토글 칩 두 개와 네 칸 칩, 문구 그대로.
- 3번 플래너: 생애 플랜 → "시간 플래너 · n월" → 캘린더/추천 봉사. "플랜 설명 AI" 한 줄.
- 4번 홈(봉사하기): "추천 봉사"(오른쪽 "플래너 ›"), 설명 한 줄, 3건. "동네 기관 소식 · 전체 ›"가 봉사하기에서도 보인다. 빠른 메뉴 "첫 활동 연습".
- 9번 단골 관리 시트: 설명문, "내가 등록한 단골"(자동 · n회 / 직접 등록, [해지]), "나를 단골로 등록한 사람"(n회 만남, [해지]), [+ 단골 직접 등록] → "함께한 분을 즐겨찾기처럼 단골로 등록해요".
- 10번 첫 활동 연습: 제목·설명문·상황 4개 이름·어르신 말·고를 답·칭찬 문구·"이렇게 바꿔 말해 보세요" + 이유 + 문장, [다른 상황] [연습 마치기]. 말풍선 색(`.rp-msg` 등)은 프로토타입 값.
- 12번 마이: "설정" 구역(위치 정보 시트, 큰 글씨 모드 켜짐/꺼짐), "봉사 신원 인증 — 신분증 · 범죄경력 회보서(선택)". 큰 글씨는 화면 전체가 커진다(기존 `html.big` 확대).
- 13번 가입: 약관 4줄 문구, 안내문, 필수 2개만으로 가입, 환영 문구.
- 14번 봉사 신원 인증: 문구, 안전 교육 줄 없음.
- 15번 시간 선물: "연결된 가족이 없어요" + [가족 연결 신청하러 가기].
- 16번: 홈 통장 카드 크기(`.pbh`), 시드 공지 "11월 첫 활동 연습 안내".

프로토타입과 다르게 한 것
- 10번: 끝내도 "연습 수료 배지"가 없다(명세 B-54, 총괄 지시). 연습은 시트가 아니라 `/edu` 화면에 상황 목록이 있고, 상황을 누르면 시트가 뜬다. 고를 답 말고 직접 적거나 말로 답할 수 있다. 틀린 답 뒤에 [다시 답하기]가 하나 더 있다. 그 아래에 기존 "AI 어르신과 길게 대화해 보기"와 읽을거리(규칙 카드·퀴즈)를 남겼다.
- 4번: 프로토타입은 추천을 누르면 바로 플래너로 간다. 명세 B-61("골라 계획 만들기를 누르면")에 맞춰 누르면 선택되고 [선택한 n개로 계획 만들기]를 눌러야 플래너로 간다.
- 9번: "+ 단골 직접 등록"의 후보는 나를 도와준 적 있는 분만 나온다. 한 번도 안 만난 분은 공고·프로필의 "☆ 단골로 등록" 버튼으로 등록한다. "나를 단골로 등록한 사람" 가운데 기관 대상(별칭)은 [해지]가 없다.
- 2번: 토글이 헤더의 도움 받기/봉사하기와 같이 움직인다(기존 방식 유지). 월별 횟수 요약 표는 명세 B-46에 따라 더한 것이다.
- 3번: 달력 아래 "추천 봉사" 탭이 남아 있다(홈에서 고르는 것과 같은 기능).

## 내 범위 밖에서 고친 파일

- `FE/ui/Frame.jsx` — 사용자 아래 탭 한 줄(내 활동 → 플래너)
- `FE/screens/request.jsx` — 평가 화면에 단골 등록 버튼 한 줄
- `FE/App.jsx` — `/my/noti` 권한, `/edu/rp`·`/edu/cert`를 `/edu`로 돌림, import 한 줄
- `FE/styles/app.css` — 프로토타입 끝 스타일(통장 카드 크기, 연습 말풍선) 덧붙임
- `BE/service/Views.java` — 단골 집계 부분(해지 반영, `regularBadgeList`), 소개 카드·공고 카드의 `eduDone` 두 곳
- `BE/service/MatchReasonService.java` — 교육 수료 가점 두 줄 삭제
- `BE/service/RequestService.java` — `remind` 한 군데(1시간 전, `remindAt` 추가)
- `BE/web/PlannerController.java` — 홈 추천 API, 계획 만들기 응답
- `BE/service/DataSeeder.java` — 공지 한 줄
- `BE/domain/PlannerPick.java`, 새 파일 `BE/ai/RoleplayAi.java`, `BE/ai/CertAi.java`
- 테스트: `LlmAssistFlowTest`, `MeetingChangesTest`, `Proto1111Test`
