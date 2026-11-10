# 시간 적금 DB

- `schema.sql` — 실제 JPA 엔티티(`backend/src/main/java/com/timesavings/domain`)에서 Hibernate 스키마 생성으로 뽑은 MySQL 8 DDL(테이블 29개).
- 기본 실행은 **H2 파일 DB**(`backend/data/timesavings.mv.db`, `MODE=MySQL`)이고 `ddl-auto=update`로 같은 구조가 자동 생성된다.
- 데모 데이터는 SQL 파일이 아니라 서버 첫 기동 때 `DataSeeder`가 넣는다(DB가 비어 있을 때만, 오늘 날짜 기준 상대 날짜).

## 테이블

| 테이블 | 설명 | 요구사항 |
|---|---|---|
| `users` | 사용자·기관 담당자·운영자 계정. `role`(USER/ORG/ADMIN), 신원(`id_status`)·범죄경력(`crime_status`) 인증, 교육 수료, 패널티·경고 횟수, 프로필(지역·시간대·카테고리) | A-01~A-04, A-09, B-01, B-43 |
| `auth_token` | 로그인 토큰(만료 30일, 로그인 유지 안 하면 1일) | A-02 |
| `organizations` | 기관 서비스 신청·승인 상태 | C-01, D-01 |
| `elders` | 도움 대상(독거노인). **별칭·동·나이대·필요 도움·주의사항만** 저장(이름·주소·연락처 없음) | C-02 |
| `elder_memos` | 담당자 메모 | C-02 |
| `care_notes` | 전달 메모 정리(요청 한 건에 하나, `request_id` 유일). `subject_type`·`subject_id`(받는 사람: ELDER/USER), `next_steps`(다음에 챙길 것)·`hospital`("병원 안내 있음 (…)" — 병명·약 이름 없음)·`life_changes`(줄바꿈 구분), `signals`(생활 변화 신호 이름: 식사 거름·외출 감소·기운 없음·잠 못 잠·말수·기분 변화), `flag_raised`(이 메모로 '확인 권장'이 처음 켜져 담당자 알림을 보냄), `source`(ai/rule) | 7.14 C1 |
| `ai_results` | 이벤트 때 한 번 만든 LLM 결과 저장·재사용(`kind`+`ref_key` 유일): `ORG_BRIEFING`(담당자 id)·`REPORT_TRIAGE`(신고 id)·`ANOMALY_NOTE`(봉사자:대상)·`INQUIRY_DRAFT`(문의 id). `fingerprint`가 바뀌면(큰 변화·근거 변경) 다시 만든다. `payload`는 화면용 JSON | 7.14 C3·C5·C6·C7 |
| `elder_blocks` | 담당자의 "이 봉사자 받지 않기"(`elder_id`, `volunteer_id`, `reason` 필수, `manager_id`, `request_id`). 있으면 그 봉사자는 그 대상 건에 요청·제안할 수 없음(403 `VOLUNTEER_BLOCKED`) | 회의반영 O1 |
| `elder_transfers` | 담당자 간 이관(PENDING/ACCEPTED/REJECTED, 운영자 대리 = ADMIN) | C-03, D-01 |
| `posts` | 공고. `type` OFFER(봉사 가능)/NEED(도움 요청), 시간 범위, 카테고리, 난이도·가중치, 반복(`series_id`), 상태. LLM 추가 A: `easy_text`(쉬운 말 읽어 주기 문장, 공고별 1회 생성·수정하면 지움), `fix_tips_json`·`fix_checked_at`(24시간 요청 없는 공고 고치기 제안·근거, 공고당 1회) | B-03~B-11, C-04, 7.12 |
| `requests` | 요청·제안·단골 요청과 활동 진행 전체(상태, 응답 기한, 체크인 코드·시각, 일지, 시간 조정, 적립, 이상 패턴 보류). `match_reason`="왜 이 봉사자?" 한 줄(요청 생성 때 1회, 숫자는 코드 값, LLM 추가 A). `care_memo`=일지와 함께 남기는 전달 메모(선택, 500자, 건강 정보가 들어갈 수 있는 민감정보 — 봉사자 본인·받은 사람·담당자·공유 동의한 가족만) | A-05, B-12~B-25, C-05~C-10, 회의반영 4장 |
| `messages` | 요청별 메시지(시스템 메시지 포함, 연락처·계좌 패턴이면 `warn`) | A-08, C-09 |
| `ledger` | 시간 통장 거래(EARN/HOLD/RELEASE/USE/GIFT_OUT/GIFT_IN/ADJUST/REFUND). CLASS_FEE/CLASS_EARN/POOL은 삭제된 동네 클래스 기록을 읽기 위해 enum에만 남김(새로 쓰지 않음) | A-06, B-26~B-34 |
| `ratings` | 평가(봉사자 평가는 공개, 받는 사람 평가는 `is_private`) | B-38~B-40 |
| `notifications` | 알림. `voice_text`=음성 알림 켠 사용자에게 전화로 읽을 문장(숫자는 한글 읽기, LLM 추가 A) | A-07, C-08, B-42 |
| `interest_alerts` | 관심 조건(지역·카테고리·평일/주말) | B-21 |
| `reports` | 신고와 처리 결과(경고·정지·퇴출) | B-36, B-37, D-02 |
| `inquiries` | 1:1 문의·답변 | B-48, B-49, D-08 |
| `notices` | 공지 | A-10, D-09 |
| `family_links` | 가족 연결. `share_report`=신청한 쪽의 리포트 공유 동의, `target_share_report`=받은 쪽의 동의 | B-32, B-44, P-04, P-06 |
| `difficulty_appeals` | 난이도 이의 신청 | B-28 |
| `public_services` | 공공 서비스 안내 DB(AI는 여기 있는 것만 안내) | P-01, P-02 |
| `life_reports` | 월간 생활 변화 리포트(`report_month`=YYYY-MM, 숫자는 `stats_json`, 서술은 `narrative`) | P-03~P-07 |
| `edu_progress` | 첫 활동 교육 진행(진단·카드·퀴즈·역할극 결과) | B-01 |
| `roleplay_personas` | 교육 역할극 가상 상대(대본 `turns_json`) | 교육 |
| `regular_marks` | 수동 단골 등록(받는 사람 `user_id` → 봉사자 `volunteer_id`, 한 쌍에 하나). 자동 단골(DONE 3회 이상)과 합쳐 `/api/regulars`에 나온다. 함께 끝낸 활동이 1번 이상일 때만 등록 | 2026-11-09 회의 U7 |
| `org_events` | 동네 소식: 기관 행사·클래스 홍보(제목·내용·일시·장소·정원(0=제한 없음)·`apply_type` APP/LINK·`url`, 링크 미리보기 `og_title`/`og_image`/`og_desc`, `status` OPEN/CLOSED) | 2026-11-09 회의 2장·O3 |
| `event_applications` | 동네 소식 앱 신청(`status` APPLIED/CANCELLED) | 2026-11-09 회의 2장 |
| `ai_notes` | 7.13 LLM 추가 B: 이벤트 때 한 번 만든 AI(키 없으면 규칙) 결과 저장·재사용. `kind`+`ref_id`(+`ref_key`) 하나에 한 행 — BRIEFING(요청 id, 확정 때 방문 전 체크리스트) · MESSAGE(메시지 id, 안전 감지 `warn_level` none/caution/warn·답장 후보·일정 변경, 메시지당 1회) · ADJUST(요청 id, 시간 조정 판단 보조) · APPEAL(이의 신청 id) · GOAL(사용자 id, 거꾸로 플랜 추천) · MONTH(사용자 id + `ref_key`=YYYY-MM, 월별 결산 한 줄). `fingerprint`가 바뀌면(그 달 거래·목표 입력·조정 시간) 다시 만든다. 결과는 `body_json`, `source` ai/rule, `provider`. 지난 전달 메모·연락처는 넣지 않음 | 계약서 7.13 |

