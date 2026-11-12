import { useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUi } from './ctx.jsx';
import { post } from '../api.js';
import { addDays, dlabel, hh, todayISO } from '../lib/format.js';

/* ---------- 공고 다시 올리기 (B-06) ----------
   지난 공고를 같은 내용으로 불러와 "날짜만 골라" 새 공고 1건으로 올린다. 자동 반복 등록은 하지 않는다.
   사용자의 내 공고, 담당자의 독거노인 지난 요청에 같은 시트를 쓴다(가족 대신 올린 사람은 읽기만이라 못 한다)(서버: POST /api/posts/{id}/repost {date}). */
function RepostSheet({ p, onDone, onClose }) {
  const days = Array.from({ length: 14 }, (_, i) => addDays(i + 1));
  const [date, setDate] = useState(''); // 날짜는 미리 골라 두지 않는다: 직접 골라야 올릴 수 있다
  const [busy, setBusy] = useState(false);
  const go = async () => {
    if (!date || date < todayISO()) return;
    setBusy(true);
    try { onDone(await post(`/posts/${p.id}/repost`, { date })); } catch { /* 토스트 */ }
    setBusy(false);
  };
  return (
    <>
      <h2>같은 내용으로 다시 올리기</h2>
      <p className="muted" style={{ marginTop: 6 }}>내용은 그대로 두고 <b>날짜만 골라</b> 올려요. 시간은 {hh(p.startHour)}~{hh(p.endHour)} 그대로예요.</p>
      <div className="card" style={{ marginTop: 10 }}><b>{p.type === 'OFFER' ? p.note || p.title : p.title}</b><div className="mute2" style={{ marginTop: 2 }}>지난 날짜 {dlabel(p.date)} · {p.area}</div></div>
      <label className="f">다시 올릴 날짜</label>
      <div className="tags">{days.map((d) => <button key={d} className={'chip ' + (date === d ? 'on' : '')} onClick={() => setDate(d)}>{dlabel(d)}</button>)}</div>
      <input className="in" type="date" style={{ marginTop: 10 }} min={todayISO()} value={date || ''} onChange={(e) => setDate(e.target.value)} aria-label="다른 날짜 고르기" />
      <div className="mute2" style={{ marginTop: 8 }}>한 번에 공고 1건만 올라가요. 매주 자동으로 올리지 않아요. 다시 올린 공고도 요청 1건만 받아요.</div>
      <div className="btns" style={{ marginTop: 14 }}><button className="btn ghost" onClick={onClose}>취소</button><button className="btn" disabled={busy || !date || date < todayISO()} onClick={go}>{busy ? '올리는 중…' : date ? `${dlabel(date)}에 올리기` : '날짜를 골라 주세요'}</button></div>
    </>
  );
}

/** const repost = useRepost(); repost(공고) → 날짜 고르는 시트 → 올린 뒤 새 공고 화면으로 간다(onDone 을 주면 그것을 부른다). */
export function useRepost() {
  const nav = useNavigate();
  const { sheet, closeSheet, toast } = useUi();
  return useCallback((p, onDone) => {
    sheet(() => (
      <RepostSheet p={p} onClose={closeSheet} onDone={(np) => {
        closeSheet();
        toast(`${dlabel(np.date)}에 같은 내용으로 다시 올렸어요`);
        if (onDone) onDone(np); else nav(`/post/${np.id}`);
      }} />
    ));
  }, [sheet, closeSheet, toast, nav]);
}
