# 기능명세서 적용 결과 — 기관 담당자 범위

- 범위: 기능 C-03, O-01, O-02, O-03, O-04, O-05, O-06, O-08, O-09, O-10, O-11, O-12, O-13 (기능 O-07 단체 봉사 공고는 다른 담당)
- 기준 글: `docs/requirements/기능명세서.txt` (요구사항명세서 41판의 ID와 다른 체계라 "기능 O-11"처럼 적는다)
- 가지: `func-org` · 날짜: 2026-11-12
- 경로 줄임: `B/` = `backend/src/main/java/com/timesavings/`, `F/` = `frontend/src/`

## 1. 기능 ID별 대조 표

| 기능 | 우선 | 적용 전 | 지금 | 근거(파일:줄) |
|---|---|---|---|---|
| C-03 기관 서비스 신청 | 상 | 충족(주소는 안 적어도 신청됨) | 충족 · 화면에서 주소 필수 확인 추가 | 신청 `B/web/AuthController.java:304`(재직 증빙 없으면 `PROOF_REQUIRED` :310), 운영자 승인 `B/web/AdminController.java:151`, 화면 `F/screens/auth.jsx` OrgApply(기관명·종류·주소·담당자·재직 증빙) |
| O-01 관리 대상 등록 | 상 | 부분(서비스 동의 여부가 화면에 안 보이고, 동의 기록 없는 예전 대상에 남길 길이 없음) | 고침 | 별칭·동·나이대·필요한 도움·주의사항 `B/web/OrgController.java:130`, 주소·연락처 막기 :131, 등록 때 동의 기록 :153, 예전 대상 동의 남기기 :193, 응답 `serviceConsent` :107, 화면 `F/screens/org.jsx:208` |
| O-02 관리 대상 목록·상세 | 상 | 부분(상세에 이번 달 활동이 없음) | 고침 | 상세 `F/screens/org.jsx:207`(이번 달 활동·누적), 단골 :204 부근, 받은 도움 기록·담당자 메모는 같은 화면, 서버 `B/web/OrgController.java` elderView·get |
| O-03 대리 봉사 요청(✨) | 상 | 충족 | 그대로 | 대상 골라 작성 `F/screens/write.jsx:113`·`:206`, 들은 내용 정리 `POST /api/ai/tidy-post` `B/web/NewsController.java:110`(키 없으면 규칙), 여러 건 `F/screens/opsai.jsx` |
| O-04 이슈 메모 | 상 | **다름**(공고의 관리 대상 이슈와 방문 주의사항이 확정 전 봉사자·누구에게나 응답으로 나감) | 고침 | 공개 조건 `B/service/Views.java:178`(확정·진행·일지·완료), 공고 카드 :221~227(`elderIssueLocked`), 요청 응답 :331(`cautionLocked`), 화면 `F/screens/board.jsx:189`, `F/screens/request.jsx:575` |
| O-05 대신 수락·거절 | 상 | 충족 | 그대로 | 담당자 버튼 `B/service/Views.java:267`(VOL_ACCEPTED·제안에 accept/decline), 전화 확인 체크 `F/screens/request.jsx:468` |
| O-06 도움 찾기 | 중 | 충족 | 그대로 | `F/screens/board.jsx:30`(대상 대신 요청, 지역·날짜·시간대·카테고리 필터 :99 부근), 요청 `B/service/RequestService.java:98` |
| O-08 일지 확인 | 상 | 충족 | 그대로 | 대신 확인 `B/service/Views.java:297`, 평가 :303, QR·체크 기록 `F/screens/request.jsx:513` |
| O-09 행사 올리기 | 중 | 부분(정원 칸이 화면·응답에서 빠져 있었음) | 고침 | 정원 받기·검사 `B/service/EventService.java:108`·`:121`, 응답 :50, 소식 목록 `B/web/NewsController.java` news(), 화면 `F/screens/news.jsx:170`(입력)·`:94`(상세)·`:24`(목록) |
| O-10 관리 대상 리포트(✨) | 상 | 부분(일지만 분석, 신청 내역(거절·만료)은 안 봄) | 고침 | 신청 집계 `B/service/ElderChangeService.java:81`·`:116`, 점수 :91, 문장 :104, 응답 `apply`·`applyText`·`checkFirst` :226 부근, 화면 `F/screens/org.jsx:58`·`:358` |
| O-11 돌봄 편성표 | 상 | 없음 | 새로 만듦 | `B/service/CareScheduleService.java:97`, `B/web/OrgScheduleController.java`, 화면 `F/screens/schedule.jsx:103`, 탭 `F/ui/Frame.jsx:10`, 경로 `F/App.jsx`(`/o/schedule`) |
| O-12 편성 대안 추천(✨) | 상 | 없음 | 새로 만듦 | 흐름 계산 `B/service/CareScheduleService.java:79`, 후보 :162, 추천·대안 :209, 글 `B/ai/ScheduleAi.java`, 화면 `F/screens/schedule.jsx:18` |
| O-13 공고 한꺼번에 올리기 | 중 | 없음 | 새로 만듦 | `B/service/CareScheduleService.java:271`, 화면 `F/screens/schedule.jsx:57` |

