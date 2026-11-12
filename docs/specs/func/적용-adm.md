# 기능명세서 적용 — 운영자 (기능 A-01 ~ A-17)

가지 `func-adm`. 기능 ID는 `docs/requirements/기능명세서.txt` 기준이다(요구사항 명세서 41판의 A-01… 과 다른 체계).
`docs/design/contract.md`, `db/schema.sql` 은 고치지 않았다. 합칠 때 아래 ②·③을 옮겨 적으면 된다.

## ① 기능 ID별 대조

| 기능 | 적용 전 | 지금 | 근거 |
|---|---|---|---|
| A-01 운영 대시보드 | 충족 | 충족(리포트 진입 줄만 추가) | 이번 달 활동·매칭률·처리할 일 `web/AdminController.java:75-139`, 화면 `screens/admin.jsx` `ADash`(KPI·처리할 일·시간 균형 카드) |
| A-02 봉사 유형 리포트 ✨ | 일부(카테고리별 수요·공급 건수만. 주는 쪽·받는 쪽 사람 비율, 사용자 유형 분포, 해석 없음) | 새로 만듦 | `service/AdminReportService.java` `types()`, `ai/ForecastAi.java` `interpret()`, 화면 `screens/adminReports.jsx` `AStats` |
| A-03 나이별 인기 봉사 | 충족 | 충족(안 건드림) | `service/AnalyticsService.java:163-216`(byRecipientAge·byVolunteerAge 상위 3), 화면 `admin.jsx` `AgeCard` |
| A-04 계절별 봉사 리포트 ✨ | 일부(계절·월별 변화는 있음. 다음 계절 예상 수요·해석 없음) | 고침 | `AdminReportService.nextSeason()`(코드 계산)·`season()`, 화면 `AStats` |
| A-05 시간 은행 재정 추계 ✨ | 없음 | 새로 만듦 | `service/FinanceModel.java`(코호트 모형), `service/FinanceForecastService.java`(입력 뽑기·월간 요약 저장), `ForecastAi.riskSummary()`, 화면 `AForecast` |
| A-06 정책 시뮬레이션 ✨ | 없음 | 새로 만듦 | `ForecastAi.parsePolicy()`·`parseRule()`·`fromJson()`, `FinanceForecastService.simulate()`, 화면 `ASim` |
| A-07 대응 추천 ✨ | 없음(부족 이유 짐작만 있음) | 새로 만듦 | `AdminReportService.recruit()`·`ruleDraft()`, `ForecastAi.recruit()`, 화면 `ARecruit`(복사·공지로 올리기) |
| A-08 특보 알림 리포트 ✨ | 없음 | 새로 만듦 | `service/WeatherProvider.java`, `service/KmaWeatherProvider.java`, `service/WeatherAlertService.java`, `domain/WeatherAlert.java`, 화면 `AWeather` |
| A-09 특보 전달 | 없음 | 새로 만듦 | `WeatherAlertService.send()`(공지 게시 + 해당 지역 담당자 알림, 한 번만), 화면 `AWeather`의 "공지 게시 · 담당자 알림 보내기"(확인 창) |
| A-10 기관 신청 승인 | 충족 | 충족(안 건드림) | `AdminController.java:148-172`, 화면 `admin.jsx` `AApprove`(재직 증빙 열어 보기·승인·반려) |
| A-11 담당자 계정 승인 | 없음(신청 자체가 없었음) | 화면만 만듦 | 화면 `adminReports.jsx` `AMgrApps`(`GET /api/admin/manager-applications` 가 없거나 실패하면 빈 목록, 승인 취소 = 기존 `POST /api/admin/managers/{id}/suspend`). 서버 목록 API는 계정 담당 몫 |
| A-12 봉사자 신원 확인 | 충족 | 충족(안 건드림) | `AdminController.java:225-244`, 화면 `AApprove` 봉사자 신원 탭(제출 파일 보기·승인·반려) |
| A-13 신고 관리 | 일부(신고 내용·경고·정지·기각·퇴출은 있음. 관련 기록 묶음 없음) | 고침 | `web/AdminReportController.java` `reportContext()`, 화면 `ReportContext`(신고 처리 시트 안). 퇴출은 그대로 둠 |
| A-14 문의 관리 | 충족 | 충족(안 건드림) | `AdminController.java:442-480`, 화면 `AInquiries` |
| A-15 카테고리 건의 처리 | 다름(답변만 하고 카테고리는 바뀌지 않음) | 고침 | `service/CategoryRegistry.java`, `Rules.java`(더한 카테고리 등록), `AdminReportController.adopt()`, `PlannerController.answer()`(반영이면 추가), 화면 `SuggestAnswer`(등급 골라 승인), `lib/cats.js` |
| A-16 공지사항 관리 | 일부(등록·수정·삭제. 고정 없음) | 고침 | `domain/Notice.java` `pinned`, `AdminReportController.pin()`, `CommonController.notices()`(고정 먼저), 화면 `ANotices`·`common.jsx` `Notice` |
| A-17 공공 서비스 정보 관리 | 충족 | 충족(안 건드림) | `AdminController.java:503-528`, 화면 `APub` |

