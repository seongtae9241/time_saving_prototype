# 기능명세서 적용 — 단체 봉사 (기능 U-10 · U-11 · O-07)

작업 가지 `func-grp`. 기능 ID는 `docs/requirements/기능명세서.txt` 체계다(요구사항명세서 41판 ID와 다름).
AI를 부르는 곳은 없다(세 기능 모두 ✨ 아님). 인원·적립 시간은 모두 서버 코드가 계산한다.

## 1. 기능 ID별 대조

| 기능 | 상세 설명(문장) | 적용 전 | 지금 | 근거 |
|---|---|---|---|---|
| U-10 | 1:1 공고와 분리된 단체 공고 탭 | 없음 | 충족 | 공고 화면 맨 위 "1:1 공고 / 단체 공고" 탭 `frontend/src/screens/group.jsx:16`(BoardSeg), `board.jsx:92`(단체 목록 부르기). 표·API도 따로(`group_posts`, `/api/group-posts`) |
| U-10 | 모집 인원, 현재 참여 인원 | 없음 | 충족 | 카드·상세의 "모집 n명 · 지금 m명 참여" 막대 `group.jsx:27`(People), 서버 `GroupService.java:263`(card: capacity·joined·left·full) |
| U-10 | 참여 신청 | 없음 | 충족 | `GroupService.java:154`(join: 신분증 인증·정원 선착순·시간 겹침), 취소 `:173`, 화면 `group.jsx:96`(GroupDetail) |
| U-11 | 동아리 등 2명 이상이 함께 봉사하겠다는 공고 작성 | 없음 | 충족 | 제공형(OFFER) 올리기 `GroupService.java:82`, 화면 `group.jsx:213`(GroupNew), 들어가는 곳: 공고 올리기 화면 "단체로 봉사하기" `write.jsx:33`, 단체 탭의 "단체 공고 올리기" 버튼 |
| U-11 | 인원 2명 이상이면 단체 공고로 분류 | 없음 | 충족(아래 "판단" 1) | 인원 2명 미만이면 거절 `GroupService.java:92`(INVALID_CAPACITY, "혼자 하는 봉사는 일반 공고로"), 화면도 1명이면 안내 |
| O-07 | 기관 봉사거리로 단체 모집 | 없음 | 충족 | 모집형(RECRUIT)은 담당자만 `GroupService.java:88`, 담당자 홈 "단체 봉사 공고" `org.jsx:126-127` → 올리기 화면에서 "단체 모집" 선택 |
| O-07 | 기관 봉사 단체의 봉사 가능 공고 | 없음 | 충족 | 담당자도 제공형을 올릴 수 있다("기관 봉사단의 봉사 가능"), 다른(또는 같은) 기관 담당자가 "우리 기관에서 받기" `GroupService.java:183` |
| (총괄 지침) | 당일 담당자가 참석 확인·완료 → 참여한 개인 계정에 각각 적립 | 없음 | 충족 | `GroupService.java:223`(complete), 적립 `:244`(EARN, 시간 × 1.0, 0.5 단위) |
| (총괄 지침) | 신분증 인증된 사용자만 참여 | 없음 | 충족 | `GroupService.java:143`(joinBlock), `:157` |
| (총괄 지침) | 6종 카테고리·금지 요청 판정 적용 | 없음 | 충족 | `GroupService.java:98`(카테고리), `:108`(신체 돌봄·의료·금전·위험 작업 BLOCK), 개인정보(연락처·상세 주소) 거절 |
| (총괄 지침) | 플래너 달력·내 활동에 단체 일정 | 없음 | 충족(가장 작게 연결) | 달력: `PlannerController.java:58` → `GroupService.java:350`, 누르면 단체 공고로 `planner.jsx:22`. 내 활동(봉사한 활동): `bank.jsx:42` → `group.jsx:277`(MyGroups) |
| (총괄 지침) | 통장 내역에 단체 봉사 적립 | 없음 | 충족 | 거래 이름 "단체 봉사 · 제목 · 기관 이름"(EARN) — 통장 화면은 고치지 않음(이름 그대로 보임) |

## 2. 새 API (`web/GroupController.java`, 모두 로그인 필요)

| 방법·경로 | 누가 | 하는 일 |
|---|---|---|
| `GET /api/group-posts?kind=&area=&cat=` | 모두 | 열려 있고 날짜가 안 지난 단체 공고(날짜·시각 순). kind: `RECRUIT`(단체 모집) · `OFFER`(단체 봉사 가능) |
| `GET /api/group-posts/mine` | 사용자·담당자 | 사용자=올렸거나 참여한 것, 담당자=우리 기관이 올렸거나 받기로 한 것 |
| `GET /api/group-posts/schedule?month=2026-11` | 사용자 | 내 단체 일정(플래너 일정과 같은 모양: `kind:"give", group:true, date, startHour, hours, category, title, who, amount, groupPostId, status`) |
| `GET /api/group-posts/{id}` | 모두 | 상세. `participants`(이름)는 올린 쪽·참석을 확인하는 기관 담당자에게만 |
| `POST /api/group-posts` | 사용자(제공형만, 신분증 인증)·담당자(모집형·제공형) | 올리기. 본문 `{kind, date, startHour, hours, area, place?, category, title?, detail?, capacity, groupName?}` |
| `DELETE /api/group-posts/{id}` | 올린 사람(담당자 공고는 같은 기관 담당자) | 공고 내리기(CANCELLED, 참여자에게 알림) |
| `POST /api/group-posts/{id}/join` · `/leave` | 사용자 | 참여 신청(정원까지 선착순) · 취소 |
| `POST /api/group-posts/{id}/host` · `/unhost` | 담당자 | 제공형 단체의 봉사를 우리 기관에서 받기 · 받기 취소 |
| `POST /api/group-posts/{id}/complete` | 담당자(모집형=올린 기관, 제공형=받은 기관) | 본문 `{attended:[사용자 id…]}`. 활동 날부터 가능. 참석자마다 적립, 나머지는 불참 기록, 공고는 DONE |

