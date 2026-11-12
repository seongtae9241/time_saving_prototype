# 요구사항 41판 적용 — 담당자·운영자·기관 소식 (가지 `req41-org`)

작업 폴더 `/home/user/ts-wt/org`. `docs/design/contract.md`와 `db/schema.sql`은 고치지 않았다(총괄이 이 문서를 보고 합친다).
확인: `cd backend && sh ./mvnw -q -o test` → 151건 통과(실패 0, 고치기 전 142건), `cd frontend && npm run build` 성공. 브라우저로 화면을 직접 열어 보지는 않았다.

## 1. 고친 항목

| ID | 무엇을 어떻게 |
|---|---|
| C-01 · D-01 | 기관 신청 때 재직 증빙 파일이 **있어야** 신청된다. 로그인 전이라 신청 내용과 파일을 한 번에(multipart, 파일 칸 `proof`) 보낸다. JSON 신청은 이미 올린 파일 주소(`proofFileUrl`)가 실제로 있을 때만 받는다. 파일 주소는 `organizations.proof_file_url`에 저장(메모 글자에 붙이던 방식 없앰). 운영자 승인 화면에 "재직증명서 열어 보기 ↗" 링크(없으면 "제출된 파일이 없어요"). |
| C-02 | 도움 대상 등록(`POST /api/org/elders`)에 `consent: true`가 없으면 400 `CONSENT_REQUIRED`. 확인한 시각·담당자를 `elders.service_consent_at/by`에 기록. 수정에는 다시 필요 없다. 별칭 예시 문구에서 실명 모양을 뺐다. 나이대 칸은 남기고 "담당자에게만 보여요" 표시. |
| C-13 | 메모를 저장할 때 읽기 쉬운 정리본을 만들어 `elder_memos.tidy_text`·`source`에 따로 저장한다(원문 `text`는 그대로). 새 `ai/ManagerMemoAi`(기존 `LlmJson` 방식, 키 없으면 규칙 정리: 문장 단위로 나누고 같은 줄 제거, 최대 5줄, `source: ai|rule`). AI 결과에도 연락처 지우기를 다시 적용. 화면은 "봉사자에게 보내는 정보"(주의사항, `Elder.caution`)와 "담당자 메모"를 구역으로 나눴다. |
| C-12 | `/api/org/home`의 `counts.monthDone`(이번 달 완료 활동 건수) 추가, 홈 위쪽에 "이번 달 활동" 표시. 빠른 메뉴 4개를 명세 이름으로(대상 등록 · 공고 대신 등록 · 봉사 공고 찾기 · 단골에게 요청). |
| C-14 | 대상 상세 빠른 메뉴: 공고 대신 등록 · 봉사 공고에 요청 · 단골에게 요청 · 메모 쓰기(메모 입력 칸으로 이동) + "지난 요청 다시 올리기"(지난 공고를 고르고 **날짜를 골라** 올림). |
| C-16 | 새 `GET /api/org/change-report`(`service/ElderChangeService`, `ai/ElderChangeAi`): 대상마다 최근 30일 일지를 그 앞 30일과 비교해 변화·특이사항을 정리하고 살펴볼 순위를 준다. 횟수·날짜·점수·순위는 코드, AI는 일지 내용만 정리(키 없으면 규칙 문장), 진단 금지 문구·병명 지우기 적용. `?range=today`는 오늘 일지가 들어온 대상만. 월간 리포트(`LifeReportService`)도 지난달 일지를 `[지난달 기록]` 표시로 함께 줘서 내용 비교가 되게 했다. 홈 "살펴볼 대상"과 리포트 화면이 이 순위를 쓴다. |
| D-04 | `/api/admin/analytics`에 `areaShortage`(지역 × 봉사 유형 수요·공급·부족) 추가, 대시보드에 "지역별 부족한 도움 (요청 대비 부족 건수)" 카드. |
| D-10 | 이미 있던 나이대별 인기 봉사 카드 그대로(바꾼 것 없음). |
| D-11 | `/api/admin/analytics`에 `seasonSupplyDemand`(계절 × 봉사 유형별 봉사 요청·봉사 가능량, 최근 12개월) 추가, 대시보드에 계절 칩 + 막대 카드. |
| D-08 | `PUT /api/admin/inquiries/{id}/status`로 처리 단계만 따로 바꾼다(접수 · 확인 중 · 답변 완료 · 처리 완료). 바뀌면 문의한 사람에게 알림. 사용자 문의 내역(`common.jsx`)은 단계 이름만 바꿔 보여 준다. |
| B-64 · C-15 | 앱 안 신청·취소 주소, 정원, 신청자 목록을 없앴다. 소식을 올릴 때 기관 페이지 링크가 필수(`URL_REQUIRED`). 새 `GET /api/news`: 담당자가 올린 소식 + AI가 찾은 소식(서버가 출처 링크를 열어 확인한 것만) + 링크 있는 공공데이터 강좌를 한 목록으로. 화면은 카드마다 "담당자가 올림" / "AI가 찾음 · 출처 확인", 기관에 확인하라는 안내. 시드 소식도 모두 링크 방식으로 바꿨다. |
| A-04 | 담당자 마이·운영자 관리 메뉴에 "회원 탈퇴", `/my/withdraw` 경로를 모든 역할에 열었다. 탈퇴 확인에 담당 대상의 진행 건을 넣고, 담당 중인 대상이 남은 담당자와 마지막 운영자는 탈퇴 불가(409 `WITHDRAW_BLOCKED`). |
| C-03 (확인 → 고침) | 문제가 분명해 고쳤다: 이관 뒤에도 그 요청을 보냈던 예전 담당자가 요청을 보고 대리 입력할 수 있었다(`Views.roleOf`). 이제 독거노인 건의 담당자 권한은 지금 담당자만. |
| B-58 (담당자) | 담당자 마이에 "알림 설정" 메뉴 → `/my/noti`. |

