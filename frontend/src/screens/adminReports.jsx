// 기능명세서 운영자 리포트 화면: 기능 A-02·A-04 봉사 리포트, A-05 재정 추계, A-06 정책 시뮬레이션, A-07 대응 추천, A-08·A-09 특보, A-11 담당자 신청 내역, A-13 신고 관련 기록
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Mi, Spk, Mod, RuleNote, Loading, Empty, useLoad, useBusy, hbars } from '../ui/bits.jsx';
import { useUi } from '../ui/ctx.jsx';
import { api, post, del, openFile } from '../api.js';
import { CC, SHORT, dshort } from '../lib/format.js';

const OR = '#F59E4B', BL = '#5B8DEF', GR = '#B6BCC8';
const num = (v) => (v == null ? '-' : Math.round(Number(v)).toLocaleString());
const RISK = { 위험: 'no', 주의: 'wait', 안정: 'ok' };
const Risk = ({ s }) => <span className={'st ' + (RISK[s] || 'end')}>{s}</span>;
const Src = ({ s }) => <span className="tag2" style={{ marginLeft: 4, padding: '1px 6px', fontSize: 11, background: s === '기록' ? 'var(--pri-soft)' : 'var(--fill)', color: s === '기록' ? 'var(--brand)' : 'var(--sub)' }}>{s}</span>;
const Legend = ({ items }) => <span className="legend" style={{ whiteSpace: 'nowrap' }}>{items.map(([c, t], i) => <span key={t} style={{ marginLeft: i ? 8 : 0 }}><i style={{ background: c }} />{t}</span>)}</span>;
const ccOf = (k) => (CC[k] || ['#EEF3FF', '#5B8DEF'])[1];

/** 묶음 막대: rows = [[이름, 값1, 값2]] */
function pairBars(rows, c1, c2, label) {
  const W = 320, H = 170, pt = 22, pb = 28, gap = 4;
  const max = Math.max(1, ...rows.flatMap((r) => [r[1], r[2]])) * 1.12;
  const cw = (W - 16) / Math.max(1, rows.length), bw = Math.min(18, (cw - 12) / 2);
  const short = (v) => (v >= 10000 ? `${(v / 10000).toFixed(1)}만` : v >= 1000 ? `${(v / 1000).toFixed(1)}천` : Math.round(v));
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img" aria-label={label}>
      {rows.map(([d, a, b], i) => {
        const cx = 8 + cw * i + cw / 2, ha = ((H - pt - pb) * a) / max, hb = ((H - pt - pb) * b) / max, by = H - pb;
        return (
          <g key={d}>
            <rect x={cx - bw - gap / 2} y={by - ha} width={bw} height={ha} rx="5" fill={c1} />
            <rect x={cx + gap / 2} y={by - hb} width={bw} height={hb} rx="5" fill={c2} />
            <text x={cx - bw / 2 - gap / 2} y={by - ha - 5} textAnchor="middle" fontSize="9.5" fontWeight="700" fill="#5F6672">{short(a)}</text>
            <text x={cx + bw / 2 + gap / 2} y={by - hb - 5} textAnchor="middle" fontSize="9.5" fontWeight="700" fill="#5F6672">{short(b)}</text>
            <text x={cx} y={H - 8} textAnchor="middle" fontSize="11.5" fill="#5F6672">{d}</text>
          </g>
        );
      })}
    </svg>
  );
}

/** 선 그래프: series = [{name, color, dash, values:[…]}], labels = [x 이름]. 100% 기준선을 그린다. */
function lineChart(labels, series, label) {
  const W = 320, H = 170, pl = 30, pr = 12, pt = 16, pb = 26;
  const all = series.flatMap((s) => s.values.filter((v) => v != null));
  const max = Math.max(110, ...all) * 1.08, min = Math.min(60, ...all) * 0.92;
  const x = (i) => pl + ((W - pl - pr) * i) / Math.max(1, labels.length - 1);
  const y = (v) => pt + (H - pt - pb) * (1 - (v - min) / (max - min));
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img" aria-label={label}>
      <line x1={pl} x2={W - pr} y1={y(100)} y2={y(100)} stroke="#D9DCE3" strokeDasharray="3 3" />
      <text x={pl - 4} y={y(100) + 3.5} textAnchor="end" fontSize="9.5" fill="#687080">100%</text>
      {series.map((s) => {
        const pts = s.values.map((v, i) => (v == null ? null : `${x(i)},${y(v)}`)).filter(Boolean).join(' ');
        return (
          <g key={s.name}>
            <polyline points={pts} fill="none" stroke={s.color} strokeWidth="2.5" strokeDasharray={s.dash || undefined} strokeLinejoin="round" strokeLinecap="round" />
            {s.values.map((v, i) => v != null && <g key={i}><circle cx={x(i)} cy={y(v)} r="3.5" fill="#fff" stroke={s.color} strokeWidth="2" /><text x={x(i)} y={y(v) + (s.below ? 14 : -8)} textAnchor="middle" fontSize="9.5" fontWeight="700" fill={s.color}>{Math.round(v)}</text></g>)}
          </g>
        );
      })}
      {labels.map((l, i) => <text key={l} x={x(i)} y={H - 8} textAnchor="middle" fontSize="11" fill="#5F6672">{l}</text>)}
    </svg>
  );
}

const yl = (r) => (r.offset === 0 ? '지금' : `+${r.offset}년`);
/** 표: head = [머리글], rows = [[칸…]] */
function Grid({ head, rows, cols }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: cols || `repeat(${head.length}, auto)`, gap: '6px 10px', fontSize: 13, marginTop: 10, alignItems: 'center' }}>
      {head.map((h) => <span key={h} className="mute2" style={{ fontSize: 12 }}>{h}</span>)}
      {rows.flatMap((r, i) => r.map((c, j) => <span key={`${i}-${j}`} style={{ fontWeight: j === 0 ? 700 : 500, minWidth: 0 }}>{c}</span>))}
    </div>
  );
}

function AreaChips({ areas, value, onChange }) {
  return <div className="chips" style={{ margin: '8px 0 2px' }}>{['전체', ...areas].map((a) => <button key={a} className={'chip ' + (value === a ? 'on' : '')} onClick={() => onChange(a)}>{a}</button>)}</div>;
}
const rowsOf = (f, area) => (area === '전체' ? f.total : ((f.areas || []).find((a) => a.area === area) || {}).rows) || [];

