import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Loading, Empty, useLoad, ChipsPick, hasBack } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { ago, dshort } from '../lib/format.js';
import { VoiceField } from '../ui/VoiceInput.jsx';
import { I } from '../ui/icons.js';

const NT_IC = (t = '') => {
  t = String(t).toUpperCase();
  if (/REQUEST|PROPOSAL|NEW_REQ/.test(t)) return ['hand', ''];
  if (/ACCEPT|CONFIRM|FINAL/.test(t)) return ['check', ''];
  if (/MESSAGE|MSG/.test(t)) return ['msg', 'amber'];
  if (/LOG/.test(t)) return ['file', ''];
  if (/WELFARE/.test(t)) return ['bell', 'amber'];
  if (/REMIND|CHECK|NOSHOW|LATE|AUTO_CLOSED|TIME_GAP/.test(t)) return ['clock', 'blue'];
  if (/REPORT/.test(t)) return ['chart', ''];
  if (/EARN|GIFT|BANK/.test(t)) return ['wallet', ''];
  if (/INQUIR|NOTICE/.test(t)) return ['msg', ''];
  return ['bell', ''];
};

/** 서버가 알림에 적는 링크 → 화면 경로. 위에서부터 처음 맞는 것 하나만 바꾼다. */
const LINK_MAP = [
  [/^\/requests\/(\d+)/, '/req/$1'],                 // 요청·제안·메시지·활동 알림
  [/^\/posts\/(\d+)/, '/post/$1'],                   // 관심 공고·가족 대신 공고
  [/^\/posts\/?$/, '/board'],                         // 공고 번호가 없는 옛 알림은 공고 목록으로
  [/^\/my\/(inquiries|reports)\b.*/, '/qna'],         // 문의 답변·신고 처리 결과 → 문의·신고 내역
  [/^\/family\/(\d+)\/report/, '/my/family/$1/monthly'], // 가족 월별 리포트
  [/^\/org\/elders\/(\d+)/, '/o/elders/$1'],
  [/^\/org\/transfers\b.*/, '/o/elders'],            // 이관 신청은 도움 대상 목록 위에 보인다
  [/^\/org\/reports\b.*/, '/o/report'],
  [/^\/admin\/reports\b.*/, '/a/safety'],
  [/^\/admin\/inquiries\b.*/, '/a/inquiries'],
  [/^\/admin\/orgs\b.*/, '/a/approve?seg=org'],
  [/^\/admin\/verifications\b.*/, '/a/approve?seg=vol'],
  [/^\/admin\/appeals\b.*/, '/a/approve?seg=diff'],
];

export function goLink(nav, link, role) {
  if (!link) return;
  let l = String(link);
  if (!l.startsWith('/')) l = '/' + l;
  // 서버 알림 링크를 화면 경로로 (A-07: 알림을 누르면 해당 요청·메시지·문의·신고 화면으로)
  for (const [from, to] of LINK_MAP) {
    if (from.test(l)) { l = l.replace(from, to); break; }
  }
  // 담당자는 요청 상세를 담당자 화면으로
  if (role === 'ORG') l = l.replace(/^\/req\/(\d+)/, '/o/reqs/$1');
  nav(l);
}

/* ---------- 알림 (A-07, C-08) ---------- */
export function Notif() {
  const nav = useNavigate();
  const { role, refreshUnread } = useAuth();
  const [list, reload] = useLoad(() => api('/notifications'), []);
  return (
    <Screen title="알림" noTabs noBell>
      <div className="between" style={{ marginBottom: 4 }}>
        <span className="mute2">{role === 'ORG' ? '담당 대상 관련 알림을 대신 받아요' : '요청·메시지·일정 알림'}</span>
        <a className="mute2" role="button" tabIndex={0} style={{ cursor: 'pointer' }} onClick={async () => { await post('/notifications/read-all'); reload(); refreshUnread(); }}>모두 읽음</a>
      </div>
      {list === undefined ? <Loading /> : !list || !list.length ? <Empty>새 알림이 없어요</Empty> : list.map((n) => {
        const [i, c] = NT_IC(n.type);
        return (
          <div key={n.id} className={'nt ' + (!n.isRead && !n.read ? 'new' : '')} role="button" tabIndex={0} onClick={async () => {
            if (!n.isRead && !n.read) { try { await post(`/notifications/${n.id}/read`, {}, { quiet: true }); refreshUnread(); } catch { /* 무시 */ } }
            if (n.link) goLink(nav, n.link, role); else reload();
          }}>
            <div className={'ic ' + c}><Ic n={i} s={20} /></div>
            <div className="grow"><div className="b" style={{ fontSize: 15 }}>{n.title}</div>{n.body && <div className="mute2">{n.body}</div>}</div>
            <span className="mute2" style={{ flex: '0 0 auto', fontSize: 12 }}>{ago(n.createdAt)}</span>
          </div>
        );
      })}
    </Screen>
  );
}