## 2. 새 API · 바뀐 API

- `POST /api/orgs/apply` (로그인 불필요)
  - multipart: `name, orgType, address, phone, managerName, managerPhone, managerEmail, memo` + 파일 `proof`(필수, jpg·png·pdf 등) → `{id, status}`
  - JSON: 같은 칸 + `proofFileUrl`(`/api/files/…`, 실제 있는 파일) 필수. 없으면 400 `PROOF_REQUIRED`
- `GET /api/admin/orgs`: 항목에 `proofFileUrl`
- `POST /api/org/elders`: 본문에 `consent`(true 필수, 아니면 400 `CONSENT_REQUIRED`). 대상 응답에 `serviceConsentAt`
- `POST /api/org/elders/{id}/memos`, `GET /api/org/elders/{id}`의 `memos[]`: `{id, elderId, text(원문), tidy, lines[], source, createdAt}`. 1000자 넘으면 400 `TEXT_TOO_LONG`
- `GET /api/org/home`: `counts.monthDone`
- `GET /api/org/change-report?range=today|recent` (담당자) → `{date, range, windowDays, total, source, notice, items[{elderId, alias, displayName, dong, rank, score, level(HIGH|MID|LOW), reasons[], stats{recentCount, prevCount, todayCount, daysSinceLast, lastDate, signalCount, signals[], newSignals[], flagged}, summary, changes[], source, provider}]}`
- `GET /api/admin/analytics`: `areaShortage[{area, demand, supply, gap, shortTotal, short[], categories[]}]`, `seasonSupplyDemand[{season, demand, supply, gap, categories[{category, demand, supply, gap}]}]`
- `GET /api/admin/inquiries`: 항목에 `stage`. `PUT /api/admin/inquiries/{id}/status {status: RECEIVED|CHECKING|ANSWERED|CLOSED}`(답변 없이 ANSWERED는 400 `ANSWER_REQUIRED`). `GET /api/inquiries/mine` 항목에도 `stage`(예전 문의는 null → `status`로 봄). 대시보드 `pending.inquiries`는 접수·확인 중만 센다
- `GET /api/news?area=` → `{items[{key, kind(ORG|AI|PUBLIC), id, title, orgName, date, startHour, when, place, area, near, url, image, aiFound, notice}], area, searchAvailable, aiNotice}`
- `POST /api/events`: `url` 필수, `capacity`·`applyType`은 받지 않음. 소식 응답에서 `capacity, applied, myApplied, full, applicants` 삭제(`applyType`은 항상 `"LINK"`)
- 삭제: `POST /api/events/{id}/apply`, `POST /api/events/{id}/cancel`
- `GET /api/me/withdraw-check`, `POST /api/me/withdraw`: 담당자·운영자 조건 추가(위 A-04)
- **기대하는 다시 올리기 API(공고 담당)**: 지금은 화면이 `POST /api/posts`에 지난 공고 내용 + 고른 `date`를 보낸다(`{type, date, startHour, endHour, area, categories, title, detail, note, estHours, elderId}`). 공고 담당이 `POST /api/posts/{id}/repost {date}` 같은 전용 주소를 만들면 `org.jsx`의 `RepostSheet`에서 호출 한 줄만 바꾸면 된다(응답은 새 공고 또는 새 공고 배열).

