import { useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Cats, TBar, Loading, Empty, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { CATS, AREAS, hh, fmt, dlabel, addDays, todayISO } from '../lib/format.js';
import { SuggestLink } from '../ui/suggest.jsx';

/* ---------- 정기 요청 (기능 U-09) ----------
   반복 주기(매주 요일·시간)와 기간을 정해 두면 회차마다 도움 요청 공고가 자동으로 올라간다.
   회차 공고는 7일 전부터 하나씩 올라가고, 공고 1개에는 요청 1건만 받는 규칙은 회차마다 그대로다.
   수락·거절은 회차마다 사람이 누른다. "내 정기 요청"에서 다음 회차·남은 회차를 보고 일시정지·해지한다. */
const DAYS = ['월', '화', '수', '목', '금', '토', '일']; // 1=월 … 7=일
const WEEKS = [4, 8, 12];
const R_ST = { ACTIVE: ['go', '진행 중'], PAUSED: ['wait', '일시정지'], ENDED: ['end', '기간 끝'], CANCELLED: ['end', '해지'] };
const P_ST = { OPEN: '제안 기다리는 중', MATCHED: '봉사자 정해짐', CLOSED: '마감' };
const isoDow = (iso) => { const [y, m, d] = String(iso).slice(0, 10).split('-').map(Number); const w = new Date(y, m - 1, d).getDay(); return w === 0 ? 7 : w; };

/** 첫 회차 날짜: from 이후(그날 포함) 첫 번째 그 요일 */
function firstOn(from, weekday) {
  const [y, m, d] = from.split('-').map(Number);
  const dt = new Date(y, m - 1, d);
  dt.setDate(dt.getDate() + ((weekday - isoDow(from) + 7) % 7));
  return `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`;
}
function plusWeeks(iso, n) {
  const [y, m, d] = iso.split('-').map(Number);
  const dt = new Date(y, m - 1, d + n * 7);
  return `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`;
}

/** 공고 화면·작성 화면에 두는 한 줄 */
export function RecurringLink({ elderId, style }) {
  const nav = useNavigate();
  const q = elderId ? `?elder=${elderId}` : '';
  return (
    <div className="mute2" style={{ marginTop: 8, ...style }}>매주 같은 요일에 필요하면 → <button type="button" className="linkbtn" onClick={() => nav(`/recurring/new${q}`)}>정기 요청으로 등록</button></div>
  );
}

function RuleCard({ r, reload }) {
  const nav = useNavigate();
  const { toast, confirm } = useUi();
  const [busy, run] = useBusy();
  const st = R_ST[r.status] || R_ST.ENDED;
  const live = r.status === 'ACTIVE' || r.status === 'PAUSED';
  const act = (path, msg) => run(async () => { await post(`/recurring/${r.id}/${path}`); toast(msg); reload(); });
  const pause = async () => {
    if (!(await confirm('정기 요청을 잠시 멈출까요?', '다시 시작할 때까지 새 회차 공고를 올리지 않아요. 이미 올라간 공고는 그대로 있어요.', '일시정지'))) return;
    act('pause', '일시정지했어요. 다시 시작할 때까지 공고를 올리지 않아요');
  };
  const cancel = async () => {
    if (!(await confirm('정기 요청을 해지할까요?', '더는 회차 공고를 올리지 않아요. 이미 올라간 앞으로의 공고 가운데 수락되지 않은 것은 함께 내려가고, 수락된 회차는 그대로 진행돼요.', '해지', true))) return;
    run(async () => {
      const out = await post(`/recurring/${r.id}/cancel`);
      toast(`해지했어요${out.removedPosts ? ` · 올라가 있던 공고 ${out.removedPosts}건을 내렸어요` : ''}${out.keptPosts ? ` · 수락된 ${out.keptPosts}건은 그대로 진행돼요` : ''}`);
      reload();
    });
  };
  return (
    <div className="card" style={{ marginTop: 10 }}>
      <div className="between"><span className="b" style={{ fontSize: 17 }}>{r.title}</span><span className={`st ${st[0]}`}>{st[1]}</span></div>
      <div className="mute2" style={{ marginTop: 2 }}>
        {r.elderDisplayName ? `${r.elderDisplayName} 대신 · ` : r.proxy ? (r.proxyMine ? `${r.ownerName} 님 대신 · ` : `${r.createdByName} 님이 대신 등록 · `) : ''}{r.repeatLabel} {hh(r.startHour)}~{hh(r.endHour)}{r.estHours ? ` (${fmt(r.estHours)}시간)` : ''} · {r.area}
      </div>
      <Cats list={r.categories} style={{ marginTop: 8 }} />
      <dl className="kv" style={{ marginTop: 10 }}>
        <dt>기간</dt><dd>{dlabel(r.startDate)} ~ {dlabel(r.endDate)} · 모두 {r.totalCount}회</dd>
        <dt>다음 회차</dt>
        <dd>{!live ? '없음' : !r.nextDate ? '남은 회차가 없어요' : (
          <>
            {dlabel(r.nextDate)} {hh(r.startHour)}{' '}
            {r.status === 'PAUSED' ? <span className="mute2">(멈춰 있어 공고가 올라가지 않아요)</span>
              : r.nextPostId ? <button type="button" className="linkbtn" onClick={() => nav(`/post/${r.nextPostId}`)}>공고 보기</button>
                : <span className="mute2">({dlabel(r.nextPostAt)}에 공고가 올라가요)</span>}
          </>
        )}</dd>
        <dt>남은 회차</dt><dd>{r.remainingCount}회{r.passedCount ? ` · 지난 회차 ${r.passedCount}회` : ''}</dd>
      </dl>
      {r.stopReason && <div className="notice" role="alert" style={{ marginTop: 8 }}>자동으로 멈췄어요: {r.stopReason}</div>}
      {(r.posts || []).length > 0 && (
        <details style={{ marginTop: 8 }}>
          <summary className="mute2" style={{ cursor: 'pointer' }}>올라간 회차 공고 {r.posts.length}건 보기</summary>
          {r.posts.map((p) => (
            <div key={p.id} className="between click" role="button" tabIndex={0} style={{ padding: '8px 0' }} onClick={() => nav(`/post/${p.id}`)} onKeyDown={(e) => { if (e.key === 'Enter') nav(`/post/${p.id}`); }}>
              <span>{dlabel(p.date)}</span><span className="mute2">{P_ST[p.status] || p.status}{p.requestCount ? ` · 받은 제안 ${p.requestCount}건` : ''} ›</span>
            </div>
          ))}
        </details>
      )}
      {live && (
        <div className="btns" style={{ marginTop: 12 }}>
          {r.status === 'ACTIVE'
            ? <button className="btn ghost" disabled={busy} onClick={pause}>일시정지</button>
            : <button className="btn soft" disabled={busy} onClick={() => act('resume', '다시 시작했어요. 다가오는 회차부터 공고가 올라가요')}>다시 시작</button>}
          <button className="btn ghost" disabled={busy} onClick={cancel}>해지</button>
        </div>
      )}
    </div>
  );
}

/* ---------- 내 정기 요청 ---------- */
export function RecurringList() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const elderId = sp.get('elder');
  const [list, reload] = useLoad(() => api(`/recurring${elderId ? `?elderId=${elderId}` : ''}`), [elderId]);
  const live = (list || []).filter((r) => r.status === 'ACTIVE' || r.status === 'PAUSED');
  const past = (list || []).filter((r) => !live.includes(r));
  return (
    <Screen title={elderId ? '대상 정기 요청' : '내 정기 요청'} noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>매주 같은 요일·시간에 필요한 도움을 정해 두면, 회차마다 공고가 자동으로 올라가요. 봉사 제안의 수락·거절은 회차마다 직접 해요.</p>
      <button className="btn soft" onClick={() => nav(`/recurring/new${elderId ? `?elder=${elderId}` : ''}`)}>+ 정기 요청 등록하기</button>
      {list === undefined ? <Loading /> : (
        <>
          <div className="sec-t"><span>진행 중 <span style={{ color: 'var(--brand)' }}>{live.length}</span>개</span></div>
          {live.length ? live.map((r) => <RuleCard key={r.id} r={r} reload={reload} />) : <Empty>등록한 정기 요청이 없어요</Empty>}
          {past.length > 0 && <><div className="sec-t"><span>끝난 정기 요청</span></div>{past.map((r) => <RuleCard key={r.id} r={r} reload={reload} />)}</>}
        </>
      )}
      <div className="notice" style={{ marginTop: 14 }}>회차 공고는 7일 전부터 하나씩 올라가요. 공고 1개에는 요청 1건만 받는 규칙은 회차마다 같아요.</div>
    </Screen>
  );
}

