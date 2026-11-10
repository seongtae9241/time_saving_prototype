// 계약서 7.13 LLM 추가 B: 방문 전 브리핑 · 메시지 도우미(안전 감지·빠른 답장·일정 변경) · 판단 보조 · 월별 결산 설명
// 결과는 서버가 이벤트 때 한 번 만들어 저장한 것을 읽기만 한다. AI 키가 없으면 규칙 결과(RuleNote 표시).
import { useEffect, useState } from 'react';
import { Ic, Spk, Mod, RuleNote, AiLoading, useLoad } from './bits.jsx';
import { useUi } from './ctx.jsx';
import { api } from '../api.js';
import { fmt } from '../lib/format.js';
import '../styles/activity.css';

/* ---------- 1. 방문 전 브리핑 ---------- */
function BriefingBody({ d }) {
  return (
    <>
      <ul className="small brief-list">{(d.lines || []).map((l, i) => <li key={i}>{l}</li>)}</ul>
      <div className="mute2" style={{ fontSize: 12, marginTop: 8 }}>기본 정보(별칭·동·도움 내용·주의사항)와 활동 규칙으로 만들었어요. 지난 전달 메모는 넣지 않아요.</div>
      <RuleNote source={d.source} />
    </>
  );
}

/** 요청 상세(봉사자, 확정 뒤) 안에 펼쳐 두는 카드 */
export function BriefingCard({ id, style }) {
  const [d] = useLoad(() => api(`/requests/${id}/briefing`, { quiet: true }), [id]);
  if (d === undefined) return <div style={{ marginTop: 16, ...style }}><AiLoading label="방문 전 브리핑 준비 중…" /></div>;
  if (!d) return null;
  return <div className="ai" style={{ marginTop: 16, ...style }}><div className="ah"><Spk />방문 전 브리핑<Mod n={3} /></div><BriefingBody d={d} /></div>;
}

/** 홈 다가오는 일정 카드의 "방문 전 브리핑" 버튼 → 시트 */
export function BriefingButton({ id }) {
  const { sheet, closeSheet } = useUi();
  return (
    <button className="btn soft sm" style={{ marginTop: 8 }} onClick={(e) => {
      e.stopPropagation();
      sheet(() => <><h2>방문 전 브리핑</h2><BriefingCard id={id} style={{ marginTop: 4 }} /><button className="btn ghost" style={{ marginTop: 12 }} onClick={closeSheet}>닫기</button></>);
    }}><Ic n="shield" s={16} /> 방문 전 브리핑</button>
  );
}

/* ---------- 2·3. 메시지 도우미 ---------- */
/** const ma = useMessageAssist(requestId, 메시지 수) → { data, warn(messageId) } */
export function useMessageAssist(id, count) {
  const [data, setData] = useState(null);
  useEffect(() => {
    let alive = true;
    if (!id || !count) { setData(null); return undefined; }
    api(`/requests/${id}/message-assist`, { quiet: true }).then((d) => { if (alive) setData(d); }).catch(() => { if (alive) setData(null); });
    return () => { alive = false; };
  }, [id, count]);
  const map = {};
  ((data && data.warns) || []).forEach((w) => { map[w.messageId] = w; });
  return { data, warn: (mid) => map[mid] };
}

/** 받은 메시지 아래 경고 띠 + 신고하기(자동 차단 없음) */
export function WarnBand({ w, onReport }) {
  if (!w) return null;
  return (
    <div className={'msg-warn ' + (w.level === 'warn' ? 'bad' : '')} role="note">
      <Ic n="flag" s={16} />
      <div className="grow">
        <b>{w.level === 'warn' ? '주의가 필요한 메시지' : '한 번 살펴봐 주세요'}</b>
        <span>{(w.reasons || []).join(' · ')}{w.reasons && w.reasons.length ? ' · ' : ''}연락처·돈·앱 밖 만남은 주고받지 않아요</span>
      </div>
      <button type="button" className="mw-btn" onClick={onReport}>신고하기</button>
    </div>
  );
}

const SC_TITLE = { DELAY: '시간을 늦추자는 메시지예요', EARLY: '시간을 당기자는 메시지예요', TIME: '시작 시각을 바꾸자는 메시지예요', DATE: '날짜를 바꾸자는 메시지예요', CANCEL: '이번 약속을 못 한다는 메시지예요' };

/**
 * 마지막 상대 메시지에 맞는 일정 변경 제안 카드 + 답장 후보 버튼.
 * onPick(text): 입력칸에 채우기(보내기는 사용자). onCancel: 기존 일정 취소 흐름. onRerequest: 다시 요청 화면. 시간은 자동으로 바뀌지 않는다.
 */
