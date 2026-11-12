import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Spk, Mod, Loading, Empty, RuleNote, useLoad, useBusy } from '../ui/bits.jsx';
import { useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { CATS, SHORT, hh, ap } from '../lib/format.js';
import '../styles/schedule.css';

/* ---------- 기능 O-11 돌봄 편성표 · O-12 편성 대안 추천 · O-13 공고 한꺼번에 올리기 ---------- */
const STATE = { CONFIRMED: '확정', PROGRESS: '진행 중', PENDING: '응답 대기', OPEN: '열린 공고', DONE: '완료' };
const md = (d) => { const [, m, day] = d.split('-'); return `${Number(m)}/${Number(day)}`; };
const HOURS = Array.from({ length: 25 }, (_, i) => 8 + i * 0.5); // 8시~20시
const DUR = [0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4];
const keyOf = (elderId, date) => `${elderId}|${date}`;

/** 빈 칸 하나의 추천(기능 O-12). 후보·순서는 서버가 찾고 요청은 보내지 않는다. 담당자가 눌러 요청 화면으로 간다. */
function SuggestSheet({ cell, onPost, onClose }) {
  const nav = useNavigate();
  const [r] = useLoad(() => post('/org/schedule/suggest', { elderId: cell.elderId, date: cell.date, startHour: cell.startHour, hours: cell.hours, category: cell.category }), []);
  // 고른 칸의 날짜·시간·도움 종류·대상을 요청 화면에 미리 채워 넘긴다(다른 요일 후보는 그 요일 날짜로)
  const go = (c, date) => {
    const sl = (r && r.slot) || cell;
    const q = new URLSearchParams({ elder: cell.elderId, date: date || sl.date || cell.date });
    if (sl.startHour != null) q.set('s', sl.startHour);
    if (sl.hours != null) q.set('h', sl.hours);
    if (sl.category) q.set('cat', sl.category);
    onClose();
    nav(c.action === 'REQUEST' ? `/req/new?post=${c.offerPostId}&${q}` : `/req/new?to=${c.volunteerId}&${q}`);
  };
  const Cand = ({ c, date }) => (
    <div className="sch-row">
      <div className="between"><span className="b">{c.name} {c.regular && <span className="st ok">단골</span>} {c.sameDong && <span className="st go">같은 동</span>}</span>{c.ratingCount > 0 && <span className="mute2">★ {c.ratingAvg}</span>}</div>
      <div className="small" style={{ marginTop: 4 }}>{c.reason}</div>
      {c.action === 'POST'
        ? <div className="mute2" style={{ marginTop: 6 }}>공고도 단골도 아니라 바로 요청할 수는 없어요. 아래 "이 칸에 공고 올리기"로 올려 두면 볼 수 있어요.</div>
        : <button className="btn soft sm" style={{ marginTop: 8 }} onClick={() => go(c, date)}>{c.action === 'REQUEST' ? '이 봉사자 공고에 요청하러 가기 ›' : '단골 요청하러 가기 ›'}</button>}
    </div>
  );
  return (
    <>
      <h2>{cell.displayName} · {md(cell.date)}({cell.dow}) 추천</h2>
      {r === undefined ? <Loading /> : !r ? <Empty>추천을 불러오지 못했어요</Empty> : (
        <>
          <div className="ai">
            <div className="ah"><Spk /><span className="mod m1">편성 추천 AI</span><Mod n={4} /></div>
            <div className="small"><b>{r.slot.label}</b></div>
            <div className="small" style={{ marginTop: 4 }}>{r.message}</div>
            <RuleNote source={r.source} />
          </div>
          {r.candidates.map((c) => <Cand key={c.volunteerId} c={c} />)}
          {r.alternatives.length > 0 && <div className="sec-t">다른 요일이면 가능해요</div>}
          {r.alternatives.map((a) => (
            <div key={a.date}>
              <div className="small b" style={{ marginTop: 8 }}>{a.label}({a.dow}) · {a.count}명</div>
              {a.candidates.map((c) => <Cand key={c.volunteerId} c={c} date={a.date} />)}
            </div>
          ))}
          <button className="btn" style={{ marginTop: 12 }} onClick={() => onPost(r.postDraft)}>이 칸에 공고 올리기</button>
          <button className="btn ghost sm" style={{ marginTop: 8 }} onClick={() => { const sl = r.slot || {}; onClose(); nav(`/recurring/new?elder=${cell.elderId}&start=${sl.date || cell.date}${sl.startHour != null ? `&s=${sl.startHour}&h=${sl.hours || 1}` : ''}${sl.category ? `&cat=${encodeURIComponent(sl.category)}` : ''}`); }}>매주 이 요일이면 정기 요청으로 등록 ›</button>
          <div className="mute2" style={{ marginTop: 8 }}>{r.notice}</div>
        </>
      )}
    </>
  );
}

/** 고른 빈 칸들에 대리 공고를 한꺼번에(기능 O-13). 칸마다 도움 종류·시간을 정하고, 실패한 칸은 이유를 보여 준다. */
function BulkSheet({ cells, onDone }) {
  const { toast } = useUi();
  const [rows, setRows] = useState(cells.map((c) => ({ ...c, category: c.category || '', startHour: c.startHour ?? 10, hours: c.hours ?? 1 })));
  const [detail, setDetail] = useState('');
  const [res, setRes] = useState(null);
  const [busy, run] = useBusy();
  const set = (i, k, v) => setRows((rs) => rs.map((x, j) => (j === i ? { ...x, [k]: v } : x)));
  const submit = () => run(async () => {
    if (rows.some((x) => !x.category)) return toast('칸마다 도움 종류를 골라 주세요');
    const r = await post('/org/schedule/bulk-posts', { items: rows.map((x) => ({ elderId: x.elderId, date: x.date, startHour: Number(x.startHour), endHour: Number(x.startHour) + Number(x.hours), category: x.category, detail: detail.trim() || undefined })) });
    setRes(r);
    toast(r.failedCount ? `${r.createdCount}칸 올리고 ${r.failedCount}칸은 못 올렸어요` : `공고 ${r.createdCount}건을 올렸어요`);
  });
  if (res) {
    return (
      <>
        <h2>공고 {res.createdCount}건을 올렸어요</h2>
        {res.created.map((x) => <div key={x.index} className="sch-row"><div className="between"><span className="b">{x.displayName} · {md(x.date)}</span><span className="st ok">올림</span></div><div className="mute2">{ap(x.startHour)} · {SHORT[x.category] || x.category}</div></div>)}
        {res.failed.length > 0 && <div className="sec-t">못 올린 칸 {res.failed.length}</div>}
        {res.failed.map((x) => <div key={x.index} className="sch-row"><div className="between"><span className="b">{x.displayName || '대상'} · {x.date ? md(x.date) : ''}</span><span className="st no">실패</span></div><div className="small warnc" style={{ marginTop: 4 }}>{x.reason}</div></div>)}
        <button className="btn" style={{ marginTop: 12 }} onClick={onDone}>편성표로 돌아가기</button>
      </>
    );
  }
  return (
    <>
      <h2>공고 한꺼번에 올리기</h2>
      <p className="mute2" style={{ margin: 0 }}>고른 빈 칸 {rows.length}곳에 대신 공고를 올려요. 대상 시간은 차감되지 않아요.</p>
      {rows.map((x, i) => (
        <div key={keyOf(x.elderId, x.date)} className="sch-row">
          <div className="b">{x.displayName} · {md(x.date)}({x.dow})</div>
          <div className="row">
            <select className="in grow" aria-label="도움 종류" value={x.category} onChange={(e) => set(i, 'category', e.target.value)}><option value="">도움 종류</option>{CATS.map((c) => <option key={c.k} value={c.k}>{SHORT[c.k] || c.k}</option>)}</select>
            <select className="in grow" aria-label="시작 시각" value={x.startHour} onChange={(e) => set(i, 'startHour', +e.target.value)}>{HOURS.map((h) => <option key={h} value={h}>{hh(h)}</option>)}</select>
            <select className="in grow" aria-label="활동 시간" value={x.hours} onChange={(e) => set(i, 'hours', +e.target.value)}>{DUR.map((h) => <option key={h} value={h}>{h}시간</option>)}</select>
          </div>
        </div>
      ))}
      <label className="f">공통 내용 (선택)</label>
      <textarea className="in" style={{ minHeight: 70 }} maxLength={300} value={detail} onChange={(e) => setDetail(e.target.value)} placeholder="예) 정기 안부 방문이에요. 이야기 나눠 주세요 (연락처·주소는 적지 않아요)" />
      <div className="notice" style={{ marginTop: 10 }}>칸마다 금지 요청 판정을 거쳐요. 올릴 수 없는 칸은 이유를 알려 드려요.</div>
      <button className="btn big-cta" style={{ marginTop: 12 }} disabled={busy} onClick={submit}>{busy ? '올리는 중…' : `${rows.length}칸에 공고 올리기`}</button>
    </>
  );
}

export function OSchedule() {
  const nav = useNavigate();
  const { sheet, closeSheet } = useUi();
  const [sp, setSp] = useSearchParams();
  const week = sp.get('week') || '';
  const elderId = sp.get('elder') || '';
  const q = new URLSearchParams();
  if (week) q.set('week', week);
  if (elderId) q.set('elderId', elderId);
  const [data, reload] = useLoad(() => api(`/org/schedule${q.toString() ? `?${q}` : ''}`), [week, elderId]);
  const [sel, setSel] = useState({});
  const go = (o) => { const n = {}; const w = o.week !== undefined ? o.week : week; const e = o.elder !== undefined ? o.elder : elderId; if (w) n.week = w; if (e) n.elder = e; setSel({}); setSp(n, { replace: true }); };
  const cellInfo = (row, c) => {
    const d = (data.days || []).find((x) => x.date === c.date) || {};
    const g = c.gap || {};
    return { elderId: row.elderId, displayName: row.displayName, date: c.date, dow: d.dow, category: g.category || (row.needs || [])[0] || '', startHour: g.startHour, hours: g.hours };
  };
  const toggle = (row, c) => setSel((s) => { const k = keyOf(row.elderId, c.date); const n = { ...s }; if (n[k]) delete n[k]; else n[k] = cellInfo(row, c); return n; });
  const picked = Object.values(sel).sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : a.elderId - b.elderId));
  const openBulk = (cells) => sheet(() => <BulkSheet cells={cells} onDone={() => { closeSheet(); setSel({}); reload(); }} />);
  const openSuggest = (cell) => sheet(() => <SuggestSheet cell={cell} onClose={closeSheet} onPost={(d) => openBulk([{ ...cell, category: d.category, startHour: d.startHour, hours: d.endHour - d.startHour }])} />);
  const gaps = [];
  ((data && data.elders) || []).forEach((row) => row.cells.forEach((c) => { if (c.gap) gaps.push({ row, c }); }));
  const cnt = (data && data.counts) || {};
  return (
    <Screen title="돌봄 편성표" back={false}>
      {data === undefined ? <Loading /> : !data ? <Empty>편성표를 불러오지 못했어요</Empty> : (
        <>
          <div className="sch-nav">
            <button onClick={() => go({ week: data.prevWeek })} aria-label="이전 주">‹ 이전 주</button>
            <b>{md(data.weekStart)} ~ {md(data.weekEnd)}{data.thisWeek ? ' · 이번 주' : ''}</b>
            <button onClick={() => go({ week: data.nextWeek })} aria-label="다음 주">다음 주 ›</button>
          </div>
          <div className="chips" role="tablist" aria-label="대상 필터" style={{ flexWrap: 'wrap', marginBottom: 8 }}>
            <button role="tab" aria-selected={!elderId} className={'chip ' + (!elderId ? 'on' : '')} onClick={() => go({ elder: '' })}>전체</button>
            {(data.filter || []).map((e) => <button key={e.elderId} role="tab" aria-selected={String(e.elderId) === elderId} className={'chip ' + (String(e.elderId) === elderId ? 'on' : '')} onClick={() => go({ elder: String(e.elderId) })}>{e.displayName}</button>)}
            {!data.thisWeek && <button className="chip" onClick={() => go({ week: '' })}>이번 주로</button>}
          </div>
          {elderId && <div className="mute2" style={{ marginBottom: 8 }}>매주 같은 요일에 필요한 도움은 → <button type="button" className="linkbtn" onClick={() => nav(`/recurring?elder=${elderId}`)}>이 대상의 정기 요청 관리</button></div>}
          {!data.elders.length ? <Empty>담당 대상이 없어요</Empty> : (
            <div className="sch-wrap">
              <table className="sch" aria-label="주간 돌봄 편성표">
                <thead><tr><th className="who" scope="col">대상</th>{data.days.map((d) => <th key={d.date} scope="col" className={d.today ? 'td-on' : ''}>{d.dow}<small>{d.label}</small></th>)}</tr></thead>
                <tbody>
                  {data.elders.map((row) => (
                    <tr key={row.elderId}>
                      <th className="who" scope="row"><a role="button" tabIndex={0} onClick={() => nav(`/o/elders/${row.elderId}`)}>{row.displayName}</a><small>{row.dong}</small></th>
                      {row.cells.map((c) => {
                        const on = !!sel[keyOf(row.elderId, c.date)];
                        if (c.empty) {
                          if (!c.selectable) return <td key={c.date}><div className="sch-c past" aria-label={`${md(c.date)} 일정 없음`}>-</div></td>;
                          return <td key={c.date}><button type="button" className={'sch-c empty ' + (on ? 'sel' : c.gap ? 'gap' : '')} aria-pressed={on} aria-label={`${row.displayName} ${md(c.date)} 빈 칸${c.gap ? ' · 빈 일정' : ''}${on ? ' · 고름' : ''}`} onClick={() => toggle(row, c)}>{on ? '✓' : c.gap ? <>빈 일정<span>{SHORT[c.gap.category] || c.gap.category}</span></> : '+'}</button></td>;
                        }
                        return (
                          <td key={c.date}><div className={'sch-c ' + (c.past ? 'past' : '')} style={{ background: 'transparent', justifyContent: 'flex-start' }}>
                            {c.items.map((it) => <button key={it.kind + (it.requestId || it.postId)} type="button" className={`sch-i ${it.state}`} aria-label={`${row.displayName} ${md(c.date)} ${STATE[it.state]} ${hh(it.startHour)} ${it.category || ''}`} onClick={() => nav(it.kind === 'POST' ? `/post/${it.postId}` : `/o/reqs/${it.requestId}`)}>{STATE[it.state]}<em>{hh(it.startHour)}</em></button>)}
                          </div></td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="sch-lg" aria-label="표시 설명">
            <span><i style={{ background: '#EAF0FF' }} />확정 {cnt.fixed ?? 0}</span><span><i style={{ background: '#E6F7EE' }} />진행 중</span><span><i style={{ background: '#FFF4E5' }} />응답 대기 {cnt.pending ?? 0}</span>
            <span><i style={{ background: '#fff', boxShadow: 'inset 0 0 0 1.5px #5B8DEF' }} />열린 공고 {cnt.open ?? 0}</span><span><i style={{ background: '#F1F2F5' }} />완료</span>
            <span><i style={{ background: '#fff', border: '1.5px dashed #D5DAE2' }} />빈 칸</span><span><i style={{ background: '#FFF4E5', border: '1.5px dashed #D9731A' }} />빈 일정 {cnt.gaps ?? 0}</span>
          </div>
          <div className="mute2" style={{ marginTop: 8 }}>빈 칸(+)을 눌러 고르면 추천을 보거나 공고를 한꺼번에 올릴 수 있어요. 일정을 누르면 그 요청·공고로 가요.</div>
          {picked.length > 0 && (
            <div className="sch-bar" role="region" aria-label="고른 빈 칸">
              <div className="small b">빈 칸 {picked.length}곳을 골랐어요</div>
              <div className="btns" style={{ marginTop: 8 }}>
                {picked.length === 1 && <button className="btn soft sm" onClick={() => openSuggest(picked[0])}>✨ 봉사자 추천</button>}
                <button className="btn sm" onClick={() => openBulk(picked)}>공고 한꺼번에 올리기</button>
                <button className="btn ghost sm" style={{ flex: '0 0 auto', width: 'auto', padding: '0 12px' }} onClick={() => setSel({})}>취소</button>
              </div>
            </div>
          )}
          <div className="sec-t"><span>빈 일정 <span style={{ color: '#D9731A' }}>{gaps.length}</span></span><span className="mute2" style={{ fontWeight: 500 }}>지난 4주 방문 흐름 기준</span></div>
          {gaps.length ? gaps.map(({ row, c }) => (
            <div key={keyOf(row.elderId, c.date)} className="card" style={{ marginTop: 8 }}>
              <div className="between"><span className="b">{row.displayName} · {md(c.date)}({(data.days.find((d) => d.date === c.date) || {}).dow})</span><span className="st wait">빈 일정</span></div>
              <div className="small" style={{ marginTop: 4 }}>{c.gap.reason}</div>
              <div className="btns" style={{ marginTop: 8 }}><button className="btn soft sm" onClick={() => openSuggest(cellInfo(row, c))}>✨ 봉사자 추천 보기</button><button className="btn ghost sm" onClick={() => openBulk([cellInfo(row, c)])}>공고 올리기</button></div>
            </div>
          )) : <Empty>이번 주에 비어 보이는 일정이 없어요</Empty>}
          <div className="notice" style={{ marginTop: 14 }}>추천은 후보만 보여 줘요. 요청과 공고는 담당자가 눌러야 보내져요.</div>
        </>
      )}
    </Screen>
  );
}
