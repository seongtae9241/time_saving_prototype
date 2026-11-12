# 기능명세서 적용 결과 — 공고·요청 (기능 U-02~U-09, U-12, U-13)

- 기준: `docs/requirements/기능명세서.txt` (73개 기능). 여기의 ID는 **기능명세서 ID**다(요구사항 명세서 41판의 B-06 같은 ID와 다른 체계).
- 가지: `func-post`. 계약서(`docs/design/contract.md`)와 `db/schema.sql`은 고치지 않았다 → 합칠 때 아래 ②·③을 옮겨 적어야 한다.
- AI는 모두 규칙 모드(키 없음)로 확인했다. 실제 유료 AI 호출 없음.

## ① 기능 ID별 대조

| 기능 | 우선 | 적용 전 | 지금 | 한 일 · 근거 |
|---|---|---|---|---|
| U-02 공고 목록 | 상 | 일부 | 충족 | 모드에 따라 목록(`frontend/src/screens/board.jsx:34`), 지역·카테고리·날짜 필터(`board.jsx:64-69`), 거리순(가까운 순)은 있었음. **시간순 정렬이 없어 추가**: `sort=time`(활동 날짜·시작 시각 빠른 순, `service/PostService.java` list), 화면 정렬 버튼이 최신순 → 가까운 순 → 시간순으로 돈다(`board.jsx:50-58`). 위치 동의가 없으면 거리순을 건너뛰고 시간순. |
| U-03 공고 상세 | 상 | 일부 | 충족 | 작성자 신뢰 배지(✓ 신원 확인)·평점(`frontend/src/ui/bits.jsx:108-109`), 날짜·시간·카테고리·내용은 있었음. 예상 적립 시간이 봉사 요청 공고에만 있어 **도움 가능 공고에도 "1시간 도우면 +N시간" 줄 추가**(`board.jsx` PostDetail). |
| U-04 AI 공고 작성 | 상 | 충족 | 충족 | 대화로 카테고리·날짜·시간·장소·내용 정리(`screens/write.jsx` WriteNeed, `ai/AiClient.java` postChat), 불가 요청은 막고 공공 서비스 카드 안내(`write.jsx:377`). 고친 것은 "매주" 안내뿐(U-09). |
| U-05 직접 공고 작성 | 상 | 충족 | 충족 | 봉사 요청 직접 적기(`write.jsx:117`), 도움 가능(`write.jsx:163`). |
| U-06 지난 공고 다시 올리기 | 중 | 일부 | 충족 | 날짜만 바꿔 다시 올리기(`ui/repost.jsx`, `POST /api/posts/{id}/repost`)는 있었음. 목록이 최근 3개만 보여 **"지난 공고 N개 모두 보기" 추가**(`write.jsx:24-48`). |
| U-07 카테고리 건의 | 하 | 충족 | 충족 | 작성 중 "맞는 종류가 없어요 → 운영자에게 건의"(이름·이유)(`ui/suggest.jsx`, `write.jsx:90,347`). 정기 요청 등록 화면에도 같은 줄을 둠. |
| U-08 가족 대신 요청 | 중 | 일부 | 충족 | AI 대화 작성에서만 됐음. **직접 적기와 정기 요청 등록에 "도움 받는 사람: 나 / 연결된 가족" 고르기 추가**(`write.jsx:120-145`, `screens/recurring.jsx`). 서버는 그대로 `forUserId`(연결 확인). |
| U-09 정기 요청 | 중 | 없음(41판에서 뺌) | **새로 만듦** | 아래 "U-09" 참고. |
| U-12 빈 시간 묶음 추천 ✨ | 상 | 없음 | **새로 만듦** | 아래 "U-12" 참고. |
| U-13 요청·제안 보내기 | 상 | 충족 | 충족 | 요청·제안 보내기(`screens/request.jsx:88`), 24시간 기한(`service/RequestService.java:185,216`, 만료 `:652`). 묶음 한 번에 제안도 같은 길을 쓴다. |