## ② 새·바뀐 API (모두 `/api/admin/**` 는 운영자만)

| 방식·주소 | 설명 |
|---|---|
| `GET /api/admin/forecast` | A-05. `{baseYear, offsets[0,1,3,5,10], areas[{area, risk, rows[{offset, year, supply, demand, gap, ratio, status, members, elders, seniorShare}], reasons[], joinsPerMonth, youngJoinsPerMonth, churnYear}], total[rows], assumptions{bands[{band, users, elders, supplyPer, supplySource(기록\|가정), demandPer, demandSource}], joinsPerMonth, joinsSource, churnYear, churnSource, notes[]}}` |
| `POST /api/admin/forecast/summary?refresh=` | A-05 이번 달 요약. 저장된 것이 있으면 그대로, 없거나 refresh=true 면 다시 만들어 저장. `{month, headline, areas[{area, risk, reasons[], text}], total, source, savedAt}` |
| `GET /api/admin/forecast/summaries` | 저장된 월간 요약 목록(최근 달 먼저) |
| `POST /api/admin/forecast/simulate` | A-06. 본문 `{text}` 또는 `{adjustments:[{type, ageBand, area, value}]}`. `type` = `JOIN_ADD`(월 가입 ±명)·`CHURN_MULT`(이탈률 ×배)·`SUPPLY_ADD`·`DEMAND_ADD`(1인당 월 시간 ±). 응답 `{adjustments[{…, label}], understood, source(ai\|rule\|manual), before, after(알아들었을 때만), areas, bands, assumptions}` |
| `GET /api/admin/stats/types` | A-02. `{categories[{category, givers, receivers, giverPct, receiverPct, count, hours}], userTypes[{key, label, count, pct}], members, elders, interpretation{text, source}}` |
| `GET /api/admin/stats/season` | A-04. `{monthly[12], seasons[4], nextSeason{season, growth, basis, categories[{category, base, expected}], total}, interpretation{text, source}}` |
| `GET /api/admin/recruit-drafts` | A-07. `{drafts[{area, category, timeSlot, demand, supply, gap, title, body}], source, days}` (부족 큰 순 5개) |
| `GET /api/admin/weather/alerts` | A-08. `{live, types, levels, knownAreas, alerts[{id, type, level, areas, startDate, endDate, source(MANUAL\|SAMPLE\|KMA), facts{areas[{area, elders, elders80, activities, managers[]}], totalElders, …}, report{summary, actions[], source}, noticeId, sentAt, sentManagers}]}` |
| `POST /api/admin/weather/alerts` | 특보 직접 입력 `{type, level, areas[], startDate?, endDate?, memo?}` |
| `POST /api/admin/weather/alerts/sample` | 시연용 표본 특보(계절에 맞는 종류, 관리 대상 많은 동네 두 곳) |
| `POST /api/admin/weather/fetch` | 기상청에서 받아 오기(키 없으면 `{live:false}`) |
| `POST /api/admin/weather/alerts/{id}/refresh` · `DELETE …/{id}` | 리포트 문장 다시 만들기 · 삭제(전달한 것은 409) |
| `POST /api/admin/weather/alerts/{id}/send` | A-09. 공지 게시 + 해당 지역 담당자 알림(type `WEATHER`, 링크 `/notice/{id}`). 두 번째는 409 `ALREADY_SENT` |
| `GET /api/admin/report-context/{id}` | A-13. 대상 계정의 경고·패널티·상태, 지난 신고, 관련 활동, 그 활동의 최근 메시지 10개, 두 사람이 함께 끝낸 활동 수 |
| `POST /api/admin/notices/{id}/pin` | A-16. `{pinned}`(없으면 뒤집기). `GET /api/notices` 는 고정 공지를 먼저 준다(응답에 `pinned` 추가) |
| `GET /api/categories` | A-15. 로그인한 누구나. 기본 6종 + 더한 카테고리 `[{name, level, levelLabel, weight, extra}]` |
| `POST /api/admin/category-suggestions/{id}/adopt` | A-15. `{level?, answer?}` 같은 이름의 건의를 모두 반영으로 바꾸고 카테고리에 더하고 건의자에게 알림 |
| `DELETE /api/admin/categories/{name}` | 더한 카테고리 빼기(그 종류의 공고·요청이 있으면 409) |
| (바뀜) `POST /api/admin/category-suggestions/{id}/answer` | 상태가 `ADOPTED` 면 기본 등급으로 카테고리에 더한다. 건의할 때 이미 더해진 이름도 `CATEGORY_EXISTS` |