카드 응답의 주요 칸: `id, kind, kindLabel, title, detail, category, date, startHour, hours, endHour, area, place, groupName, capacity, joined, left, full, status(OPEN·DONE·CANCELLED), past, ownerName, byOrg, orgName, hosted, hostOrgName, weight(1.0), estEarn, mine, myStatus(JOINED·ATTENDED·ABSENT), myEarned, canJoin, joinBlock, canLeave, canCancel, canHost, canUnhost, canComplete, confirmSide`.
오류 코드: `INVALID_KIND, INVALID_CAPACITY, INVALID_DATE, INVALID_TIME, INVALID_HOURS, INVALID_CATEGORY, AREA_REQUIRED, GROUP_NAME_REQUIRED, TOO_LONG, FORBIDDEN_REQUEST, PII_NOT_ALLOWED, ID_NOT_VERIFIED`(400) · `CANNOT_JOIN, TIME_OVERLAP, OWNER_CANNOT_LEAVE, ALREADY_HOSTED, NOT_HOSTED, TOO_EARLY, INVALID_STATE`(409) · 403.
바뀐 API: `GET /api/me/planner` 의 `items` 에 그 달의 내 단체 일정이 더해진다(위 schedule 모양, `groupPostId` 로 구분). `goal`(목표 계산)은 그대로.
알림 종류 `GROUP`(끌 수 없는 종류): 참여 신청·취소(올린 사람에게), 받기·받기 취소, 공고 취소, 적립·불참.

## 3. DB 변경 (엔티티 추가, `ddl-auto: update` 로 생김 — `db/schema.sql` 은 고치지 않음)

- 새 표 `group_posts`: `id, kind(8), owner_id, org_id, group_name(50), date, start_hour, hours, area(30), place(100), category(30), title(100), detail(1000), capacity, status(10), host_org_id, host_by, hosted_at, done_by, done_at, created_at` (색인: date, owner_id)
- 새 표 `group_participants`: `id, group_post_id, user_id, status(10: JOINED·LEFT·ATTENDED·ABSENT), earned, created_at` (색인: group_post_id, user_id)
- 기존 표는 바꾸지 않았다. 적립은 기존 `ledger` 에 `EARN`(request_id 없음, base_hours=활동 시간, weight=1.0)으로 들어간다.
- 합칠 때 `db/schema.sql`·`docs/design/contract.md` 에 위 두 표와 2장의 API를 옮겨 적어야 한다(총괄 몫으로 남김).

## 4. 판단해서 정한 것 · 못 한 것 · 남은 것 · 모의 상태

판단해서 정한 것
1. **사용자는 제공형만, 모집형은 기관 담당자만**(총괄 지침의 좁히는 안). 일반 사용자가 "n명 필요"를 올리면 올린 사람 시간에서 n명분을 빼야 하는데 이번에는 만들지 않았다. 그래서 단체 봉사에는 **차감이 없다**(받는 쪽이 항상 기관).
2. "인원 2명 이상이면 단체 공고로 분류": 1:1 공고 작성 화면(다른 담당 범위)에 인원 칸을 넣어 자동으로 옮기는 대신, **단체 공고 작성 화면을 따로 두고 2명 미만이면 받지 않는** 방식으로 했다(공고 올리기 화면에 "단체로 봉사하기" 한 줄 추가). 1명이면 "일반 공고로 올려 주세요"라고 안내한다.
3. 참여는 **정원까지 선착순**(올린 사람이 한 명씩 확정하지 않음). 사용자가 올린 제공형은 올린 사람도 1명으로 센다.
4. **제공형은 기관 담당자가 "우리 기관에서 받기"를 눌러야** 참석 확인을 할 수 있다. 참석 확인·완료는 언제나 기관 담당자만 한다(사용자가 스스로 완료해 시간을 만들 수 없게). 받는 것은 한 기관만.
5. 가중치는 카테고리와 관계없이 **기본 1.0**(총괄 지침). 활동 시간은 1:1 과 같은 30분~4시간.
6. 같은 날 시간이 겹치는 **다른 단체 봉사**에는 참여할 수 없다(1:1 요청 일정과의 겹침은 보지 않는다 — 1:1 쪽에도 그런 검사가 없어 맞췄다).
7. 참여자 이름은 올린 쪽과 참석을 확인하는 담당자에게만 보인다(다른 사람에게는 인원 수만).