### U-09 정기 요청 (새로 만듦)
- 반복 주기(매주 요일·시작~끝 시각)와 기간(시작 날짜 + 회차 수 4·8·12회, API는 2~26회 또는 끝 날짜)을 정해 등록한다.
- **회차 공고는 한꺼번에 만들지 않는다.** 등록할 때와 1분마다 도는 스케줄러(`SchedulerService.runAll` → `RecurringService.generateDue`)가 "오늘부터 7일 안" 회차만 공고로 만든다. 만든 공고에는 `seriesId="RC{번호}"`, `repeatRule="매주 목요일"`이 남는다.
- 회차 공고는 보통 공고와 같은 길(`PostService.create`)로 만들어 검사·관심 알림·**공고 1개에 요청 1건** 규칙이 그대로다. 수락·거절은 회차마다 사람이 누른다.
- "내 정기 요청"(`/recurring`): 다음 회차(공고 보기 또는 공고가 올라갈 날짜)·남은 회차·지난 회차, 올라간 회차 공고 목록, **일시정지·다시 시작·해지**.
  - 일시정지: 새 회차를 만들지 않는다(이미 올라간 공고는 그대로).
  - 해지: 더 만들지 않고, 이미 올라간 앞으로의 회차 공고 중 수락되지 않은 것은 함께 내린다(받아 둔 제안은 취소 알림). 수락된 회차는 그대로 진행.
  - 올릴 수 없게 되면(가족 연결 끊김, 담당 종료, 계정 정지) 자동으로 멈추고 까닭을 알림으로 보낸다. 다시 시작은 사람이 누른다.
- 가족 대신(`forUserId`), 담당자의 관리 대상 대리(`elderId`, `elderIssue`)도 받는다. 담당자 화면에서 들어가는 연결(버튼)은 담당자 담당 범위라 넣지 않았다(주소 `/recurring?elder=번호`, `/recurring/new?elder=번호`는 동작).
- "매주"라고 말하면 정기 요청으로 이어진다: 대화 초안에 `draft.repeat {weekly, weekday, label}`이 실리고(`ai/AiClient.java`, `ai/PostChatRule.java`), AI 작성 화면은 "정기 요청으로 올리기(기간 정하기)" 버튼, 말로 요청하기는 회차 수(4·8·12번)를 골라 바로 등록한다. 격주·매일·매달은 정기 요청이 안 되어 한 번만 올리고 다시 올리기를 안내한다.
- `POST /api/posts`에 `repeat`를 보내는 예전 방식은 지금도 한 건만 올리고 안내 문장(`repeatNotice`)만 돌려준다(문장만 정기 요청 안내로 바꿈).

### U-12 빈 시간 묶음 추천 (새로 만듦)
- 화면 `/bundle`: 날짜·가능한 시간 범위를 넣고 "묶음 추천 받기" → 묶음 최대 3개(묶음마다 2~3건), 건별 추천 시각·이동 거리·여유, 합계 시간·예상 적립, 설명 문장, "코드가 계산한 근거 보기", **"이 N건에 한 번에 제안하기"**.
- 진입점: 공고 화면(봉사하기)과 홈(봉사하기 모드)의 "묶음 추천" 줄.
- **묶음 고르기는 전부 코드**(`service/BundleService.java`):
  - 후보: 그 날짜의 열린 도움 요청 공고 중 내 공고·내가 대신 올린 공고·이미 제안한 공고·받지 않기로 된 대상의 공고를 빼고, 가능한 시간 안에 활동 시간이 들어가는 것.
  - 거리: 공고의 동 이름으로 `service/Geo.java` 동별 좌표 사이 거리(표에 없는 동은 2.5km로 봄).
  - 건 사이 간격: 걸어서 시속 4km 이동 시간 + 10분, 최소 30분, 30분 단위 올림. 내 다른 일정(기다리는·확정된 건)과도 30분 간격.
  - 순서를 바꿔 가며 가장 이른 시각에 차례로 넣어 들어가는 조합만 남기고, 점수(건 사이 이동 km + 출발지 거리×0.5 + 빈 시간×0.5, 3건 묶음 우대)순으로 고른다.
- **LLM은 설명 문장만**(`ai/BundleAi.java`): 코드가 준 사실 문장으로 한두 문장. 키가 없거나 LLM 문장에 코드에 없는 숫자가 있으면 규칙 문장. 응답에 `source: ai|rule`.
- 한 번에 제안(`POST /api/bundles/propose`): 공고마다 기존 제안 만들기(`RequestService.create`, PROPOSAL)를 시간 순서대로 부른다. 묶음 안에서 시간이 겹치면 아무것도 보내지 않고 400. 일부가 실패하면 건별로 `ok / code / error`를 돌려주고 화면이 건마다 "보냈어요 / 보내지 못했어요: 까닭"을 보여 준다.

## ② 새·바뀐 API (계약서에 옮겨 적을 것)