## ③ DB 변경 (`ddl-auto: update` 로 생김)

- 새 표 `weather_alerts`: id, type(10), level(10), areas(300, 쉼표 목록), start_date, end_date, source(10), memo(300), report_json(3000), notice_id, sent_at, sent_managers, created_at
- `notices.pinned` (boolean, null 허용)
- `ai_results`: 새 kind `FIN_MONTHLY`(ref_key = `연-월`) — 표는 그대로
- `app_settings`: 새 키 `categories.extra`(JSON `{이름: 등급}`) — 표는 그대로

## ④ 못 한 것 · 남은 것 · 모의 상태

- **A-08 기상청 실제 호출은 키 없이 검증하지 못했다.** `KmaWeatherProvider.current()` 는 환경변수 `KMA_API_KEY` 가 있을 때만 기상청 API허브 특보 현황 주소(`KMA_API_URL`, 기본값 있음)를 부르고, 응답 줄에서 구역 이름(`KMA_REGION`, 기본 "서울")·특보 종류·수준 낱말을 찾는다. 응답 형식은 짐작이라 키를 넣은 뒤 `parse()` 를 실제 응답에 맞춰야 한다. 서비스의 동네는 모두 그 구역 하나에 있다고 본다. 키가 없으면 직접 입력·표본(실제 기상 정보 아님)만 쓴다.
- **A-11** 서버 목록 API(`GET /api/admin/manager-applications`)는 계정 담당 몫이라 이 가지에는 없다. 화면은 응답이 배열이거나 `{items}`/`{applications}` 일 때를 받고, 칸 이름은 `userId|managerId`, `name|managerName`, `orgName`, `loginId`, `status`, `createdAt|appliedAt`, `orgApproved`, `phoneVerified` 를 본다. 합칠 때 맞춰야 한다.
- **A-05 모형은 단순 참고용이다.** 시연 자료는 회원이 8명뿐이라 대부분 나이대가 "가정" 값으로 계산된다. 이탈률은 탈퇴 날짜 기록이 없어 "가입 90일이 넘은 회원 중 탈퇴했거나 최근 90일 활동이 없는 비율"로 대신한다. 관리 대상은 수를 그대로 두고 나이만 올린다. 사망·전출, 동네 사이 이동은 없다.
- **A-06** `CHURN_MULT`·`JOIN_ADD` 가 음수가 되지 않게 자르고 값 범위를 제한한다(가입 ±1000명, 배수 0~5, 시간 ±20). 규칙 해석은 가입 수·이탈률(절반·배·%)·봉사/도움 시간 문장만 알아듣는다.
- **A-15** 더한 카테고리는 공고·요청·등급(가중치)에는 바로 적용되지만, 운영자 통계(카테고리별 수요·공급, 계절 리포트, 예상 수요)와 AI 난이도·글 검사 규칙의 낱말 사전에는 들어가지 않는다(기본 6종만 집계). 아이콘·색은 기본 모양을 쓴다. 화면은 로그인할 때 `GET /api/categories` 로 읽어 붙인다.
- **A-07** 시간대는 요청이 가장 많은 "평일/주말 × 오전/오후/저녁" 한 칸만 쓴다.
- 문자·푸시 같은 바깥 발송은 없다. 특보 전달은 앱 안 알림과 공지뿐이다.
- ✨ 기능은 모두 키 없이 규칙 모드로 확인했다. 실제 LLM 호출 경로는 형식 검사(`ForecastAi.fromJson`·`valid`) 단위 테스트만 했고 실제 호출은 하지 않았다.

