-- 시간 적금 DB 스키마 (MySQL 8 호환)
-- 실제 JPA 엔티티(backend/src/main/java/com/timesavings/domain)에서 Hibernate 스키마 생성으로 뽑은 DDL이다.
-- 다시 만들기: backend 에서 SPRING_PROFILES_ACTIVE=schema 로 실행(README 참고). H2(MODE=MySQL)에서도 같은 구조가 ddl-auto=update 로 생성된다.
-- 동네 클래스 테이블(classes·class_enrollments·spaces)과 ledger.class_id는 2026-11-10 사용자 결정으로 삭제했다(기존 DB의 남은 칸·행은 쓰지 않음).
-- 목록형 칸(categories, time_slots, needs, tags, keywords)은 쉼표로 이은 문자열, *_json 칸은 JSON 문자열이다.
-- 2026-11-11 역할별 정리판: category_suggestions · life_plans · planner_picks · app_settings 추가, posts.issue_note(담당자 대리 공고의 관리 대상 이슈) 추가.
-- 2026-11-12 요구사항 명세서 41판 적용(계약서 7.17): 아래 칸을 더했다. 새 표는 없다. 실행 때는 ddl-auto=update 가 같은 칸을 만든다.
--   users: terms_agreed_at · privacy_agreed_at · loc_consent · interest_consent · noti_req · noti_msg · noti_result · noti_remind · noti_interest · sms_alert
--   requests: qr_secret · noshow_notified · auto_closed · gap_notified / elders: qr_code · qr_issued_at · service_consent_at · service_consent_by
--   reports: kind · elder_id / inquiries: stage / interest_alerts: date_from · date_to / organizations: proof_file_url
--   elder_memos: tidy_text · source / regular_marks: removed · removed_by / planner_picks: start_hour · hours · plan_source
--   app_settings 에 새 줄 edu.situations(첫 활동 연습 상황 목록 JSON, 표 변경 없음).
-- 41판부터 쓰지 않는 칸·표는 지우지 않고 그 줄에 "41판부터 쓰지 않음" 주석을 달았다(예전 자료를 읽기 위해 남김). 이 가운데 일부는 기능명세서에서 다시 쓴다(아래 '기능명세서 적용' 참고):
--   users.edu_done · edu_progress.completed_at · posts.repeat_rule · posts.series_id · org_events.capacity · org_events.apply_type · 표 event_applications
--   requests.check_code 는 값은 계속 들어가지만 시작·종료 확인에는 쓰지 않는다(qr_secret 없는 예전 자료의 대용 값).
-- 2026-11-12 남은 문제 고침(계약서 7.17 ⑪): 표 upload_files(올린 파일의 주인) 추가, users.banned(퇴출) 추가.
--   requests.check_method 의 'manual'(번호 없이 기록)은 더 만들지 않는다(예전 기록에만 남음). 표 event_applications 는 엔티티만 남고 쓰는 코드가 없다.
-- 2026-11-12 기능명세서(73개 기능) 적용(계약서 7.18): 엔티티에 맞춰 손으로 더했다(실행 때는 ddl-auto=update 가 같은 구조를 만든다).
--   새 표 5개: group_posts · group_participants(단체 봉사, 기능 U-10·U-11·O-07) / recurring_posts(정기 요청, 기능 U-09)
--             manager_applications(담당자 계정 신청 자동 승인 내역, 기능 C-04·A-11) / weather_alerts(기상 특보, 기능 A-08·A-09)
--   새 칸 3개: requests.log_photo_url2(집안일 "한 뒤" 사진, 기능 U-16) · edu_progress.practice_json(끝낸 연습 상황, 기능 U-34) · notices.pinned(고정 공지, 기능 C-08·A-16)
--   41판에서 "쓰지 않음"이던 칸을 기능명세서에서 다시 쓴다: users.edu_done · edu_progress.completed_at(첫 활동 교육 이수, 기능 U-34)
--     posts.repeat_rule · posts.series_id(정기 요청 회차 공고, 기능 U-09) · org_events.capacity(행사 정원 — 표시용, 기능 O-09)
--   여전히 쓰지 않는 것: org_events.apply_type(늘 LINK) · 표 event_applications · requests.check_code(확인에는 안 씀)
--   표는 그대로이고 값만 늘어난 것: ai_results.kind 에 FIN_MONTHLY(재정 추계 월간 요약, ref_key=연-월)
--     app_settings 에 categories.extra(운영자가 더한 카테고리 {이름: 등급} JSON), verify.items 값에 edu 항목
--     ledger 의 단체 봉사 적립은 type=EARN · request_id null · label "단체 봉사 · …"
-- 2026-11-13 사용자 화면 변경(계약서 7.20): 새 표 post_bookmarks(관심 공고 — 사용자가 ☆로 담은 1:1 공고). 다른 표는 그대로.