## 2. 새·바뀐 API

### 새 API (모두 담당자 계정만, `/api/org/**`)
- `GET /api/org/schedule?week=YYYY-MM-DD&elderId=` — 주간 편성표. `week`는 그 주의 아무 날짜(비우면 이번 주, 주는 월~일), `elderId`를 주면 그 대상만.
  - 응답: `weekStart, weekEnd, prevWeek, nextWeek, today, thisWeek, days[{date,dow,label,today,past}], elders[{elderId,alias,displayName,dong,needs,cells[7]}], filter[{elderId,displayName}], counts{fixed,pending,open,gaps,empty}`
  - 칸: `{date, items[], empty, past, selectable, gap}`. 항목 `kind`=`REQUEST`(`state`: `CONFIRMED` 확정 / `PROGRESS` 진행 중·일지 / `PENDING` 응답 대기 / `DONE` 완료, `requestId, startHour, hours, category, volunteerName`) 또는 `POST`(`state`=`OPEN` 열려 있는 공고, `postId, startHour, endHour, category, title, pendingCount`).
  - 거절·취소·만료·노쇼 요청은 일정으로 올리지 않는다. 열린 공고에 온 응답 대기 건은 공고 항목의 `pendingCount`로만 센다.
  - `gap`(빈 일정): 오늘 이후 빈 칸 가운데, 같은 요일의 지난 4번 중 2번 이상 방문(확정~완료)이 있었던 칸. `{weeks, category, startHour, hours, reason}`.
- `POST /api/org/schedule/suggest` `{elderId, date, startHour?, hours?, category?}` — 빈 칸 하나의 추천. 비운 값은 지난 4주 흐름 → 대상의 "필요한 도움" 첫 항목·10시·1시간 순으로 채운다.
  - 응답: `slot{date,dow,startHour,hours,category,label}, pattern, candidates[], alternatives[{date,dow,label,count,candidates[]}], postDraft{elderId,date,startHour,endHour,category}, message, source(ai|rule), provider?, notice, sent:false`
  - 후보(최대 5명, 점수순): `{volunteerId,name,area,ratingAvg,ratingCount,regular,doneCount,sameDong,timeFit,offerPostId,offerStartHour,offerEndHour,action(REQUEST|REGULAR),facts[],reason,score}`
  - 후보 찾기(코드): 이 대상의 단골 + 그 날짜·시간을 덮는 열린 봉사 가능 공고(도움 종류 일치)의 주인. 빼는 것: 받지 않기 목록, 그 시간에 다른 일정이 있는 봉사자, 정지·미인증 계정. 점수 = 단골 50 + 이 대상 완료 횟수×3(최대 15) + 같은 동 20 + 그 시간 공고 30 + 평가×2.
  - 후보가 없으면 앞뒤 6일 안(오늘 이후)에서 후보가 있는 날을 최대 3일 `alternatives`로, 공고 올리기는 `postDraft`로 준다.
  - **아무것도 보내거나 만들지 않는다.** 화면에서 담당자가 눌러 요청 화면(`/req/new?post=&elder=` 또는 `/regular?elder=`)이나 일괄 등록으로 간다.