function Assumptions({ a }) {
  const [open, setOpen] = useState(false);
  if (!a) return null;
  return (
    <div className="card" style={{ marginTop: 12 }}>
      <div className="between"><span className="b">계산에 쓴 값</span><span className="mute2"><Src s="기록" /> 실제 기록 <Src s="가정" /> 기본 가정</span></div>
      <div className="small" style={{ marginTop: 8 }}>한 달 가입 <b>{a.joinsPerMonth}명</b><Src s={a.joinsSource} /> · 한 해 이탈률 <b>{a.churnYear}%</b><Src s={a.churnSource} /></div>
      <button className="btn ghost sm" style={{ marginTop: 10 }} aria-expanded={open} onClick={() => setOpen(!open)}>{open ? '나이대별 값 접기' : '나이대별 값 보기'}</button>
      {open && (
        <>
          <Grid cols="auto auto 1fr 1fr" head={['나이대', '회원·대상', '월 봉사 시간', '월 도움 시간']} rows={(a.bands || []).map((b) => [b.band, `${b.users}·${b.elders}명`, <span key="s">{b.supplyPer}<Src s={b.supplySource} /></span>, <span key="d">{b.demandPer}<Src s={b.demandSource} /></span>])} />
          <ul className="small muted" style={{ margin: '10px 0 0', paddingLeft: 18 }}>{(a.notes || []).map((n) => <li key={n}>{n}</li>)}</ul>
        </>
      )}
      <div className="mute2" style={{ marginTop: 8 }}>최근 {a.windowMonths}개월 기록을 썼어요. 예측은 지금 추세가 이어진다고 본 참고 값이에요.</div>
    </div>
  );
}

/* ---------- 기능 A-05 시간 은행 재정 추계 ---------- */
export function AForecast() {
  const nav = useNavigate();
  const { sheet } = useUi();
  const [f] = useLoad(() => api('/admin/forecast'), []);
  const [sum, setSum] = useState(undefined);
  const [busy, run] = useBusy();
  const [area, setArea] = useState('전체');
  const loadSum = (refresh) => run(async () => { try { setSum(await post(`/admin/forecast/summary${refresh ? '?refresh=true' : ''}`)); } catch { setSum(null); } });
  useEffect(() => { loadSum(false); }, []); // eslint-disable-line
  if (f === undefined) return <Screen title="시간 은행 재정 추계" noTabs><Loading /></Screen>;
  if (!f) return <Screen title="시간 은행 재정 추계" noTabs><Empty>불러오지 못했어요</Empty></Screen>;
  const rows = rowsOf(f, area);
  const cur = (f.areas || []).find((a) => a.area === area);
  return (
    <Screen title="시간 은행 재정 추계" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>회원 나이 분포와 가입·이탈 추세가 이어지면 앞으로 10년 동안 동네마다 봉사 시간(공급)과 도움 시간(수요)이 어떻게 될지 계산해요.</p>
      <div className="ai">
        <div className="ah"><Spk />이번 달 요약<Mod n={5} /></div>
        {sum === undefined ? <div className="skel" /> : !sum ? <div className="small">요약을 만들지 못했어요</div> : (
          <>
            <div className="small">{sum.headline}</div>
            {(sum.areas || []).map((a) => (
              <div key={a.area} style={{ marginTop: 10 }}>
                <div className="between"><b>{a.area}</b><Risk s={a.risk} /></div>
                <div className="small" style={{ marginTop: 2 }}>{a.text}</div>
                {sum.source === 'ai' && <div className="mute2" style={{ fontSize: 12, marginTop: 2 }}>근거: {(a.reasons || []).join(' · ')}</div>}
              </div>
            ))}
            <div className="mute2" style={{ marginTop: 10, fontSize: 12 }}>{sum.month} 요약 · {dshort(sum.savedAt)} 저장 · 숫자는 시스템 계산, 문장만 {sum.source === 'ai' ? 'AI' : '규칙'}</div>
            <RuleNote source={sum.source} />
          </>
        )}
        <div className="btns" style={{ marginTop: 10 }}>
          <button className="btn soft sm" disabled={busy} onClick={() => loadSum(true)}>{busy ? '만드는 중…' : '요약 다시 만들기'}</button>
          <button className="btn ghost sm" onClick={() => sheet(() => <SummaryList />)}>지난 요약 보기</button>
        </div>
      </div>
      <div className="card" style={{ marginTop: 12 }}>
        <div className="between"><span className="b">연도별 수요 · 공급</span><Legend items={[[OR, '수요'], [BL, '공급']]} /></div>
        <AreaChips areas={(f.areas || []).map((a) => a.area)} value={area} onChange={setArea} />
        {rows.length ? pairBars(rows.map((r) => [yl(r), Number(r.demand) || 0, Number(r.supply) || 0]), OR, BL, '연도별 수요 공급') : <Empty>아직 데이터가 없어요</Empty>}
        <Grid cols="auto 1fr 1fr auto auto" head={['연도', '공급(시간)', '수요(시간)', '공급/수요', '상태']} rows={rows.map((r) => [r.year, num(r.supply), num(r.demand), r.ratio == null ? '-' : `${Math.round(r.ratio)}%`, <Risk key="r" s={r.status} />])} />
        {cur && <ul className="small muted" style={{ margin: '10px 0 0', paddingLeft: 18 }}>{(cur.reasons || []).map((x) => <li key={x}>{x}</li>)}</ul>}
        <div className="mute2" style={{ marginTop: 8 }}>한 해 시간이에요. 공급이 수요의 80%에 못 미치면 위험, 100%에 못 미치면 주의로 표시해요.</div>
      </div>
      <div className="card" style={{ marginTop: 12 }}>
        <div className="b">동네별 위험도</div>
        {(f.areas || []).length ? f.areas.map((a) => {
          const last = a.rows[a.rows.length - 1], now = a.rows[0];
          return (
            <div key={a.area} role="button" tabIndex={0} className="click" style={{ padding: '10px 0', borderTop: '1px dashed var(--line)', cursor: 'pointer' }} onClick={() => setArea(a.area)} onKeyDown={(e) => { if (e.key === 'Enter') setArea(a.area); }}>
              <div className="between"><b>{a.area}</b><Risk s={a.risk} /></div>
              <div className="mute2">회원 {num(now.members)}명 · 관리 대상 {num(now.elders)}명 · 공급/수요 {now.ratio == null ? '-' : `${Math.round(now.ratio)}%`} → {last.year}년 {last.ratio == null ? '-' : `${Math.round(last.ratio)}%`}</div>
            </div>
          );
        }) : <Empty>동네 정보가 있는 회원이 없어요</Empty>}
      </div>
      <Assumptions a={f.assumptions} />
      <button className="btn" style={{ marginTop: 14 }} onClick={() => nav('/a/sim')}>조건을 바꿔 다시 계산하기</button>
    </Screen>
  );
}

