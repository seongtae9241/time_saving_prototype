# 기능명세서 적용 결과 — 공통·플래너·AI 안내·마이 (가지 `func-me`)

- 기준 글: `docs/requirements/기능명세서.txt` (73개 기능). 여기의 ID(기능 C-04, 기능 U-34 …)는 요구사항 명세서 41판의 ID와 다른 체계다.
- 범위: 기능 C-01, C-02, C-04, C-05, C-06, C-07, C-08, U-01, U-26, U-27, U-28, U-30, U-31, U-32, U-33, U-34, U-35 (17개)
- `docs/design/contract.md`, `db/schema.sql` 은 고치지 않았다. 합칠 때 아래 ②·③을 옮겨 적으면 된다.
- 줄 번호는 이 가지의 마지막 커밋 기준이다.

## ① 기능 ID별 대조

| 기능 | 우선 | 적용 전 | 지금 | 한 일 · 근거(파일:줄) |
|---|---|---|---|---|
| C-01 회원가입 | 상 | 충족 | 충족(손대지 않음) | 휴대폰 본인인증 `frontend/src/screens/auth.jsx:89`(PhoneVerify), 이름·동네·나이대 `auth.jsx:168`(Signup 1·2단계), 약관 4항목 `auth.jsx:161`, 서버 `web/AuthController.java:90`·`:97`(필수 약관 검사) |
| C-02 로그인·로그아웃 | 상 | 충족 | 충족(손대지 않음) | 아이디·비밀번호 `auth.jsx:53`, 로그인 유지 체크 `auth.jsx:72` → 토큰 30일/1일 `service/AuthService.java:56`, 로그아웃 `AuthController.java:131` |
| C-04 담당자 계정 신청 | 중 | 없음 | **새로 만듦** | 화면 `frontend/src/screens/managerApply.jsx`(주소 `/manager-apply`, 로그인 화면에 "담당자 신청" 버튼 `auth.jsx:82`), 서버 `web/ManagerApplyController.java:65`(신청·자동 승인), `:121`(운영자 내역), `:126`(취소). 승인된 기관만 고름 → 휴대폰 본인인증 → 재직 증빙 파일 → 바로 담당자 계정(역할 ORG) 생성·로그인. 운영자와 같은 기관의 기존 담당자에게 알림 |
| C-05 알림 목록 | 상 | 충족 | 충족(안내 문구만) | 목록·읽음·모두 읽음 `frontend/src/screens/common.jsx:57`, `web/CommonController.java:38~56`. 요청(NEW_REQUEST·NEW_PROPOSAL)·수락(VOL_ACCEPTED·CONFIRMED)·일정(REMIND·LATE)·일지(LOG_SUBMITTED)·적립(EARNED) 알림은 `service/RequestService.java`·`SchedulerService.java` 가 이미 보낸다. 화면 머리 문구를 "요청·수락·일정·일지·시간 적립 알림"으로 맞춤 |
| C-06 알림 설정 | 하 | 충족 | 충족(손대지 않음) | 종류 5개 켜기·끄기 `frontend/src/screens/my.jsx:377`, 서버 `AuthController.java:206`, 꺼 둔 종류는 만들지 않음 `service/NotificationService.java`(wants) |
| C-07 문의·신고 | 중 | 충족 | 충족(손대지 않음) | 1:1 문의 작성 `common.jsx:146`, 답변 확인 `common.jsx:117`, 사람 신고 `frontend/src/screens/request.jsx:79`·`:647`, 메시지 신고 `request.jsx:622~623`, 서버 `CommonController.java`(inquiries·reports) |
| C-08 공지사항 | 하 | 부분(고정 없음) | **고침** | 목록·상세는 있었고, 고정 공지가 맨 위에 오게 함: `domain/Notice.java`(pinned), 정렬 `CommonController.java:60`, 화면 "고정" 표시 `common.jsx:91`·`:103`. 시연용 고정 공지 1건 `service/DataSeeder.java`. 고정 값을 바꾸는 운영자 화면·API(기능 A-16)는 운영자 담당 |
| U-01 모드 토글 | 상 | 부분(플래너만 안 걸러짐) | **고침** | 헤더 전환 `frontend/src/ui/Frame.jsx:26`. 홈 `screens/home.jsx`(give 로 거름)·공고 `screens/board.jsx:34`·활동 내역 `screens/bank.jsx:22` 는 이미 모드를 따랐고, 플래너 달력이 봉사·도움 일정을 섞어 보여 주던 것을 모드별로 거르게 함 `screens/planner.jsx:136` |
| U-26 캘린더 | 상 | 부분(월만, 모드 구분 없음) | **고침** | 월·주 전환 `planner.jsx`(`view === 'week'` 120·207·227줄), 주 단위 일정 API `service/PlannerService.java:256`(GET `/api/me/planner/week`), 모드에 따라 봉사할 일정(+내 계획) 또는 도움 받을 일정만 |
| U-27 AI 도움 추천 ✨ | 상 | 대부분 충족(플래너 입구 없음) | **고침** | 받을 수 있는 도움 종류 + 단골 섹션 / 가까운·시간 맞는 사람 섹션은 있었음 `screens/ai.jsx:120`, `web/AiController.java:102`(splitByRegular). 도움 받기 모드 플래너에 "AI 도움 추천" 탭과 달력 아래 버튼을 넣고(`planner.jsx:279`), 적은 문장으로 바로 찾게 함(`/recommend?q=`) |
| U-28 추천 봉사 ✨ | 중 | 부분(프로필 기준만) | **고침** | 끝낸 봉사 기록에서 가장 많이 한 카테고리·지역·시간대를 코드가 세고(`PlannerService.java:218` historyBasis) 그것과 프로필을 함께 봐서 순위·이유를 만든다(`:398` "가장 많이 하신 동행(5회)이에요 · 자주 활동하신 …"). 플래너·홈에 바탕 문장(basisText)과 공고별 이유 표시. 설명 문장은 기존 PlannerAi(키 없으면 규칙) |
| U-30 공공 서비스 안내 AI ✨ | 상 | 부분(봉사 가능 여부 없음) | **고침** | 근처 공공기관·신청 방법 안내는 있었음. 응답에 `volunteer`(봉사로 가능/주의/불가/확인 필요, 맞는 도움 종류와 난이도·가중치, 봉사 기준 5줄, 공고 작성 링크)를 더함: `ai/VolunteerGuideAi.java`, `AiController.java:88`, `web/NewsController.java:113`, 화면 `ai.jsx:172`(VolunteerGuide) — "이 내용으로 봉사 요청 공고 쓰기" 버튼이 `/write/need?prefill=` 로 이어짐 |
| U-31 동네 기관 소식 | 중 | 부분(홈에 모든 동네 행사) | **고침** | 홈 섹션이 내 동네 근처 행사만 받게 함: `home.jsx:239`(`/api/events?nearby=true`), `service/EventService.java:87`(같은 동 + 동 중심 1.2km 안의 옆 동). 신청은 기관 페이지 링크로만 `screens/news.jsx:29`·`:99`(그대로) |
| U-32 프로필 관리 | 상 | 충족 | 충족(이름표만) | 소개·활동 지역·가능한 시간대·잘하는 도움 `my.jsx:191`. 칸 이름 "할 수 있는 도움" → "잘하는 도움" |
| U-33 가족 연결 | 중 | 충족 | 충족(손대지 않음) | 상대 아이디(또는 휴대폰)로 신청 `my.jsx:292`, 상대 승인·거절 `my.jsx:263` 아래, 서버 `web/BankController.java:192~`. 시간 선물·가족 대신 요청에 사용(기존) |
| U-34 봉사 인증 | 상 | 부분(교육 이수 없음) | **고침** | 신분증 확인·범죄경력 회보서(선택, 신뢰 배지)는 있었음. 첫 활동 교육(안전 교육)을 되살림: 연습 상황(기본 4개)을 모두 안전 규칙에 맞게 답하면 이수 기록 `service/EduService.java:145`, 봉사 자격 검사 한 곳 `service/VolunteerGate.java:73`(RequestService `:94`, PostService `:107` 이 부름), 인증 항목에 `edu` 추가(`/api/verify-items`), 인증 화면 `my.jsx:218`, 교육 화면 `screens/edu.jsx`, 마이·프로필 "✓ 안전 교육" 배지 |
| U-35 봉사 확인서 | 하 | 부분(인쇄로만 저장) | **고침** | 기간 선택은 있었음 `my.jsx:427`. "PDF 파일로 받기"가 실제 .pdf 파일을 내려 줌 `frontend/src/lib/certPdf.js`, `my.jsx:467`. 인쇄하기도 남김 |