| 주소 | 설명 |
|---|---|
| `GET /api/recurring[?elderId=]` | 내 정기 요청 목록(도움 받는 본인·대신 등록한 사람·그 대상의 담당자). 한 건: `id, weekday(1=월…7=일), repeatLabel, startHour, endHour, estHours, area, categories, title, detail, note, startDate, endDate, status(ACTIVE·PAUSED·ENDED·CANCELLED), stopReason, totalCount, remainingCount, passedCount, nextDate, nextPostId, nextPostAt, posts[{id,date,status,requestCount}], estEarnPerVisit, elderId, elderDisplayName, ownerId, ownerName, proxy, proxyMine, createdByName, actions[pause·resume·cancel]` |
| `GET /api/recurring/{id}` | 한 건(권한 없으면 403) |
| `POST /api/recurring` | 등록. `{weekday?, startDate?, weeks(2~26) 또는 endDate, startHour, endHour, estHours?, area, categories, title, detail?, note?, forUserId?, elderId?, elderIssue?}` → 위 한 건 + `createdCount, createdPostIds, firstPost`. 오류: `INVALID_PERIOD, INVALID_DATE, INVALID_WEEKDAY, DUPLICATE_RECURRING(409), TOO_MANY_RECURRING(409)` + 공고 등록과 같은 검사 오류(`INVALID_TIME, INVALID_CATEGORY, FORBIDDEN_REQUEST, ELDER_REQUIRED …`) |
| `POST /api/recurring/{id}/pause` · `/resume` · `/cancel`, `DELETE /api/recurring/{id}` | 일시정지 · 다시 시작(`createdPostIds`) · 해지(`removedPosts, keptPosts`). 상태가 맞지 않으면 409 `INVALID_STATE` |
| `POST /api/bundles/recommend` | `{date, fromHour, toHour, lat?, lng?}` → `{date, fromHour, toHour, startDong, startBy(profile·location), candidateCount, bundles[{key, count, items[{postId, post(PostCard), startHour, endHour, hours, estEarn, travelKm, travelMin, waitMin}], totalKm, travelMin, idleMin, totalHours, totalEarn, startHour, endHour, reason, facts[], source}], message, source(ai·rule), rule}`. 좌표는 출발 동을 정하는 데만 쓰고 저장하지 않는다. 오류: `INVALID_DATE, INVALID_TIME, RANGE_TOO_SHORT, INVALID_LOCATION`, 사용자 아니면 403 |
| `POST /api/bundles/propose` | `{items[{postId, startHour, hours}](1~3), memo?}` → `{results[{postId, title, ok, requestId?, startHour?, hours?, deadlineAt?, code?, error?}], okCount, failCount, message}`. 보내기 전 오류(아무것도 안 보냄): `ITEMS_REQUIRED, TOO_MANY, INVALID_ITEM, DUPLICATE, DIFFERENT_DAY, TIME_OVERLAP` |
| `GET /api/posts?sort=time` | (바뀜) 정렬 값 추가: `time` = 활동 날짜·시작 시각 빠른 순 |
| `POST /api/ai/post-chat` | (바뀜) "매주"면 `draft.repeat {weekly:true, weekday, label}` 추가, `draft.repeatNotice` 문장이 정기 요청 안내로 바뀜(격주·매달 등은 "한 번만" 안내) |
| `POST /api/posts` | (바뀜) `repeat`를 보내면 돌려주는 `repeatNotice` 문장만 바뀜(여전히 한 건만 올림) |
| 알림 종류 | (추가) `RECURRING_POST`(회차 공고가 올라감), `RECURRING_STOPPED`(자동으로 멈춤). 끌 수 없는 종류. |

## ③ DB 변경 (`ddl-auto: update`로 생김 — `db/schema.sql`에 옮겨 적을 것)

- 새 표 `recurring_posts`(`domain/RecurringPost.java`): `id, owner_id, elder_id, created_by(not null), weekday(not null), start_hour, end_hour, est_hours, area(30), categories(200), title(100), detail(1000), note(500), issue_note(300), start_date(not null), end_date(not null), status(10, not null, 기본 ACTIVE), stop_reason(200), last_generated, created_at, updated_at`. 색인 `ix_recur_owner(owner_id)`, `ix_recur_status(status)`.
- `posts`: 칸 추가 없음. 이미 있던 `repeat_rule`, `series_id`를 정기 요청 회차 공고에 다시 쓴다(보통 공고는 null).

## ④ 못 한 것 · 남은 것 · 모의 상태