function SummaryList() {
  const [list] = useLoad(() => api('/admin/forecast/summaries'), []);
  return (
    <>
      <h2>저장된 월간 요약</h2>
      {list === undefined ? <Loading /> : (list || []).length ? list.map((s) => (
        <div key={s.month} className="card" style={{ marginTop: 8 }}>
          <div className="between"><span className="b">{s.month}</span><span className="mute2">{dshort(s.savedAt)}</span></div>
          <div className="small" style={{ marginTop: 4 }}>{s.headline}</div>
          {(s.areas || []).map((a) => <div key={a.area} className="small muted" style={{ marginTop: 6 }}><b>{a.area}</b> <Risk s={a.risk} /> {a.text}</div>)}
        </div>
      )) : <Empty>저장된 요약이 없어요</Empty>}
    </>
  );
}

/* ---------- 기능 A-06 정책 시뮬레이션 ---------- */
const EXAMPLES = ['20대 가입이 매달 30명 늘면', '이탈률이 절반으로 줄면', '60대 봉사 시간이 월 2시간 늘면'];
const ADJ = { JOIN_ADD: ['한 달 가입 수', '명'], CHURN_MULT: ['이탈률 배수', '배'], SUPPLY_ADD: ['1인당 월 봉사 시간', '시간'], DEMAND_ADD: ['1인당 월 도움 시간', '시간'] };
export function ASim() {
  const { toast } = useUi();
  const [text, setText] = useState('');
  const [res, setRes] = useState(null);
  const [busy, run] = useBusy();
  const [area, setArea] = useState('전체');
  const [meta] = useLoad(() => api('/admin/forecast', { quiet: true }).catch(() => null), []);
  const [manual, setManual] = useState(false);
  const [list, setList] = useState([]);
  const [m, setM] = useState({ type: 'JOIN_ADD', ageBand: '', area: '', value: '' });
  const areas = (res && res.areas) || ((meta && meta.areas) || []).map((a) => a.area);
  const bands = (res && res.bands) || ((meta && meta.assumptions && meta.assumptions.bands) || []).map((b) => b.band);
  const calc = (body) => run(async () => {
    const r = await post('/admin/forecast/simulate', body);
    setRes(r);
    if (!r.understood) { setManual(true); toast('문장을 알아듣지 못했어요. 조정 값을 직접 넣어 주세요'); }
  });
  const addAdj = () => {
    const v = Number(m.value);
    if (m.value === '' || Number.isNaN(v)) return toast('바꿀 값을 숫자로 적어 주세요');
    setList([...list, { type: m.type, ageBand: m.ageBand || null, area: m.area || null, value: v }]);
    setM({ ...m, value: '' });
  };
  const b = res && res.before, a = res && res.after;
  const br = b ? rowsOf(b, area) : [], ar = a ? rowsOf(a, area) : [];
  return (
    <Screen title="정책 시뮬레이션" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>조건을 말로 적으면 재정 추계와 같은 방법으로 다시 계산해 전후를 비교해요. AI는 문장을 조정 값으로 바꾸기만 하고, 계산은 시스템이 해요.</p>
      <label className="f" htmlFor="sim-t">바꿔 볼 조건</label>
      <textarea id="sim-t" className="in" style={{ minHeight: 70 }} maxLength={300} placeholder="예) 20대 가입이 매달 30명 늘면" value={text} onChange={(e) => setText(e.target.value)} />
      <div className="tags" style={{ marginTop: 8 }}>{EXAMPLES.map((x) => <button key={x} type="button" className="tag2" onClick={() => setText(x)}>{x}</button>)}</div>
      <button className="btn" style={{ marginTop: 12 }} disabled={busy || !text.trim()} onClick={() => calc({ text: text.trim() })}>{busy ? '계산 중…' : '다시 계산'}</button>
      <button className="btn ghost sm" style={{ marginTop: 8 }} aria-expanded={manual} onClick={() => setManual(!manual)}>{manual ? '직접 넣기 접기' : '조정 값 직접 넣기'}</button>
      {manual && (
        <div className="card" style={{ marginTop: 10 }}>
          <div className="b">조정 값 직접 넣기</div>
          <label className="f" htmlFor="sim-ty">무엇을</label>
          <select id="sim-ty" className="in" value={m.type} onChange={(e) => setM({ ...m, type: e.target.value })}>{Object.entries(ADJ).map(([k, v]) => <option key={k} value={k}>{v[0]}</option>)}</select>
          <div className="btns" style={{ marginTop: 8 }}>
            <select className="in" aria-label="나이대" value={m.ageBand} onChange={(e) => setM({ ...m, ageBand: e.target.value })}><option value="">모든 나이대</option>{bands.map((x) => <option key={x}>{x}</option>)}</select>
            <select className="in" aria-label="동네" value={m.area} onChange={(e) => setM({ ...m, area: e.target.value })}><option value="">전체 동네</option>{areas.map((x) => <option key={x}>{x}</option>)}</select>
          </div>
          <label className="f" htmlFor="sim-v">{m.type === 'CHURN_MULT' ? '몇 배로 (절반이면 0.5)' : `얼마나 (${ADJ[m.type][1]}, 줄이면 -)`}</label>
          <div className="row"><input id="sim-v" className="in grow" inputMode="decimal" value={m.value} onChange={(e) => setM({ ...m, value: e.target.value })} /><button type="button" className="btn soft sm" style={{ width: 'auto', padding: '0 16px', flex: '0 0 auto' }} onClick={addAdj}>더하기</button></div>
          {list.length > 0 && <div className="tags" style={{ marginTop: 10 }}>{list.map((x, i) => <button key={i} type="button" className="tag2" aria-label="빼기" onClick={() => setList(list.filter((_, j) => j !== i))}>{x.area || '전체'} · {x.ageBand || '모든 나이대'} · {ADJ[x.type][0]} {x.type === 'CHURN_MULT' ? '×' : x.value > 0 ? '+' : ''}{x.value} ✕</button>)}</div>}
          <button className="btn sm" style={{ marginTop: 10 }} disabled={busy || !list.length} onClick={() => calc({ adjustments: list })}>이 값으로 계산</button>
        </div>
      )}
      {res && (
        <>
          <div className="ai" style={{ marginTop: 14 }}>
            <div className="ah"><Spk />해석한 조건<Mod n={5} /></div>
            {res.understood ? <ul>{res.adjustments.map((x, i) => <li key={i} className="small">{x.label}</li>)}</ul>
              : <div className="small">이 문장에서는 바꿀 조건을 찾지 못했어요. 가입 수·이탈률·봉사 시간처럼 숫자가 든 조건으로 적거나, 조정 값을 직접 넣어 주세요.</div>}
            <div className="mute2" style={{ fontSize: 12, marginTop: 6 }}>{res.source === 'ai' ? 'AI가 문장을 조정 값으로 바꿨어요' : res.source === 'manual' ? '직접 넣은 값이에요' : '규칙으로 문장을 읽었어요'} · 결과 숫자는 시스템 계산</div>
            <RuleNote source={res.source} />
          </div>
          {a && (
            <>
              <div className="card" style={{ marginTop: 12 }}>
                <div className="between" style={{ flexWrap: 'wrap', gap: 4 }}><span className="b">공급 / 수요 비율</span><Legend items={[[GR, '지금 추세'], [BL, '바꾼 뒤']]} /></div>
                <AreaChips areas={areas} value={area} onChange={setArea} />
                {lineChart(br.map(yl), [{ name: '전', color: GR, dash: '5 4', below: true, values: br.map((r) => r.ratio) }, { name: '후', color: BL, values: ar.map((r) => r.ratio) }], '전후 공급 수요 비율')}
                <div className="mute2">100%보다 낮으면 봉사 시간이 도움 시간에 못 미쳐요.</div>
              </div>
              <div className="card" style={{ marginTop: 12 }}>
                <div className="between" style={{ flexWrap: 'wrap', gap: 4 }}><span className="b">한 해 봉사 시간(공급)</span><Legend items={[[GR, '지금 추세'], [BL, '바꾼 뒤']]} /></div>
                {pairBars(br.map((r, i) => [yl(r), Number(r.supply) || 0, Number((ar[i] || {}).supply) || 0]), GR, BL, '전후 공급 시간')}
                <Grid cols="auto 1fr 1fr 1fr" head={['연도', '공급(전→후)', '수요(전→후)', '비율(전→후)']} rows={br.map((r, i) => { const x = ar[i] || {}; return [r.year, `${num(r.supply)} → ${num(x.supply)}`, `${num(r.demand)} → ${num(x.demand)}`, `${r.ratio == null ? '-' : Math.round(r.ratio)}% → ${x.ratio == null ? '-' : Math.round(x.ratio)}%`]; })} />
              </div>
              <div className="card" style={{ marginTop: 12 }}>
                <div className="b">동네별 위험도 변화</div>
                {(b.areas || []).map((x) => { const y = (a.areas || []).find((z) => z.area === x.area) || x; return <div key={x.area} className="between" style={{ padding: '8px 0', borderTop: '1px dashed var(--line)' }}><b>{x.area}</b><span style={{ display: 'flex', gap: 6, alignItems: 'center' }}><Risk s={x.risk} />→<Risk s={y.risk} /></span></div>; })}
              </div>
            </>
          )}
          <Assumptions a={res.assumptions} />
        </>
      )}
    </Screen>
  );
}