## ⑤ 확인 방법

- 테스트: `cd backend && sh ./mvnw -q test` (새 테스트 `FuncAdminTest` 16건, 전체 228건 통과)
- 화면(운영자 `admin`/`1234`): 대시보드 위쪽 리포트 메뉴 또는 관리 탭
  - `/a/forecast` 이번 달 요약 · 연도별 수요·공급(동네 칩) · 동네별 위험도 · 계산에 쓴 값(기록/가정)
  - `/a/sim` 예시 문장 누르고 "다시 계산" → 전후 선·막대 그래프와 표. "날씨가 좋으면"처럼 못 알아듣는 문장 → 조정 값 직접 넣기
  - `/a/stats` 봉사 유형(주는 쪽·받는 쪽, 사용자 유형, 해석) · 계절별(월별, 다음 계절 예상, 해석)
  - `/a/recruit` 모집 문구 초안 → 복사 / 공지로 올리기
  - `/a/weather` 표본 특보 불러오기 또는 직접 입력 → 리포트 → "공지 게시 · 담당자 알림 보내기" → 담당자(`org1`) 알림과 공지 확인
  - `/a/notices` 고정 · `/a/suggest` 반영(등급 고르기) → 글쓰기 화면 카테고리에 추가됨 · `/a/safety` 신고 누르면 "관련 기록" · `/a/mgr-apps`

## 범위 밖에서 고친 파일 (모두 줄 추가 위주)

- `service/Rules.java`: 더한 카테고리 등록(`putExtraCategory` 등)과 `isCategory`·`categoryLevel` 이 그것도 보게 함
- `web/PlannerController.java`: 건의 답변이 반영이면 카테고리 추가(필드 주입 1개, 3줄), 안내 문장 1줄
- `web/CommonController.java`: 공지 목록 고정 먼저(정렬 1줄)
- `domain/Notice.java`: `pinned` 칸
- `frontend/src/App.jsx`: 경로 6줄 · 가져오기 1줄
- `frontend/src/ui/ctx.jsx`: 로그인 정보 읽을 때 `loadCats()` 1줄
- `frontend/src/screens/common.jsx`: 공지 목록의 "고정" 표시
- `web/AdminController.java`, `service/DataSeeder.java`, `Views.java`, `RequestService.java`, `Frame.jsx`, `home.jsx` 는 고치지 않았다.
