package com.timesavings.domain;

/** 계약서 3·4장의 상태 값. 이름을 그대로 JSON 문자열로 주고받는다. */
public final class Enums {
    private Enums() {}
    public enum Role { USER, ORG, ADMIN }
    public enum UserStatus { ACTIVE, SUSPENDED, WITHDRAWN }
    public enum VerifyStatus { NONE, PENDING, APPROVED, REJECTED }
    public enum OrgStatus { PENDING, APPROVED, REJECTED }
    public enum ElderStatus { ACTIVE, ENDED }
    public enum TransferStatus { PENDING, ACCEPTED, REJECTED, ADMIN }
    public enum PostType { OFFER, NEED }
    public enum PostStatus { OPEN, MATCHED, CLOSED, DELETED }
    public enum Level { BASIC, EFFORT, RESPONSIBLE }
    public enum Kind { REQUEST, PROPOSAL, REGULAR }
    public enum ReqStatus {
        SENT, VOL_ACCEPTED, CONFIRMED, IN_PROGRESS, CHECKED_OUT, LOG_SUBMITTED, DONE,
        REJECTED, DECLINED, EXPIRED, CANCELLED, NOSHOW;
        public boolean terminal() { return this == DONE || this == REJECTED || this == DECLINED || this == EXPIRED || this == CANCELLED || this == NOSHOW; }
        public boolean pending() { return this == SENT || this == VOL_ACCEPTED; }
        public boolean active() { return this == CONFIRMED || this == IN_PROGRESS || this == CHECKED_OUT || this == LOG_SUBMITTED; }
    }
    public enum LedgerType { EARN, HOLD, RELEASE, USE, GIFT_OUT, GIFT_IN, CLASS_FEE, CLASS_EARN, ADJUST, REFUND, POOL }
    public enum ReportTarget { POST, REQUEST, MESSAGE, USER }
    public enum ReportStatus { RECEIVED, REVIEWING, DONE }
    public enum ReportAction { NONE, WARNING, SUSPEND, BAN }
    public enum InquiryStatus { RECEIVED, ANSWERED }
    public enum LinkStatus { PENDING, ACCEPTED, REJECTED }
    public enum AppealStatus { RECEIVED, ACCEPTED, REJECTED }
    public enum SubjectType { USER, ELDER }
    public enum ClassStatus { SPACE_PENDING, OPEN, DONE, CANCELLED }
    public enum EnrollStatus { APPLIED, ATTENDED, CANCELLED }
    public enum FilterLevel { ALLOW, CAUTION, BLOCK }
}