/* ---------- 기능 A-02 · A-04 봉사 리포트 ---------- */
function AiNote({ title, x }) {
  if (!x) return null;
  return <div className="ai" style={{ marginTop: 10 }}><div className="ah"><Spk />{title}<Mod n={5} /></div><div className="small">{x.text}</div><div className="mute2" style={{ fontSize: 12, marginTop: 6 }}>숫자는 시스템 집계, 풀이는 {x.source === 'ai' ? 'AI' : '규칙'} 문장이에요</div><RuleNote source={x.source} /></div>;
}
function monthBars(months) {
  const W = 320, H = 150, pt = 16, pb = 24;
  const max = Math.max(1, ...months.map((m) => Number(m.total) || 0)) * 1.12;
  const cw = (W - 8) / Math.max(1, months.length), bw = Math.min(16, cw - 6);
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img" aria-label="월별 도움 요청">
      {months.map((m, i) => {
        const cx = 4 + cw * i + cw / 2;
        let y = H - pb;
        const segs = Object.entries(m.counts || {}).filter(([, v]) => v > 0).map(([k, v]) => { const h = ((H - pt - pb) * v) / max; y -= h; return <rect key={k} x={cx - bw / 2} y={y} width={bw} height={h} fill={ccOf(k)}><title>{`${k} ${v}건`}</title></rect>; });
        return <g key={m.month}>{segs}{m.total > 0 && <text x={cx} y={y - 4} textAnchor="middle" fontSize="9.5" fontWeight="700" fill="#5F6672">{m.total}</text>}<text x={cx} y={H - 8} textAnchor="middle" fontSize="10.5" fill="#687080">{Number(String(m.month).slice(5))}월</text></g>;
      })}
    </svg>
  );
}
const UT = { GIVE: '봉사만', BOTH: '주고받음', GET: '도움만', NONE: '활동 없음' };
/* 이용 리포트 상단 토글(2026-11-13 대표 지시): 도움 요청 / 봉사 가능 에 따라 나이별 인기 · 계절별 · 지역별 통계가 달라진다 */
function ByTypeStats() {
  const [type, setType] = useState('NEED');
  const [d] = useLoad(() => api(`/admin/stats/by-type?type=${type}`), [type]);
  const color = type === 'NEED' ? OR : BL;
  const keys = Object.keys((((d && d.monthly) || [])[0] || {}).counts || {});
  const tops = (top) => (top || []).map((c, i) => `${i + 1}위 ${SHORT[c.category] || c.category}(${c.count})`).join(' · ') || '기록 없음';
  return (
    <>
      <div className="seg2" role="tablist" style={{ display: 'flex', gap: 6, marginTop: 4 }}>
        {[['NEED', '도움 요청'], ['OFFER', '봉사 가능']].map(([k, t]) => <button key={k} role="tab" aria-selected={type === k} className={'chip ' + (type === k ? 'on' : '')} style={{ flex: 1, justifyContent: 'center' }} onClick={() => setType(k)}>{t}</button>)}
      </div>
      <div className="mute2" style={{ marginTop: 6 }}>{type === 'NEED' ? '도움 요청 공고' : '봉사 가능 공고'} 기준 통계예요{d ? ` · 최근 ${d.periodMonths}개월 공고 ${d.postCount}건` : ''}</div>
      {d === undefined ? <Loading /> : !d ? <Empty>불러오지 못했어요</Empty> : (
        <>
          <div className="sec-t">나이별 인기</div>
          <div className="card">
            {(d.byAge || []).length ? d.byAge.map((a) => (
              <div key={a.ageGroup} style={{ padding: '8px 0', borderTop: '1px dashed var(--line)' }}>
                <div className="between"><b>{a.ageGroup}</b><span className="mute2">{a.total}건</span></div>
                <div className="small" style={{ marginTop: 2 }}>{tops(a.top)}</div>
              </div>
            )) : <Empty>나이대를 알 수 있는 공고가 아직 없어요</Empty>}
            <div className="mute2" style={{ marginTop: 6 }}>나이대는 공고를 올린 사람(기관 대리 공고는 그 관리 대상) 기준이에요</div>
          </div>
          <div className="sec-t">계절별</div>
          <div className="card">
            <div className="between"><span className="b">월별 {d.label} 공고</span><span className="mute2">최근 {d.periodMonths}개월</span></div>
            {monthBars(d.monthly || [])}
            <div className="legend" style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 10px' }}>{keys.map((k) => <span key={k}><i style={{ background: ccOf(k) }} />{SHORT[k] || k}</span>)}</div>
            {(d.seasons || []).map((x) => <div key={x.season} className="small" style={{ marginTop: 6 }}><b>{x.season}</b> {x.total}건 · {tops(x.top)}</div>)}
          </div>
          <div className="sec-t">지역별</div>
          <div className="card">
            {(d.byArea || []).length ? (
              <>
                {hbars(d.byArea.map((a) => [a.area, a.total]), color)}
                {d.byArea.map((a) => <div key={a.area} className="small" style={{ marginTop: 6 }}><b>{a.area}</b> {a.total}건 · {tops(a.top)}</div>)}
              </>
            ) : <Empty>아직 공고가 없어요</Empty>}
          </div>
        </>
      )}
    </>
  );
}