/* ---------- 정기 요청 등록 ---------- */
export function RecurringNew() {
  const nav = useNavigate();
  const loc = useLocation();
  const [sp] = useSearchParams();
  const elderId = sp.get('elder');
  const { me } = useAuth();
  const { toast } = useUi();
  const [busy, run] = useBusy();
  // 대화형·말로 요청하기 초안에서 넘어온 값(매주라고 말했을 때) 또는 지난 공고에서 넘어온 값
  const d0 = (loc.state && loc.state.draft) || {};
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  const [links] = useLoad(() => (elderId || !me || me.role !== 'USER' ? Promise.resolve([]) : api('/family', { quiet: true }).catch(() => [])), [elderId]);
  const linked = (links || []).filter((f) => f.status === 'ACCEPTED' && f.user);
  const start0 = d0.date && d0.date > todayISO() ? d0.date : addDays(1);
  const [f, setF] = useState({
    weekday: (d0.repeat && d0.repeat.weekday) || isoDow(start0), startDate: start0, weeks: 4,
    startHour: d0.startHour != null ? Number(d0.startHour) : 10, endHour: d0.endHour != null ? Number(d0.endHour) : 12,
    estHours: d0.estHours != null ? Number(d0.estHours) : null,
    area: d0.area || '', categories: d0.categories || [], title: d0.title || '', detail: d0.detail || '', note: d0.note || '',
    forUserId: (loc.state && loc.state.forUserId) || null, elderIssue: null,
  });
  if (!me || (elderId && elder === undefined)) return <Screen title="정기 요청 등록" noTabs><Loading /></Screen>;
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const area = f.area || (elder && elder.dong) || me.area || '행복동';
  const areas = AREAS.includes(area) ? AREAS : [area, ...AREAS];
  const span = f.endHour - f.startHour;
  const est = Math.min(4, f.estHours != null && f.estHours <= span ? f.estHours : span);
  const first = firstOn(f.startDate, f.weekday);
  const last = plusWeeks(first, f.weeks - 1);
  const who = elder ? `${elder.alias} 님` : null;
  const fam = linked.find((x) => x.user.id === f.forUserId);
  const issue = f.elderIssue != null ? f.elderIssue : (elder && elder.caution) || '';
  const submit = () => run(async () => {
    if (!f.categories.length) return toast('필요한 도움을 골라 주세요');
    if (!(f.title || '').trim()) return toast('제목을 적어 주세요');
    const chk = await post('/ai/filter', { text: `${f.title} ${f.detail || ''}` }, { quiet: true }).catch(() => null);
    if (chk && chk.level === 'BLOCK') return toast(chk.reason || '이 요청은 올릴 수 없어요');
    const r = await post('/recurring', {
      weekday: f.weekday, startDate: f.startDate, weeks: f.weeks, startHour: f.startHour, endHour: f.endHour, estHours: est, area,
      categories: f.categories, title: f.title.trim(), detail: f.detail || '', note: f.note || '',
      ...(elder ? { elderId: Number(elderId), elderIssue: issue } : {}), ...(fam ? { forUserId: fam.user.id } : {}),
    });
    toast(r.createdCount ? `정기 요청을 등록했어요. ${dlabel(r.nextDate)} 회차 공고가 올라갔어요` : `정기 요청을 등록했어요. 첫 공고는 ${dlabel(r.nextPostAt)}에 올라가요`);
    nav(`/recurring${elderId ? `?elder=${elderId}` : ''}`, { replace: true });
  });
  return (
    <Screen title={who ? '대신 정기 요청' : '정기 요청 등록'} noTabs>
      {who ? <div className="lock"><Ic n="users" s={18} /><div><b>{who}</b>({elder.dong}) 대신 등록해요. 대상 시간은 차감되지 않아요.</div></div>
        : <div className="mute2" style={{ margin: '-2px 0 6px' }}>매주 같은 요일·시간에 필요한 도움을 정해 두면 회차마다 공고가 자동으로 올라가요.</div>}
      {d0.repeat && <div className="notice" style={{ marginTop: 8 }}>대화에서 정리한 내용을 넣어 두었어요. 요일과 기간을 확인해 주세요.</div>}
      {!elder && linked.length > 0 && (
        <>
          <label className="f">도움 받는 사람</label>
          <div className="tags">
            <button className={'chip ' + (!fam ? 'on' : '')} onClick={() => set('forUserId', null)}>나</button>
            {linked.map((x) => <button key={x.user.id} className={'chip ' + (fam && fam.user.id === x.user.id ? 'on' : '')} onClick={() => set('forUserId', x.user.id)}>{x.relation ? `${x.relation} ` : ''}{x.user.name}</button>)}
          </div>
          {fam && <div className="mute2" style={{ marginTop: 6 }}>공고는 {fam.user.name} 님 이름으로 올라가고, 봉사 제안의 최종 수락·거부는 {fam.user.name} 님이 직접 해요.</div>}
        </>
      )}
      <label className="f">반복 요일 <span className="mute2" style={{ fontWeight: 500 }}>(매주)</span></label>
      <div className="tags" role="radiogroup" aria-label="반복 요일">{DAYS.map((d, i) => <button key={d} role="radio" aria-checked={f.weekday === i + 1} className={'chip ' + (f.weekday === i + 1 ? 'on' : '')} onClick={() => set('weekday', i + 1)}>{d}</button>)}</div>
      <label className="f">시간</label>
      <div className="row">
        <select className="in grow" aria-label="시작 시각" value={f.startHour} onChange={(e) => { const s = +e.target.value; setF((x) => ({ ...x, startHour: s, endHour: x.endHour <= s ? Math.min(20, s + 1) : x.endHour })); }}>{Array.from({ length: 24 }, (_, i) => 8 + i * 0.5).filter((h) => h < 20).map((h) => <option key={h} value={h}>{hh(h)}</option>)}</select>
        <span>~</span>
        <select className="in grow" aria-label="끝 시각" value={f.endHour} onChange={(e) => set('endHour', +e.target.value)}>{Array.from({ length: 24 }, (_, i) => 8.5 + i * 0.5).filter((h) => h <= 20 && h > f.startHour).map((h) => <option key={h} value={h}>{hh(h)}</option>)}</select>
      </div>
      <div className="card" style={{ marginTop: 10, padding: '12px 16px' }}><TBar s={f.startHour} e={f.endHour} /><div className="mute2" style={{ marginTop: 6 }}>이 시간 범위 안에서 한 번에 {fmt(est)}시간 도움을 받아요{span > 4 ? ' (한 번에 최대 4시간)' : ''}</div></div>
      <label className="f" htmlFor="rc-start">기간</label>
      <div className="row">
        <input id="rc-start" className="in grow" type="date" min={todayISO()} value={f.startDate} onChange={(e) => e.target.value && set('startDate', e.target.value)} aria-label="시작 날짜" />
        <span>부터</span>
      </div>
      <div className="tags" style={{ marginTop: 8 }} role="radiogroup" aria-label="회차 수">{WEEKS.map((w) => <button key={w} role="radio" aria-checked={f.weeks === w} className={'chip ' + (f.weeks === w ? 'on' : '')} onClick={() => set('weeks', w)}>{w}회 ({w}주)</button>)}</div>
      <div className="card" style={{ marginTop: 10, padding: '12px 16px' }}>
        <div className="b">매주 {DAYS[f.weekday - 1]}요일 {hh(f.startHour)}~{hh(f.endHour)} · 모두 {f.weeks}회</div>
        <div className="mute2" style={{ marginTop: 2 }}>첫 회차 {dlabel(first)} · 마지막 회차 {dlabel(last)}</div>
      </div>
      <label className="f">지역</label>
      <select className="in" value={area} onChange={(e) => set('area', e.target.value)}>{areas.map((a) => <option key={a}>{a}</option>)}</select>
      <label className="f">필요한 도움</label>
      <div className="tags">{CATS.map((c) => <button key={c.k} className={'chip ' + (f.categories.includes(c.k) ? 'on' : '')} onClick={() => set('categories', f.categories.includes(c.k) ? f.categories.filter((x) => x !== c.k) : [...f.categories, c.k])}>{c.k}</button>)}</div>
      <SuggestLink />
      <label className="f" htmlFor="rc-title">제목</label>
      <input id="rc-title" className="in" maxLength={100} value={f.title} onChange={(e) => set('title', e.target.value)} placeholder="예) 매주 말벗과 산책" />
      <label className="f" htmlFor="rc-detail">내용</label>
      <textarea id="rc-detail" className="in" maxLength={1000} value={f.detail} onChange={(e) => set('detail', e.target.value)} />
      <label className="f" htmlFor="rc-note">메모</label>
      <input id="rc-note" className="in" maxLength={500} value={f.note} onChange={(e) => set('note', e.target.value)} placeholder="예) 엘리베이터 있어요" />
      {elder && (
        <>
          <label className="f" htmlFor="rc-issue">관리 대상 이슈 <span className="mute2" style={{ fontWeight: 500 }}>(메이트에게 보일 주의사항)</span></label>
          <textarea id="rc-issue" className="in" style={{ minHeight: 76 }} maxLength={300} value={issue} onChange={(e) => set('elderIssue', e.target.value)} placeholder="예) 오전에는 기운이 없으셔서 천천히 걸어 주세요" />
          <div className="mute2" style={{ marginTop: 6 }}>회차 공고마다 같이 보여요. 병명·연락처·주소는 적지 않아요.</div>
        </>
      )}
      <div className="notice" style={{ marginTop: 14 }}>회차 공고는 7일 전부터 하나씩 자동으로 올라가요. 공고 1개에는 요청 1건만 받고, 수락·거절은 회차마다 직접 해요. 언제든 일시정지·해지할 수 있어요.</div>
      <button className="btn big-cta" style={{ marginTop: 14 }} disabled={busy} onClick={submit}>{busy ? '등록하는 중…' : `매주 ${DAYS[f.weekday - 1]}요일 정기 요청 등록하기`}</button>
    </Screen>
  );
}