create table ai_notes (
    created_at datetime(6),
    id bigint not null auto_increment,
    ref_id bigint not null,
    request_id bigint,
    kind varchar(10) not null,
    source varchar(10),
    warn_level varchar(10),
    provider varchar(20),
    ref_key varchar(20) not null,
    fingerprint varchar(120),
    body_json TEXT,
    primary key (id)
) engine=InnoDB;

create table ai_results (
    created_at datetime(6),
    id bigint not null auto_increment,
    source varchar(10),
    kind varchar(20) not null,
    provider varchar(20),
    ref_key varchar(60) not null,
    fingerprint varchar(300),
    payload TEXT,
    primary key (id)
) engine=InnoDB;

create table app_settings (
    updated_at datetime(6),
    setting_key varchar(50) not null,
    setting_value varchar(2000),
    primary key (setting_key)
) engine=InnoDB;

create table auth_token (
    expires_at datetime(6) not null,
    user_id bigint not null,
    token varchar(64) not null,
    primary key (token)
) engine=InnoDB;

create table care_notes (
    activity_date date,
    flag_raised bit not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    request_id bigint not null,
    subject_id bigint not null,
    updated_at datetime(6),
    source varchar(10),
    provider varchar(20),
    hospital varchar(200),
    signals varchar(200),
    life_changes varchar(600),
    next_steps varchar(600),
    subject_type enum ('ELDER','USER') not null,
    primary key (id)
) engine=InnoDB;

create table category_suggestions (
    answered_at datetime(6),
    answered_by bigint,
    created_at datetime(6),
    id bigint not null auto_increment,
    user_id bigint not null,
    status varchar(10) not null,
    name varchar(30) not null,
    name_key varchar(30) not null,
    answer varchar(500),
    reason varchar(500),
    primary key (id)
) engine=InnoDB;

create table difficulty_appeals (
    created_at datetime(6),
    id bigint not null auto_increment,
    request_id bigint not null,
    user_id bigint not null,
    admin_note varchar(500),
    reason varchar(500),
    requested_level enum ('BASIC','EFFORT','RESPONSIBLE'),
    result_level enum ('BASIC','EFFORT','RESPONSIBLE'),
    status enum ('ACCEPTED','RECEIVED','REJECTED') not null,
    primary key (id)
) engine=InnoDB;

create table edu_progress (
    cards_done bit not null,
    quiz_score integer,
    completed_at datetime(6), -- 기능명세서(기능 U-34)에서 다시 씀: 첫 활동 교육 이수 시각(연습 상황을 모두 마친 때). 41판에서는 쓰지 않았다
    user_id bigint not null,
    diag_json TEXT,
    practice_json TEXT, -- 기능명세서 추가(기능 U-34): 안전 규칙에 맞게 끝낸 연습 상황 key 목록(JSON 배열)
    roleplay_json TEXT,
    primary key (user_id)
) engine=InnoDB;

create table elder_blocks (
    created_at datetime(6),
    elder_id bigint not null,
    id bigint not null auto_increment,
    manager_id bigint,
    request_id bigint,
    volunteer_id bigint not null,
    reason varchar(300) not null,
    primary key (id)
) engine=InnoDB;

create table elder_memos (
    source varchar(5), -- 41판 추가: 정리본 출처 ai/rule
    created_at datetime(6),
    elder_id bigint not null,
    id bigint not null auto_increment,
    text varchar(1000) not null,
    tidy_text varchar(1000), -- 41판 추가: 메모 정리본(C-13, 원문은 text)
    primary key (id)
) engine=InnoDB;