## ② 새·바뀐 API

### 새로 생긴 것
| 주소 | 누가 | 내용 |
|---|---|---|
| `GET /api/auth/manager-apply/orgs` | 누구나(로그인 전) | 승인된 기관 목록 `[{id, name, orgType, address}]` (이름 순) |
| `POST /api/auth/manager-apply` (multipart) | 누구나(로그인 전) | 칸: `orgId, name, phone, verifyToken, loginId, password, proof(파일)`. 휴대폰 본인인증(`/api/auth/phone/verify` 의 verifyToken)이 맞으면 바로 담당자 계정 생성. 응답 `{id, status:"APPROVED", orgId, orgName, loginId, token, user, message}`. 오류: `ORG_NOT_APPROVED`·`NAME_REQUIRED`·`INVALID_LOGIN_ID`·`WEAK_PASSWORD`·`PHONE_NOT_VERIFIED`·`PROOF_REQUIRED`(400), `DUPLICATE_LOGIN_ID`·`ALREADY_MANAGER`(409), `TOO_MANY_REQUESTS`(429, 같은 IP 10분에 5번) |
| `GET /api/admin/manager-applications` | 운영자 | 자동 승인 내역(최근 순) `[{id, orgId, orgName, orgType, userId, name, loginId, phone, proofFileUrl, status(APPROVED|REVOKED), statusLabel, userStatus, existingManagers(신청 때 그 기관 담당자 수), otherManagersNow, revokeReason, revokedAt, createdAt}]`. `proofFileUrl` 은 운영자만 열린다 |
| `POST /api/admin/manager-applications/{id}/revoke` `{reason?}` | 운영자 | 신청을 취소(REVOKED)로 바꾸고 그 담당자 계정을 정지·로그아웃. 이미 취소면 409 |
| `GET /api/me/planner/week?start=YYYY-MM-DD&mode=give|recv` | 사용자 | start 가 든 주(일~토)의 일정. `{start, end, today, mode, days:[{date, items[]}], items[]}`. item 모양은 `/api/me/planner` 의 items 와 같다. AI 를 부르지 않는다 |

