import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Loading, Empty, useLoad, ChipsPick } from '../ui/bits.jsx';
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
  if (/REMIND|CHECK|NOSHOW|LATE/.test(t)) return ['clock', 'blue'];
  if (/REPORT/.test(t)) return ['chart', ''];
  if (/EARN|GIFT|BANK/.test(t)) return ['wallet', ''];
  if (/INQUIR|NOTICE/.test(t)) return ['msg', ''];
  return ['bell', ''];
};

export function goLink(nav, link, role) {
  if (!link) return;
  let l = String(link);
  if (!l.startsWith('/')) l = '/' + l;
  // 서버 알림 링크(/requests/1)를 화면 경로로
  l = l.replace(/^\/requests\/(\d+)/, '/req/$1');
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
        <div className="ml">{list.map((n) => <div key={n.id} role="button" tabIndex={0} onClick={() => nav(`/notice/${n.id}`)}><div className="grow">{n.title}<span>{dshort(n.createdAt).split(' ')[0]}</span></div>›</div>)}</div>
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
          <div className="between"><span className="b">{q.title}</span><span className={'st ' + (q.status === 'ANSWERED' ? 'ok' : 'wait')}>{q.status === 'ANSWERED' ? '답변 완료' : '접수'}</span></div>
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
            <div className="mute2">{dshort(r.createdAt).split(' ')[0]}{r.status === 'DONE' ? ` · 결과: ${[RP_ACT[r.action], r.result].filter(Boolean).join(' · ')}` : ''}</div>
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
        try { await post('/inquiries', { type, title: title.trim(), content: content.trim() }); nav(-1); toast('문의를 등록했어요. 답변이 오면 알려드려요'); } catch { /* 토스트 */ }
      }}>등록하기</button>
    </Screen>
  );
}

/* ---------- 신고 시트 (B-36) ---------- */
const RP_TYPES = ['노쇼', '부적절한 언행', '신체 접촉·방문 외 연락', '연락처 교환·금전 요구', '허위 공고', '기타'];
function ReportForm({ target, onDone }) {
  const [type, setType] = useState(RP_TYPES[0]);
  const [content, setContent] = useState('');
  const { toast } = useUi();
  return (
    <>
      <h2>신고하기</h2>
      <p className="mute2" style={{ margin: '0 0 10px' }}>대상: {target.targetLabel}</p>
      {RP_TYPES.map((t) => <div key={t} className={'opt ' + (type === t ? 'on' : '')} role="button" tabIndex={0} onClick={() => setType(t)}><span className="rd" /><div>{t}</div></div>)}
      <div style={{ marginTop: 10 }}><VoiceField value={content} onChange={setContent}><textarea className="in" placeholder="내용 (선택)" value={content} onChange={(e) => setContent(e.target.value)} aria-label="신고 내용" /></VoiceField></div>
      <button className="btn warn" style={{ marginTop: 12 }} onClick={async () => {
        try { await post('/reports', { ...target, type, content }); onDone(); toast('신고가 접수됐어요. 처리 결과는 알림으로 알려드려요'); } catch { /* 토스트 */ }
      }}>신고 접수</button>
    </>
  );
}
/** const report = useReport(); report({targetType:'POST', targetId, targetLabel}) */
export function useReport() {
  const { sheet, closeSheet } = useUi();
  return (target) => sheet(() => <ReportForm target={target} onDone={closeSheet} />);
}