export function MsgHelper({ a, onPick, onCancel, onRerequest }) {
  if (!a) return null;
  const sc = a.schedule;
  const replies = a.replies || [];
  if (!sc && !replies.length) return null;
  return (
    <div className="msg-help">
      {sc && (
        <div className="ai amber" style={{ marginBottom: 8 }}>
          <div className="ah"><Spk />시간 변경 요청<Mod n={2} /></div>
          <div className="b" style={{ fontSize: 15 }}>{SC_TITLE[sc.kind] || '약속을 바꾸자는 메시지예요'}</div>
          <div className="small">{sc.text}</div>
          <div className="mute2" style={{ fontSize: 12, marginTop: 4 }}>시간은 자동으로 바뀌지 않아요. 바꾸려면 지금 일정을 취소하고 새 시간으로 다시 요청해요.</div>
          <div className="btns" style={{ marginTop: 10 }}>
            {onCancel && <button className="btn ghost sm" onClick={onCancel}>일정 취소하기</button>}
            {onRerequest && <button className="btn sm" onClick={onRerequest}>새 시간으로 다시 요청</button>}
          </div>
          <RuleNote source={a.source} />
        </div>
      )}
      {replies.length > 0 && (
        <div>
          <div className="mute2" style={{ fontSize: 12, margin: '2px 2px 6px' }}>빠른 답장 · 누르면 입력칸에 채워져요</div>
          <div className="chips qr-chips">{replies.map((t) => <button key={t} type="button" className="chip" onClick={() => onPick(t)}>{t}</button>)}</div>
        </div>
      )}
    </div>
  );
}

/* ---------- 4. 시간 조정·난이도 이의 판단 보조 (운영자·받는 쪽 화면에 끼우는 표시용) ---------- */
export function JudgeAssist({ kind, id, style }) {
  const [d] = useLoad(() => api(`/judge-assist?kind=${kind}&id=${id}`, { quiet: true }), [kind, id]);
  const [open, setOpen] = useState(false);
  if (d === undefined) return <div style={{ marginTop: 10, ...style }}><AiLoading label="기록을 비교하는 중…" /></div>;
  if (!d) return null;
  const rows = d.table || [];
  return (
    <div className="ai" style={{ marginTop: 10, ...style }}>
      <div className="ah"><Spk />판단 보조 · 결정은 직접 해요<Mod n={2} /></div>
      <div className="small b">{d.summary}</div>
      <div className="jg-cols">
        <div><div className="jg-h ok">타당해 보이는 이유</div>{(d.pro || []).length ? <ul className="small">{d.pro.map((x, i) => <li key={i}>{x}</li>)}</ul> : <div className="mute2">없음</div>}</div>
        <div><div className="jg-h ck">다시 확인할 점</div>{(d.con || []).length ? <ul className="small">{d.con.map((x, i) => <li key={i}>{x}</li>)}</ul> : <div className="mute2">없음</div>}</div>
      </div>
      <button type="button" className="jg-toggle" onClick={() => setOpen(!open)} aria-expanded={open}>{open ? '비교표 접기' : `예정 vs 실제 비교표 보기 (${rows.length})`}</button>
      {open && (
        <table className="jg-t">
          <thead><tr><th>항목</th><th>예정</th><th>실제·신청</th></tr></thead>
          <tbody>{rows.map((x, i) => (
            <tr key={i} className={x.flag === 'check' ? 'ck' : ''}><td>{x.item}</td><td>{String(x.planned ?? '-')}</td><td>{String(x.actual ?? '-')}{x.note && <div className="mute2">{x.note}</div>}</td></tr>
          ))}</tbody>
        </table>
      )}
      <RuleNote source={d.source} />
    </div>
  );
}

/* ---------- 6. 월별 결산 한 줄 설명 ---------- */
export function MonthNote() {
  const [d] = useLoad(() => api('/bank/month-note', { quiet: true }), []);
  if (!d) return null;
  const n = d.numbers || {};
  return (
    <div className="month-note">
      <div className="ah"><Spk />이번 달 한 줄<Mod n={4} /></div>
      <div className="small">{d.text}</div>
      {d.suggestion && <div className="mn-sug"><Ic n="spark" s={14} /> {d.suggestion}</div>}
      {(n.byCategory || []).length > 0 && <div className="mute2" style={{ fontSize: 12, marginTop: 6 }}>{n.byCategory.map((c) => `${c.category} +${fmt(c.earned)}${c.weight > 1 ? `(×${Number(c.weight).toFixed(1)})` : ''}`).join(' · ')}</div>}
      <RuleNote source={d.source} />
    </div>
  );
}
