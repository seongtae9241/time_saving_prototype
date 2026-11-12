import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import QRCode from 'qrcode';
import { I, ic, LOGO } from './icons.js';
import { CI, ccOf, SHORT, ST, hh, fmt, dlabel, KIND_LABEL, lvName, lvWeight } from '../lib/format.js';
import { api } from '../api.js';
import { DIc, duoColor } from './proto.jsx';
import { DI } from './duoIcons.js';
import { MatchWhy, EasyListen } from './assist.jsx'; // LLM 추가 A(7.12)

/** SVG 문자열을 그대로 그린다(감싼 span은 박스를 만들지 않음) */
export function Ic({ n, s, h }) {
  const html = h || (s ? ic(n, s) : I[n] || '');
  return <span style={{ display: 'contents' }} dangerouslySetInnerHTML={{ __html: html }} />;
}
export const Logo = () => <span style={{ display: 'contents' }} dangerouslySetInnerHTML={{ __html: LOGO }} />;
export const Spk = () => <span className="spark"><Ic h={I.spark} /></span>;
export const MODS = ['', '작성 도우미 AI', '판단 AI', '기록 정리 AI', '추천·안내 AI', '리포트 AI', '교육 AI'];
export const Mod = ({ n }) => <span className={`mod m${n}`}>{MODS[n]}</span>;
/** AI 응답이 규칙 기반이면 작게 표시 */
export const RuleNote = ({ source }) => (source === 'rule' ? <div className="mute2" style={{ fontSize: 12, marginTop: 6 }}>AI 키 없음 · 규칙 모드</div> : null);

/** 카테고리 아이콘·칩: 새 프로토타입의 듀오톤(dIc) */
export function CatIc({ k, size = 48 }) { return <DIc k={DI[k] ? k : '말동무·정서'} size={size} />; }
export const CatChip = ({ k }) => { const c = duoColor(DI[k] ? k : '말동무·정서'); return <span className="cat-chip" style={{ background: c[2], color: c[0] }}><DIc k={DI[k] ? k : '말동무·정서'} size={16} bare />{SHORT[k] || k}</span>; };
export const Cats = ({ list, style }) => <div className="cats" style={{ display: 'flex', gap: 6, flexWrap: 'wrap', ...style }}>{(list || []).map((k) => <CatChip key={k} k={k} />)}</div>;

export function TBar({ s, e, ss, se }) {
  const L = (v) => (((v - 8) / 12) * 100);
  const c = (v) => Math.max(0, Math.min(100, L(v)));
  return (
    <>
      <div className="tb"><i style={{ left: `${c(s)}%`, width: `${c(e) - c(s)}%` }} />{ss != null && <b style={{ left: `${c(ss)}%`, width: `${c(se) - c(ss)}%` }} />}</div>
      <div className="tb-l"><span>8시</span><span>12시</span><span>16시</span><span>20시</span></div>
    </>
  );
}
export const Stars = ({ r }) => <><span className="stars">★</span> <b>{Number(r || 0).toFixed(1)}</b></>;
export const StTag = ({ st }) => { const v = ST[st] || ['end', st]; return <span className={`st ${v[0]}`}>{v[1]}</span>; };

export function FlowBar({ r }) {
  const prop = r.kind === 'PROPOSAL';
  const steps = prop ? ['제안', '수락', '활동 중', '일지', '완료'] : ['요청', '수락', '확정', '활동 중', '일지', '완료'];
  const ix = prop
    ? { SENT: 0, CONFIRMED: 1, IN_PROGRESS: 2, CHECKED_OUT: 2, LOG_SUBMITTED: 3, DONE: 4 }[r.status]
    : { SENT: 0, VOL_ACCEPTED: 1, CONFIRMED: 2, IN_PROGRESS: 3, CHECKED_OUT: 3, LOG_SUBMITTED: 4, DONE: 5 }[r.status];
  if (ix == null) return null;
  return <div className="flow">{steps.map((s, i) => <div key={s} className={`${i <= ix ? 'on' : ''} ${i === ix ? 'cur' : ''}`}><span />{s}</div>)}</div>;
}

export function Avatar({ name, s = 44, elder, style, photo }) {
  // B-12: 프로필 사진이 있으면 사진을, 없으면 이름 첫 글자를 보여 준다
  if (photo) return <img className="avatar" src={photo} alt={name ? `${name} 님 사진` : '프로필 사진'} style={{ width: s, height: s, objectFit: 'cover', ...style }} />;
  return <div className="avatar" style={{ width: s, height: s, fontSize: Math.round(s * 0.38), ...(elder ? { background: '#E1ECFE', color: '#3A6FD8' } : {}), ...style }}>{(name || '?')[0]}</div>;
}