export function AStats() {
  const nav = useNavigate();
  const [t] = useLoad(() => api('/admin/stats/types'), []);
  const [s] = useLoad(() => api('/admin/stats/season'), []);
  const cats = ((t && t.categories) || []).filter((c) => c.count > 0);
  const ns = s && s.nextSeason;
  const keys = Object.keys((((s && s.monthly) || [])[0] || {}).counts || {});
  return (
    <Screen title="이용 리포트" noTabs>
      <ByTypeStats />
      <div className="sec-t">봉사 유형</div>
      {t === undefined ? <Loading /> : !t ? <Empty>불러오지 못했어요</Empty> : (
        <>
          <div className="card">
            <div className="between"><span className="b">종류별 주는 쪽 · 받는 쪽</span><Legend items={[[BL, '주는 사람'], [OR, '받는 사람']]} /></div>
            {cats.length ? cats.map((c) => (
              <div key={c.category} style={{ padding: '8px 0', borderTop: '1px dashed var(--line)' }}>
                <div className="between"><b>{SHORT[c.category] || c.category}</b><span className="mute2">{c.count}건 · {c.hours}시간</span></div>
                <div style={{ display: 'flex', height: 12, borderRadius: 6, overflow: 'hidden', margin: '6px 0' }}><i style={{ width: `${c.giverPct}%`, background: BL }} /><i style={{ width: `${c.receiverPct}%`, background: OR }} /></div>
                <div className="small muted">주는 사람 {c.givers}명({c.giverPct}%) · 받는 사람 {c.receivers}명({c.receiverPct}%)</div>
              </div>
            )) : <Empty>최근 12개월에 끝난 활동이 없어요</Empty>}
            <div className="mute2" style={{ marginTop: 6 }}>최근 {t.periodMonths}개월에 끝난 활동 {t.doneCount}건 · 사람 수는 겹치지 않게 셌어요</div>
          </div>
          <div className="card" style={{ marginTop: 12 }}>
            <div className="b">사용자 유형 분포</div>
            {hbars((t.userTypes || []).map((u) => [UT[u.key] || u.label, u.count]), BL)}
            <div className="small muted" style={{ marginTop: 8 }}>{(t.userTypes || []).map((u) => `${u.label} ${u.count}명(${u.pct}%)`).join(' · ')}</div>
            <div className="mute2" style={{ marginTop: 8 }}>회원 {t.members}명 · 기관 관리 대상(도움만 받음) {t.elders}명</div>
          </div>
          <AiNote title="AI 해석" x={t.interpretation} />
        </>
      )}
      <div className="sec-t">계절별 봉사</div>
      {s === undefined ? <Loading /> : !s ? <Empty>불러오지 못했어요</Empty> : (
        <>
          <div className="card">
            <div className="between"><span className="b">월별 도움 요청</span><span className="mute2">최근 {s.periodMonths}개월</span></div>
            {monthBars(s.monthly || [])}
            <div className="legend" style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 10px' }}>{keys.map((k) => <span key={k}><i style={{ background: ccOf(k) }} />{SHORT[k] || k}</span>)}</div>
            <div className="small muted" style={{ marginTop: 8 }}>{(s.seasons || []).map((x) => `${x.season} 요청 ${x.demand} · 가능 ${x.supply}`).join(' / ')}</div>
          </div>
          {ns && (
            <div className="card" style={{ marginTop: 12 }}>
              <div className="between"><span className="b">다음 계절({ns.season}) 예상 수요</span><span className="pill">약 {ns.total}건</span></div>
              {ns.total > 0 ? hbars(ns.categories.map((c) => [SHORT[c.category] || c.category, c.expected]), OR) : <Empty>수요 기록이 적어 예상을 내기 어려워요</Empty>}
              <div className="mute2" style={{ marginTop: 8 }}>{ns.basis} × 최근 석 달 추세 {ns.growth}배(최근 90일 {ns.recent90}건 ÷ 그 앞 90일 {ns.prev90}건, 0.5~2배로 자름) · 시스템 계산</div>
            </div>
          )}
          <AiNote title="AI 해석" x={s.interpretation} />
        </>
      )}
      <div className="mute2" style={{ marginTop: 14 }}>나이대별 인기 봉사 순위는 대시보드에서 볼 수 있어요.</div>
      <button className="btn ghost" style={{ marginTop: 8 }} onClick={() => nav('/a/dash')}>대시보드로</button>
    </Screen>
  );
}