create table elder_transfers (
    created_at datetime(6),
    elder_id bigint not null,
    from_manager_id bigint,
    id bigint not null auto_increment,
    to_manager_id bigint not null,
    status enum ('ACCEPTED','ADMIN','PENDING','REJECTED') not null,
    primary key (id)
) engine=InnoDB;

create table elders (
    report_consent bit not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    manager_id bigint,
    org_id bigint,
    qr_issued_at datetime(6), -- 41판 추가: 현관 QR 카드 발급 시각
    service_consent_at datetime(6), -- 41판 추가: 서비스 이용 설명·동의 확인 시각(C-02)
    service_consent_by bigint, -- 41판 추가: 동의를 확인한 담당자
    qr_code varchar(12), -- 41판 추가: 현관 QR 카드 번호(6자리, 다시 발급하면 바뀜, C-11)
    age_group varchar(20),
    alias varchar(30) not null,
    dong varchar(30),
    needs varchar(200),
    caution varchar(500),
    status enum ('ACTIVE','ENDED') not null,
    primary key (id)
) engine=InnoDB;

-- 41판부터 쓰지 않음: 동네 기관 소식의 앱 안 신청을 없앴다(B-64). 예전 신청 기록을 읽기 위해 표만 남긴다.
create table event_applications (
    created_at datetime(6),
    event_id bigint not null,
    id bigint not null auto_increment,
    user_id bigint not null,
    status varchar(10) not null,
    primary key (id)
) engine=InnoDB;

create table family_links (
    share_report bit not null,
    target_share_report bit not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    requester_id bigint not null,
    target_id bigint not null,
    relation varchar(20),
    status enum ('ACCEPTED','PENDING','REJECTED') not null,
    primary key (id)
) engine=InnoDB;

-- 기능명세서 추가(기능 U-10·U-11·O-07): 단체 봉사 참여자. status = JOINED(참여 신청) · LEFT(취소) · ATTENDED(참석 확인·적립) · ABSENT(불참)
create table group_participants (
    earned float(53), -- 참석이 확인돼 적립된 시간(ledger 의 EARN 과 같은 값)
    created_at datetime(6),
    group_post_id bigint not null,
    id bigint not null auto_increment,
    user_id bigint not null,
    status varchar(10) not null,
    primary key (id)
) engine=InnoDB;

-- 기능명세서 추가(기능 U-10·U-11·O-07): 단체 봉사 공고. 1:1 공고(posts)·요청(requests)과 섞지 않는 별도 표.
-- kind = RECRUIT(모집형 — 기관 담당자가 올림) · OFFER(제공형 — 단체가 봉사 가능). status = OPEN · DONE · CANCELLED
create table group_posts (
    capacity integer not null, -- 모집 인원(모집형) 또는 함께 봉사할 인원(제공형). 2명 이상
    date date not null,
    hours float(53) not null,
    start_hour float(53) not null,
    created_at datetime(6),
    done_at datetime(6),
    done_by bigint, -- 참석을 확인하고 완료한 담당자
    host_by bigint,
    host_org_id bigint, -- 제공형: 이 단체의 봉사를 받기로 한 기관
    hosted_at datetime(6),
    id bigint not null auto_increment,
    org_id bigint, -- 담당자가 올렸으면 그 기관
    owner_id bigint not null,
    kind varchar(8) not null,
    status varchar(10) not null,
    area varchar(30),
    category varchar(30),
    group_name varchar(50),
    place varchar(100), -- 모이는 곳(기관·공공장소 이름만, 개인 주소 금지)
    title varchar(100),
    detail varchar(1000),
    primary key (id)
) engine=InnoDB;

create table inquiries (
    answered_at datetime(6),
    created_at datetime(6),
    id bigint not null auto_increment,
    user_id bigint not null,
    stage varchar(10), -- 41판 추가: 처리 단계 RECEIVED/CHECKING/ANSWERED/CLOSED(D-08, null이면 status 로 봄)
    type varchar(30),
    title varchar(100) not null,
    answer varchar(2000),
    content varchar(2000),
    status enum ('ANSWERED','RECEIVED') not null,
    primary key (id)
) engine=InnoDB;

