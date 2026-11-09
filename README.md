# 시간 적금 (시간은행 + LLM 돌봄 플랫폼)

이웃을 도운 시간만큼 "시간 통장"에 쌓이고, 그 시간으로 도움을 받는 서비스입니다. 사용자(봉사하는·봉사받는 사람), 기관 담당자(독거노인 대리), 운영자 세 역할을 지원합니다.

- 요구사항: `docs/requirements/요구사항명세서.txt`
- 채택 프로토타입(황수빈 안): `docs/prototypes/adopted-황수빈-첫활동교육-동네클래스.html`
- 구현 계약서(API·DB·규칙): `docs/design/contract.md`
- **구현 보고서**: `docs/reports/구현보고서.md` · 음성 입력 분석: `docs/reports/음성입력-분석보고서.md`

## 구성

| 폴더 | 내용 |
|---|---|
| `backend/` | Spring Boot 3.5 · Java 21 · JPA. REST API, 스케줄러, AI(`AiClient`) |
| `frontend/` | React 18 · Vite · react-router 6. 모바일 화면 70여 개 |
| `db/` | `schema.sql`(MySQL 호환 DDL, 테이블 25개), ERD·설명 |
| `scripts/build.sh` | 프론트 빌드 → 백엔드에 넣어 jar 하나로 묶기 |

## 실행

필요: Java 21, Node 18 이상.

개발 모드(두 창):
```bash
cd backend && sh ./mvnw spring-boot:run
```
```bash
cd frontend && npm install && npm run dev
```
→ http://localhost:5173

한 번에(jar 하나):
```bash
bash scripts/build.sh
```
```bash
java -jar backend/target/time-savings-0.1.0.jar
```
→ http://localhost:8080

- 데모 계정(비밀번호 모두 `1234`): 사용자 `yujin`, `sky`, `mikyung`, `doyun`, `bok`, `mom` / 담당자 `org1`, `org2` / 운영자 `admin`. 로그인 화면에 빠른 로그인 버튼이 있습니다.
- DB는 처음 실행할 때 `./data/`에 H2 파일로 만들어지고 데모 데이터가 들어갑니다. MySQL은 `--spring.profiles.active=mysql`과 `DB_URL`/`DB_USER`/`DB_PASSWORD` 환경변수로 씁니다(`db/README.md`).
- AI: 환경변수 `ANTHROPIC_API_KEY`가 있으면 Claude(`claude-haiku-5-5`)를 쓰고, 없으면 규칙 모드로 같은 기능이 동작합니다. 키는 파일에 적지 마세요.
- 테스트: `cd backend && sh ./mvnw test` (34개, AI 호출 없음)