### 바뀐 것(칸 추가)
| 주소 | 바뀐 점 |
|---|---|
| `GET /api/me`, 로그인·가입 응답의 user | `eduDone`, `canVolunteer`, `volunteerBlock{code,message}|null`, `verify.eduDone`·`verify.eduRequired`·`verify.eduOn` 추가 |
| `GET /api/users/{id}/profile` | `eduDone` 추가 |
| `GET /api/verify-items`, `PUT /api/admin/verify-items` | 항목에 `edu`(첫 활동 교육(안전 교육)) 추가. 순서: phone, id, edu, crime. edu 의 기본값은 켜짐·필수 |
| `GET /api/edu` | `education{done, completedAt, total, doneCount, doneKeys[], required, on}` 추가, `optional` 은 교육이 필수가 아닐 때만 true |
| `POST /api/edu/practice` | 로그인 사용자의 진행을 기록. 답이 안전 규칙에 맞으면(ok) 그 상황을 끝낸 것으로 적고 응답에 `education{…}` 추가. 모든 상황을 끝내면 `users.edu_done=true` |
| 봉사 제안·요청 수락(`POST /api/requests`, `…/accept`), 도움 가능 공고 등록(`POST /api/posts`) | 교육이 필수이고 미이수면 400 `EDU_REQUIRED`("첫 활동 교육(안전 교육)을 마쳐야 봉사할 수 있어요…"). 요청을 받는 봉사자가 미이수면 보내는 쪽에 "이 봉사자는 아직 첫 활동 교육을 마치지 않았어요…" |
| `GET /api/me/planner` | `basis{doneCount, topCategory, topCategoryCount, topArea, topAreaCount, topSlot, topSlotCount}`, `basisText` 추가. recommends[].why 가 기록 기준 이유를 담고 `whyBasis{category, area, slot}` 추가 |
| `GET /api/me/recommends` | `basis` 에 위 기록 값이 더해지고 `basisText` 추가 |
| `POST /api/ai/public-services`, `POST /api/ai/public-services/nearby` | `volunteer{possible(YES|CAUTION|NO|UNKNOWN), label, message, reason, categories[{category, level, levelLabel, weight}], criteria[], writeLink|null, source}` 추가 |
| `GET /api/events?nearby=true` | 내 동네 근처(같은 동 + 1.2km 안의 옆 동) 행사만. 파라미터가 없으면 예전과 같다 |
| `GET /api/notices` | 고정 공지(`pinned=true`)가 맨 위, 그 안에서 최근 순. 항목에 `pinned` |

### 설정값
- `app.verify.edu-required-default` (기본 `true`): 운영자가 인증 항목 설정을 한 번도 바꾸지 않았을 때 첫 활동 교육이 필수인지. 테스트(`backend/src/test/resources/application.properties`)에서는 `false`.

## ③ DB 변경 (`ddl-auto: update` 로 생김. `db/schema.sql` 에는 아직 없음)

