# 기능명세서 적용 — 진행·일지·평가·단골·시간 통장 (act)

- 범위: 기능 U-14 ~ U-25, U-29 (기능명세서 ID. 요구사항 명세서 41판의 ID와 다른 체계)
- 가지: `func-act` · 작업일 2026-11-12
- 계약서(`docs/design/contract.md`)와 `db/schema.sql`은 고치지 않았다. 새·바뀐 API와 DB 변경은 아래 ②·③에 적었으니 합칠 때 옮겨 적어야 한다.

## ① 기능 ID별 대조

| 기능 | 우선 | 적용 전 | 지금 | 한 일 · 근거(파일:줄) |
|---|---|---|---|---|
| U-14 진행 화면 | 상 | 대부분 충족 | 충족 | 단계 표시 `frontend/src/ui/bits.jsx:41`(요청·수락·확정·활동 중·일지·완료), 일정·메시지·취소는 `screens/request.jsx` `RequestDetail`. **모자란 것 1건 고침**: 봉사자가 1차 수락한 뒤(최종 수락 전) 화면에 취소 버튼이 없었음 → "수락 취소" 추가(`screens/request.jsx:503`, 서버는 원래 허용) |
| U-15 QR 시작·종료 | 상 | 충족 | 충족(안 고침) | 봉사자가 받는 분 QR을 찍고, 안 되면 6자리 번호: `screens/request.jsx:239`(`ScanSheet`), 서버 `service/RequestService.java:427`(`verifyCode`, 번호 없이 기록하는 길 없음) |
| U-16 사진 인증 | 중 | 없음(사진은 모두 선택) | **새로 만듦** | 장보기·심부름 = 영수증 1장, 가벼운 집안일 = 전·후 2장이 없으면 제출 불가(`PHOTO_REQUIRED`): `RequestService.java:492`(`photoRule`), `:518`·`:520`. 직접 올린 파일만(`web/RequestController.java` `log`), 두 번째 사진도 같은 열람 권한(`service/FileAccessService.java:65`). 화면 `screens/request.jsx:691`(필수 표시·얼굴/개인정보 안내·없으면 제출 막힘), 일지에 사진 표시(`LogPhotos`) |
| U-17 봉사 일지 | 상 | 충족 | 충족(안 고침) | 한 줄 기록 → AI 다듬기(`screens/request.jsx:723`, `/api/ai/log-tidy`, 키 없으면 규칙) → 받는 분·담당자 확인 → 적립(`RequestService.java:547` `settle`) |
| U-18 평가 | 중 | 부분(봉사자에게 "새 평가를 받았어요 (★n)" 알림으로 개별 별점이 보였음) | **고침** | 알림에서 별점을 뺌(`RequestService.java:631`). 개별 평가를 상대에게 주는 API는 없음(확인). 평가 화면에 "상대에게 공개되지 않아요" 안내를 양쪽 모두에(`screens/request.jsx:777`) |
| U-19 단골 자동 지정 | 중 | 부분(받는 쪽에서만 단골로 보였고, 봉사자는 마이의 단골 관리 시트에서만 확인) | **고침** | 완료 3회째에 **양쪽 모두** 알림(`RequestService.java:569` `regularNotice`). 봉사자 화면에도 단골 표시: 요청 상세 상대 카드 "★ 서로 단골", 단골 화면에 "내가 도운 단골" 목록(`screens/request.jsx:200`) |
| U-20 단골 직접 지정·요청 | 중 | 충족 | 충족(작게 고침) | 등록·해제 `ui/extras.jsx:60`, `web/NeighborController.java:56`. 공고 없이 바로 요청 `RequestService.java:131`(kind REGULAR). 단골 화면에서 자동(3회) 단골도 바로 해제할 수 있게 버튼 조건만 넓힘 |
| U-21 잔고 | 상 | 부분(묶인 시간이 0보다 클 때만 작은 글씨 "보류") | **고침** | 쓸 수 있는 시간(통장 위) + 묶인 시간 · 누적 번 시간 · 누적 쓴 시간 3칸을 늘 보임(`screens/bank.jsx:137`) |
| U-22 사용 내역 | 상 | 부분(날짜별 목록, 월별 합계는 최근 6개월 막대뿐, 계산식에 등급 이름·결과 없음) | **고침** | 내역을 달마다 묶고 달 머리에 번 시간·쓴 시간 합계(`screens/bank.jsx:107`, 서버 `service/BankService.java:110` `monthsAll`). 건별 계산식 "1.0시간 × 수고 1.2 = 1.0시간" |
| U-23 시간 선물 | 중 | 충족 | 충족(안 고침) | 가족에게만: `web/BankController.java:71`. 매매·양도 불가 안내: `screens/bank.jsx:180`(선물 화면), `:98`(이용 규칙) |
| U-24 시간 플랜 ✨ | 상 | 부분(생애 플랜은 내가 고른 도움으로만 계산, 다른 사람의 사용 기록은 안 봄) | **새로 만듦** | `service/TimePlanService.java:103`(`peerPattern`), `:143`(`compute`), `ai/TimePlanAi.java`, `web/TimePlanController.java`, 화면 `screens/timeplan.jsx`(`/bank/time-plan`), 통장 화면에서 들어감. 기존 생애 플랜·내 봉사 플랜은 그대로 둠 |
| U-25 월별 리포트 ✨ | 중 | 부분(통장의 "이번 달 한 줄"은 적립·사용 시간만, 횟수·많이 한 도움·지난달 대비 없음) | **새로 만듦** | `TimePlanService.java:304`(`monthReport`), 화면 `screens/timeplan.jsx`(`/bank/report`, 지난달로 넘겨 보기) |
| U-29 내 활동 내역 | 상 | 충족 | 충족(안 고침) | `screens/bank.jsx:13`(`Act`): 진행 중·지난 활동, 모드(도움 받기/봉사하기)에 따라 받은/봉사한 내역 |