- `POST /api/org/schedule/bulk-posts` `{items:[{elderId,date,startHour,endHour?,category,detail?}]}` (최대 20칸) — 칸마다 대리 공고 1건.
  - 응답: `created[{index,elderId,displayName,date,startHour,endHour,category,postId,title}], failed[{index,…,code,reason}], createdCount, failedCount`
  - 칸마다 공고 등록과 같은 검사(`PostService.create`: 날짜·시간·카테고리·금지 요청 판정)를 거치고, 같은 대상·같은 시간에 일정이나 열린 공고가 있으면 `SLOT_TAKEN`. 한 칸이 실패해도 다른 칸은 올라간다. 오류 코드: `ITEMS_REQUIRED`, `TOO_MANY`.

### 바뀐 API
- `GET /api/org/change-report`: 항목마다 `apply{total,matched,waiting,rejected,expired,cancelled,noshow,unmatchedPosts,failed}`, `applyText`, `checkFirst` 추가. `score`·`level`·`reasons`·`rank`에 신청 내역 점수가 더해진다(맞춰지지 않은 신청 2건 이상 +15, 1건 +5, 노쇼 +10, 봉사자를 못 구하고 마감된 공고 +10). `notice` 문구가 바뀜.
- `POST /api/events`: `capacity`(정수, 비우면 제한 없음, 1~9999, 오류 `INVALID_CAPACITY`) 받음. `GET /api/events`, `/api/events/{id}`, `/api/org/events`, `/api/news`(ORG 항목) 응답에 `capacity`(없으면 null) 추가. 앱 안 신청·남은 자리 계산은 없다(신청은 링크).
- 공고 카드(`GET /api/posts`, `/api/posts/{id}` 등 PostCard): `elderIssueLocked` 추가. 담당자와 **일정이 확정된(확정·진행·일지·완료) 그 공고의 봉사자**가 아니면 `elderIssue`·`elderCaution`이 null.
- 요청 응답(RequestView): `elder.cautionLocked` 추가. 담당자와 확정된 봉사자가 아니면 `elder.caution`이 null이고 `baseCaution`이 없다.
- `PUT /api/org/elders/{id}`: `consent:true`를 보내면 동의 기록이 없는 대상에 동의 시각·담당자를 남긴다(이미 있는 기록은 바꾸지 않음). 대상 응답에 `serviceConsent`(참/거짓) 추가.

## 3. DB 변경
- 없음. 새 표·새 칸을 만들지 않았다. 행사 정원은 이미 있던 `org_events.capacity`(0 = 제한 없음)를 다시 쓴다.

## 4. 못 한 것 · 남은 것 · 모의 상태
- **AI(✨) 실제 호출은 확인하지 않았다.** 키 없이 규칙 모드로만 돌렸다. 기능 O-12의 추천 이유·안내 문장과 O-10 일지 정리는 키가 있으면 AI가 다듬고, 숫자가 달라지면 코드 문장으로 되돌리게 만들었지만 실제 AI 응답으로는 못 봤다.
- 기능 O-12 "시간 맞는 봉사자"는 **그 날짜·시간에 봉사 가능 공고를 올려 둔 봉사자**와 **단골**만 본다. 공고 없이 평소 가능한 요일을 적어 두는 자료가 없어, 공고도 단골도 아닌 봉사자는 후보에 안 나온다. 단골은 공고가 없어도 후보로 나오고 "그 시간 공고는 없지만 단골이라 직접 요청할 수 있어요"라고 적힌다(실제로 되는지는 봉사자 수락에 달림).
- 기능 O-12 추천 시트의 "요청하러 가기"는 기존 요청 화면으로 넘어갈 뿐, 고른 날짜·시간을 미리 채워 주지 않는다(단골 요청 화면에서 다시 고른다).
- 기능 O-11 편성표는 주간만 있다(월간 없음). 한 칸에 일정이 여러 개면 세로로 쌓인다.
- 기능 O-13은 한 번에 20칸까지. 정기 요청(기능 U-09)과의 연결은 지시대로 하지 않았다.
- 기능 O-09 정원은 안내용 숫자다. 신청자 수를 세지 않아 "남은 자리"는 없다.
- 기능 O-01 "나이"는 정확한 나이가 아니라 나이대(60대~90대 이상)로 받는다(예전 그대로).
- 기능 C-03 주소 필수는 화면에서만 확인한다. 서버는 기관명·담당자·재직 증빙만 필수다(회원 담당 파일과 기존 테스트를 건드리지 않으려고).
- 기능 O-04로 예전에 올린 대리 공고의 공개 칸(`note`)에 이미 들어가 있는 "방문 시 주의: …" 글은 그대로 남는다. 새로 올리는 대리 공고(말로 만들기·AI 대화·통화 메모)는 주의사항을 관리 대상 이슈로만 보낸다.
- `docs/design/contract.md`, `db/schema.sql`은 지시대로 고치지 않았다. 위 2장을 합칠 때 계약서에 옮겨야 한다.