create table interest_alerts (
    date_from date, -- 41판 추가: 관심 조건 날짜 시작(B-21, null=상관없음)
    date_to date, -- 41판 추가: 관심 조건 날짜 끝
    enabled bit not null,
    id bigint not null auto_increment,
    user_id bigint not null,
    day_type varchar(10),
    area varchar(30),
    categories varchar(200),
    primary key (id)
) engine=InnoDB;

create table ledger (
    amount float(53) not null,
    base_hours float(53),
    weight float(53),
    created_at datetime(6),
    elder_id bigint,
    id bigint not null auto_increment,
    request_id bigint,
    user_id bigint,
    label varchar(200),
    type enum ('ADJUST','CLASS_EARN','CLASS_FEE','EARN','GIFT_IN','GIFT_OUT','HOLD','POOL','REFUND','RELEASE','USE') not null,
    primary key (id)
) engine=InnoDB;

create table life_plans (
    age integer not null,
    monthly_hours float(53) not null,
    start_age integer not null,
    updated_at datetime(6),
    user_id bigint not null,
    needs_json varchar(500),
    primary key (user_id)
) engine=InnoDB;

create table life_reports (
    big_change bit not null,
    source varchar(5),
    report_month varchar(7) not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    subject_id bigint not null,
    narrative varchar(2000),
    stats_json TEXT,
    subject_type enum ('ELDER','USER') not null,
    primary key (id)
) engine=InnoDB;

-- 기능명세서 추가(기능 C-04·A-11): 담당자 계정 신청. 승인된 기관 + 휴대폰 본인인증이면 자동 승인되어 담당자 계정(users)이 바로 생긴다.
-- status = APPROVED(자동 승인) · REVOKED(운영자가 취소 — 그 계정은 정지)
create table manager_applications (
    existing_managers integer, -- 신청 때 그 기관에 이미 있던 담당자 수
    created_at datetime(6),
    id bigint not null auto_increment,
    org_id bigint not null,
    revoked_at datetime(6),
    user_id bigint, -- 자동 승인으로 만든 담당자 계정
    status varchar(10) not null,
    phone varchar(20),
    login_id varchar(50),
    name varchar(50),
    proof_file_url varchar(200), -- 재직 증빙 파일(운영자만 연다). 내용은 자동 승인 때 검사하지 않는다
    revoke_reason varchar(300),
    primary key (id)
) engine=InnoDB;

create table messages (
    system_msg bit not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    request_id bigint not null,
    sender_id bigint,
    sender_label varchar(50),
    warn varchar(200),
    text varchar(1000) not null,
    primary key (id)
) engine=InnoDB;

create table notices (
    pinned bit, -- 기능명세서 추가(기능 C-08·A-16): 고정 공지(목록 맨 위). null·false = 고정 아님
    created_at datetime(6),
    id bigint not null auto_increment,
    title varchar(100) not null,
    content varchar(4000),
    primary key (id)
) engine=InnoDB;

create table notifications (
    is_read bit not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    user_id bigint not null,
    type varchar(30),
    link varchar(200),
    title varchar(200) not null,
    voice_text varchar(300),
    body varchar(500),
    primary key (id)
) engine=InnoDB;

create table org_events (
    capacity integer not null, -- 기능명세서(기능 O-09)에서 다시 씀: 행사 정원(표시용 숫자, 0 = 제한 없음). 앱 안 신청·남은 자리 계산은 없다. 41판에서는 쓰지 않았다
    event_date date,
    start_hour float(53),
    apply_type varchar(5) not null, -- 41판부터 쓰지 않음(B-64 신청은 기관 페이지 링크로만, 새 소식은 항상 LINK)
    created_at datetime(6),
    created_by bigint,
    id bigint not null auto_increment,
    org_id bigint,
    status varchar(10) not null,
    place varchar(100),
    title varchar(100) not null,
    og_title varchar(200),
    og_desc varchar(300),
    og_image varchar(500),
    url varchar(500),
    content varchar(2000),
    primary key (id)
) engine=InnoDB;