못 한 것 · 남은 것
- **첫 활동 교육(기능 U-34) 필수 여부를 단체 참여에 연결하지 않았다.** 지금 이 가지의 `/api/verify-items` 에는 교육 항목이 없어 신분증 인증만 본다. U-34 담당 가지와 합친 뒤 `GroupService.joinBlock`(143줄)에 한 줄 더하면 된다.
- 일반 사용자의 모집형(시간 차감 포함), 사용자가 제공형 단체에게 직접 도움을 요청하는 흐름.
- 봉사 확인서(`/api/certificate`)·월별 횟수 요약(`/api/my/activity-summary`)·플래너 목표 계산·운영자 통계의 "봉사 횟수"에는 단체 봉사가 들어가지 않는다(모두 1:1 요청 표로 센다, 다른 담당 파일). 통장 잔액·적립 합계·직접 적립 10시간 규칙에는 들어간다.
- 불참 벌점, 대기 신청(정원이 찬 뒤 줄 서기), 단체 공고 수정(지금은 내리고 다시 올리기), 관심 조건 알림, 운영자의 단체 공고 관리·신고 화면.
- 정원 경쟁: 같은 순간에 두 사람이 마지막 한 자리를 신청하면 둘 다 들어갈 수 있다(잠금 없음).
- 참석 확인은 담당자가 명단에서 고르는 방식이다(QR 체크 없음).

모의(흉내) 상태: 없음. 알림의 음성·문자 병행은 기존과 같이 로그만 남는 모의다.

범위 밖에서 고친 파일(모두 줄 추가, 기존 줄 정리 없음)
- `frontend/src/screens/board.jsx`: import 1줄, 단체 탭이면 단체 목록을 돌려주는 1줄(92), 탭 줄 1줄(사용자에게만)
- `frontend/src/App.jsx`: import 1줄, 경로 2줄(`/group/new`, `/group/:id`)
- `frontend/src/screens/planner.jsx`: 일정 누르기 한 줄에 `groupPostId` 갈래 추가(22줄, 이 줄만 고침)
- `frontend/src/screens/bank.jsx`(내 활동): import 1줄, `{give && <MyGroups />}` 1줄
- `frontend/src/screens/org.jsx`(담당자 홈): "단체 봉사" 제목 1줄 + 들어가는 줄 1줄
- `frontend/src/screens/write.jsx`(공고 올리기 고르기): "단체로 봉사하기" 1줄
- `backend/.../web/PlannerController.java`: 플래너 응답에 단체 일정 더하기(메서드 몸통 3줄 + 필드 주입 1줄)
- `DataSeeder`·`Views`·`RequestService`·`AdminController`·`Frame.jsx`·`home.jsx` 는 건드리지 않았다. 시연 자료는 새 파일 `service/GroupSeeder.java`(단체 공고가 하나도 없을 때만: 오늘 모집 1, 며칠 뒤 모집 1, 사용자 제공형 1, 기관 봉사단 제공형 1, 지난 완료 1 — 손유진 잔액은 건드리지 않음).

## 5. 확인 방법

- 서버 테스트: `cd backend && sh ./mvnw -q test` → 223건 통과(기존 212 + `GroupTest` 11). `GroupTest` 는 올리기 규칙(2명 이상·6종·금지 요청·개인정보·권한), 참여(신분증·선착순·취소·겹침), 참석 확인과 개인별 적립·이중 적립 방지, 제공형 받기, 공고 내리기, 플래너 일정을 본다.
- 화면 빌드: `cd frontend && npm run build` 성공.
- 화면(폭 390, 규칙 모드, 포트 8101)에서 직접 눌러 본 것:
  - 사용자 `yujin`: 공고 → "단체 공고" 탭(4건) → 종류 칩·내 단체 봉사 → 모집 공고 상세 → 참여 신청(확인 창) → "참여 신청했어요" → 플래너 달력에 "[단체] …" 일정이 보이고 누르면 단체 공고로 → 내 활동(봉사한 활동)에 단체 봉사 묶음 → 공고 올리기 "단체로 봉사하기" → 1명이면 막힘, "목욕" 제목은 금지 안내, 고쳐서 올리면 상세로
  - 담당자 `org1`: 홈 "단체 봉사 공고" → 목록 → 사용자가 올린 제공형 "우리 기관에서 받기" → 우리 기관 목록 → 오늘 모집 공고에서 한 명을 불참으로 바꾸고 "참석 2명 확인하고 완료하기" → "참석 2명에게 2.0시간씩 적립했어요"
  - 다시 `yujin`: 시간 통장 내역에 "단체 봉사 · 어르신 스마트폰 교실 도우미 · 행복동 주민센터" +2시간, 잔액 24.5 → 26.5
- 화면으로 못 본 것: 큰 글씨 모드, 받기 취소·공고 내리기·참여 취소 버튼(서버 테스트로만 확인), 담당자의 "기관 봉사단의 봉사 가능" 올리기 끝까지(화면은 열어 봄, 서버 테스트로 확인).