/* ---------- 기능 A-07 대응 추천: 모집 문구 초안 ---------- */
function DraftToNotice({ d, onDone }) {
  const [t, setT] = useState(d.title);
  const [c, setC] = useState(d.body);
  const { toast } = useUi();
  const [busy, run] = useBusy();
  return (
    <>
      <h2>공지로 올리기</h2>
      <p className="mute2" style={{ margin: 0 }}>초안이에요. 고친 뒤 올리면 공지사항에 바로 게시돼요.</p>
      <label className="f" htmlFor="dn-t">제목</label><input id="dn-t" className="in" maxLength={100} value={t} onChange={(e) => setT(e.target.value)} />
      <label className="f" htmlFor="dn-c">내용</label><textarea id="dn-c" className="in" style={{ minHeight: 160 }} value={c} onChange={(e) => setC(e.target.value)} />
      <button className="btn" style={{ marginTop: 12 }} disabled={busy} onClick={() => run(async () => { if (!t.trim()) return toast('제목을 적어 주세요'); await post('/admin/notices', { title: t.trim(), content: c }); toast('공지로 올렸어요'); onDone(); })}>공지로 게시</button>
    </>
  );
}
export function ARecruit() {
  const { toast, sheet, closeSheet } = useUi();
  const [d, reload] = useLoad(() => api('/admin/recruit-drafts'), []);
  const list = (d && d.drafts) || [];
  const copy = async (x) => { try { await navigator.clipboard.writeText(`${x.title}\n\n${x.body}`); toast('문구를 복사했어요'); } catch { toast('복사하지 못했어요. 문구를 길게 눌러 복사해 주세요'); } };
  return (
    <Screen title="대응 추천" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>도움 요청보다 봉사 가능이 모자란 동네·시간대·도움 종류를 찾아 봉사자 모집 문구 초안을 만들어요. 올릴지는 운영자가 정해요.</p>
      {d === undefined ? <Loading /> : !list.length ? <Empty>지금은 공급이 모자란 곳이 없어요</Empty> : list.map((x, i) => (
        <div key={i} className="card" style={{ marginTop: 10 }}>
          <div className="between"><span className="b">{x.area} · {SHORT[x.category] || x.category}</span><span className="st wait">{x.gap}건 부족</span></div>
          <div className="mute2">{x.timeSlot} 요청이 많아요 · 도움 요청 {x.demand} · 봉사 가능 {x.supply}</div>
          <div className="ai" style={{ marginTop: 10 }}>
            <div className="ah"><Spk />모집 문구 초안<Mod n={1} /></div>
            <div className="b" style={{ fontSize: 15 }}>{x.title}</div>
            <div className="small" style={{ marginTop: 6, whiteSpace: 'pre-wrap' }}>{x.body}</div>
          </div>
          <div className="btns" style={{ marginTop: 10 }}>
            <button className="btn ghost sm" onClick={() => copy(x)}>복사</button>
            <button className="btn sm" onClick={() => sheet(() => <DraftToNotice d={x} onDone={closeSheet} />)}>공지로 올리기</button>
          </div>
        </div>
      ))}
      {d && <><div className="mute2" style={{ marginTop: 10 }}>최근 {d.days}일 공고·요청 기준 · 부족 건수는 시스템 집계</div><RuleNote source={d.source} /></>}
      <button className="btn ghost" style={{ marginTop: 12 }} onClick={reload}>다시 만들기</button>
    </Screen>
  );
}