create table organizations (
    created_at datetime(6),
    id bigint not null auto_increment,
    manager_phone varchar(20),
    phone varchar(20),
    org_type varchar(30),
    manager_name varchar(50),
    manager_email varchar(100),
    name varchar(100) not null,
    address varchar(200),
    proof_file_url varchar(200), -- 41판 추가: 재직 증빙 파일 주소(C-01)
    memo varchar(500),
    status enum ('APPROVED','PENDING','REJECTED') not null,
    primary key (id)
) engine=InnoDB;

create table planner_picks (
    hours float(53), -- 41판 추가: 계획 시간
    start_hour float(53), -- 41판 추가: 계획 시작 시각(AI 또는 규칙이 빈 시간에서 정함, B-61)
    created_at datetime(6),
    id bigint not null auto_increment,
    post_id bigint not null,
    user_id bigint not null,
    plan_source varchar(10), -- 41판 추가: ai/rule
    primary key (id)
) engine=InnoDB;

create table post_bookmarks ( -- 2026-11-13 추가: 관심 공고(북마크)
    created_at datetime(6),
    id bigint not null auto_increment,
    post_id bigint not null,
    user_id bigint not null,
    primary key (id)
) engine=InnoDB;

create table posts (
    date date not null,
    end_hour float(53) not null,
    est_hours float(53),
    start_hour float(53) not null,
    weight float(53),
    created_at datetime(6),
    created_by bigint,
    elder_id bigint,
    fix_checked_at datetime(6),
    id bigint not null auto_increment,
    owner_id bigint,
    area varchar(30),
    series_id varchar(40), -- 기능명세서(기능 U-09)에서 다시 씀: 정기 요청 회차 공고면 "RC{recurring_posts.id}", 보통 공고는 null. 41판에서는 쓰지 않았다
    repeat_rule varchar(50), -- 기능명세서(기능 U-09)에서 다시 씀: 정기 요청 회차 공고면 "매주 목요일" 같은 글, 보통 공고는 null. 41판에서는 쓰지 않았다
    title varchar(100),
    categories varchar(200),
    difficulty_reason varchar(300),
    easy_text varchar(300),
    issue_note varchar(300),
    note varchar(500),
    detail varchar(1000),
    fix_tips_json varchar(3000),
    difficulty enum ('BASIC','EFFORT','RESPONSIBLE'),
    status enum ('CLOSED','DELETED','MATCHED','OPEN') not null,
    type enum ('NEED','OFFER') not null,
    primary key (id)
) engine=InnoDB;

create table public_classes (
    end_date date,
    hidden bit not null,
    lat float(53),
    lng float(53),
    start_date date,
    verified bit not null,
    created_at datetime(6),
    expires_at datetime(6),
    fetched_at datetime(6),
    id bigint not null auto_increment,
    org_event_id bigint,
    verified_at datetime(6),
    category varchar(10) not null,
    source varchar(12) not null,
    capacity_text varchar(60),
    fee_text varchar(60),
    region varchar(60),
    search_key varchar(80),
    org_name varchar(100),
    apply_how varchar(200),
    page_title varchar(200),
    place varchar(200),
    schedule_text varchar(200),
    target varchar(200),
    title varchar(200) not null,
    apply_url varchar(500),
    image_url varchar(500),
    description varchar(1000),
    raw_json TEXT,
    primary key (id)
) engine=InnoDB;

create table public_services (
    created_at datetime(6),
    id bigint not null auto_increment,
    region varchar(50),
    name varchar(100) not null,
    contact varchar(200),
    apply_how varchar(300),
    target varchar(300),
    keywords varchar(500),
    content varchar(1000),
    primary key (id)
) engine=InnoDB;

create table ratings (
    is_private bit not null,
    stars integer not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    rater_id bigint not null,
    request_id bigint not null,
    target_user_id bigint not null,
    tags varchar(300),
    comment varchar(500),
    primary key (id)
) engine=InnoDB;