## 3. DB 변경 (모두 null 허용, `ddl-auto: update`로 생김)

| 표 | 칸 | 뜻 |
|---|---|---|
| `organizations` | `proof_file_url` varchar(200) | 재직 증빙 파일 주소 |
| `elders` | `service_consent_at` datetime, `service_consent_by` bigint | 서비스 이용 동의 확인 시각·확인한 담당자 |
| `elder_memos` | `tidy_text` varchar(1000), `source` varchar(5) | 메모 정리본, ai/rule |
| `inquiries` | `stage` varchar(10) | 처리 단계(RECEIVED/CHECKING/ANSWERED/CLOSED) |

- `inquiries.status` enum에 값을 늘리지 않고 글자 칸 `stage`를 따로 뒀다(이미 만들어진 DB의 enum 검사 제약이 `update`로는 안 바뀔 수 있어서).
- `event_applications` 표와 `org_events.capacity`·`apply_type` 칸은 남아 있지만 쓰지 않는다(새 소식은 capacity 0, apply_type LINK).
- 변화 리포트는 새 표 없이 계산한다(AI 글은 서버 메모리에만 잠깐 보관).

## 4. 못 한 것 · 남은 것

- 동네 기관 소식 위쪽 문구: 프로토타입은 "접속 위치 기준"인데 목록은 프로필 동네 기준이라 "내 동네 기준"으로 적었다. 위치 권한 흐름(B-59)은 위치 담당 범위. "접속 위치로 다시 찾기" 버튼은 누를 때만 위치를 쓴다.
- 알림 설정 메뉴는 `/my/noti`가 담당자에게 열려야 동작한다(계정 담당이 경로 권한을 여는 것을 기다림). 지금 가지에서는 누르면 첫 화면으로 돌아간다.
- 봉사 인증 항목 화면은 세 줄만 보이게 화면에서 걸렀다. 서버 기본값(`PlannerController.VERIFY_DEFAULT`의 `edu`·`orientation`)은 인증 담당 범위라 그대로다.
- 운영자 대시보드 응답의 `edu`(교육 수료 수)와 회원 상세의 `eduDone` 값은 서버에 남아 있다(화면에서만 뺐다, B-54 담당 범위).
- 플래너(`PlannerService`)·홈(`home.jsx`)의 "신청한 기관 소식" 표시는 `myApplied`가 없어져 아무것도 안 나온다. 코드 정리는 플래너 담당 몫.
- `DataSeeder`의 `eventApps` 필드는 쓰이지 않지만 남겨 뒀다.
- 로그인 없이 파일이 딸린 기관 신청을 받으므로 횟수 제한은 없다(파일 종류·10MB 제한만). 필요하면 따로 정해야 한다.
- 변화 리포트 AI는 실제 키로 불러 보지 않았다(규칙 모드로만 확인).

## 5. 범위 밖에서 고친 파일