/* ---------- 공지사항 (A-10) ---------- */
export function Notice() {
  const nav = useNavigate();
  const [list] = useLoad(() => api('/notices'), []);
  return (
    <Screen title="공지사항" noTabs>
      {list === undefined ? <Loading /> : !list || !list.length ? <Empty>공지가 없어요</Empty> : (
        <div className="ml">{list.map((n) => <div key={n.id} role="button" tabIndex={0} onClick={() => nav(`/notice/${n.id}`)}><div className="grow">{n.pinned && <em className="badge" style={{ fontStyle: 'normal', marginRight: 6 }}>고정</em>}{n.title}<span>{dshort(n.createdAt).split(' ')[0]}</span></div>›</div>)}</div>
      )}
    </Screen>
  );
}
export function NoticeDetail() {
  const { id } = useParams();
  const [n] = useLoad(() => api(`/notices/${id}`), [id]);
  return (
    <Screen title="공지사항" noTabs>
      {n === undefined ? <Loading /> : !n ? <Empty>공지를 찾을 수 없어요</Empty> : (
        <>
          <div className="b" style={{ fontSize: 20, letterSpacing: '-.5px' }}>{n.title}</div>
          <div className="mute2">{dshort(n.createdAt)}</div>
          <div className="card" style={{ marginTop: 14, whiteSpace: 'pre-wrap' }}>{n.content}</div>
        </>
      )}
    </Screen>
  );
}

/* ---------- 1:1 문의 · 신고 내역 (B-37, B-47~49) ---------- */
/** D-08 문의 처리 단계(운영자가 따로 바꿈). stage 가 없는 예전 문의는 status(접수/답변 완료)를 쓴다 */
export const INQ_ST = { RECEIVED: ['wait', '접수'], CHECKING: ['wait', '확인 중'], ANSWERED: ['ok', '답변 완료'], CLOSED: ['ok', '처리 완료'] };
const RP_ST = { RECEIVED: ['wait', '접수'], REVIEWING: ['wait', '검토 중'], DONE: ['ok', '처리 완료'] };
const RP_ACT = { NONE: '조치 없음', WARNING: '경고', SUSPEND: '이용 정지', BAN: '퇴출' };
export function Qna() {
  const nav = useNavigate();
  const [qs] = useLoad(() => api('/inquiries/mine'), []);
  const [rs] = useLoad(() => api('/reports/mine'), []);
  return (
    <Screen title="1:1 문의 · 신고 내역" noTabs>
      <button className="btn" onClick={() => nav('/qna/new')}><Ic h={I.plus} /> 문의하기</button>
      <div className="sec-t">내 문의</div>
      {qs === undefined ? <Loading /> : !qs || !qs.length ? <Empty>문의한 내역이 없어요</Empty> : qs.map((q) => (
        <div className="card" key={q.id}>
          <div className="between"><span className="b">{q.title}</span><span className={'st ' + (INQ_ST[q.stage || q.status] || INQ_ST.RECEIVED)[0]}>{(INQ_ST[q.stage || q.status] || INQ_ST.RECEIVED)[1]}</span></div>
          <div className="mute2">{q.type} · {dshort(q.createdAt).split(' ')[0]}</div>
          {q.content && <div className="small muted" style={{ marginTop: 6, whiteSpace: 'pre-wrap' }}>{q.content}</div>}
          {q.answer && <div className="ai" style={{ marginTop: 10, background: '#fff' }}><div className="small"><b>운영자 답변</b><br />{q.answer}</div></div>}
        </div>
      ))}
      <div className="sec-t">내 신고</div>
      {rs === undefined ? <Loading /> : !rs || !rs.length ? <Empty>신고한 내역이 없어요</Empty> : rs.map((r) => {
        const s = RP_ST[r.status] || ['wait', r.status];
        return (
          <div className="card" key={r.id}>
            <div className="between"><span className="b">{r.type} · {r.targetLabel || r.targetType}</span><span className={'st ' + s[0]}>{s[1]}</span></div>
            <div className="mute2">{dshort(r.createdAt).split(' ')[0]}{r.kind === 'WELFARE' ? ' · 제재 없는 접수 기록' : ''}{r.status === 'DONE' ? ` · 결과: ${(r.kind === 'WELFARE' ? [r.result] : [RP_ACT[r.action], r.result]).filter(Boolean).join(' · ')}` : ''}</div>
          </div>
        );
      })}
    </Screen>
  );
}
export function QnaNew() {
  const nav = useNavigate();
  const { toast } = useUi();
  const [type, setType] = useState('적립 오류');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  return (
    <Screen title="문의하기" noTabs>
      <label className="f">문의 유형</label>
      <ChipsPick options={['이용 방법', '적립 오류', '신고 관련', '기타']} value={type} onChange={setType} />
      <label className="f" htmlFor="qt">제목</label><input id="qt" className="in" placeholder="제목" value={title} onChange={(e) => setTitle(e.target.value)} />
      <label className="f" htmlFor="qc">내용</label><VoiceField value={content} onChange={setContent}><textarea id="qc" className="in" placeholder="자세히 적어 주세요" value={content} onChange={(e) => setContent(e.target.value)} /></VoiceField>
      <button className="btn big-cta" style={{ marginTop: 18 }} onClick={async () => {
        if (!title.trim() || !content.trim()) return toast('제목과 내용을 적어 주세요');
        try { await post('/inquiries', { type, title: title.trim(), content: content.trim() });
          if (hasBack()) nav(-1); else nav('/qna', { replace: true }); // 주소로 바로 들어왔으면 이전 화면이 없으니 문의 내역으로
          toast('문의를 등록했어요. 답변이 오면 알려드려요'); } catch { /* 토스트 */ }
      }}>등록하기</button>
    </Screen>
  );
}