## 5. 범위 밖에서 고친 파일 (모두 작게)
- `B/service/Views.java` — 기능 O-04 공개 조건(메서드 2개 추가, 공고 카드·요청 응답의 3곳).
- `B/service/EventService.java`, `B/web/NewsController.java` — 기능 O-09 정원.
- `F/App.jsx`(경로 1줄·import 1줄), `F/ui/Frame.jsx`(담당자 탭에 편성표 추가, "변화 리포트" 탭 이름을 "리포트"로 — 6칸이라 줄임).
- `F/screens/home.jsx` — 기관 소식 카드의 "남은 자리 n"을 "정원 n명"으로(신청자 수를 세지 않으므로).
- `F/screens/board.jsx`, `F/screens/request.jsx` — 특이사항 잠금 안내 한 줄씩.
- `F/screens/write.jsx`, `F/screens/voice.jsx`, `F/screens/opsai.jsx` — 대리 공고의 주의사항을 공개 칸(`note`) 대신 `elderIssue`로 보냄.
- `F/screens/auth.jsx` — 기관 신청 주소 확인 1줄.

## 6. 판단해서 정한 것
- 기능 O-04 "특이사항"의 범위: 공고에 적는 관리 대상 이슈뿐 아니라 **대상의 방문 주의사항도** 확정된 봉사자에게만 준다(기능명세서의 예 "귀가 어두움, 반려견"이 대상 주의사항과 같은 내용이고, 대리 공고 화면이 이슈 칸을 대상 주의사항으로 미리 채우기 때문). 41판의 "봉사자에게 별칭·동·주의사항까지"와 다르지만 기능명세서를 따랐다. 확정 전 봉사자는 가린 이름·동·도움 내용·시간만 본다.
- "확정된"의 뜻: 요청 상태가 확정·진행 중·종료 기록·일지 제출·완료. 요청·수락 대기와 거절·취소·만료는 아니다.
- 빈 일정 기준: 같은 요일의 지난 4번 중 2번 이상 방문.
- 리포트 순위에서 담당자가 스스로 거둔 취소는 "맞춰지지 않은 신청"으로 세지 않는다.
- 담당자 탭은 6칸(홈·도움 대상·편성표·요청 관리·리포트·마이)으로 했다.

## 7. 확인 방법
- 서버 테스트: `cd backend && sh ./mvnw -q test` — `FuncOrgTest` 10건(O-01, O-04, O-09, O-10 2건, O-11, O-12 3건, O-13) 포함 전체 222건.
- 화면(폭 390, `org1`/`1234`): 아래 탭 "편성표" → 다음 주 → 빈 칸(+) 여러 개 누르기 → "공고 한꺼번에 올리기" → 결과(올림/실패 이유) → 표에 "열린 공고"로 보임. 빈 일정 카드의 "봉사자 추천 보기" → 후보·이유·"요청하러 가기". "리포트" 탭 → 카드의 "신청 내역" 줄. 마이 → 기관 소식 운영 → 소식 올리기 → 정원.
- 특이사항 잠금: `yujin`으로 독거노인 대리 공고 상세를 열면 "특이사항은 일정이 확정된 봉사자에게만 보여요". 서버 응답은 `GET /api/posts/{id}`의 `elderIssue`·`elderCaution`이 null, `elderIssueLocked`가 true.