/* ---------- 기능 A-08 · A-09 특보 알림 리포트 · 전달 ---------- */
const SRC = { MANUAL: '직접 입력', SAMPLE: '시연용 표본', KMA: '기상청' };
function AlertForm({ meta, onDone }) {
  const { toast } = useUi();
  const [f, setF] = useState({ type: meta.types[0], level: meta.levels[0], areas: [], startDate: '', endDate: '', memo: '' });
  const [busy, run] = useBusy();
  const tog = (a) => setF({ ...f, areas: f.areas.includes(a) ? f.areas.filter((x) => x !== a) : [...f.areas, a] });
  return (
    <>
      <h2>특보 직접 입력</h2>
      <label className="f">종류</label>
      <div className="tags">{meta.types.map((x) => <button key={x} type="button" className={'chip ' + (f.type === x ? 'on' : '')} aria-pressed={f.type === x} onClick={() => setF({ ...f, type: x })}>{x}</button>)}</div>
      <label className="f">수준</label>
      <div className="tags">{meta.levels.map((x) => <button key={x} type="button" className={'chip ' + (f.level === x ? 'on' : '')} aria-pressed={f.level === x} onClick={() => setF({ ...f, level: x })}>{x}</button>)}</div>
      <label className="f">동네 (여러 곳 가능)</label>
      <div className="tags">{meta.knownAreas.map((x) => <button key={x} type="button" className={'chip ' + (f.areas.includes(x) ? 'on' : '')} aria-pressed={f.areas.includes(x)} onClick={() => tog(x)}>{x}</button>)}</div>
      <div className="btns" style={{ marginTop: 4 }}>
        <div><label className="f" htmlFor="wa-s">시작일</label><input id="wa-s" type="date" className="in" value={f.startDate} onChange={(e) => setF({ ...f, startDate: e.target.value })} /></div>
        <div><label className="f" htmlFor="wa-e">끝나는 날</label><input id="wa-e" type="date" className="in" value={f.endDate} onChange={(e) => setF({ ...f, endDate: e.target.value })} /></div>
      </div>
      <div className="mute2" style={{ marginTop: 6 }}>날짜를 비우면 오늘부터 사흘로 봐요.</div>
      <button className="btn" style={{ marginTop: 12 }} disabled={busy} onClick={() => run(async () => {
        if (!f.areas.length) return toast('동네를 하나 이상 골라 주세요');
        await post('/admin/weather/alerts', { type: f.type, level: f.level, areas: f.areas, startDate: f.startDate || null, endDate: f.endDate || null, memo: f.memo });
        toast('특보 리포트를 만들었어요'); onDone();
      })}>리포트 만들기</button>
    </>
  );
}
export function AWeather() {
  const nav = useNavigate();
  const { toast, sheet, closeSheet, confirm } = useUi();
  const [d, reload] = useLoad(() => api('/admin/weather/alerts'), []);
  const [busy, run] = useBusy();
  const list = (d && d.alerts) || [];
  return (
    <Screen title="특보 알림" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>폭염·한파 같은 기상 특보가 내려진 동네의 관리 대상 수와 담당 기관을 모아 리포트를 만들어요. 공지와 담당자 알림은 운영자가 눌러야 나가요.</p>
      {d === undefined ? <Loading /> : (
        <>
          <div className="notice">{d.live ? '기상청 특보를 받아 올 수 있어요.' : '기상 API 키가 없어 자동으로 받아 오지 못해요. 특보를 직접 넣거나 시연용 표본을 불러와 주세요.'}</div>
          <div className="btns" style={{ marginTop: 10 }}>
            {d.live
              ? <button className="btn soft sm" disabled={busy} onClick={() => run(async () => { const r = await post('/admin/weather/fetch'); toast(r.message); reload(); })}>기상청에서 받아 오기</button>
              : <button className="btn soft sm" disabled={busy} onClick={() => run(async () => { await post('/admin/weather/alerts/sample'); toast('시연용 표본 특보를 불러왔어요'); reload(); })}>표본 특보 불러오기</button>}
            <button className="btn sm" onClick={() => sheet(() => <AlertForm meta={d} onDone={() => { closeSheet(); reload(); }} />)}>특보 직접 입력</button>
          </div>
          {!list.length ? <Empty>아직 특보가 없어요</Empty> : list.map((a) => {
            const f = a.facts || {}, rep = a.report || {};
            return (
              <div key={a.id} className="card" style={{ marginTop: 12 }}>
                <div className="between"><span className="b" style={{ fontSize: 16 }}>{a.type} {a.level}</span><span className={'st ' + (a.sentAt ? 'ok' : 'wait')}>{a.sentAt ? '전달함' : '전달 전'}</span></div>
                <div className="mute2">{(a.areas || []).join('·')} · {f.from} ~ {f.to} · {SRC[a.source] || a.source}</div>
                {a.memo && <div className="small muted" style={{ marginTop: 4 }}>{a.memo}</div>}
                <div className="kpis"><div><b>{f.totalElders ?? 0}명</b><span>관리 대상</span></div><div><b>{f.totalElders80 ?? 0}명</b><span>80대 이상</span></div><div><b>{f.totalActivities ?? 0}건</b><span>기간 안 방문·동행</span></div></div>
                {(f.areas || []).map((r) => (
                  <div key={r.area} className="small" style={{ marginTop: 8 }}><b>{r.area}</b> <span className="mute2">대상 {r.elders}명 · 80대 이상 {r.elders80}명 · 예정 {r.activities}건</span>
                    {(r.managers || []).length > 0 && <div className="muted">담당: {r.managers.map((m) => `${m.orgName || '기관'} ${m.name}(${m.elders}명)`).join(', ')}</div>}
                  </div>
                ))}
                <div className={'ai ' + (a.level === '경보' ? 'warn' : 'amber')} style={{ marginTop: 10 }}>
                  <div className="ah"><Spk />특보 리포트<Mod n={5} /></div>
                  <div className="small">{rep.summary}</div>
                  {(rep.actions || []).length > 0 && <><div className="b" style={{ fontSize: 13, marginTop: 8 }}>담당자에게 권할 일</div><ul>{rep.actions.map((x) => <li key={x} className="small">{x}</li>)}</ul></>}
                  <div className="mute2" style={{ fontSize: 12, marginTop: 6 }}>대상 수·담당 기관은 시스템 집계 · 건강 판단은 하지 않아요</div>
                  <RuleNote source={rep.source} />
                </div>
                {a.sentAt ? (
                  <>
                    <div className="small muted" style={{ marginTop: 10 }}>{dshort(a.sentAt)} 공지로 게시하고 담당자 {a.sentManagers ?? 0}명에게 알렸어요.</div>
                    <button className="btn ghost sm" style={{ marginTop: 8 }} onClick={() => nav(`/notice/${a.noticeId}`)}>게시한 공지 보기</button>
                  </>
                ) : (
                  <>
                    <button className="btn" style={{ marginTop: 10 }} disabled={busy} onClick={async () => {
                      if (!(await confirm('이 특보를 전달할까요?', `공지사항에 게시하고 ${(a.areas || []).join('·')} 담당자 ${f.managerCount ?? 0}명에게 알림을 보내요.`, '전달'))) return;
                      run(async () => { const r = await post(`/admin/weather/alerts/${a.id}/send`); toast(`공지로 게시하고 담당자 ${r.sentManagers}명에게 알렸어요`); reload(); });
                    }}>공지 게시 · 담당자 알림 보내기</button>
                    <div className="btns" style={{ marginTop: 8 }}>
                      <button className="btn ghost sm" disabled={busy} onClick={() => run(async () => { await post(`/admin/weather/alerts/${a.id}/refresh`); reload(); toast('리포트를 다시 정리했어요'); })}>다시 정리</button>
                      <button className="btn ghost sm" disabled={busy} onClick={async () => { if (await confirm('이 특보를 지울까요?', `${a.type} ${a.level}`, '삭제', true)) run(async () => { await del(`/admin/weather/alerts/${a.id}`); reload(); toast('지웠어요'); }); }}>삭제</button>
                    </div>
                  </>
                )}
              </div>
            );
          })}
        </>
      )}
    </Screen>
  );
}