/* ---------- 신고 시트 (B-36, B-37) ---------- */
const RP_TYPES = ['노쇼', '부적절한 언행', '신체 접촉·방문 외 연락', '연락처 교환·금전 요구', '허위 공고', '기타'];
export const WELFARE_TYPE = '이상 징후(안부 확인)';
/**
 * target: {targetType, targetId, targetLabel, welfare?, welfareFirst?}
 * welfare 가 true 면(독거노인 활동의 봉사자·담당자) "이상 징후(안부 확인)" 유형을 함께 보여 준다.
 * 이상 징후는 제재용 신고가 아니라 담당자에게 알리고 운영자 목록에 기록만 남기는 접수다.
 */
function ReportForm({ target, onDone }) {
  const { welfare, welfareFirst, ...tg } = target;
  const types = welfare ? [WELFARE_TYPE, ...RP_TYPES] : RP_TYPES;
  const [type, setType] = useState(welfare && welfareFirst ? WELFARE_TYPE : RP_TYPES[0]);
  const [content, setContent] = useState('');
  const { toast } = useUi();
  const wf = type === WELFARE_TYPE;
  return (
    <>
      <h2>신고하기</h2>
      <p className="mute2" style={{ margin: '0 0 10px' }}>대상: {tg.targetLabel}</p>
      {types.map((t) => <div key={t} className={'opt ' + (type === t ? 'on' : '')} role="button" tabIndex={0} onClick={() => setType(t)}><span className="rd" /><div>{t}{t === WELFARE_TYPE && <div className="mute2">식사 거름, 몸 상태 이상 등 · 담당자에게 알림이 가요</div>}</div></div>)}
      {wf && <div className="alert bad"><Ic n="phone" s={20} /><div><b>응급 상황이면 먼저 전화해 주세요</b>이 접수는 응급을 처리하지 않아요. <a href="tel:119" style={{ fontWeight: 700 }}>119 전화</a> · <a href="tel:112" style={{ fontWeight: 700 }}>112 전화</a></div></div>}
      <div style={{ marginTop: 10 }}><VoiceField value={content} onChange={setContent}><textarea className="in" placeholder={wf ? '어떤 점이 걱정됐는지 적어 주세요 (필수) 예) 점심을 거르셨고 기운이 없어 보이셨어요' : '내용 (선택)'} value={content} onChange={(e) => setContent(e.target.value)} aria-label="신고 내용" /></VoiceField></div>
      <button className="btn warn" style={{ marginTop: 12 }} onClick={async () => {
        if (wf && !content.trim()) return toast('어떤 점이 걱정됐는지 적어 주세요');
        try {
          await post('/reports', { ...tg, type, content, ...(wf ? { kind: 'WELFARE' } : {}) });
          onDone();
          toast(wf ? '접수했어요. 담당자에게 알렸고 운영자 기록에 남아요' : '신고를 접수했어요. 운영자가 확인해요');
        } catch { /* 토스트 */ }
      }}>{wf ? '안부 확인 접수' : '신고 접수'}</button>
      <div className="mute2" style={{ marginTop: 8 }}>{wf ? '이상 징후 접수는 누구도 제재하지 않는 기록이에요.' : '처리 결과는 알림으로 알려드려요.'}</div>
    </>
  );
}
/** const report = useReport(); report({targetType:'POST', targetId, targetLabel}) */
export function useReport() {
  const { sheet, closeSheet } = useUi();
  return (target) => sheet(() => <ReportForm target={target} onDone={closeSheet} />);
}