/** AI 로딩 스켈레톤 (프로토타입 aiRun) */
export const AiLoading = ({ label = 'AI가 정리하는 중…' }) => (
  <div className="ai"><div className="ah"><Spk />{label}</div><div className="skel" /><div className="skel" style={{ width: '80%' }} /><div className="skel" style={{ width: '60%' }} /></div>
);
export const Empty = ({ children = '없어요' }) => <div className="empty">{children}</div>;
export const Loading = () => <div style={{ padding: '20px 0' }}><div className="skel" /><div className="skel" style={{ width: '80%' }} /><div className="skel" style={{ width: '60%' }} /></div>;

/** 데이터 읽기 훅: const [data, reload, err] = useLoad(() => api('/x'), [deps]) */
export function useLoad(fn, deps = []) {
  const [data, setData] = useState(undefined);
  const [err, setErr] = useState(null);
  const alive = useRef(true);
  const load = useCallback(async () => {
    try { const d = await fn(); if (alive.current) { setData(d); setErr(null); } return d; } catch (e) { if (alive.current) { setErr(e); setData((x) => (x === undefined ? null : x)); } return null; }
  }, deps); // eslint-disable-line
  useEffect(() => { alive.current = true; load(); return () => { alive.current = false; }; }, [load]);
  return [data, load, err];
}
/** 버튼 연타 방지 */
export function useBusy() {
  const [busy, setBusy] = useState(false);
  const run = useCallback(async (f) => { if (busy) return undefined; setBusy(true); try { return await f(); } catch { return undefined; } finally { setBusy(false); } }, [busy]);
  return [busy, run];
}

export const postCats = (p) => (p.categories && p.categories.length ? p.categories : p.category ? [p.category] : []);
export function postWho(p) {
  if (p.elderId || p.elderDisplayName) return { name: p.elderDisplayName || '기관 대상', sub: `${p.area || ''} · 기관 등록 대상`, elder: true, badge: <span className="badge elder">기관</span> };
  const o = p.owner || {};
  if (p.mine) return { name: '내 공고', sub: p.area, badge: null };
  return {
    name: p.ownerName,
    sub: <>{o.ageGroup ? `${o.ageGroup} · ` : ''}<Stars r={o.ratingAvg} />{p.distanceKm != null ? ` · ${p.distanceKm}km` : ''}</>,
    badge: o.crime ? <span className="badge trust">✓ 신원 확인</span> : null,
  };
}
export function PostCard({ p, to }) {
  const nav = useNavigate();
  const w = postWho(p);
  const offer = p.type === 'OFFER';
  return (
    <div className="pc2 click" role="button" tabIndex={0} onClick={() => nav(to || `/post/${p.id}`)}>
      <div className="top"><Avatar name={w.name} elder={w.elder} /><div className="grow"><div className="who">{w.name}{w.badge}{p.repeatRule && <span className="kind reg">{p.repeatRule}</span>}</div><div className="sub">{w.sub}</div></div><span className={`kind ${offer ? 'offer' : 'need'}`}>{offer ? '도움 가능' : '봉사 요청'}</span></div>
      <div className="ttl">{offer ? (p.note || p.title) : p.title}</div>
      <Cats list={postCats(p)} style={{ marginTop: 10 }} />
      {offer && <MatchWhy text={p.matchReason} style={{ marginTop: 10 }} />}
      <div className="between" style={{ marginTop: 12 }}><span className="tr-t">{dlabel(p.date)} {hh(p.startHour)}~{hh(p.endHour)}</span>{!offer && p.estEarn != null && <span className="pill">예상 +{fmt(p.estEarn)}시간</span>}{p.status && p.status !== 'OPEN' && <span className="st end">{{ MATCHED: '매칭됨', CLOSED: '마감', DELETED: '삭제' }[p.status] || p.status}</span>}</div>
      <TBar s={p.startHour} e={p.endHour} />
      <div style={{ marginTop: 10 }}><EasyListen postId={p.id} compact /></div>
    </div>
  );
}