## ② 새·바뀐 API

| API | 내용 |
|---|---|
| `POST /api/requests/{id}/log` (바뀜) | 본문에 `photoUrl2`(집안일의 "한 뒤" 사진) 추가. 장보기·심부름은 `photoUrl`, 가벼운 집안일은 `photoUrl`+`photoUrl2`가 없으면 400 `PHOTO_REQUIRED`. 인증 사진은 촬영 동의 칸과 상관없이 저장. 남이 올린 파일이면 400 `FILE_NOT_MINE`(두 장 모두 검사). 고쳐서 다시 낼 때 사진을 안 보내면 먼저 올린 사진 유지 |
| `GET /api/requests/{id}` 등 요청 상세 응답 (바뀜) | `photoRule`(`NONE`·`RECEIPT`·`BEFORE_AFTER`), `logPhotoUrl2` 추가 |
| `GET /api/bank` (바뀜) | `months`: 거래가 있는 모든 달 `[{month, in(번 시간), out(쓴 시간), count}]` 최근 달부터. 보류·보류 해제는 뺌 |
| `GET /api/me/time-plan?age=&startAge=` (새) | 시간 플랜. `age`·`startAge`는 선택(바꿔 보기, 저장 안 함). 응답: `age, startAge, saveYears, balance, earnRate, earnMonths, useMonthly, useYearly, saved, nowYears, years, helpMonths, endAge, targetAge, needTotal, shortfall, goalMonthly, goalExtra, onTrack, rows[{category, hoursPerMonth, weight, costPerMonth, totalHours}], pattern{basis: SAME_AGE·SENIOR·DEFAULT, label, peers, minPeers, usedDefault, windowMonths}, notice, text, goalText, source(ai·rule)`. 사용자 계정만 |
| `GET /api/me/month-report?month=YYYY-MM` (새) | 월별 리포트(비우면 이번 달). 응답: `month, prevMonth, isCurrent, give{count, hours, earned, byCategory, top, topCount}, receive{count, hours, used, …}, prev{give{count,hours}, receive{…}}, change{giveCount, receiveCount, giveHours, receiveHours}, text, source` |
| 알림 종류 | `REGULAR`(단골이 됐어요, 양쪽) 추가. `RATED` 제목이 "활동 평가가 등록됐어요"로 바뀜(별점 없음) |

계산 규칙(시간 플랜):
- 비슷한 사람들: ① 나와 같은 나이대 사용자 → ② 모자라면 60대 이상 사용자. 최근 12개월의 완료된 요청에서 사람마다 "카테고리별 받은 시간 ÷ 쓴 기간(달)"을 구해 사람 수로 평균. 기록이 있는 사람이 **5명 미만이면 일반적인 가정**(동행 월 2 · 장보기 월 2 · 말동무 월 1시간)을 쓰고 `notice`에 "아직 기록이 적어 일반적인 가정으로 계산했어요". 본인 기록·독거노인(계정 없음) 기록은 넣지 않고, 응답에는 평균과 사람 수만 나간다.
- 적립 속도: 최근 6개월(적립을 시작한 지 6개월이 안 됐으면 그 기간) 동안 직접 번 시간의 한 달 평균. 선물받은 시간은 빼고 셈.
- 한 달 사용량 = Σ 카테고리 시간 × 가중치(1.0/1.2/1.5). 모이는 시간 = 잔액 + 적립 속도 × 12 × (도움 받기 시작 나이 − 지금 나이). 받을 수 있는 기간 = 모이는 시간 ÷ 한 해 사용량(95세까지만).
- 적립 목표 = 90세까지 받는 데 필요한 시간을 남은 기간에 나눈 한 달 시간(0.5 단위 올림).
- 나이: 생애 플랜에 저장한 나이 → 나이대에서 어림(예: 70대 → 75세) → 50세. 도움 받기 시작: 생애 플랜 값 → 65세 이상이면 지금 → 70세.
- AI는 설명 문장과 적립 목표 문구만 쓰고, 계산 결과에 없는 숫자가 섞이면 규칙 문장으로 바꾼다. 키가 없으면 규칙 문장(`source: rule`).