-- 기능명세서 추가(기능 U-09): 정기 요청. 매주 한 요일·시간과 기간을 정해 두면 스케줄러가 다가오는 회차(7일 안)의 도움 요청 공고를 만든다.
-- status = ACTIVE(진행) · PAUSED(일시정지) · ENDED(기간 끝) · CANCELLED(해지). 회차 공고는 posts.series_id = "RC{id}" 로 이어진다.
create table recurring_posts (
    end_date date not null, -- 마지막 회차 날짜
    end_hour float(53),
    est_hours float(53),
    last_generated date, -- 회차 공고를 만든(또는 건너뛴) 마지막 날짜
    start_date date not null, -- 첫 회차 날짜
    start_hour float(53),
    weekday integer not null, -- 1=월 … 7=일
    created_at datetime(6),
    created_by bigint not null, -- 등록한 사람(본인·가족 대신 올린 사람·담당자)
    elder_id bigint, -- 담당자의 관리 대상 대리 요청이면 그 대상
    id bigint not null auto_increment,
    owner_id bigint, -- 도움 받는 사용자(가족 대신이면 그 가족). 관리 대상 대리면 null
    updated_at datetime(6),
    status varchar(10) not null,
    area varchar(30),
    title varchar(100),
    categories varchar(200),
    stop_reason varchar(200), -- 자동으로 멈춘 까닭(가족 연결 끊김 등)
    issue_note varchar(300), -- 관리 대상 이슈(회차 공고의 posts.issue_note 로 복사)
    note varchar(500),
    detail varchar(1000),
    primary key (id)
) engine=InnoDB;

create table regular_marks (
    removed bit, -- 41판 추가: 해지됨(B-65, 자동 3회 단골 해지도 이 줄로 기록)
    created_at datetime(6),
    id bigint not null auto_increment,
    removed_by bigint, -- 41판 추가: 해지한 사용자 id(봉사자가 해지했으면 다시 등록 불가)
    user_id bigint not null,
    volunteer_id bigint not null,
    primary key (id)
) engine=InnoDB;

create table reports (
    created_at datetime(6),
    elder_id bigint, -- 41판 추가: 이상 징후 접수가 가리키는 도움 대상
    handled_at datetime(6),
    id bigint not null auto_increment,
    reporter_id bigint not null,
    target_id bigint,
    kind varchar(10), -- 41판 추가: null=일반 신고, WELFARE=이상 징후(안부 확인) 접수(B-36, D-02)
    type varchar(30),
    target_label varchar(100),
    result varchar(500),
    content varchar(1000),
    action enum ('BAN','NONE','SUSPEND','WARNING'),
    status enum ('DONE','RECEIVED','REVIEWING') not null,
    target_type enum ('MESSAGE','POST','REQUEST','USER') not null,
    primary key (id)
) engine=InnoDB;

create table requests (
    adjust_hours float(53),
    anomaly_hold bit not null,
    auto_closed boolean default false, -- 41판 추가: 예정 종료 시각으로 자동 마감됨(B-53)
    date date not null,
    earned float(53),
    est_earn float(53) not null,
    gap_notified boolean default false, -- 41판 추가: 기록 시간과 예정 시간 30분 이상 차이 안내 보냄(B-51)
    hours float(53) not null,
    late_notified bit not null,
    noshow_notified boolean default false, -- 41판 추가: 노쇼 의심(30분) 알림 보냄(B-52)
    photo_consent bit not null,
    reminded bit not null,
    start_hour float(53) not null,
    weight float(53) not null,
    check_code varchar(6), -- 41판부터 시작·종료 확인에 쓰지 않음(값은 들어감. qr_secret 없는 예전 자료의 대용 값)
    check_in_at datetime(6),
    check_out_at datetime(6),
    confirmed_at datetime(6),
    created_at datetime(6),
    deadline_at datetime(6),
    done_at datetime(6),
    elder_id bigint,
    id bigint not null auto_increment,
    log_submitted_at datetime(6),
    post_id bigint,
    recipient_id bigint,
    sender_id bigint not null,
    volunteer_id bigint not null,
    adjust_status varchar(10),
    check_method varchar(10),
    category varchar(20),
    qr_secret varchar(64), -- 41판 추가: 30초마다 바뀌는 QR·번호를 만드는 비밀값(확정 때 생성, B-51)
    log_photo_url varchar(200), -- 일지 사진. 기능 U-16: 장보기·심부름은 영수증, 가벼운 집안일은 "하기 전" 사진(이 두 종류는 없으면 일지 제출 불가)
    log_photo_url2 varchar(200), -- 기능명세서 추가(기능 U-16): 가벼운 집안일의 "한 뒤" 사진
    match_reason varchar(200),
    adjust_reason varchar(300),
    reject_reason varchar(300),
    care_memo varchar(500),
    log_raw varchar(500),
    memo varchar(500),
    content varchar(1000),
    log_text varchar(1000),
    difficulty enum ('BASIC','EFFORT','RESPONSIBLE'),
    kind enum ('PROPOSAL','REGULAR','REQUEST') not null,
    status enum ('CANCELLED','CHECKED_OUT','CONFIRMED','DECLINED','DONE','EXPIRED','IN_PROGRESS','LOG_SUBMITTED','NOSHOW','REJECTED','SENT','VOL_ACCEPTED') not null,
    primary key (id)
) engine=InnoDB;

