# 시간 적금 백엔드

Spring Boot 3.5.16 · Java 21 · Maven Wrapper · H2(기본)/MySQL 8. 기준 문서는 `../docs/design/contract.md`(구현 계약서).

## 실행

```bash
cd backend
./mvnw spring-boot:run            # 실행 권한이 없으면: sh ./mvnw spring-boot:run
# 다른 포트: ./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8086
```

- 주소 `http://localhost:8080`, API는 `/api/**`, H2 콘솔 `/h2-console`(JDBC URL `jdbc:h2:file:./data/timesavings`, 사용자 `sa`).
- 첫 기동 때 DB가 비어 있으면 `DataSeeder`가 데모 데이터를 넣는다. 다시 넣으려면 서버를 내리고 `data/`를 지운다.
- 데모 계정(비밀번호 모두 `1234`): `yujin`(손유진), `sky`·`mikyung`·`doyun`·`bok`·`mom`, 담당자 `org1`(박지원)·`org2`, 운영자 `admin`.
- 휴대폰 본인인증은 모의: 인증번호는 항상 `123456`(`POST /api/auth/phone/send` 응답의 `demoCode`).
- 프론트 빌드 결과를 `src/main/resources/static/`에 넣으면 같은 서버가 화면도 서빙한다(`/api`, `/h2-console` 밖의 경로는 `index.html`).
- CORS: `http://localhost:5173`(설정 `app.cors-origins`, 쉼표로 여러 개).

### 테스트

```bash
./mvnw test          # 34개, AI 를 부르지 않는다(app.ai.enabled=false, 메모리 H2)
```

## 환경변수

AI 호출은 모두 `ai/AiClient`(공급자: `ai/LlmProvider` ← `GeminiProvider`, `AnthropicProvider`), 음성 인식은 `ai/SttClient`(녹음은 메모리에서만 처리하고 저장하지 않음), Firebase 토큰 확인은 `service/FirebasePhoneVerifier` 한 곳을 지난다.


| 이름 | 용도 |
|---|---|
| `GEMINI_API_KEY` | 있으면 AI 기능이 Gemini `generateContent`를 부른다(헤더 `x-goog-api-key`). 서버 음성 인식 `/api/stt`도 이 키로만 켜진다. **파일·로그에 적지 않는다** |
| `GEMINI_MODEL` | Gemini 모델(기본 `gemini-flash-latest`) |
| `ANTHROPIC_API_KEY` | 있으면 Claude Messages API(`claude-haiku-5-5`). **파일·로그에 적지 않는다** |
| `AI_PROVIDER` | `gemini`·`anthropic`·`rule`. 비우면 Gemini 키 → Anthropic 키 → 규칙 순. 고른 공급자의 키가 없거나 호출이 실패(429·오류·형식 이상)하면 그 호출만 규칙 결과(`source: "rule"`). `rule`이면 서버 음성 인식도 꺼짐 |
| `FIREBASE_WEB_API_KEY` | Firebase 웹 API 키. `POST /api/auth/phone/verify`의 `firebaseIdToken`을 Identity Toolkit `accounts:lookup`으로 확인 |
| `PHONE_DEMO` | `false`면 데모 인증번호 `123456`을 끔(`app.phone.demo-code-enabled`, 기본 true) |
| `SPRING_PROFILES_ACTIVE=mysql` | MySQL 8 사용 |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | MySQL 접속 정보(mysql 프로필에서만) |

설정(`application.yml`의 `app.*`): `app.ai.enabled`(기본 true), `app.ai.model`, `app.scheduler.enabled`, `app.seed.enabled`, `app.upload-dir`(기본 `./data/uploads`).

## AI (`ai/AiClient`)

모든 AI 호출은 `AiClient` 한 클래스만 거친다. 키가 있으면 JSON만 답하게 한 프롬프트로 부르고, 결과를 서버가 검증한다(카테고리 6개 밖 값 버림, 등급은 카테고리 기본 등급 아래로 못 내림, 규칙이 '불가'로 본 요청은 AI가 풀어 주지 않음, 개인정보·병명은 서버가 다시 지움). 실패하면 규칙 결과로 대신한다.

| 기능 | 규칙 대체 구현 |
|---|---|
| 대화형 공고 작성 `post-chat` (글·음성) | `KoreanParser`(상대 날짜·한글 수사 시각·길이·동/장소·카테고리·군말 제거), `PostChatRule`(빠진 항목 하나씩 되묻기, 요약 문장), `VoiceSafety`(의도, 위급 119 안내, 전화번호·계좌·상세 주소 지우기, 병명 없는 주의사항, 반복 규칙) |
| 금지 요청 필터 | `RuleEngine.filter` 키워드·정규식(신체 돌봄·의료 행위·금전 관리·위험 작업 = 불가, 부축·은행·칼 등 = 주의, 부정 표현 처리) + 공공 서비스 DB 대체 안내 |
| 난이도 제안 | `RuleEngine.difficulty` 6요소(신체·책임·위험·전문성·이동·접근성) 점수 → 등급·근거, 가중치는 `Rules` |
| 일지 정리 | `RuleEngine.logTidy` 군말 제거·문장 나누기·어미 다듬기 |
| 공공 서비스 안내(RAG) | `RuleEngine.searchServices` DB 검색 → 그 결과만으로 안내, 자격 판정 안 함 |
| AI 도움 추천 | 카테고리 키워드 → 맞는 공고 |
| 교육 역할극 | 프로토타입 `rpClassify`/`rpScore` 그대로 |
| 생활 변화 리포트 서술 | 숫자는 코드 집계, 서술은 템플릿(진단·건강 판단 없음) |

시드 데이터는 키가 있어도 규칙 모드로만 만든다(`AiClient.ruleOnly`).

## 패키지 구조

```
com.timesavings
├─ TimeSavingsApplication      (@EnableScheduling)
├─ config   ApiException, GlobalExceptionHandler({error, code}), AuthInterceptor(Bearer 토큰·역할), CurrentUser, WebConfig(CORS·인자 주입), SpaController
├─ domain   엔티티 26개 + Enums(상태 값) + StringListConverter
├─ repo     Spring Data JPA 저장소
├─ ai       AiClient(유일한 AI 호출), RuleEngine, KoreanParser, PostChatRule, VoiceSafety
├─ service  Rules(적립·일정 규칙), AuthService, BankService, RequestService(상태 흐름), PostService, EventService·LinkPreviewService,
│           EduService·EduContent, LifeReportService, NotificationService, SchedulerService, FileService, Views(응답 모양), DataSeeder
└─ web      AuthController, PostController, AiController, RequestController, BankController, CommonController,
            EduController, NewsController, OrgController, AdminController
```

## 스케줄러 (`SchedulerService`, 1분 간격)

응답 기한 24시간 만료(B-19) · 공고 날짜 지나면 마감(B-11) · 일지 제출 24시간 뒤 자동 확정(C-10, 일지 없으면 안 함) · 시작 15분 지나도 체크인 없으면 받는 쪽(독거노인은 담당자)·봉사자 알림 · 시작 전날 리마인드 · 매월 1일 지난달 생활 변화 리포트(P-03). `POST /api/admin/scheduler/run`으로 즉시 실행.