## ③ DB 변경

| 표 | 칸 | 설명 |
|---|---|---|
| `requests` | `log_photo_url2` varchar(200), null 허용 | 기능 U-16: 집안일의 "한 뒤" 사진. `log_photo_url`은 영수증 또는 "하기 전" 사진으로 함께 쓴다 |

`ddl-auto: update`로 생긴다. 새 표는 없다.

## ④ 못 한 것 · 남은 것 · 모의 상태

- **사진 내용은 검사하지 않는다**: 올린 사진이 정말 영수증·전후 사진인지, 얼굴·개인정보가 들어 있는지는 확인하지 않고 안내 문구만 있다(자동 판별·가리기 없음).
- **시간 플랜 시연 자료**: "같은 나이대 5명 이상" 길이 보이도록 70대 이웃 5명(nb1~nb5)과 최근 석 달 기록 35건을 `service/FuncActSeeder.java`로 넣는다(새 DB에만, 기존 시연 계정의 통장·기록은 그대로). 운영자 화면의 회원·활동 수가 그만큼 늘어난다.
- 시간 플랜의 "비슷한 사람들"은 나이대로만 묶는다(지역·건강 상태 등은 보지 않음). 의료·건강 판단은 하지 않는다.
- 월별 리포트는 활동 날짜 기준으로 완료된 활동만 센다. 이번 달은 아직 안 끝난 달이라 지난달보다 적게 나올 수 있다(화면 아래에 기준을 적음).
- U-18: 여러 평가를 모은 평균 별점과 자주 받은 칭찬(프로필·소개 카드)은 그대로 보인다. "공개되지 않음"은 개별 평가(누가 몇 점·무슨 말)를 가리키는 것으로 보았다. 평균까지 숨겨야 하면 따로 정해야 한다.
- 계약서·`db/schema.sql`·README에는 반영하지 않았다(지시에 따라). 합칠 때 ②·③을 옮겨야 한다.
- 실제 AI(키 있음)로는 확인하지 않았다. 규칙 모드로만 확인했다.

## ⑤ 범위 밖에서 고친 파일

- `frontend/src/App.jsx`: import 1줄, 경로 2줄(`/bank/time-plan`, `/bank/report`) 추가.
- `backend/src/main/java/com/timesavings/service/RequestService.java`(함께 쓰는 파일): 일지 사진 규칙, 단골 알림, 평가 알림 문구 — 줄 추가 위주.
- `backend/src/main/java/com/timesavings/service/FileAccessService.java`: 한 줄(두 번째 사진도 같은 권한).
- `backend/src/test/java/com/timesavings/FlowIntegrationTest.java`: 장보기 일지 제출에 영수증 사진 주소를 넣음(새 규칙 때문에 한 줄).
- `Views.java`, `DataSeeder.java`, `home.jsx`, `Frame.jsx`, `AdminController.java`는 고치지 않았다.

## ⑥ 확인 방법

- 서버 테스트: `cd backend && sh ./mvnw -q test` — `FuncActTest` 11건 포함 전체 통과.
- 화면(시연 자료, 비밀번호 1234):
  1. 봉사자로 가벼운 집안일(또는 장보기) 활동을 종료까지 진행 → "활동 일지 쓰기": 사진 칸이 필수로 나오고, 없이 제출하면 막힌다. 두 장 올리고 제출 → 받는 분 화면의 일지에 "하기 전 / 한 뒤" 사진.
  2. 받는 분이 "확인했어요" → 평가 화면에 "공개되지 않아요" 안내. 봉사자 알림은 "활동 평가가 등록됐어요"(별점 없음).
  3. `yujin` 시간 통장: 묶인 시간·누적 번/쓴 시간, 내역의 달 머리 합계와 계산식. "시간 플랜"(60대 이상 이웃 기록), "월별 리포트"(‹ 로 지난달).
  4. `bok` 시간 통장 → 시간 플랜: "같은 70대 이웃 5명" 기록으로 계산.
  5. `yujin` `/regular`: 내 단골과 "내가 도운 단골".