동네 클래스용 `classes`·`class_enrollments`·`spaces` 테이블과 `ledger.class_id`는 2026-11-10 사용자 결정으로 삭제했다(동네 소식 `org_events`는 유지).

외래 키는 DB 제약 대신 `*_id` 숫자 칸으로 두고 서버 코드에서 검사한다(데모 규모, H2·MySQL 공통 단순화).

## ERD

```mermaid
erDiagram
    organizations ||--o{ users : "담당자(org_id)"
    organizations ||--o{ elders : "org_id"
    users ||--o{ elders : "manager_id"
    elders ||--o{ elder_memos : ""
    elders ||--o{ elder_blocks : ""
    requests ||--o| care_notes : "request_id"
    users ||--o{ elder_blocks : "volunteer_id"
    elders ||--o{ elder_transfers : ""
    users ||--o{ auth_token : ""
    users ||--o{ posts : "owner_id"
    elders ||--o{ posts : "elder_id (담당자 대리)"
    posts ||--o{ requests : "post_id"
    users ||--o{ requests : "sender / volunteer / recipient"
    elders ||--o{ requests : "elder_id"
    requests ||--o{ messages : ""
    requests ||--o{ ai_notes : "request_id (7.13 B)"
    requests ||--o{ ratings : ""
    requests ||--o{ difficulty_appeals : ""
    requests ||--o{ ledger : "request_id"
    users ||--o{ ledger : "user_id"
    users ||--o{ notifications : ""
    users ||--o{ interest_alerts : ""
    users ||--o{ reports : "reporter_id"
    users ||--o{ inquiries : ""
    users ||--o{ family_links : "requester / target"
    users ||--|| edu_progress : ""
    users ||--o{ regular_marks : "user_id"
    users ||--o{ regular_marks : "volunteer_id"
    organizations ||--o{ org_events : "org_id"
    org_events ||--o{ event_applications : "event_id"
    users ||--o{ event_applications : "user_id"
    users ||--o{ life_reports : "subject (USER)"
    elders ||--o{ life_reports : "subject (ELDER)"

    users {
        bigint id PK
        varchar login_id UK
        varchar role
        varchar status
        varchar id_status
        varchar crime_status
        bit edu_done
        int penalty_count
    }
    elders {
        bigint id PK
        bigint manager_id
        varchar alias
        varchar dong
        varchar age_group
        varchar needs
        varchar caution
    }
    posts {
        bigint id PK
        varchar type
        bigint owner_id
        bigint elder_id
        date date
        double start_hour
        double end_hour
        varchar categories
        varchar difficulty
        double weight
        varchar status
    }
    requests {
        bigint id PK
        varchar kind
        bigint post_id
        bigint volunteer_id
        bigint recipient_id
        bigint elder_id
        date date
        double start_hour
        double hours
        varchar status
        datetime deadline_at
        varchar check_code
        double earned
    }
    ledger {
        bigint id PK
        bigint user_id
        bigint elder_id
        varchar type
        double amount
        double base_hours
        double weight
        bigint request_id
    }
    care_notes {
        bigint id PK
        bigint request_id UK
        varchar subject_type
        bigint subject_id
        date activity_date
        varchar next_steps
        varchar hospital
        varchar life_changes
        varchar signals
        bit flag_raised
    }
    ai_results {
        bigint id PK
        varchar kind
        varchar ref_key
        varchar fingerprint
        text payload
    }
    regular_marks {
        bigint id PK
        bigint user_id
        bigint volunteer_id
        datetime created_at
    }
    org_events {
        bigint id PK
        bigint org_id
        varchar title
        date event_date
        double start_hour
        varchar place
        int capacity
        varchar apply_type
        varchar url
        varchar og_title
        varchar og_image
        varchar status
    }
    event_applications {
        bigint id PK
        bigint event_id
        bigint user_id
        varchar status
    }
    life_reports {
        bigint id PK
        varchar subject_type
        bigint subject_id
        varchar report_month
        text stats_json
        varchar narrative
        bit big_change
    }
```