/** 요청·제안 목록 한 줄 (프로토타입 reqRow) */
export function reqOther(r) {
  if (r.myRole === 'MANAGER') return r.volunteer ? r.volunteer.name : '';
  if (r.myRole === 'VOLUNTEER') return r.elder ? r.elder.displayName : r.recipient ? r.recipient.name : '';
  return r.volunteer ? r.volunteer.name : '';
}
export function ReqRow({ r }) {
  const nav = useNavigate();
  const org = r.myRole === 'MANAGER';
  const role = org ? (r.elder ? r.elder.displayName : '') : r.myRole === 'VOLUNTEER' ? '내가 봉사' : '내가 받음';
  const [, fg] = ccOf(r.category);
  return (
    <div className="rq click" role="button" tabIndex={0} onClick={() => nav(org ? `/o/reqs/${r.id}` : `/req/${r.id}`)}>
      <CatIc k={r.category} size={48} />
      <div className="grow">
        <div className="rq-cat" style={{ color: fg }}>{role} · {KIND_LABEL[r.kind] || '요청'}</div>
        <div className="rq-t">{r.content}</div>
        <div className="rq-m">{reqOther(r)} · {dlabel(r.date)} {hh(r.startHour)}~{hh(Number(r.startHour) + Number(r.hours))}</div>
        {r.myRole !== 'VOLUNTEER' && r.volunteer && <MatchWhy text={r.volunteer.matchReason} style={{ marginTop: 6 }} />}
      </div>
      <div style={{ textAlign: 'right' }}><StTag st={r.status} />{r.leftText && ['SENT', 'VOL_ACCEPTED'].includes(r.status) && <div className="mute2" style={{ marginTop: 6, fontSize: 12 }}>{r.leftText} 남음</div>}</div>
    </div>
  );
}
export const diffText = (r) => `${lvName(r.difficulty)} ${lvWeight(r.difficulty, r.weight).toFixed(1)}`;

/** 실제 QR (내용: 체크 코드) */
export function QrSvg({ text, size }) {
  const [svg, setSvg] = useState('');
  useEffect(() => {
    let on = true;
    QRCode.toString(String(text || '0'), { type: 'svg', margin: 1, errorCorrectionLevel: 'M' }).then((s) => { if (on) setSvg(s); }).catch(() => {});
    return () => { on = false; };
  }, [text]);
  return <span style={size ? { display: 'block', width: size, height: size } : { display: 'contents' }} dangerouslySetInnerHTML={{ __html: svg }} />;
}

/** 파일 올리기 버튼 (프로토타입 .upl) */
export function Upload({ label, onUploaded, accept = 'image/*,application/pdf', style }) {
  const [state, setState] = useState(null);
  const ref = useRef(null);
  return (
    <>
      <button type="button" className="upl" style={{ ...style, ...(state === 'ok' ? { borderColor: '#1F9D63', color: '#1F9D63' } : {}) }} onClick={() => ref.current && ref.current.click()}>
        <Ic n={state === 'ok' ? 'check' : 'file'} s={24} /><span>{state === 'ok' ? '올렸어요' : state === 'up' ? '올리는 중…' : label}</span>
      </button>
      <input ref={ref} type="file" accept={accept} hidden onChange={async (e) => {
        const f = e.target.files && e.target.files[0];
        if (!f) return;
        setState('up');
        try {
          const fd = new FormData(); fd.append('file', f);
          const r = await api('/files', { method: 'POST', form: fd });
          setState('ok'); onUploaded && onUploaded(r.url);
        } catch { setState(null); }
      }} />
    </>
  );
}

/** 메뉴 리스트 한 줄 (프로토타입 mi) */
export function Mi({ t, s, i, onClick, children }) {
  return <div role="button" tabIndex={0} onClick={onClick} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick && onClick(); } }}><Ic n={i} s={20} /><div className="grow">{t}{s ? <span>{s}</span> : null}</div>{children || '›'}</div>;
}

/** 칩 선택 묶음 */
export function ChipsPick({ options, value, onChange, multi, label = (x) => x }) {
  const on = (o) => (multi ? (value || []).includes(o) : value === o);
  return (
    <div className="tags">
      {options.map((o) => (
        <button type="button" key={o} className={'chip ' + (on(o) ? 'on' : '')} onClick={() => {
          if (multi) onChange(on(o) ? value.filter((x) => x !== o) : [...(value || []), o]);
          else onChange(o);
        }}>{label(o)}</button>
      ))}
    </div>
  );
}

export function hbars(rows, c) {
  const mx = Math.max(1, ...rows.map((r) => Number(r[1]) || 0));
  return (
    <div className="hb" style={{ padding: '10px 0 0', background: 'none' }}>
      {rows.map(([k, v]) => <div className="hb-r" key={k}><span>{k}</span><div className="hb-b"><i style={{ width: `${Math.max(6, (v / mx) * 100)}%`, background: c }} /></div><b>{v}</b></div>)}
    </div>
  );
}