| 표 | 칸 | 설명 |
|---|---|---|
| `manager_applications` (새 표) | `id` PK, `org_id` not null(색인 ix_mgrapp_org), `user_id`, `name`(50), `phone`(20), `login_id`(50), `proof_file_url`(200), `status`(10, not null, APPROVED/REVOKED), `existing_managers` int null, `revoke_reason`(300), `revoked_at`, `created_at` | 기능 C-04 자동 승인 내역 |
| `edu_progress` | `practice_json` TEXT null | 끝낸 연습 상황 key 목록(JSON 배열). `completed_at` 은 이수 시각으로 다시 씀 |
| `notices` | `pinned` boolean null | 고정 공지(null·false = 고정 아님) |
| `users` | (변경 없음) `edu_done` | 다시 쓴다: 첫 활동 교육 이수 여부 |
| `app_settings` | (변경 없음) 키 `verify.items` | 값에 `edu` 항목이 더해질 수 있다 |

## ④ 못 한 것 · 남은 것 · 모의(흉내) 상태

- **기능 A-11(운영자 화면)**: 서버 API 까지만 만들었다. 운영자 화면에 "담당자 계정 승인 내역" 목록·재직 증빙 보기·취소 버튼을 붙이는 일은 운영자 담당. 운영자 알림(`MANAGER_APPLY`)의 링크 `/admin/manager-applications` 는 지금 담당자 관리 화면(`/a/managers`)으로 연결해 두었다(`common.jsx` LINK_MAP).
- **기능 A-16(공지 고정 쓰기)**: 읽는 쪽(정렬·표시)만 했다. 운영자가 고정을 켜고 끄는 화면·API 는 운영자 담당 — 칸 이름은 `notices.pinned`(Boolean). 운영자 쪽에서 같은 칸을 다른 이름으로 더했다면 합칠 때 하나로 맞춰야 한다.
- **휴대폰 본인인증**: 기존과 같다 — Firebase 키가 없으면 데모 코드 123456(모의). 담당자 계정 신청도 같은 인증을 쓴다. 통신사 본인확인(실명 대조)은 아니다.
- **재직 증빙**: 자동 승인 때 내용을 검사하지 않는다(파일을 받아 운영자가 나중에 확인). 이것이 자동 승인의 남은 위험이고, 알림·내역·취소로 보완했다.
- **교육 필수 기본값**: 기본이 필수라서, 이 가지를 적용한 뒤에는 **이미 가입해 있던 봉사자도 첫 활동 교육을 마쳐야** 봉사 제안·수락·도움 가능 공고 등록을 할 수 있다(시연 계정은 시드에서 이수 처리). 기존 봉사자를 한꺼번에 이수로 바꾸는 일은 하지 않았다 — 필요하면 운영자가 인증 항목 설정에서 edu 를 선택으로 바꾸면 된다.
- **AI 어르신과 길게 대화하기·규칙 카드·퀴즈**는 이수 조건이 아니다(선택 연습). 이수는 "상황 4개(운영자 설정 목록)를 모두 안전 규칙에 맞게 답하기".
- **봉사 확인서 PDF**: 서버가 만드는 문서가 아니라 브라우저가 확인서를 그림으로 그려 PDF 로 싼다(글자 선택·검색은 안 됨, 직인·진위 확인 코드 없음). 한글 파일 이름(`봉사활동확인서_이름_날짜.pdf`)은 실제 브라우저에서는 그대로 붙지만, 확인에 쓴 헤드리스 크롬에서는 `download` 로 나왔다(그 환경의 글자 설정 문제로 보임).
- **근처 판정(U-31)**: 동 중심 좌표 표(`service/Geo.java`, 시범 5개 동의 가상 좌표)로 계산한다. 표에 없는 동은 같은 동일 때만 근처로 본다. 시드 행사는 모두 행복동이라 화면에서는 걸러지는 모습이 보이지 않는다(테스트로 확인).
- **U-30 봉사 가능 여부**: 금지 요청 필터(키가 있으면 LLM 이 문장을 읽고, 규칙이 불가로 잡은 것은 LLM 이 풀지 못함)와 6가지 도움 찾기(코드) 결과다. 6가지에 들지 않는 말은 "확인 필요"로 답한다.
- **C-06**: 켜고 끌 수 있는 종류는 기존 5개(새 요청·제안, 메시지, 수락·거절 결과, 리마인드, 관심 공고) 그대로다. 일정 확정·일지·적립 알림은 끌 수 없다(41판 그대로).

## ⑤ 확인 방법