/* ---------- 기능 A-11 담당자 계정 신청·승인 내역 ---------- */
const APP_ST = { APPROVED: ['ok', '승인'], PENDING: ['wait', '확인 중'], REJECTED: ['no', '반려'], CANCELLED: ['no', '취소'], SUSPENDED: ['no', '정지'] };
export function AMgrApps() {
  const { toast, confirm } = useUi();
  // 담당자 계정 신청(기능 C-04) API 가 아직 없거나 실패하면 빈 목록으로 보인다
  const [list, reload] = useLoad(() => api('/admin/manager-applications', { quiet: true }).then((r) => (Array.isArray(r) ? r : (r && (r.items || r.applications)) || [])).catch(() => []), []);
  const [mgrs, reloadM] = useLoad(() => api('/admin/managers', { quiet: true }).catch(() => []), []);
  const [busy, run] = useBusy();
  return (
    <Screen title="담당자 계정 신청" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>승인된 기관 소속이고 본인 확인이 끝난 담당자 계정 신청은 자동으로 승인돼요. 여기서 내역을 보고, 문제가 있으면 계정을 정지할 수 있어요.</p>
      {list === undefined ? <Loading /> : !(list || []).length ? <Empty>신청·승인 내역이 없어요</Empty> : list.map((a, i) => {
        const uid = a.userId || a.managerId || (a.user && a.user.id);
        const m = (mgrs || []).find((x) => x.id === uid);
        const sus = (m && m.status === 'SUSPENDED') || a.status === 'SUSPENDED' || a.status === 'CANCELLED' || a.status === 'REVOKED' || a.userStatus === 'SUSPENDED';
        const st = APP_ST[sus ? 'SUSPENDED' : a.status] || APP_ST.APPROVED;
        return (
          <div key={a.id || i} className="card" style={{ marginTop: 8 }}>
            <div className="between"><span className="b">{a.name || a.managerName || (a.user && a.user.name) || (m && m.name) || '담당자'}</span><span className={'st ' + st[0]}>{st[1]}</span></div>
            <div className="mute2">{a.orgName || (a.org && a.org.name) || (m && m.orgName) || ''}{a.loginId ? ` · ${a.loginId}` : ''}{a.createdAt || a.appliedAt ? ` · ${dshort(a.createdAt || a.appliedAt).split(' ')[0]} 신청` : ''}</div>
            <div className="small muted" style={{ marginTop: 4 }}>기관 승인 {a.orgApproved === false ? '안 됨' : '확인'} · 본인 확인 {a.phoneVerified === false ? '안 됨' : '확인'}</div>
            {a.existingManagers > 0 && <div className="small" style={{ marginTop: 4, color: '#A0521A' }}>신청할 때 이 기관에 이미 담당자가 {a.existingManagers}명 있었어요</div>}
            {a.proofFileUrl && <button className="btn soft sm" style={{ marginTop: 8 }} onClick={() => openFile(a.proofFileUrl)}>재직 증빙 열기</button>}
            {uid && !sus && <button className="btn ghost sm" style={{ marginTop: 10 }} disabled={busy} onClick={async () => { if (await confirm('이 담당자 계정을 정지할까요?', '정지하면 로그인할 수 없어요. 담당자 계정 화면에서 다시 풀 수 있어요.', '정지', true)) run(async () => { if (a.id) await post(`/admin/manager-applications/${a.id}/revoke`, { reason: '운영자 승인 취소' }); else await post(`/admin/managers/${uid}/suspend`); toast('승인을 취소하고 계정을 정지했어요'); reload(); reloadM(); }); }}>승인 취소(계정 정지)</button>}
          </div>
        );
      })}
    </Screen>
  );
}

/* ---------- 리포트 진입(대시보드·관리 메뉴에서 쓴다) ---------- */
export function ReportMenu() {
  const nav = useNavigate();
  return (
    <div className="ml" style={{ marginTop: 12 }}>
      <Mi t="시간 은행 재정 추계" s="10년 뒤 동네별 수요·공급 · 위험 동네 월간 요약" i="chart" onClick={() => nav('/a/forecast')} />
      <Mi t="정책 시뮬레이션" s="조건을 말로 바꿔 전후 비교" i="spark" onClick={() => nav('/a/sim')} />
      <Mi t="이용 리포트" s="도움 요청·봉사 가능별 나이·계절·지역 통계 · 봉사 유형" i="chart" onClick={() => nav('/a/stats')} />
      <Mi t="대응 추천" s="공급이 모자란 곳의 모집 문구 초안" i="file" onClick={() => nav('/a/recruit')} />
      <Mi t="특보 알림" s="폭염·한파 위험 지역 리포트 · 공지와 담당자 알림" i="flag" onClick={() => nav('/a/weather')} />
    </div>
  );
}

/* ---------- 기능 A-13 신고 관련 기록 ---------- */
const ACT_KO = { NONE: '기각', WARNING: '경고', SUSPEND: '정지', BAN: '퇴출' };
export function ReportContext({ id }) {
  const [c] = useLoad(() => api(`/admin/report-context/${id}`, { quiet: true }).catch(() => null), [id]);
  if (!c) return null;
  const t = c.target;
  return (
    <div className="card" style={{ marginTop: 12 }}>
      <div className="b">관련 기록</div>
      {t ? <div className="small" style={{ marginTop: 6 }}>대상 <b>{t.name}</b> · 경고 {t.warningCount}회 · 노쇼 패널티 {t.penaltyCount}회 · {t.banned ? '퇴출' : t.status === 'SUSPENDED' ? '정지 중' : '정상'} · 끝낸 봉사 {t.doneAsVolunteer}회</div> : <div className="small muted" style={{ marginTop: 6 }}>제재할 대상 계정이 없는 신고예요.</div>}
      {t && <div className="small muted" style={{ marginTop: 4 }}>신고자와 함께 끝낸 활동 {c.betweenDone ?? 0}회 · 신고자가 낸 신고 {c.reporterReportCount}건</div>}
      {(c.pastReports || []).length > 0 && <><div className="b" style={{ fontSize: 13, marginTop: 10 }}>이 대상의 지난 신고 {c.pastReports.length}건</div>{c.pastReports.map((p) => <div key={p.id} className="mute2">#{p.id} {p.type} → {p.status === 'DONE' ? ACT_KO[p.action] || p.action : '처리 전'} · {dshort(p.createdAt).split(' ')[0]}</div>)}</>}
      {c.request && <><div className="b" style={{ fontSize: 13, marginTop: 10 }}>관련 활동</div><div className="mute2">{c.request.date} · {SHORT[c.request.category] || c.request.category} · {c.request.hours}시간{c.request.logText ? ` · 일지: ${c.request.logText}` : ''}</div></>}
      {c.post && <><div className="b" style={{ fontSize: 13, marginTop: 10 }}>신고된 공고</div><div className="mute2">{c.post.title} · {c.post.area}</div>{c.post.detail && <div className="small muted">{c.post.detail}</div>}</>}
      {(c.messages || []).length > 0 && <><div className="b" style={{ fontSize: 13, marginTop: 10 }}>그 활동의 최근 메시지</div>{c.messages.map((m) => <div key={m.id} className="small" style={{ marginTop: 4, fontWeight: m.reported ? 700 : 400 }}><span className="mute2">{m.sender}{m.byTarget ? '(대상)' : ''}</span> {m.text}{m.reported ? ' ← 신고됨' : ''}</div>)}</>}
    </div>
  );
}