create table roleplay_personas (
    active bit not null,
    initial varchar(5),
    id bigint not null auto_increment,
    fit_category varchar(20),
    age_label varchar(30),
    name varchar(30) not null,
    goal varchar(200),
    trait varchar(200),
    opening varchar(300),
    turns_json TEXT,
    primary key (id)
) engine=InnoDB;

-- 2026-11-12 추가: 올린 파일의 주인 기록. 파일(/api/files/{file_id})은 로그인하고 볼 수 있는 사람만 연다(계약서 7.17 ⑪).
-- uploader_id 가 null 이면 로그인 없이 올린 파일(기관 신청의 재직 증빙)로 운영자만 연다.
create table upload_files (
    created_at datetime(6),
    uploader_id bigint,
    file_id varchar(40) not null,
    primary key (file_id)
) engine=InnoDB;

create table users (
    edu_done bit not null, -- 기능명세서(기능 U-34)에서 다시 씀: 첫 활동 교육(안전 교육) 이수 여부. 필수 설정이면 false 인 사용자는 봉사할 수 없다. 41판에서는 쓰지 않았다
    interest_consent bit, -- 41판 추가: 관심 공고 알림 받기 동의(선택)
    loc_consent bit, -- 41판 추가: 위치 정보 이용 동의(선택, null=예전 가입자)
    noti_interest bit, -- 41판 추가: 알림 설정 — 관심 공고
    noti_msg bit, -- 41판 추가: 알림 설정 — 새 메시지
    noti_remind bit, -- 41판 추가: 알림 설정 — 시작 전 리마인드(1시간 전)
    noti_req bit, -- 41판 추가: 알림 설정 — 새 요청·제안(null=켜짐, B-58)
    noti_result bit, -- 41판 추가: 알림 설정 — 수락·거절 결과
    banned bit, -- 2026-11-12 추가: 퇴출(D-02). true 면 다시 로그인할 수 없고 정지 해제로 풀 수 없다(status 는 SUSPENDED)
    penalty_count integer not null,
    sms_alert bit, -- 41판 추가: 문자 병행(B-43, 발송은 모의)
    voice_alert bit not null,
    warning_count integer not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    org_id bigint,
    privacy_agreed_at datetime(6), -- 41판 추가: 개인정보 수집·이용 동의 시각(A-01 필수)
    terms_agreed_at datetime(6), -- 41판 추가: 서비스 이용약관 동의 시각(A-01 필수)
    text_size varchar(10),
    age_group varchar(20),
    phone varchar(20),
    area varchar(30),
    login_id varchar(50) not null,
    name varchar(50) not null,
    password_hash varchar(100) not null,
    categories varchar(200),
    crime_file_url varchar(200),
    id_file_url varchar(200),
    photo_url varchar(200),
    time_slots varchar(200),
    experience varchar(300),
    intro varchar(300),
    personality varchar(300),
    crime_status enum ('APPROVED','NONE','PENDING','REJECTED'),
    id_status enum ('APPROVED','NONE','PENDING','REJECTED'),
    role enum ('ADMIN','ORG','USER') not null,
    status enum ('ACTIVE','SUSPENDED','WITHDRAWN') not null,
    primary key (id)
) engine=InnoDB;