## H2 ↔ MySQL 전환

- 기본(H2 파일): 아무 설정 없이 실행. 접속 정보 `jdbc:h2:file:./data/timesavings;MODE=MySQL;AUTO_SERVER=FALSE`, 사용자 `sa`, 비밀번호 없음. 콘솔 `http://localhost:8080/h2-console`.
- MySQL 8:
  ```bash
  mysql -u root -p -e "CREATE DATABASE timesavings DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
  mysql -u root -p timesavings < db/schema.sql      # 선택: 미리 만들기(안 해도 ddl-auto=update 가 만든다)
  cd backend
  SPRING_PROFILES_ACTIVE=mysql DB_URL='jdbc:mysql://localhost:3306/timesavings?serverTimezone=Asia/Seoul&characterEncoding=UTF-8' \
    DB_USER=timesavings DB_PASSWORD='(비밀번호)' ./mvnw spring-boot:run
  ```
  접속 정보는 환경변수로만 준다(파일에 적지 않는다).
- DB를 처음부터 다시: 서버를 내리고 `backend/data/` 를 지우면 다음 기동 때 시드가 다시 들어간다.

## schema.sql 다시 만들기

엔티티를 바꾼 뒤:
```bash
cd backend
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=schema \
  "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --app.seed.enabled=false --app.scheduler.enabled=false --spring.datasource.url=jdbc:h2:mem:schema;MODE=MySQL --spring.jpa.hibernate.ddl-auto=none"
# ../db/schema.generated.sql 이 생긴다 → 머리말을 붙여 schema.sql 로 바꾼다
```
