# 시간 적금 DB

- `schema.sql` — 실제 JPA 엔티티(`backend/src/main/java/com/timesavings/domain`)에서 Hibernate 스키마 생성으로 뽑은 MySQL 8 DDL(테이블 40개). 요구사항 명세서 41판 + 기능명세서 73개 기능(2026-11-12) 기준이고, 칸마다의 설명은 `schema.sql` 주석과 계약서(`docs/design/contract.md`) 7.17·7.18 절을 본다. 기능명세서 적용분(새 표 5개·새 칸 3개)은 엔티티를 보고 손으로 더했다. 아래 표의 "요구사항" 칸에서 "기능 U-09"처럼 "기능"이 붙은 ID는 기능명세서 ID이고, 붙지 않은 것은 요구사항 명세서 ID다(두 체계는 번호가 다르다).
- 기본 실행은 **H2 파일 DB**(`backend/data/timesavings.mv.db`, `MODE=MySQL`)이고 `ddl-auto=update`로 같은 구조가 자동 생성된다.
- 데모 데이터는 SQL 파일이 아니라 서버 첫 기동 때 `DataSeeder`가 넣는다(DB가 비어 있을 때만, 오늘 날짜 기준 상대 날짜).

## 테이블

| 테이블 | 설명 | 요구사항 |
|---|---|---|
| `users` | 사용자·기관 담당자·운영자 계정. `role`(USER/ORG/ADMIN), 신원(`id_status`)·범죄경력(`crime_status`) 인증, 패널티·경고 횟수, 프로필(지역·시간대·카테고리), 가입 약관 동의(`terms_agreed_at`·`privacy_agreed_at`·`loc_consent`·`interest_consent`), 알림 설정(`noti_*`, `voice_alert`, `sms_alert`), 퇴출(`banned`). `edu_done`=첫 활동 교육(안전 교육) 이수 여부 — 41판에서는 쓰지 않았지만 기능명세서에서 다시 쓴다(필수 설정이면 이수해야 봉사 가능) | A-01~A-04, A-09, B-01, B-43, 기능 U-34 |
| `auth_token` | 로그인 토큰(만료 30일, 로그인 유지 안 하면 1일) | A-02 |
| `organizations` | 기관 서비스 신청·승인 상태. `proof_file_url`=재직 증빙 파일(운영자만 열 수 있음) | C-01, D-01 |
| `elders` | 도움 대상(독거노인). **별칭·동·나이대·필요 도움·주의사항만** 저장(이름·주소·연락처 없음). 등록 때 서비스 이용 동의 확인(`service_consent_at/by`), 현관 QR 카드 번호(`qr_code`, `qr_issued_at`) | C-02, C-11 |
| `elder_memos` | 담당자 메모(원문 `text` + AI·규칙 정리본 `tidy_text`, `source`) | C-02, C-14 |
| `care_notes` | 전달 메모 정리(요청 한 건에 하나, `request_id` 유일). `subject_type`·`subject_id`(받는 사람: ELDER/USER), `next_steps`(다음에 챙길 것)·`hospital`("병원 안내 있음 (…)" — 병명·약 이름 없음)·`life_changes`(줄바꿈 구분), `signals`(생활 변화 신호 이름: 쓰러짐·넘어짐·식사 거름·외출 감소·기운 없음·잠 못 잠·말수·기분 변화 — 앞의 셋은 한 건만 있어도 담당자에게 알림, B-36), `flag_raised`(이 일지·메모로 담당자 알림을 보냄), `source`(ai/rule) | 7.14 C1 |
| `ai_results` | 이벤트 때 한 번 만든 LLM 결과 저장·재사용(`kind`+`ref_key` 유일): `ORG_BRIEFING`(담당자 id)·`REPORT_TRIAGE`(신고 id)·`ANOMALY_NOTE`(봉사자:대상)·`INQUIRY_DRAFT`(문의 id). `fingerprint`가 바뀌면(큰 변화·근거 변경) 다시 만든다. `payload`는 화면용 JSON 기능명세서: `FIN_MONTHLY`(재정 추계 월간 요약, `ref_key`=연-월)도 여기에 저장 | 7.14 C3·C5·C6·C7 |
| `elder_blocks` | 담당자의 "이 봉사자 받지 않기"(`elder_id`, `volunteer_id`, `reason` 필수, `manager_id`, `request_id`). 있으면 그 봉사자는 그 대상 건에 요청·제안할 수 없음(403 `VOLUNTEER_BLOCKED`) | 회의반영 O1 |
| `elder_transfers` | 담당자 간 이관(PENDING/ACCEPTED/REJECTED, 운영자 대리 = ADMIN) | C-03, D-01 |
| `posts` | 공고. `type` OFFER(봉사 가능)/NEED(도움 요청), 시간 범위, 카테고리, 난이도·가중치, 상태. `created_by`=올린 사람(가족 대신 올리면 주인 `owner_id`와 다름, B-57). `repeat_rule`·`series_id`는 41판에서는 쓰지 않았지만 기능명세서에서 정기 요청 회차 공고에 다시 쓴다(`series_id`="RC{정기 요청 id}", `repeat_rule`="매주 목요일". 보통 공고와 다시 올리기는 null). LLM 추가 A: `easy_text`(쉬운 말 읽어 주기 문장, 공고별 1회 생성·수정하면 지움), `fix_tips_json`·`fix_checked_at`(24시간 요청 없는 공고 고치기 제안·근거, 공고당 1회) | B-03~B-11, C-04, 7.12, 기능 U-09 |
| `requests` | 요청·제안·단골 요청과 활동 진행 전체(상태, 응답 기한, 시작·종료 시각과 확인 방법 `check_method` qr/code, 30초마다 바뀌는 번호의 비밀값 `qr_secret`, 지각·노쇼 의심 알림 표시, 자동 마감 `auto_closed`, 일지, 시간 조정, 적립, 이상 패턴 보류). 일지 사진 `log_photo_url`(장보기·심부름은 영수증, 가벼운 집안일은 "하기 전")·`log_photo_url2`(집안일 "한 뒤") — 이 두 종류는 사진이 없으면 일지를 낼 수 없다(기능 U-16). `check_code`는 시작·종료 확인에 쓰지 않음. `match_reason`="왜 이 봉사자?" 한 줄(요청 생성 때 1회, 숫자는 코드 값, LLM 추가 A). `care_memo`=일지와 함께 남기는 전달 메모(선택, 500자, 건강 정보가 들어갈 수 있는 민감정보 — 봉사자 본인·받은 사람·담당자·공유 동의한 가족만) | A-05, B-12~B-25, C-05~C-10, 회의반영 4장, 기능 U-16 |
| `messages` | 요청별 메시지(시스템 메시지 포함, 연락처·계좌 패턴이면 `warn`) | A-08, C-09 |
| `ledger` | 시간 통장 거래(EARN/HOLD/RELEASE/USE/GIFT_OUT/GIFT_IN/ADJUST/REFUND). CLASS_FEE/CLASS_EARN/POOL은 삭제된 동네 클래스 기록을 읽기 위해 enum에만 남김(새로 쓰지 않음). 단체 봉사 적립은 EARN(`request_id` 없음, `label` "단체 봉사 · 제목 · 기관") | A-06, B-26~B-34, 기능 U-10 |
| `ratings` | 평가(봉사자 평가는 공개, 받는 사람 평가는 `is_private`) | B-38~B-40 |
| `notifications` | 알림. `voice_text`=음성 알림 켠 사용자에게 전화로 읽을 문장(숫자는 한글 읽기, LLM 추가 A) | A-07, C-08, B-42 |
| `interest_alerts` | 관심 조건(지역·카테고리·평일/주말·날짜 범위 `date_from`~`date_to`) | B-21 |
| `reports` | 신고와 처리 결과(경고·정지·퇴출). `kind`=WELFARE 면 독거노인 이상 징후(안부 확인) 접수로 제재 없는 기록(`elder_id`) | B-36, B-37, D-02 |
| `inquiries` | 1:1 문의·답변. 처리 단계 `stage`(RECEIVED/CHECKING/ANSWERED/CLOSED) | B-48, B-49, D-08 |
| `notices` | 공지. `pinned`=고정 공지(목록 맨 위, null·false는 고정 아님) | A-10, D-09, 기능 C-08·A-16 |
| `family_links` | 가족 연결. `share_report`=신청한 쪽의 리포트 공유 동의, `target_share_report`=받은 쪽의 동의 | B-32, B-44, P-04, P-06 |
| `difficulty_appeals` | 난이도 이의 신청 | B-28 |
| `public_services` | 공공 서비스 안내 DB(AI는 여기 있는 것만 안내) | P-01, P-02 |
| `life_reports` | 월간 생활 변화 리포트(`report_month`=YYYY-MM, 숫자는 `stats_json`, 서술은 `narrative`) | P-03~P-07 |
| `edu_progress` | 첫 활동 교육·연습 진행(진단·카드·퀴즈·역할극 결과). `practice_json`=안전 규칙에 맞게 끝낸 연습 상황 key 목록, `completed_at`=상황을 모두 마친 이수 시각(41판에서는 쓰지 않았지만 기능명세서에서 다시 씀) | B-54, 기능 U-34 |
| `roleplay_personas` | 첫 활동 연습 역할극의 가상 상대(대본 `turns_json`) | B-54 |
| `regular_marks` | 수동 단골 등록(받는 사람 `user_id` → 봉사자 `volunteer_id`, 한 쌍에 하나). 자동 단골(DONE 3회 이상)과 합쳐 `/api/regulars`에 나온다. 함께한 활동이 없어도 등록할 수 있고, 등록한 쪽·등록된 쪽 모두 해지할 수 있다(`removed`, `removed_by` — 줄은 지우지 않음) | B-42, B-65 |
| `org_events` | 동네 기관 소식: 담당자가 올리는 행사·클래스 홍보(제목·내용·일시·장소·기관 페이지 `url` 필수, 링크 미리보기 `og_title`/`og_image`/`og_desc`, `status` OPEN/CLOSED). `capacity`=행사 정원(표시용 숫자, 0은 제한 없음 — 41판에서는 쓰지 않았지만 기능명세서에서 다시 씀. 앱 안 신청·남은 자리 계산은 없음). `apply_type`은 쓰지 않음(늘 LINK) | B-64, C-15, 기능 O-09 |
| `event_applications` | 41판부터 쓰지 않음(앱 안 신청 삭제). 예전 기록을 읽기 위해 표만 남김 | — |
| `ai_notes` | 7.13 LLM 추가 B: 이벤트 때 한 번 만든 AI(키 없으면 규칙) 결과 저장·재사용. `kind`+`ref_id`(+`ref_key`) 하나에 한 행 — BRIEFING(요청 id, 확정 때 방문 전 체크리스트) · MESSAGE(메시지 id, 안전 감지 `warn_level` none/caution/warn·답장 후보·일정 변경, 메시지당 1회) · ADJUST(요청 id, 시간 조정 판단 보조) · APPEAL(이의 신청 id) · GOAL(사용자 id, 거꾸로 플랜 추천) · MONTH(사용자 id + `ref_key`=YYYY-MM, 월별 결산 한 줄). `fingerprint`가 바뀌면(그 달 거래·목표 입력·조정 시간) 다시 만든다. 결과는 `body_json`, `source` ai/rule, `provider`. 지난 전달 메모·연락처는 넣지 않음 | 계약서 7.13 |
| `public_classes` | 기관 클래스 추천: 공공기관 강좌 한 테이블. `source` AI_SEARCH(Gemini 구글 검색 그라운딩 — 서버가 `apply_url`을 열어 제목을 얻은 것만 `verified`, 아니면 `hidden`, `search_key`=지역별 하루 1회 캐시 키)/PUBLIC_DATA(공공데이터포털 평생학습강좌 CSV)/ORG(동네 소식 `org_events`를 옮겨 둔 것, `org_event_id`). 제목·기관·지역(시군구·동)·장소·기간·일정·대상·수강료·정원·신청 주소·`category`(디지털/정서·건강/생활/기타)·`expires_at`·`raw_json`(CSV 나머지 열·AI 원본) | 2026-11-10 기관 클래스 추천 |
| `category_suggestions` | 카테고리 추가 건의(2026-11-11): 사용자·담당자 `user_id`가 낸 이름 `name`·이유 `reason`. 같은 이름 묶기용 `name_key`(공백·가운뎃점 제거), `status` RECEIVED/REVIEWING/ADOPTED/HELD, 운영자 `answer`·`answered_by`·`answered_at`. 실제 카테고리 6종은 바꾸지 않는다(운영자 결정용 기록) | 2026-11-11 역할별 기능 목록 |
| `life_plans` | 생애 플랜 조건(사용자당 1행, PK `user_id`): `age`, `start_age`, `monthly_hours`, 받고 싶은 도움 `needs_json`({카테고리: 한 달 시간}). 결과 숫자는 저장하지 않고 볼 때마다 서버가 계산. 상속 칸 없음 | 플래너 3번째 칸 |
| `planner_picks` | 플래너 "추천 봉사"에서 내 계획에 넣은 봉사 요청 공고(`user_id`+`post_id` 한 쌍에 하나). 계획 시각 `start_hour`·`hours`·`plan_source`(ai/rule). 계획일 뿐이고 제안은 사용자가 직접 보낸다 | 플래너 2번째 칸 |
| `app_settings` | 운영 설정 키-값(`setting_key`, `setting_value`). `verify.items`(봉사 인증 항목 켜짐·필수 JSON), `edu.situations`(첫 활동 연습 상황 목록 JSON), `categories.extra`(운영자가 건의를 승인해 더한 카테고리 {이름: 등급} JSON). `verify.items` 값에는 `edu`(첫 활동 교육) 항목도 들어간다 | a_verify, B-54, 기능 U-34·A-15 |
| `upload_files` | 올린 파일의 주인 기록(`file_id`=파일 이름, `uploader_id` — null 이면 로그인 없이 올린 재직 증빙). 파일은 로그인하고 볼 수 있는 사람만 연다: 신분증·회보서는 본인·운영자, 재직 증빙은 운영자, 일지 사진은 그 요청의 당사자·담당자·운영자, 프로필 사진은 로그인한 사용자 | 계약서 7.17 ⑪ |
| `group_posts` | 단체 봉사 공고(1:1 공고와 별도 표). `kind` RECRUIT(모집형 — 기관 담당자만)/OFFER(제공형 — 단체가 봉사 가능), 날짜·시작 시각·시간, 동·모이는 곳(`place`, 기관·공공장소 이름만), 도움 종류, `capacity`(2명 이상), `status` OPEN/DONE/CANCELLED, 제공형을 받기로 한 기관 `host_org_id`·`host_by`·`hosted_at`, 참석 확인 `done_by`·`done_at` | 기능 U-10, U-11, O-07 |
| `group_participants` | 단체 봉사 참여자(`group_post_id`, `user_id`). `status` JOINED(참여 신청)/LEFT(취소)/ATTENDED(참석 확인)/ABSENT(불참), `earned`=참석 확인 때 적립된 시간(통장 `ledger`의 EARN과 같은 값) | 기능 U-10, O-07 |
| `recurring_posts` | 정기 요청: 도움 받는 사람 `owner_id`(관리 대상 대리면 null + `elder_id`), 등록한 사람 `created_by`, 매주 요일 `weekday`(1=월…7=일)·시각·기간(`start_date`~`end_date`), 공고 내용, `status` ACTIVE/PAUSED/ENDED/CANCELLED, 자동으로 멈춘 까닭 `stop_reason`, 회차 공고를 만든 마지막 날짜 `last_generated`. 회차 공고는 스케줄러가 7일 안 것만 `posts`에 만든다(`posts.series_id`="RC{id}") | 기능 U-09 |
| `manager_applications` | 담당자 계정 신청 내역: 승인된 기관(`org_id`) + 휴대폰 본인인증이면 자동 승인(`status` APPROVED)되어 담당자 계정(`user_id`)이 바로 생긴다. 재직 증빙 `proof_file_url`(운영자만 엶, 내용은 자동으로 검사하지 않음), 신청 때 그 기관의 담당자 수 `existing_managers`, 운영자 취소 REVOKED(`revoke_reason`, `revoked_at`) | 기능 C-04, A-11 |
| `weather_alerts` | 기상 특보: 종류 `type`(폭염·한파 등)·수준 `level`(주의보/경보)·지역 `areas`·기간, `source` MANUAL(운영자 입력)/SAMPLE(시연용 표본)/KMA(기상청 API), 리포트 문장 `report_json`, 전달 기록(`notice_id`, `sent_at`, `sent_managers` — 한 번만 전달) | 기능 A-08, A-09 |

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
    users ||--o{ category_suggestions : "user_id"
    users ||--o| life_plans : "user_id"
    users ||--o{ planner_picks : "user_id"
    posts ||--o{ planner_picks : "post_id"
    organizations ||--o{ org_events : "org_id"
    org_events ||--o{ event_applications : "event_id"
    users ||--o{ event_applications : "user_id"
    org_events ||--o| public_classes : "org_event_id (source ORG)"
    users ||--o{ life_reports : "subject (USER)"
    users ||--o{ group_posts : "owner_id"
    organizations ||--o{ group_posts : "org_id / host_org_id"
    group_posts ||--o{ group_participants : "group_post_id"
    users ||--o{ group_participants : "user_id"
    users ||--o{ recurring_posts : "owner_id / created_by"
    elders ||--o{ recurring_posts : "elder_id (담당자 대리)"
    recurring_posts ||--o{ posts : "series_id = RC{id} (회차 공고)"
    organizations ||--o{ manager_applications : "org_id"
    users ||--o| manager_applications : "user_id (자동 승인된 담당자)"
    notices ||--o| weather_alerts : "notice_id (전달한 공지)"
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
    public_classes {
        bigint id PK
        varchar source
        varchar title
        varchar org_name
        varchar region
        date start_date
        varchar apply_url
        varchar category
        bit verified
        bit hidden
        varchar search_key
        bigint org_event_id
    }
    group_posts {
        bigint id PK
        varchar kind
        bigint owner_id
        bigint org_id
        date date
        double start_hour
        double hours
        varchar category
        int capacity
        varchar status
        bigint host_org_id
    }
    group_participants {
        bigint id PK
        bigint group_post_id
        bigint user_id
        varchar status
        double earned
    }
    recurring_posts {
        bigint id PK
        bigint owner_id
        bigint elder_id
        bigint created_by
        int weekday
        double start_hour
        double end_hour
        date start_date
        date end_date
        varchar status
        date last_generated
    }
    manager_applications {
        bigint id PK
        bigint org_id
        bigint user_id
        varchar login_id
        varchar proof_file_url
        varchar status
    }
    weather_alerts {
        bigint id PK
        varchar type
        varchar level
        varchar areas
        varchar source
        bigint notice_id
        datetime sent_at
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

> 2026-11-12 기능명세서 적용: 새 표 5개(`group_posts`, `group_participants`, `recurring_posts`, `manager_applications`, `weather_alerts`), 새 칸 3개(`requests.log_photo_url2`, `edu_progress.practice_json`, `notices.pinned`). 41판에서 쓰지 않던 `users.edu_done`·`edu_progress.completed_at`·`posts.repeat_rule`·`posts.series_id`·`org_events.capacity`를 다시 쓴다. 관리 대상의 `elders.caution`·`posts.issue_note`는 DB에는 그대로 있고, 응답에서 담당자와 일정이 확정된 봉사자에게만 준다(계약서 7.18 ④).

> 2026-11-11: `posts.issue_note`(300자) — 담당자 대리 공고의 "관리 대상 이슈". 비어 있으면 `elders.caution`을 보여 준다. 연락처·주소는 저장 전에 막고 병명은 걸러 낸다.

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
# (기능명세서 적용분은 이 명령을 돌리지 않고 엔티티를 보고 손으로 더했다. 다시 뽑으면 칸 순서·주석만 달라질 수 있다)
```