-- 기능명세서 추가(기능 A-08·A-09): 기상 특보 한 건. source = MANUAL(운영자 입력) · SAMPLE(시연용 표본) · KMA(기상청 API).
-- 전달하면 공지(notice_id)와 보낸 시각·담당자 수가 남는다(한 번만 전달)
create table weather_alerts (
    end_date date,
    sent_managers integer,
    start_date date,
    created_at datetime(6),
    id bigint not null auto_increment,
    notice_id bigint,
    sent_at datetime(6),
    level varchar(10) not null, -- 주의보 · 경보
    source varchar(10),
    type varchar(10) not null, -- 폭염·한파·호우·대설·강풍·태풍·건조
    areas varchar(300), -- 동 이름을 쉼표로 이은 목록
    memo varchar(300),
    report_json varchar(3000), -- {summary, actions[], source, provider}
    primary key (id)
) engine=InnoDB;

create index ix_ai_note_req 
   on ai_notes (request_id);

alter table ai_notes 
   add constraint ux_ai_note unique (kind, ref_id, ref_key);

alter table ai_results 
   add constraint ix_ai_result_key unique (kind, ref_key);

create index ix_care_note_subject 
   on care_notes (subject_type, subject_id);

alter table care_notes 
   add constraint ix_care_note_req unique (request_id);

create index ix_cat_sug_user 
   on category_suggestions (user_id);

create index ix_block_elder_vol 
   on elder_blocks (elder_id, volunteer_id);

create index ix_group_part_post 
   on group_participants (group_post_id);

create index ix_group_part_user 
   on group_participants (user_id);

create index ix_group_posts_date 
   on group_posts (date);

create index ix_group_posts_owner 
   on group_posts (owner_id);

create index ix_ledger_user 
   on ledger (user_id);

create index ix_lr_subject 
   on life_reports (subject_type, subject_id, report_month);

create index ix_mgrapp_org 
   on manager_applications (org_id);

create index ix_msg_req 
   on messages (request_id);

create index ix_noti_user 
   on notifications (user_id);

create index ix_org_events_date 
   on org_events (event_date);

alter table planner_picks 
   add constraint uq_planner_pick unique (user_id, post_id);

create index ix_posts_date 
   on posts (date);

create index ix_posts_owner 
   on posts (owner_id);

create index ix_pc_source 
   on public_classes (source);

create index ix_pc_start 
   on public_classes (start_date);

create index ix_pc_search_key 
   on public_classes (search_key);

create index ix_recur_owner 
   on recurring_posts (owner_id);

create index ix_recur_status 
   on recurring_posts (status);

alter table post_bookmarks 
   add constraint uq_post_bookmark unique (user_id, post_id);

create index ix_post_bookmark_user 
   on post_bookmarks (user_id);

alter table regular_marks 
   add constraint uq_regular_mark unique (user_id, volunteer_id);

create index ix_req_status 
   on requests (status);

create index ix_req_post 
   on requests (post_id);

alter table users 
   add constraint ux_users_login unique (login_id);

-- 공공 서비스 연계 (2026-11-13): 사용자가 "담당자에게 전달하기"를 누르고 동의했을 때만 만들어진다
CREATE TABLE IF NOT EXISTS public_referrals (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  org_id BIGINT NOT NULL,
  question VARCHAR(1000) NOT NULL,   -- 사용자가 물어본 말
  answer VARCHAR(2000),              -- AI가 한 안내 문장
  services_json VARCHAR(6000),       -- AI가 찾은 서비스 목록(JSON)
  note VARCHAR(300),                 -- 담당자에게 한마디
  status VARCHAR(15) NOT NULL DEFAULT 'NEW',  -- NEW, CONTACTED, HELPING, DONE, NOT_ELIGIBLE
  memo VARCHAR(500),                 -- 담당자 처리 메모
  handler_id BIGINT,
  consent_at DATETIME,               -- 전달 동의 시각
  created_at DATETIME,
  updated_at DATETIME
);
