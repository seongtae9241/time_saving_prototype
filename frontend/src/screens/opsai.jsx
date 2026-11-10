// 7.14 LLM 추가 C — 담당자·운영자·안전 화면 조각. AI 결과는 .ai 카드 + 규칙 모드 표시(채택 프로토타입).
// 결정(등록·제재·보류·답변 등록)은 모두 사람이 기존 버튼으로 한다. 여기 있는 것은 초안·요약·제안뿐.
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, RuleNote, Loading, Empty, AiLoading, ChipsPick, useLoad, useBusy } from '../ui/bits.jsx';
import { useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { CATS, SHORT, ap, dshort } from '../lib/format.js';
import { MicButton } from '../ui/VoiceInput.jsx';
import '../styles/llmc.css';

const SEV = { high: ['높음', 'no'], medium: ['보통', 'wait'], low: ['낮음', 'end'] };
export const SevChip = ({ s }) => (s ? <span className={'st ' + (SEV[s] || SEV.low)[1]}>심각도 {(SEV[s] || SEV.low)[0]}</span> : null);

/* ===================== C1 생활 변화 신호 · 구조화 메모 ===================== */
/** 담당자 대상별 확인 권장 상태 { [elderId]: signal } */
export function useCareSignals() {
  const [list] = useLoad(() => api('/org/care-signals', { quiet: true }), []);
  const map = {};
  (list || []).forEach((s) => { map[s.elderId] = s; });
  return map;
}

export const SignalChip = ({ s }) => (s && s.flagged ? <span className="st sig">확인 권장</span> : null);

/** 노란 표시: 대상 상세·리포트 맨 위 */
export function SignalCard({ s, who }) {
  if (!s || !s.flagged) return null;
  return (
    <div className="ai amber" style={{ marginTop: 12 }}>
      <div className="ah"><Spk />생활 변화 · 확인 권장<Mod n={5} /></div>
      <div className="small">{who ? `${who}의 ` : ''}최근 메모 {s.window}건 중 <b>{s.count}건</b>에 생활 변화 표현이 있었어요: <b>{(s.labels || []).join(' · ')}</b></div>
      {(s.recent || []).length > 0 && <div className="mute2" style={{ marginTop: 6 }}>{s.recent.map((r) => `${(r.date || '').slice(5).replace('-', '/')} ${(r.signals || []).join('·')}`).join('  ·  ')}</div>}
      <div className="mute2" style={{ marginTop: 6 }}>{s.notice}</div>
    </div>
  );
}

/** 전달 메모 정리: 다음에 챙길 것 / 병원 안내 있음 / 생활 변화 */
export function CareNoteBox({ n, style }) {
  if (!n) return null;
  const next = n.next || [], life = n.life || [];
  if (!next.length && !n.hospital && !life.length) return null;
  return (
    <div className="cnote" style={style}>
      <div className="cnote-h"><Spk />메모 정리{n.source === 'rule' && <span className="mute2" style={{ fontWeight: 500, marginLeft: 'auto', fontSize: 11.5 }}>규칙 모드</span>}</div>
      {next.length > 0 && <div className="cnote-r"><span className="k">다음에 챙길 것</span><ul>{next.map((x, i) => <li key={i}>{x}</li>)}</ul></div>}
      {n.hospital && <div className="cnote-r"><span className="k">병원</span><div>{n.hospital} <span className="mute2">· 내용은 원문 메모에서 확인</span></div></div>}
      {life.length > 0 && <div className="cnote-r"><span className="k">생활 변화</span><ul>{life.map((x, i) => <li key={i}>{x}</li>)}</ul></div>}
    </div>
  );
}

/* ===================== C3 이번 주 브리핑 (담당자 홈 맨 위) ===================== */
export function OrgBriefing() {
  const nav = useNavigate();
  const [refresh, setRefresh] = useState(false);
  const [d, reload] = useLoad(() => api(`/org/briefing${refresh ? '?refresh=true' : ''}`, { quiet: true }), [refresh]);
  const [busy, run] = useBusy();
  if (d === undefined) return <AiLoading label="이번 주 브리핑을 만드는 중…" />;
  if (!d) return null;
  const c = d.counts || {}, s = d.summary || {};
  const chips = [['일정', c.schedule, null], ['미매칭', c.unmatched, c.unmatchedUrgent ? 'warn' : null], ['일지 확인', c.logPending, c.logPending ? 'warn' : null], ['최종 수락', c.finalPending, c.finalPending ? 'warn' : null], ['확인 권장', c.flagged, c.flagged ? 'sig' : null], ['받지 않기', c.blocksNew, null]];
  return (
    <div className="ai brief">
      <div className="ah"><Spk />이번 주 브리핑<Mod n={5} /><span className="mute2" style={{ marginLeft: 'auto', fontWeight: 500 }}>{(d.week.start || '').slice(5).replace('-', '/')}~{(d.week.end || '').slice(5).replace('-', '/')}</span></div>
      <div className="bchips">{chips.map(([t, n, k]) => <span key={t} className={'bchip ' + (k || '')}><b>{n ?? 0}</b>{t}</span>)}</div>
      <ul className="small" style={{ marginTop: 8 }}>{(s.lines || []).map((l, i) => <li key={i}>{l}</li>)}</ul>
      {(s.todo || []).length > 0 && <><div className="b" style={{ fontSize: 13.5, marginTop: 8 }}>오늘 할 일 순서</div><ol className="small btodo">{s.todo.map((t, i) => <li key={i}>{t}</li>)}</ol></>}
      {(d.flags || []).length > 0 && <div className="bflags">{d.flags.map((f) => <button key={f.elderId} className="st sig" onClick={() => nav(`/o/elders/${f.elderId}`)}>{f.alias} 님 · {(f.labels || []).join('·')} ›</button>)}</div>}
      <div className="between" style={{ marginTop: 10 }}>
        <span className="mute2" style={{ fontSize: 12 }}>숫자는 시스템이 세요 · {d.cached ? '저장된 요약' : '새로 만든 요약'} {d.generatedAt ? dshort(d.generatedAt) : ''}</span>
        <button className="btn ghost sm" style={{ width: 'auto', padding: '0 12px' }} disabled={busy} onClick={() => run(async () => { if (refresh) await reload(); else setRefresh(true); })}>다시 만들기</button>
      </div>
      <RuleNote source={s.source} />
    </div>
  );
}

/* ===================== C2 통화 메모 → 공고 여러 건 ===================== */
const HOURS = Array.from({ length: 33 }, (_, i) => 6 + i * 0.5);
const EST = [0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4];

function DraftCard({ d, i, onChange }) {
  const set = (k) => (v) => onChange({ ...d, [k]: v && v.target ? v.target.value : v });
  const block = d.filter && d.filter.level === 'BLOCK';
  const low = (d.confidence || {});
  return (
    <div className={'card dcard ' + (d.checked ? 'on' : '') + (d.done ? ' done' : '')}>
      <div className="between">
        <div className={'su-check ' + (d.checked ? 'on' : '')} role="checkbox" aria-checked={!!d.checked} tabIndex={0} onClick={() => !d.done && onChange({ ...d, checked: !d.checked })} style={{ margin: 0 }}><span className="bx" /><div className="b">초안 {i + 1}{d.repeatLabel ? ` · ${d.repeatLabel}` : ''}</div></div>
        {d.done ? <span className="st ok">등록됨{d.createdCount > 1 ? ` ${d.createdCount}건` : ''}</span> : block ? <span className="st no">등록 불가</span> : null}
      </div>
      <div className="mute2" style={{ marginTop: 4 }}>메모: “{d.segment}”</div>
      {block && <div className="warnc small" style={{ marginTop: 6 }}>{d.filter.reason}</div>}
      {d.error && <div className="warnc small" style={{ marginTop: 6 }}>{d.error}</div>}
      <label className="f">제목</label><input className="in" value={d.title || ''} onChange={set('title')} disabled={d.done} />
      <label className="f">카테고리{low.category === 'low' && <span className="warnc"> · 골라 주세요</span>}</label>
      <ChipsPick multi options={CATS.map((c) => c.k)} label={(x) => SHORT[x] || x} value={d.categories || []} onChange={set('categories')} />
      <div className="dgrid">
        <div><label className="f">날짜{!d.date && <span className="warnc"> · 필요</span>}{d.date && low.date === 'medium' && <span className="mute2"> · 확인</span>}</label><input type="date" className="in" value={d.date || ''} onChange={set('date')} disabled={d.done} /></div>
        <div><label className="f">활동 시간</label><select className="in" value={d.estHours} onChange={(e) => set('estHours')(Number(e.target.value))} disabled={d.done}>{EST.map((h) => <option key={h} value={h}>{h}시간</option>)}</select></div>
        <div><label className="f">가능 시작{low.time !== 'high' && <span className="mute2"> · 확인</span>}</label><select className="in" value={d.startHour} onChange={(e) => set('startHour')(Number(e.target.value))} disabled={d.done}>{HOURS.map((h) => <option key={h} value={h}>{ap(h)}</option>)}</select></div>
        <div><label className="f">가능 끝</label><select className="in" value={d.endHour} onChange={(e) => set('endHour')(Number(e.target.value))} disabled={d.done}>{HOURS.map((h) => <option key={h} value={h}>{ap(h)}</option>)}</select></div>
      </div>
      <div className={'su-check ' + (d.repeat && d.repeat.weekly ? 'on' : '')} role="checkbox" aria-checked={!!(d.repeat && d.repeat.weekly)} tabIndex={0} style={{ marginTop: 8 }} onClick={() => !d.done && onChange({ ...d, repeat: { weekly: !(d.repeat && d.repeat.weekly), weeks: 4 } })}><span className="bx" /><div className="small">매주 반복 (4주 공고)</div></div>
      <label className="f">할 일</label><textarea className="in" style={{ minHeight: 60 }} value={d.detail || ''} onChange={set('detail')} disabled={d.done} />
      <label className="f">주의사항 <span className="mute2">(병명 없이)</span></label><input className="in" value={d.note || ''} onChange={set('note')} disabled={d.done} />
    </div>
  );
}

export function MemoToPosts() {
  const [sp] = useSearchParams();
  const nav = useNavigate();
  const { toast } = useUi();
  const [elders] = useLoad(() => api('/org/elders'), []);
  const active = (elders || []).filter((e) => e.status !== 'ENDED');
  const [eid, setEid] = useState(sp.get('elder') || '');
  const sel = eid || (active[0] && String(active[0].id));
  const [memo, setMemo] = useState('');
  const [res, setRes] = useState(null);
  const [drafts, setDrafts] = useState([]);
  const [busy, run] = useBusy();
  const make = () => run(async () => {
    if (!memo.trim()) return toast('통화 메모를 적어 주세요');
    setRes('loading');
    try {
      const r = await post('/org/ai/memo-to-posts', { elderId: Number(sel), memo: memo.trim() });
      setRes(r); setDrafts(r.drafts || []);
    } catch { setRes(null); }
  });
  const register = () => run(async () => {
    const list = drafts.map((d, i) => [d, i]).filter(([d]) => d.checked && !d.done);
    if (!list.length) return toast('등록할 초안을 체크해 주세요');
    let ok = 0;
    const next = [...drafts];
    for (const [d, i] of list) {
      if (!d.date) { next[i] = { ...d, error: '날짜를 골라 주세요' }; continue; }
      if (!(d.categories || []).length) { next[i] = { ...d, error: '카테고리를 골라 주세요' }; continue; }
      try {
        const r = await post('/posts', { type: 'NEED', date: d.date, startHour: d.startHour, endHour: d.endHour, area: d.area, categories: d.categories, title: d.title, detail: d.detail, note: d.note || null, estHours: d.estHours, repeat: d.repeat, elderId: Number(sel) }, { quiet: true });
        next[i] = { ...d, done: true, checked: false, error: null, createdCount: r.createdCount };
        ok += 1;
      } catch (e) { next[i] = { ...d, error: e.message }; }
    }
    setDrafts(next);
    if (ok) toast(`공고 ${ok}개를 등록했어요`);
  });
  const checked = drafts.filter((d) => d.checked && !d.done).length;
  const elder = active.find((e) => String(e.id) === String(sel));
  return (
    <Screen title="통화 메모로 한 번에 만들기" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>통화하며 들은 내용을 그대로 적으면 공고 초안을 여러 개로 나눠 드려요. 하나씩 고치고 <b>체크한 것만</b> 등록돼요.</p>
      <label className="f">누구의 메모인가요?</label>
      {elders === undefined ? <Loading /> : <div className="chips" style={{ flexWrap: 'wrap' }}>{active.map((e) => <button key={e.id} className={'chip ' + (String(e.id) === String(sel) ? 'on' : '')} onClick={() => { setEid(String(e.id)); setRes(null); setDrafts([]); }}>{e.alias} 님</button>)}</div>}
      <label className="f">통화 메모 (말·글)</label>
      <div className="row" style={{ alignItems: 'flex-start' }}>
        <textarea className="in grow" style={{ minHeight: 96 }} placeholder={`예) ${elder ? elder.alias : '이순례'} 님 매주 목요일 오전 말벗, 다음 주 화요일 오후 정형외과 동행, 냉장고 정리 한 번`} value={memo} onChange={(e) => setMemo(e.target.value)} aria-label="통화 메모" />
        <MicButton onText={(t) => setMemo((x) => (x.trim() ? `${x.trim()} ${t}` : t))} />
      </div>
      <div className="mute2" style={{ marginTop: 4 }}>병명·전화번호·주소는 적지 않아도 돼요. 들어가 있으면 지우고 초안을 만들어요.</div>
      <button className="btn" style={{ marginTop: 12 }} disabled={busy || !sel} onClick={make}><Ic n="spark" s={18} /> 공고 초안 만들기</button>
      {res === 'loading' && <div style={{ marginTop: 14 }}><AiLoading label="메모를 나누는 중…" /></div>}
      {res && res !== 'loading' && (
        <>
          <div className="ai" style={{ marginTop: 14 }}>
            <div className="ah"><Spk />공고 초안 {drafts.length}개<Mod n={1} /></div>
            <div className="small">{res.notice} 날짜·시간·반복은 메모 구절에서 시스템이 읽었어요. 금지된 도움(신체 돌봄·돈 관리 등)은 등록할 수 없어요.</div>
            {(res.piiRemoved || []).length > 0 && <div className="mute2" style={{ marginTop: 6 }}>개인정보({res.piiRemoved.map((x) => ({ phone: '전화번호', account: '계좌', address: '주소' }[x] || x)).join('·')})를 지웠어요</div>}
            <RuleNote source={res.source} />
          </div>
          {drafts.map((d, i) => <DraftCard key={i} d={d} i={i} onChange={(nd) => setDrafts(drafts.map((x, j) => (j === i ? nd : x)))} />)}
          <button className="btn big-cta" style={{ marginTop: 14 }} disabled={busy || !checked} onClick={register}>체크한 {checked}건 등록</button>
          {drafts.some((d) => d.done) && <button className="btn ghost" style={{ marginTop: 8 }} onClick={() => nav(`/o/elders/${sel}`)}>{elder ? `${elder.alias} 님` : '대상'} 상세로</button>}
        </>
      )}
    </Screen>
  );
}

/* ===================== C5 신고 정리 (운영자) ===================== */
export function useTriage() {
  const [list, reload] = useLoad(() => api('/admin/ai/report-triage', { quiet: true }), []);
  const map = {};
  (list || []).forEach((t, i) => { map[t.reportId] = { ...t, order: i }; });
  return [map, reload, list];
}

export function TriageBox({ t, onRefresh }) {
  const [busy, run] = useBusy();
  if (!t) return null;
  return (
    <div className={'ai ' + (t.severity === 'high' ? 'warn' : '')} style={{ marginTop: 12 }}>
      <div className="ah"><Spk />신고 정리<Mod n={2} /><span style={{ marginLeft: 'auto' }}><SevChip s={t.severity} /></span></div>
      <div className="small">{t.summary}</div>
      <div className="mute2" style={{ marginTop: 6 }}>판단 근거(코드 규칙): {(t.reasons || []).join(' · ')}{t.aiSeverity && t.aiSeverity !== t.codeSeverity ? ` · AI 제안 ${SEV[t.aiSeverity][0]}` : ''}</div>
      {t.suggestion && <div className="small" style={{ marginTop: 8 }}><b>참고</b> {t.suggestion}</div>}
      {(t.similar || []).length > 0 && <><div className="b" style={{ fontSize: 13, marginTop: 8 }}>비슷한 과거 처리</div>{t.similar.map((x) => <div key={x.id} className="mute2">#{x.id} {x.type} → {x.result || x.action}{x.sameTarget ? ' (같은 대상)' : ''} · {dshort(x.createdAt).split(' ')[0]}</div>)}</>}
      <div className="between" style={{ marginTop: 8 }}><span className="mute2" style={{ fontSize: 12 }}>{t.notice}</span>{onRefresh && <button className="btn ghost sm" style={{ width: 'auto', padding: '0 10px' }} disabled={busy} onClick={() => run(async () => { await post(`/admin/ai/report-triage/${t.reportId}`); onRefresh(); })}>다시 정리</button>}</div>
      <RuleNote source={t.source} />
    </div>
  );
}

/* ===================== C6 이상 적립 설명 ===================== */
export function useAnomalyNotes() {
  const [list] = useLoad(() => api('/admin/ai/anomalies', { quiet: true }), []);
  return (x) => (list || []).find((n) => (x.requests || []).some((q) => (n.requestIds || []).includes(q.id)));
}

export function AnomalyNote({ n }) {
  if (!n) return null;
  const e = n.evidence || {};
  return (
    <div className="ai" style={{ marginTop: 10 }}>
      <div className="ah"><Spk />패턴 설명<Mod n={2} /></div>
      <div className="small">{n.text}</div>
      <div className="bchips" style={{ marginTop: 8 }}>{[['횟수', `${e.count}회`], ['평균', `${e.avgHours}시간`], ['주 시간대', e.topSlot], ['일지', `${e.avgLogLength}자`], ['간격', `${e.avgGapDays}일`]].map(([k, v]) => <span key={k} className="bchip"><b>{v}</b>{k}</span>)}</div>
      {(n.checks || []).length > 0 && <><div className="b" style={{ fontSize: 13, marginTop: 8 }}>확인할 점</div><ul className="small">{n.checks.map((c, i) => <li key={i}>{c}</li>)}</ul></>}
      <div className="mute2" style={{ fontSize: 12 }}>{n.notice}</div>
      <RuleNote source={n.source} />
    </div>
  );
}

/* ===================== C7 문의 답변 초안 ===================== */
export function InquiryDraft({ q, onUse }) {
  const [d, setD] = useState(null);
  const [busy, run] = useBusy();
  const make = (refresh) => run(async () => { setD('loading'); try { setD(await post(`/admin/ai/inquiries/${q.id}/draft${refresh ? '?refresh=true' : ''}`)); } catch { setD(null); } });
  if (!d) return <button className="btn soft sm" style={{ marginTop: 10 }} disabled={busy} onClick={() => make(false)}><Ic n="spark" s={16} /> 답변 초안 만들기</button>;
  if (d === 'loading') return <div style={{ marginTop: 10 }}><AiLoading label="근거를 모아 초안을 쓰는 중…" /></div>;
  return (
    <div className="ai" style={{ marginTop: 10 }}>
      <div className="ah"><Spk />답변 초안<Mod n={4} /></div>
      <div className="small" style={{ whiteSpace: 'pre-wrap' }}>{d.draft}</div>
      <div className="b" style={{ fontSize: 13, marginTop: 8 }}>근거</div>
      <ul className="small">{(d.basis || []).map((b, i) => <li key={i} className={(d.used || []).includes(i) ? 'used' : ''}><b>{b.label}</b> — {b.text}</li>)}</ul>
      <div className="btns" style={{ marginTop: 8 }}><button className="btn ghost sm" disabled={busy} onClick={() => make(true)}>다시 만들기</button><button className="btn sm" onClick={() => onUse(d.draft)}>답변 칸에 넣기</button></div>
      <div className="mute2" style={{ fontSize: 12, marginTop: 6 }}>{d.notice}</div>
      <RuleNote source={d.source} />
    </div>
  );
}

/* ===================== C4 실제 사례로 역할극 상대 만들기 ===================== */
export function PersonaFromCases({ onDraft }) {
  const [cs] = useLoad(() => api('/admin/ai/persona-cases'), []);
  const [pick, setPick] = useState(null);
  const [busy, run] = useBusy();
  const [res, setRes] = useState(null);
  const cases = (cs && cs.cases) || [];
  const key = pick || (cases[0] && cases[0].key);
  return (
    <>
      <h2>실제 사례로 만들기</h2>
      <p className="mute2" style={{ margin: 0 }}>{cs ? `${cs.period} 신고·메시지 경고·전달 메모에서 자주 나온 상황이에요. ${cs.notice}` : ''}</p>
      {cs === undefined ? <Loading /> : cases.map((c) => (
        <div key={c.key} className={'opt ' + (key === c.key ? 'on' : '')} role="button" tabIndex={0} onClick={() => setPick(c.key)}>
          <span className="rd" /><div className="grow"><div className="b">{c.label} <span className="mute2" style={{ fontWeight: 500 }}>{c.count}건</span></div>
            <div className="mute2">신고 {c.reports} · 메시지 {c.messages} · 메모 {c.memos} · 위반 유형 “{c.flag}”</div>
            {(c.samples || []).length > 0 && <div className="mute2" style={{ marginTop: 2 }}>예) {c.samples[0]}</div>}</div>
        </div>
      ))}
      {res === 'loading' && <AiLoading label="가상 상대를 만드는 중…" />}
      <button className="btn" style={{ marginTop: 12 }} disabled={busy || !key} onClick={() => run(async () => { setRes('loading'); try { const r = await post('/admin/ai/persona-draft', { caseKey: key }); setRes(r); onDraft(r); } catch { setRes(null); } })}><Ic n="spark" s={18} /> 초안 만들기</button>
    </>
  );
}

/** PersonaForm 위에 붙는 초안 안내 */
export function PersonaDraftNote({ res }) {
  if (!res) return null;
  return (
    <div className="ai" style={{ marginBottom: 10 }}>
      <div className="ah"><Spk />역할극 초안<Mod n={6} /></div>
      <div className="small">“{res.case && res.case.label}” 상황으로 만든 가상 상대예요(이름은 가명). 대사·판정(good/ok/poor/bad)·위반 유형을 확인하고 저장해요.</div>
      <div className="mute2" style={{ fontSize: 12, marginTop: 6 }}>{res.notice}</div>
      <RuleNote source={res.source} />
    </div>
  );
}