- **담당자 화면 연결 없음**(지시대로): 담당자가 관리 대상 정기 요청을 등록·관리하는 버튼은 담당자 담당이 `/recurring/new?elder=번호`, `/recurring?elder=번호`로 이어야 한다. 서버·화면 자체는 담당자 계정으로 동작한다(테스트 `담당자는_관리_대상_대리_정기_요청을_등록한다`).
- 정기 요청은 **도움 요청(NEED) 공고만**, **매주 한 요일만** 된다. 격주·매달, 한 주에 여러 요일은 없다(요일마다 따로 등록). 등록한 뒤 내용 고치기는 없다(해지하고 새로 등록).
- 한 사람이 진행·일시정지로 둘 수 있는 정기 요청은 5개.
- 묶음 추천의 **거리는 동 중심 좌표 사이 직선거리**이고 이동 시간은 걷는 속도 가정이다(`Geo`의 좌표는 시범 지역 가상 좌표 — 모의). 실제 길·대중교통 시간이 아니다.
- 묶음 설명 문장은 키가 없어 **규칙 문장만 확인**했다. LLM 경로(`BundleAi.explain`)는 실제 호출로 확인하지 못했다(숫자 검사 `numbersWithin`만 단위 테스트).
- 화면의 지역 고르기 목록(`AREAS`)에는 소망동이 없다(예전부터). `Geo`에는 있어 묶음 계산은 된다. 범위 밖이라 그대로 뒀다.
- 마이 화면에 "내 정기 요청" 메뉴는 넣지 않았다(마이는 다른 담당 파일). 진입점은 공고 올리기 화면의 두 줄과 회차 공고 상세의 "정기 요청 관리".

## ⑤ 확인 방법

- 백엔드: `cd backend && sh ./mvnw -q test` — 새 테스트 `FuncPostTest`(15건: 정기 요청 8, 묶음 추천 6, 시간순 1), 고친 테스트 `VoiceRepeatTest`(5건).
- 화면(폭 390, 규칙 모드, 포트 8102에서 직접 눌러 확인):
  1. `yujin` 로그인 → 헤더 "봉사하기" → 홈 또는 공고 화면의 **묶음 추천** → 날짜 5일 뒤, 9:00~18:00 → 묶음 3개 → "이 2건에 한 번에 제안하기" → 건마다 "✓ 제안을 보냈어요". 다시 추천받으면 제안한 공고는 빠진다.
  2. 공고 화면 정렬 버튼: 최신순 → (위치 동의 없으면 안내 뒤) 시간순.
  3. `bok` 로그인 → 공고 올리기 → **정기 요청 등록** → 요일·시간·회차 수·종류·제목 → 등록 → **내 정기 요청**에 다음 회차·남은 회차 → 공고 보기(회차 공고에 "매주 금요일 정기 요청의 회차 공고예요") → 일시정지 → 다시 시작 → 해지.
  4. `bok` → 봉사 요청(AI) → "매주 목요일 오전 10시에 한 시간 말벗 해 줄 사람" → 요약 아래 "매주 목요일 정기 요청으로 올리기" → 등록 화면에 요일·제목·종류가 채워짐.
  5. `bok` → 말로 요청하기 → "매주 화요일 오후 2시에 한 시간 장보기 도와줄 사람" 적어 보내기 → 반복 칸에서 4번 → "매주 화요일 4번 정기 요청으로 올리기" → "정기 요청을 등록했어요".
  6. `yujin` → 공고 올리기 → 봉사 요청 직접 적기 → "도움 받는 사람"에서 엄마 고르기.

## 범위 밖에서 고친 파일 (가장 작게, 새 줄 추가 방식)

- `frontend/src/App.jsx`: import 2줄, 경로 3줄(`/bundle`, `/recurring`, `/recurring/new`).
- `frontend/src/screens/home.jsx`: import 1줄, 봉사하기 모드에 `<BundleLink />` 1줄.
- `frontend/src/screens/voice.jsx`(말로 요청하기): "매주"일 때 정기 요청 등록 버튼·회차 수 고르기, 안내 문장.
- `backend/.../service/SchedulerService.java`: 필드 1개, `runAll`에 `recurringPosts` 1줄.
- `backend/.../service/DataSeeder.java`: 묶음 추천 시연용 도움 요청 공고 2건(5일 뒤, 행복동 10~12시·희망동 12~15시).
- `backend/.../ai/AiClient.java`, `ai/PostChatRule.java`: "매주" 반복 표시(`draft.repeat`)와 안내 문장.
- `backend/src/test/.../VoiceRepeatTest.java`: 41판의 "자동 반복 없음" 단정 3건을 기능명세서(U-09)에 맞게 고침.
