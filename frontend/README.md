# 시간 적금 — 프론트엔드

React 18 + Vite 5 + react-router-dom 6 (JavaScript). UI 라이브러리 없이 채택 프로토타입(`docs/prototypes/adopted-황수빈-첫활동교육-동네클래스.html`)의 CSS를 `src/styles/proto.css`로 그대로 옮겼고, 프로토타입에 없는 것만 `src/styles/app.css`에 더했다.

## 실행

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173  (/api → http://localhost:8080 프록시)
npm run build      # 결과물 frontend/dist
```

백엔드(8080)가 먼저 떠 있어야 한다. 데모 계정(비밀번호 모두 `1234`): 사용자 `yujin`, 담당자 `org1`, 운영자 `admin`. 로그인 화면의 빠른 로그인 버튼이나 헤더의 역할 칩(다른 데모 계정으로 로그인)으로 바꿀 수 있다.

## 구조

| 파일 | 내용 |
|---|---|
| `src/api.js` | fetch 래퍼 한 곳. 토큰은 localStorage(`ts-token`), `Authorization: Bearer`, 오류 응답의 `error` 문장을 토스트로 |
| `src/ui/ctx.jsx` | 토스트·바텀시트·확인 시트(`useUi`), 로그인 사용자·역할(`useAuth`), 큰 글씨 |
| `src/ui/Frame.jsx` | 화면 틀(헤더·뒤로·알림 벨·역할 칩·역할별 아래 탭), 데모 계정 시트 |
| `src/ui/bits.jsx` | 프로토타입 도우미를 컴포넌트로: 카테고리 아이콘·칩, 시간 막대, 상태 태그, 진행 단계, 공고 카드, 요청 줄, AI 로딩(aiRun), 규칙 모드 표시, QR(`qrcode` 패키지) |
| `src/ui/icons.js` | 프로토타입 아이콘 `I`, `ic()`, 로고 그대로 |
| `src/lib/format.js` | 카테고리·가중치·시간 표시(`hh`, `ap`, `fmt`), 날짜 표시, 상태 이름 |
| `src/screens/*.jsx` | 화면 |

- AI 응답에 `source: "rule"`이 오면 작은 글씨로 "AI 키 없음 · 규칙 모드"를 보여 준다.
- 음성: `SpeechRecognition`(ko-KR, 중간 결과 표시)이 있으면 실제로 받아쓰고, 없거나 마이크 권한이 꺼져 있으면 안내 + 글 입력으로 같은 흐름. 읽어 주기는 `speechSynthesis`(ko-KR, 속도 0.9), 끄기 토글(localStorage `ts-tts`).
- 큰 글씨: 마이 › 큰 글씨로 보기(프로토타입 `html.big`, localStorage `tb-big`).

## 화면 ↔ 라우트 ↔ 요구사항

| 프로토타입 화면 | 라우트 | 쓰는 API | 요구사항 |
|---|---|---|---|
| splash | `/` | `GET /me` | — |
| login (온보딩) | `/login` | — | A-02 |
| loginForm | `/login/form` | `POST /auth/login` | A-02 |
| signup | `/signup` | `POST /auth/phone/send·verify`, `POST /auth/signup` | A-01 |
| (참고안) 비밀번호 찾기 | `/pw-reset` | `POST /auth/phone/*`, `POST /auth/password/reset` | A-03 |
| orgApply | `/org-apply` | `POST /files`, `POST /orgs/apply` | C-01 |
| notif | `/notif` | `GET /notifications`, `POST /notifications/{id}/read`, `read-all`, `unread-count` | A-07, C-08 |
| notice | `/notice`, `/notice/:id` | `GET /notices`, `GET /notices/{id}` | A-10 |
| qna (문의·신고 내역) | `/qna` | `GET /inquiries/mine`, `GET /reports/mine` | B-37, B-47, B-49 |
| qnaNew | `/qna/new` | `POST /inquiries` | B-48 |
| 신고 시트 | (시트) | `POST /reports` | B-36 |
| u_home | `/u/home` | `GET /me`, `/bank`, `/requests?box=all`, `/posts?type=OFFER`, `/classes`, `/edu` | A-06, B-46 |
| **voice_req (추가 요구(사용자 요청): 음성 요청서)** | `/voice`, `/voice?elder=` | `POST /ai/post-chat` {type:'NEED', inputMode:'voice'}, `POST /posts`, `GET /org/elders/{id}` | B-02, B-03, B-05, B-09, B-42, C-04, P-01 |
| board | `/board?type=OFFER\|NEED`, `/board?elder=` | `GET /posts?type&area&date&cat&sort` | B-20, C-05 |
| post | `/post/:id` | `GET /posts/{id}`, `DELETE /posts/{id}`, `POST /posts`(다시 올리기), `GET /users/{id}/profile` | B-11, B-12, B-13, B-20 |
| (공고 수정) | `/post/:id/edit` | `PUT /posts/{id}` | B-11 |
| req_form | `/req/new?post=\|to=&elder=` | `GET /posts/{id}`, `POST /ai/filter`, `POST /ai/difficulty`, `POST /requests` | B-08, B-09, B-10, B-14~16, B-22, C-05, C-06 |
| regular | `/regular`, `/regular?elder=` | `GET /regulars`, `GET /regulars?elderId=` | B-14, B-41, C-06 |
| r_detail · q_detail | `/req/:id` (사용자), `/o/reqs/:id` (담당자) | `GET /requests/{id}`, `accept/reject/decline/cancel/noshow/check-in/check-out/confirm/adjust/adjust/answer/rate/appeal/messages` | A-05, A-08, B-12, B-17~19, B-22, B-23, B-25, B-28, B-35, B-38, C-07~C-10 |
| log_write | `/req/:id/log` | `POST /ai/log-tidy`, `POST /files`, `POST /requests/{id}/log` | B-24 |
| rate | `/req/:id/rate` | `POST /requests/{id}/rate` | B-38, B-39 |
| write | `/write` | `GET /posts/mine` | B-03 |
| write_offer | `/write/offer` | `POST /posts` (반복 `repeat`) | B-04, B-06 |
| write_need | `/write/need`, `?elder=`, `?family=` | `POST /ai/post-chat`, `POST /posts` | B-02, B-03, B-05, B-09, C-04 |
| act | `/act?seg=` | `GET /requests?box=all`, `GET /posts/mine` | B-45 |
| bank | `/bank` | `GET /bank` | A-06, B-30, B-33 |
| gift | `/gift` | `GET /family`, `POST /bank/gift` | B-32, B-34 |
| my | `/my` | `GET /me`, `/bank`, `/family`, `/posts/mine`, `/interest-alerts` | B-43~47 |
| profileEdit | `/my/profile` | `POST /files`, `PUT /me` | B-43 |
| verify | `/my/verify` | `POST /files`, `POST /me/verify/id·crime` | B-01 |
| family | `/my/family` | `GET/POST /family`, `answer`, `PUT share`, `DELETE` | B-44, P-06 |
| famReport | `/my/family/:userId/report` | `GET /family/{userId}/report` | P-04 |
| alertset | `/my/alerts` | `GET/POST/PUT/DELETE /interest-alerts` | B-21 |
| notiSet | `/my/noti` | `PUT /me` (voiceAlert), speechSynthesis 미리 듣기 | B-42 |
| cert | `/my/cert` | `GET /certificate` | — |
| (참고안) 내 정보 수정·비밀번호 | `/my/info` | `PUT /me`, `PUT /me/password` | A-09 |
| (참고안) 회원 탈퇴 | `/my/withdraw` | `GET /me/withdraw-check`, `POST /me/withdraw` | A-04 |
| recommend | `/recommend` | `POST /ai/recommend` | — |
| public | `/public?q=` | `POST /ai/public-services` | P-01 |
| edu | `/edu` | `GET /edu`, `PUT /edu/diag` | B-01 |
| edu_card | `/edu/card` | `POST /edu/cards-done` | B-01 |
| edu_quiz | `/edu/quiz` | `POST /edu/quiz` | B-01 |
| edu_rp_list · edu_rp · edu_rp_res | `/edu/rp`, `/edu/rp/:id`, `/edu/rp/:id/result` | `POST /ai/roleplay`, `POST /edu/roleplay/{id}/finish`, `POST /edu/complete` | B-01 |
| edu_cert | `/edu/cert` | `GET /edu` | B-01 |
| cls | `/cls` | `GET /classes`, `GET /classes/mine` | — |
| cls_detail | `/cls/:id` | `GET /classes/{id}`, `apply`, `cancel`, `attend`, `qr`, `finish`, `review` | — |
| cls_note | `/cls/:id/note` | `GET /classes/{id}` | — |
| cls_new | `/cls/new`, `/cls/new?org=1` | `POST /ai/filter`, `POST /classes` | — |
| o_home | `/o/home` | `GET /org/home`, `/org/elders`, `/org/requests`, `/classes` | C-02~C-10 |
| o_elders | `/o/elders` | `GET /org/elders`, `GET /org/transfers`, `POST /org/transfers/{id}/answer` | C-02, C-03 |
| e_detail | `/o/elders/:id` | `GET /org/elders/{id}`, `memos`, `consent`, `end`, `transfer`, `GET /org/managers` | C-02, C-03, P-05, P-06 |
| e_new · (수정) | `/o/elders/new`, `/o/elders/:id/edit` | `POST /org/elders`, `PUT /org/elders/{id}` | C-02 |
| o_reqs | `/o/reqs?seg=` | `GET /org/requests?box=all` | C-05~C-10 |
| o_report | `/o/report` | `GET /org/reports`, `POST /org/reports/{id}/flag` | P-05 |
| o_my | `/o/my` | — | A-09 |
| o_space | `/o/space` | `GET /classes?status=SPACE_PENDING`, `GET/POST /org/spaces`, `POST /org/classes/{id}/assign-space` | — |
| a_dash | `/a/dash` | `GET /admin/dashboard`, `POST /admin/reports/generate`, `POST /admin/scheduler/run` | D-04, D-05, P-07 |
| a_approve | `/a/approve?seg=org\|vol\|diff` | `GET /admin/orgs`, `approve/reject`, `GET /admin/verifications`, `…/{kind}/approve\|reject`, `GET /admin/appeals`, `answer` | D-01, D-03, B-28 |
| a_safety | `/a/safety` | `GET /admin/reports`, `handle`, `GET /admin/anomalies`, `hold\|release` | D-02, D-06 |
| a_manage | `/a/manage` | — | — |
| (참고안) 회원 관리 | `/a/members` | `GET /admin/users`, `GET /admin/users/{id}`, `suspend\|unsuspend` | D-07 |
| (참고안) 담당자 계정 | `/a/managers` | `GET /admin/managers`, `suspend\|unsuspend`, `POST /admin/elders/{id}/transfer` | D-01, C-03 |
| (참고안) 문의 관리 | `/a/inquiries` | `GET /admin/inquiries`, `answer` | D-08 |
| (참고안) 공지 관리 | `/a/notices` | `GET /notices`, `POST/PUT/DELETE /admin/notices` | D-09 |
| aPub | `/a/pub` | `GET/POST/PUT/DELETE /admin/public-services` | P-02 |
| aPersona | `/a/persona` | `GET/POST/PUT/DELETE /admin/personas` | — |