- `web/AuthController.java`: 기관 신청(증빙), 탈퇴 조건
- `service/Views.java`: `roleOf` 한 줄(C-03)
- `service/PublicClassService.java`: `newsItems` 추가, 기관 소식 옮겨 둘 때 신청 문구·정원 두 줄
- `service/DataSeeder.java`: `seedEvents`만
- `frontend/src/App.jsx`: `/my/withdraw` 경로 권한 한 줄
- `frontend/src/ui/Frame.jsx`: 담당자 탭 이름 한 곳
- `frontend/src/screens/common.jsx`: 문의 단계 이름표(`INQ_ST`)와 표시 한 줄
- `backend/src/test/.../MeetingChangesTest.java`: 소식 신청 테스트를 41판에 맞게 바꿈

## 6. 프로토타입 대조 (`proto-2026-11-12-명세41판.html`)

| 번호 | 맞춘 것 | 못 맞춘 것 · 다르게 한 것 |
|---|---|---|
| 5 동네 기관 소식 | "📍 ○○ 근처" 줄, 소식 검색 AI 안내 두 문장 그대로, 카드마다 "담당자가 올림"/"AI가 찾음 · 출처 확인", 카드 배치(기관 · 제목 · 일시·장소 · 링크 · [요약 보기][기관 페이지로 이동]), 아래 안내 문장 | "접속 위치 기준" → "내 동네 기준"(위 4절). 지역 칩과 공공데이터 강좌("공공데이터 · 출처 확인")는 프로토타입에 없지만 남겼다 |
| 17 담당자 탭 | 4번째 탭 "변화 리포트", 홈 "변화 리포트 ›" | — |
| 18 변화 리포트 | AI 안내 문장 그대로, 카드에 "n순위", "먼저 살펴보기"/"비슷해요", **변화**·**특이사항** 한 줄씩 | [최근 30일][오늘] 칩을 더했다(명세 "오늘·최근"). 기존 "최근 활동 기록(인수인계용)"·"지난 월간 리포트"는 아래에 남겼다 |
| 19 도움 대상 상세 | "봉사자에게 보내는 정보 · 메모와 따로 관리"(주의사항 + 추가 입력), "담당자 메모 · 담당자만 봐요 · ✨ AI 정리", "기록 정리 AI — 메모 n건을 날짜순으로 정리했어요" 목록과 아래 안내 문장, "변화 리포트 · 이전 상태와 비교" | 정리는 메모를 저장할 때 미리 해 두고 ✨ AI 정리를 누르면 펼쳐 보여 준다 |
| 20 운영자 대시보드 | "지역별 부족한 도움 (요청 대비 부족 건수)" 막대 카드를 시간 균형 위에, "리포트 만들기" 버튼 삭제("공동 적립" 카드는 원래 없음) | 지역별 부족 유형 태그, 계절별 카드(D-11)는 프로토타입에 없지만 명세라 넣었다. "스케줄러 지금 실행(시연)" 버튼은 남겼다. 리포트 만들기 API(`/api/admin/reports/generate`)는 남아 있다 |
| 21 봉사 인증 항목 | 세 줄(휴대폰 본인인증 · 신분증 인증 · 범죄경력 회보서)과 문구, 관리 메뉴 "신분증 · 범죄경력(선택)", 회원 상세 "신분증 · 범죄경력 회보서" | 서버 기본값은 그대로(위 4절) |

## 7. 확인 방법

- 자동: `backend/src/test/java/com/timesavings/Req41OrgTest.java`(9건: C-01, C-02, C-13, C-12, C-16, D-04·D-11, D-08, B-64, A-04·C-03), `MeetingChangesTest.기관_소식은_링크가_있어야_하고_앱_신청은_없다`
- 손으로: ① 로그인 화면 → 기관 서비스 신청 → 파일 없이 신청하면 막히는지, 파일을 올리면 운영자 승인 화면에서 열리는지 ② 담당자 → 도움 대상 등록에서 동의 체크 ③ 대상 상세에서 메모 추가 → ✨ AI 정리, 주의사항 추가, 지난 요청 다시 올리기 ④ 담당자 탭 "변화 리포트"의 순위·오늘 칩 ⑤ 운영자 대시보드의 지역별·계절별 카드, 1:1 문의의 상태 칩 ⑥ 사용자 → 동네 기관 소식 목록과 기관 페이지 이동
