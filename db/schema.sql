-- 시간 적금 DB 스키마 (MySQL 8 호환)
-- 실제 JPA 엔티티(backend/src/main/java/com/timesavings/domain)에서 Hibernate 스키마 생성으로 뽑은 DDL이다.
-- 다시 만들기: backend 에서 SPRING_PROFILES_ACTIVE=schema 로 실행(README 참고). H2(MODE=MySQL)에서도 같은 구조가 ddl-auto=update 로 생성된다.
-- 동네 클래스 테이블(classes·class_enrollments·spaces)과 ledger.class_id는 2026-11-10 사용자 결정으로 삭제했다(기존 DB의 남은 칸·행은 쓰지 않음).
-- 목록형 칸(categories, time_slots, needs, tags, keywords)은 쉼표로 이은 문자열, *_json 칸은 JSON 문자열이다.


create table ai_notes (
    created_at datetime(6),
    id bigint not null auto_increment,
    ref_id bigint not null,
    request_id bigint,
    kind varchar(10) not null,
    source varchar(10),
    warn_level varchar(10),
    ref_key varchar(20) not null,
    provider varchar(20),
    fingerprint varchar(120),
    body_json TEXT,
    primary key (id)
) engine=InnoDB;

create table auth_token (
    expires_at datetime(6) not null,
    user_id bigint not null,
    token varchar(64) not null,
    primary key (token)
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
    completed_at datetime(6),
    user_id bigint not null,
    diag_json TEXT,
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
    created_at datetime(6),
    elder_id bigint not null,
    id bigint not null auto_increment,
    text varchar(1000) not null,
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
    age_group varchar(20),
    alias varchar(30) not null,
    dong varchar(30),
    needs varchar(200),
    caution varchar(500),
    status enum ('ACTIVE','ENDED') not null,
    primary key (id)
) engine=InnoDB;

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

create table inquiries (
    answered_at datetime(6),
    created_at datetime(6),
    id bigint not null auto_increment,
    user_id bigint not null,
    type varchar(30),
    title varchar(100) not null,
    answer varchar(2000),
    content varchar(2000),
    status enum ('ANSWERED','RECEIVED') not null,
    primary key (id)
) engine=InnoDB;

create table interest_alerts (
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
    body varchar(500),
    primary key (id)
) engine=InnoDB;

create table org_events (
    capacity integer not null,
    event_date date,
    start_hour float(53),
    created_at datetime(6),
    created_by bigint,
    id bigint not null auto_increment,
    org_id bigint,
    apply_type varchar(5) not null,
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
    memo varchar(500),
    status enum ('APPROVED','PENDING','REJECTED') not null,
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
    id bigint not null auto_increment,
    owner_id bigint,
    area varchar(30),
    series_id varchar(40),
    repeat_rule varchar(50),
    title varchar(100),
    categories varchar(200),
    difficulty_reason varchar(300),
    note varchar(500),
    detail varchar(1000),
    difficulty enum ('BASIC','EFFORT','RESPONSIBLE'),
    status enum ('CLOSED','DELETED','MATCHED','OPEN') not null,
    type enum ('NEED','OFFER') not null,
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

create table regular_marks (
    created_at datetime(6),
    id bigint not null auto_increment,
    user_id bigint not null,
    volunteer_id bigint not null,
    primary key (id)
) engine=InnoDB;

create table reports (
    created_at datetime(6),
    handled_at datetime(6),
    id bigint not null auto_increment,
    reporter_id bigint not null,
    target_id bigint,
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
    date date not null,
    earned float(53),
    est_earn float(53) not null,
    hours float(53) not null,
    late_notified bit not null,
    photo_consent bit not null,
    reminded bit not null,
    start_hour float(53) not null,
    weight float(53) not null,
    check_code varchar(6),
    care_memo varchar(500),
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
    log_photo_url varchar(200),
    adjust_reason varchar(300),
    reject_reason varchar(300),
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

create table users (
    edu_done bit not null,
    penalty_count integer not null,
    voice_alert bit not null,
    warning_count integer not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    org_id bigint,
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

create index ix_ai_note_req
   on ai_notes (request_id);

create index ix_block_elder_vol
   on elder_blocks (elder_id, volunteer_id);

create index ix_ledger_user
   on ledger (user_id);

create index ix_lr_subject 
   on life_reports (subject_type, subject_id, report_month);

create index ix_msg_req 
   on messages (request_id);

create index ix_noti_user 
   on notifications (user_id);

create index ix_org_events_date 
   on org_events (event_date);

create index ix_posts_date 
   on posts (date);

create index ix_posts_owner 
   on posts (owner_id);

create index ix_req_status 
   on requests (status);

create index ix_req_post 
   on requests (post_id);

alter table users 
   add constraint ux_users_login unique (login_id);

alter table regular_marks 
   add constraint uq_regular_mark unique (user_id, volunteer_id);

alter table ai_notes 
   add constraint ux_ai_note unique (kind, ref_id, ref_key);
