// 2026-11-11 카테고리 추가 건의: 공고 작성(수동·AI)의 종류 선택 옆 "맞는 종류가 없어요 → 운영자에게 건의"
// 실제 카테고리 6종은 바뀌지 않는다(가중치·금지 규칙이 걸려 있어 운영자 결정). 건의는 POST /api/category-suggestions.
import { useState } from 'react';
import { useUi } from './ctx.jsx';
import { api, post } from '../api.js';
import { useLoad, Empty } from './bits.jsx';
import { dshort } from '../lib/format.js';

export const SUG_ST = { RECEIVED: ['wait', '접수'], REVIEWING: ['go', '검토 중'], ADOPTED: ['ok', '반영'], HELD: ['end', '보류'] };

function SuggestForm({ onDone, onClose }) {
  const [name, setName] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const send = async () => {
    if (busy) return;
    if (name.trim().length < 2) return setErr('종류 이름을 2자 이상 적어 주세요');
    if (reason.trim().length < 5) return setErr('필요한 이유를 조금 더 적어 주세요');
    setBusy(true); setErr('');
    try { await post('/category-suggestions', { name: name.trim(), reason: reason.trim() }, { quiet: true }); onDone(); } catch (e) { setErr(e.message); }
    setBusy(false);
  };
  return (
    <>
      <h2>새 종류 건의하기</h2>
      <p className="mute2" style={{ margin: '0 0 6px' }}>지금 6가지 종류에 맞는 것이 없으면 운영자에게 알려 주세요. 검토한 뒤 답변을 알림으로 보내 드려요.</p>
      <label className="f" htmlFor="sug-name">종류 이름</label>
      <input id="sug-name" className="in" value={name} maxLength={20} onChange={(e) => setName(e.target.value)} placeholder="예) 반려동물 돌봄" />
      <label className="f" htmlFor="sug-reason">필요한 이유</label>
      <textarea id="sug-reason" className="in" style={{ minHeight: 90 }} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} placeholder="예) 병원에 입원할 때 강아지 산책을 부탁하고 싶어요" />
      <div className="mute2" style={{ marginTop: 6 }}>연락처·주소 같은 개인정보는 적지 않아요.</div>
      {err && <div className="notice" role="alert" style={{ marginTop: 8 }}>{err}</div>}
      <div className="btns" style={{ marginTop: 12 }}><button className="btn ghost" onClick={onClose}>취소</button><button className="btn" disabled={busy} onClick={send}>{busy ? '보내는 중…' : '운영자에게 건의'}</button></div>
    </>
  );
}

/** 건의 시트를 여는 함수 */
export function useSuggestSheet() {
  const { sheet, closeSheet, toast } = useUi();
  return (after) => sheet(() => <SuggestForm onClose={closeSheet} onDone={() => { closeSheet(); toast('건의했어요. 답변이 오면 알려 드릴게요'); after && after(); }} />);
}

/** 종류 선택 아래에 두는 한 줄 */
export function SuggestLink({ style }) {
  const open = useSuggestSheet();
  return <div className="mute2" style={{ marginTop: 8, ...style }}>맞는 종류가 없어요 → <button type="button" className="linkbtn" onClick={() => open()}>운영자에게 건의</button></div>;
}

/** 내가 건의한 목록(마이) */
export function MySuggestions() {
  const [list, reload] = useLoad(() => api('/category-suggestions/mine', { quiet: true }).catch(() => []), []);
  const open = useSuggestSheet();
  return (
    <>
      <p className="su-p" style={{ marginTop: 0 }}>공고 종류(6가지)에 없는 도움을 운영자에게 건의할 수 있어요. 종류를 실제로 늘릴지는 운영자가 정해요.</p>
      <button className="btn soft" onClick={() => open(reload)}>+ 새 종류 건의하기</button>
      <div className="sec-t">내 건의</div>
      {list === undefined ? null : list.length ? list.map((s) => (
        <div key={s.id} className="card" style={{ marginTop: 8 }}>
          <div className="between"><span className="b">{s.name}</span><span className={`st ${(SUG_ST[s.status] || SUG_ST.RECEIVED)[0]}`}>{(SUG_ST[s.status] || SUG_ST.RECEIVED)[1]}</span></div>
          <div className="small muted" style={{ marginTop: 6 }}>{s.reason}</div>
          {s.answer && <div className="hnote" style={{ marginTop: 8 }}>운영자 답변: {s.answer}</div>}
          <div className="mute2" style={{ marginTop: 6 }}>{dshort(s.createdAt).split(' ')[0]} 건의</div>
        </div>
      )) : <Empty>아직 건의한 것이 없어요</Empty>}
    </>
  );
}