- 서버 테스트: `cd backend && sh ./mvnw -q -o test` — `FuncMeTest`(12건: C-04 3건, U-34 3건, U-28 2건, U-26·U-30·U-31·C-08 각 1건) 포함 전체 통과.
- 화면(폭 390, 키 없이 규칙 모드, 포트 8104)에서 눌러 확인한 것:
  1. 로그인 화면 › "담당자 신청" › 기관 고르기 › 본인인증(123456) › 아이디·비밀번호 › 재직 증빙 › 신청하기 → "담당자 계정이 만들어졌어요" → 담당자 홈.
  2. 새로 가입 › 마이 › 봉사 인증: "첫 활동 교육 · 필수 · 0/4개" → 교육 받으러 가기 → 상황 4개를 차례로 답함 → "안전 교육을 이수했어요", 봉사 인증 화면 "이수", 마이에 "✓ 안전 교육".
  3. 신분증만 승인된 미이수 사용자가 봉사 요청 공고에 제안 보내기 → "첫 활동 교육(안전 교육)을 마쳐야 봉사할 수 있어요…" 토스트.
  4. 플래너(yujin): 월/주 전환, 다음 주 이동, 봉사하기 모드는 봉사할 일정만·도움 받기 모드는 도움 받을 일정만, 도움 받기 모드의 "AI 도움 추천" 탭 → 문장 입력 → AI 도움 추천 화면에서 바로 결과(받을 수 있는 도움 / 내 단골 중에서 / 가까운 · 시간 맞는 사람).
  5. 플래너 › 추천 봉사: "지금까지 많이 하신 봉사(동행 5회 · 행복동 11회 · 평일 오후 12회)를 바탕으로 골랐어요"와 공고별 이유.
  6. 공공 서비스 안내: "무릎이 아파서 장보기가 힘들어요" → "이웃 봉사로 받을 수 있나요? · 봉사로 가능", 봉사 기준 보기, "이 내용으로 봉사 요청 공고 쓰기" → 공고 작성 대화에 문장이 들어감. "목욕을 도와줄 사람이 필요해요" → 봉사로 불가.
  7. 홈 "동네 기관 소식 · 행복동 근처", 공지사항 목록 맨 위 "고정" 공지, 봉사 확인서 "PDF 파일로 받기" → PDF 1.4 파일(A4) 내려받기.

## 범위 밖에서 고친 파일 (가장 작게)

| 파일 | 고친 내용 |
|---|---|
| `service/RequestService.java` | `checkVolunteerEligible` 가 `VolunteerGate.require` 를 부르게(3줄 + 호출 2곳에 인자 하나) |
| `service/PostService.java` | 도움 가능 공고 등록 때 `volunteerGate.require` 한 줄 + 필드 주입 |
| `service/DataSeeder.java` | 신분증 승인된 시연 계정의 `eduDone=true` 한 줄, 공지 문구 1건, 고정 공지 1건 |
| `frontend/src/App.jsx` | import 1줄, `/manager-apply` 경로 1줄 |
| `frontend/src/screens/home.jsx` | 기관 소식을 `?nearby=true` 로 받기, 섹션 제목에 "○○동 근처", 추천 봉사 카드에 이유 한 줄·바탕 문장 |
| `frontend/src/screens/request.jsx` | 프로필에 "✓ 안전 교육" 배지 한 줄 |
| `backend/src/test/resources/application.properties` | `app.verify.edu-required-default=false` (기존 테스트가 교육 없이 봉사 흐름을 돌리므로) |
| `MeetingChangesTest`·`Req41MeTest`·`Proto1111Test` | 41판의 "교육 표시 없음·인증 항목 3개"를 확인하던 줄 5곳을 기능명세서에 맞게 고침 |

## 판단해서 정한 것

- 상황 하나는 **안전 규칙에 맞는 답을 했을 때** 끝낸 것으로 친다(어긋난 답은 "다시 답하기").
- 담당자 계정 신청은 로그인 전 주소라 `/api/auth/**` 아래에 두었다(공개 경로 설정 파일을 고치지 않으려고). 같은 번호로 그 기관에 이미 담당자 계정이 있으면 막는다.
- 자동 승인 뒤 잘못된 계정을 되돌릴 길이 필요해 운영자 취소 API 를 함께 만들었다.
- "근처"는 같은 동이거나 동 중심 사이 1.2km 이하.
- 추천 점수: 기존 점수(같은 동 3 · 카테고리 2 · 시간대 2)에 기록 기준 지역·시간대도 같은 점수로 치고, 가장 많이 한 카테고리와 맞으면 1점을 더한다.
