import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { MySuggestions } from '../ui/suggest.jsx';
import { Ic, Spk, Mod, Mi, Loading, Empty, RuleNote, ChipsPick, Upload, useLoad, useBusy, Avatar } from '../ui/bits.jsx';
import { useAuth, useUi, getBig, applyBig, saveBigToServer } from '../ui/ctx.jsx';
import { api, post, put, del } from '../api.js';
import { CATS, SHORT, AREAS, AGE_GROUPS, TIME_SLOTS, ST, fmt, arr, dshort, dlabel, todayISO, addDays } from '../lib/format.js';
import { speak } from './voice.jsx';
import { SttServerToggle } from '../ui/VoiceInput.jsx';

/** PUT /api/me 는 전체 값을 보낸다(일부만 보내 지워지지 않게) */
export async function saveMe(me, changes) {
  const body = {
    name: me.name, phone: me.phone, area: me.area, ageGroup: me.ageGroup, intro: me.intro, experience: me.experience, personality: me.personality,
    categories: arr(me.categories), timeSlots: arr(me.timeSlots), photoUrl: me.photoUrl, textSize: me.textSize, voiceAlert: !!me.voiceAlert, ...changes,
  };
  return put('/me', body);
}

/* 큰 글씨 모드 (B-55): 화면 전체가 커지고, 서버에 저장해 다음 접속 때도 유지한다. 사용자·담당자 공용 */
export function TextSizeRow() {
  const { toast } = useUi();
  const { refresh } = useAuth();
  const [big, setBig] = useState(getBig());
  const tg = async () => { const v = !big; setBig(v); applyBig(v); toast(v ? '큰 글씨 모드를 켰어요' : '큰 글씨 모드를 껐어요'); await saveBigToServer(v); refresh(); };
  return (
    <div role="switch" aria-checked={big} tabIndex={0} onClick={tg} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); tg(); } }}>
      <Ic n="gear" s={20} /><div className="grow">큰 글씨 모드<span>{big ? '켜짐 · 글자를 크게 봐요' : '꺼짐'}</span></div><span className={'toggle ' + (big ? 'on' : '')} aria-hidden="true" />
    </div>
  );
}

/* 위치 정보 (선택) 시트 — 가입 때 선택한 동의를 여기서 바꾼다 (A-01, B-59 / 프로토타입 locSheet) */
function LocSheet({ me, refresh, onClose }) { // 시트는 로그인 정보(AuthProvider) 밖에서 그려지므로 me·refresh 를 받아 쓴다
  const { toast } = useUi();
  const [on, setOn] = useState(!!(me && me.locConsent));
  const tg = async () => {
    const v = !on; setOn(v);
    try { await put('/me', { locConsent: v }, { quiet: true }); await refresh(); toast(v ? '위치 정보 사용에 동의했어요' : '위치 정보 사용 동의를 껐어요'); } catch { setOn(!v); toast('저장하지 못했어요'); }
  };
  return (
    <>
      <h2>위치 정보 (선택)</h2>
      <p className="mute2" style={{ margin: '0 0 10px' }}>켜면 공고와 도움을 가까운 순으로 안내해요. 가입할 때 선택한 동의를 여기서 바꿀 수 있어요.</p>
      <div className="ml"><div role="switch" aria-checked={on} tabIndex={0} onClick={tg} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); tg(); } }}><div className="grow">위치 정보 사용<span>{on ? '동의함' : '동의 안 함'}</span></div><span className={'toggle ' + (on ? 'on' : '')} aria-hidden="true" /></div></div>
      <div className="notice" style={{ marginTop: 12 }}>켜면 브라우저가 위치 권한을 물어봐요. <b>'허용'</b>을 눌러야 가까운 순으로 보여요. 허용하지 않아도 사는 동네 기준으로 쓸 수 있어요.</div>
      <button className="btn ghost" style={{ marginTop: 12 }} onClick={onClose}>닫기</button>
    </>
  );
}

/* 단골 관리 시트 (B-42, B-65 / 프로토타입 regMgr): 내가 등록한 단골 · 나를 단골로 등록한 사람(누구를 몇 회) · 양쪽 해지 · 직접 등록 */
function RegularSheet({ refresh, onClose }) {
  const { toast } = useUi();
  const [mine, reloadMine] = useLoad(() => api('/regulars', { quiet: true }).catch(() => []), []);
  const [badges, reloadBadges] = useLoad(() => api('/me/regular-badges', { quiet: true }).catch(() => []), []);
  const [done] = useLoad(() => api('/requests?box=done', { quiet: true }).catch(() => []), []);
  const [pick, setPick] = useState(false);
  const [busy, run] = useBusy();
  const regIds = (mine || []).map((v) => v.id);
  // 함께한 분: 나를 도와준 적 있는 봉사자 가운데 아직 단골이 아닌 분
  const cand = [];
  (done || []).forEach((r) => { const v = r.volunteer; if (r.myRole === 'RECIPIENT' && !r.elder && v && !regIds.includes(v.id) && !cand.some((c) => c.id === v.id)) cand.push(v); });
  if (pick) {
    return (
      <>
        <h2>단골로 등록</h2>
        <p className="mute2" style={{ margin: '0 0 10px' }}>함께한 분을 즐겨찾기처럼 단골로 등록해요</p>
        {cand.length ? cand.slice(0, 8).map((v) => (
          <div key={v.id} className="card item click" style={{ marginTop: 8 }} role="button" tabIndex={0} onClick={() => run(async () => { await post(`/regulars/${v.id}`); toast(`${v.name} 님을 단골로 등록했어요`); await reloadMine(); setPick(false); })}>
            <Avatar name={v.name} s={40} /><div className="grow"><div className="b">{v.name}</div><div className="mute2">{v.experience || v.intro || '함께 활동한 분'}</div></div><span className="st go">등록</span>
          </div>
        )) : <Empty>함께한 분 가운데 등록할 분이 없어요. 공고나 프로필에서도 ☆ 단골로 등록을 누를 수 있어요</Empty>}
        <button className="btn ghost" style={{ marginTop: 12 }} onClick={() => setPick(false)}>뒤로</button>
      </>
    );
  }
  return (
    <>
      <h2>단골 관리</h2>
      <p className="mute2" style={{ margin: '0 0 6px' }}>3번 이상 만나면 자동으로 단골이 되고, 직접 등록할 수도 있어요. 해지는 등록한 쪽도, 등록된 쪽도 할 수 있어요.</p>
      <div className="sec-t" style={{ marginTop: 6 }}>내가 등록한 단골</div>
      {mine === undefined ? <Loading /> : (mine || []).length ? mine.map((v) => (
        <div key={v.id} className="card item" style={{ marginTop: 8 }}>
          <Avatar name={v.name} s={40} /><div className="grow"><div className="b">{v.name} <span className="badge">★ 단골</span></div><div className="mute2">{v.auto ? `자동 · ${v.doneCount}회` : '직접 등록'}</div></div>
          <button className="btn ghost sm" style={{ width: 'auto', padding: '0 12px' }} disabled={busy} onClick={() => run(async () => { await del(`/regulars/${v.id}`); toast(`${v.name} 님과 단골을 해지했어요`); await reloadMine(); })}>해지</button>
        </div>
      )) : <div className="empty" style={{ padding: '12px 0' }}>아직 없어요</div>}
      <div className="sec-t">나를 단골로 등록한 사람</div>
      {badges === undefined ? <Loading /> : (badges || []).length ? badges.map((b) => (
        <div key={b.key} className="card item" style={{ marginTop: 8 }}>
          <Avatar name={b.name} s={40} elder={b.kind === 'ELDER'} /><div className="grow"><div className="b">{b.name}</div><div className="mute2">{b.count}회 만남{b.manual && !b.auto ? ' · 직접 등록' : ''}</div></div>
          {b.kind === 'USER' && <button className="btn ghost sm" style={{ width: 'auto', padding: '0 12px' }} disabled={busy} onClick={() => run(async () => { await del(`/me/regular-badges/${b.userId}`); toast('단골 관계를 해지했어요'); await reloadBadges(); refresh(); })}>해지</button>}
        </div>
      )) : <div className="empty" style={{ padding: '12px 0' }}>아직 없어요</div>}
      <button className="btn soft" style={{ marginTop: 12 }} onClick={() => setPick(true)}>+ 단골 직접 등록</button>
      <button className="btn ghost" style={{ marginTop: 8 }} onClick={onClose}>닫기</button>
    </>
  );
}

export function useLogout() {
  const { logout } = useAuth();
  const nav = useNavigate();
  const { toast } = useUi();
  return async () => { await logout(); nav('/login', { replace: true }); toast('로그아웃했어요'); };
}

/* ---------- 마이 (B-43~47) ---------- */
export function My() {
  const nav = useNavigate();
  const { me, refresh } = useAuth();
  const logout = useLogout();
  const [bank] = useLoad(() => api('/bank', { quiet: true }), []);
  const [done] = useLoad(() => api('/requests?box=done', { quiet: true }), []);
  const [fam] = useLoad(() => api('/family', { quiet: true }), []);
  const [posts] = useLoad(() => api('/posts/mine', { quiet: true }), []);
  const [alerts] = useLoad(() => api('/interest-alerts', { quiet: true }), []);
  const { sheet, closeSheet } = useUi();
  if (!me) return <Screen title="마이" back={false}><Loading /></Screen>;
  const v = me.verify || { idStatus: me.idStatus, crimeStatus: me.crimeStatus };
  const d = (done || []).filter((r) => r.status === 'DONE');
  const linked = (fam || []).filter((f) => f.status === 'ACCEPTED');
  const fam0 = linked[0];
  const last = bank && bank.entries && bank.entries[0];
  const badges = me.regularBadges || [];
  const al = (alerts || [])[0];
  return (
    <Screen title="마이" back={false} tab="/my">
      <div className="row" style={{ gap: 14 }}>
        <div className="avatar lg">{me.name[0]}</div>
        <div className="grow">
          <div className="b" style={{ fontSize: 20 }}>{me.name}</div><div className="mute2">{me.ageGroup} · {me.area}</div>
          <div className="tags" style={{ marginTop: 6 }}>
            {v.idStatus === 'APPROVED' && <span className="badge trust">✓ 신원 인증</span>}
            {v.crimeStatus === 'APPROVED' ? <span className="badge trust">✓ 범죄경력</span> : <span className="badge" style={{ background: '#F1F2F5', color: '#5F6672' }}>{v.crimeStatus === 'PENDING' ? '범죄경력 확인 중' : '범죄경력 미제출'}</span>}
          </div>
        </div>
      </div>
      <div className="kpis" style={{ marginTop: 16 }}><div><b>{d.filter((r) => r.myRole === 'VOLUNTEER').length}회</b><span>봉사</span></div><div><b>{d.filter((r) => r.myRole === 'RECIPIENT').length}회</b><span>도움 받음</span></div><div><b>{me.ratingAvg ? Number(me.ratingAvg).toFixed(1) : '-'}</b><span>받은 평가</span></div></div>
      <div className="card item click" style={{ marginTop: 10 }} role="button" tabIndex={0} onClick={() => nav('/bank', { replace: true })}><div className="ic"><Ic n="wallet" s={20} /></div><div className="grow"><div className="b">시간 통장 {fmt(me.balance)}시간{me.accountNo ? <span className="mute2" style={{ fontWeight: 500 }}> · {me.accountNo}</span> : null}</div><div className="mute2">{last ? `최근: ${last.label} ${last.amount > 0 ? '+' : '−'}${fmt(Math.abs(last.amount))}` : '아직 내역이 없어요'}</div></div>›</div>
      <div className="sec-t">활동</div>
      <div className="ml">
        <Mi t="프로필 관리" s="지역·시간대·가능한 도움·경험" i="user" onClick={() => nav('/my/profile')} />
        <Mi t="봉사 신원 인증" s="신분증 · 범죄경력 회보서(선택)" i="shield" onClick={() => nav('/my/verify')} />
        <Mi t="내 공고" s={`올린 공고 ${(posts || []).length}개`} i="list" onClick={() => nav('/act?seg=posts')} />
        <Mi t="내 활동" s="도움 받은 활동 · 봉사한 활동 · 월별 횟수" i="cal" onClick={() => nav('/act')} />
        <Mi t="단골 관리" s={badges.length ? `받은 단골 배지 ${badges.length}개 · 등록·해지` : '내 단골 · 받은 단골 배지 · 등록·해지'} i="star" onClick={() => sheet(<RegularSheet refresh={refresh} onClose={closeSheet} />)} />
        <Mi t="봉사 확인서 발급" s="기간을 골라 PDF로 받아요" i="file" onClick={() => nav('/my/cert')} />
        <Mi t="AI 분석" s="내 활동·평가 습관 분석 · 봉사 플랜" i="chart" onClick={() => nav('/my/insight')} />
        <Mi t="시간 플래너" s="생애 플랜 · 캘린더 · 추천 봉사" i="cal" onClick={() => nav('/planner', { replace: true })} />
        <Mi t="첫 활동 연습" s="AI 역할극 · 선택이라 안 해도 봉사할 수 있어요" i="cap" onClick={() => nav('/edu')} />
      </div>
      <div className="sec-t">가족</div>
      <div className="ml">
        <Mi t="내가 받은 도움 기록" s="건별 메모 · 병원에서 들은 말" i="list" onClick={() => nav('/my/report')} />
        <Mi t="가족 연결" s={fam0 ? `${fam0.relation || ''} ${fam0.user.name} · 연결됨${linked.length > 1 ? ` 외 ${linked.length - 1}명` : ''}` : '연결된 가족이 없어요'} i="users" onClick={() => nav('/my/family')} />
        {fam0 && <Mi t="가족 대신 봉사 요청" s={`${fam0.user.name} 님 이름으로 봉사 요청`} i="chat" onClick={() => nav(`/write/need?family=${encodeURIComponent(fam0.user.name)}`)} />}
        {fam0 && <Mi t="가족 최근 기록" s={`${fam0.user.name} 님 최근 활동·메모 (공유 동의한 경우)`} i="chart" onClick={() => nav(`/my/family/${fam0.user.id}/report`)} />}
        <Mi t="내 리포트 공유 동의" s="가족 연결 화면에서 가족마다 켜고 꺼요" i="heart" onClick={() => nav('/my/family')} />
      </div>
      <div className="sec-t">알림·도움</div>
      <div className="ml">
        <Mi t="관심 공고 알림" s={al ? `${al.area || '전체'} · ${arr(al.categories).join(', ') || '전체'} · ${al.dayType || '전체'}${al.enabled === false ? ' (꺼짐)' : ''}` : '조건을 저장해 두면 알려드려요'} i="bell" onClick={() => nav('/my/alerts')} />
        <Mi t="알림 설정" s={me.voiceAlert ? '알림 종류 · AI 음성 안내 켜짐' : '알림 종류별 켜고 끄기'} i="gear" onClick={() => nav('/my/noti')} />
        <Mi t="1:1 문의 · 신고 내역" s="문의·신고 처리 상태" i="msg" onClick={() => nav('/qna')} />
        <Mi t="공지사항" i="file" onClick={() => nav('/notice')} />
        <Mi t="공공 서비스 안내" s="AI가 맞는 서비스를 찾아줘요" i="building" onClick={() => nav('/public')} />
        <Mi t="동네 기관 소식" s="근처 기관이 여는 교실·모임·행사" i="cal" onClick={() => nav('/news')} />
        <Mi t="카테고리 건의" s="6가지 종류에 없는 도움을 운영자에게 건의" i="flag" onClick={() => nav('/my/suggest')} />
      </div>
      <div className="sec-t">설정</div>
      <div className="ml">
        <Mi t="위치 정보" s={`가까운 순 안내 · 선택 동의${me.locConsent ? ' · 동의함' : ''}`} i="pin" onClick={() => sheet(<LocSheet me={me} refresh={refresh} onClose={closeSheet} />)} />
        <TextSizeRow />
      </div>
      <div className="sec-t">계정</div>
      <div className="ml">
        <Mi t="내 정보 수정" s="이름·연락처·비밀번호" i="pen" onClick={() => nav('/my/info')} />
        <Mi t="로그아웃" i="logout" onClick={logout} />
        <div role="button" tabIndex={0} onClick={() => nav('/my/withdraw')}><span className="danger" style={{ paddingLeft: 32 }}>회원 탈퇴</span></div>
      </div>
    </Screen>
  );
}

/* ---------- 프로필 관리 (B-43) ---------- */
export function ProfileEdit() {
  const nav = useNavigate();
  const { me, refresh } = useAuth();
  const { toast } = useUi();
  const [f, setF] = useState(() => (me ? { intro: me.intro || '', area: me.area, categories: arr(me.categories), timeSlots: arr(me.timeSlots), experience: me.experience || '', personality: me.personality || '', ageGroup: me.ageGroup, photoUrl: me.photoUrl } : null));
  const [busy, run] = useBusy();
  if (!me || !f) return <Screen title="프로필 관리" noTabs><Loading /></Screen>;
  const set = (k) => (v) => setF({ ...f, [k]: v && v.target ? v.target.value : v });
  return (
    <Screen title="프로필 관리" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>공고와 소개 카드에 보여요. 매칭은 자동이 아니라 상대가 보고 골라요.</p>
      <label className="f">사진 (선택)</label>
      <div className="row" style={{ gap: 12 }}>{f.photoUrl ? <img src={f.photoUrl} alt="" style={{ width: 64, height: 64, borderRadius: '50%', objectFit: 'cover' }} /> : <Avatar name={me.name} s={64} />}<div className="grow"><Upload label="사진 올리기" accept="image/*" style={{ minHeight: 64 }} onUploaded={set('photoUrl')} /></div></div>
      <label className="f">한 줄 소개</label><input className="in" value={f.intro} onChange={set('intro')} />
      <label className="f">활동 지역</label><ChipsPick options={AREAS} value={f.area} onChange={set('area')} />
      <label className="f">나이대</label><ChipsPick options={AGE_GROUPS} value={f.ageGroup} onChange={set('ageGroup')} />
      <label className="f">가능한 시간대</label><ChipsPick multi options={TIME_SLOTS} value={f.timeSlots} onChange={set('timeSlots')} />
      <label className="f">할 수 있는 도움</label><ChipsPick multi options={CATS.map((c) => c.k)} value={f.categories} onChange={set('categories')} />
      <label className="f">경험</label><textarea className="in" style={{ minHeight: 80 }} value={f.experience} onChange={set('experience')} placeholder="예) 카페 아르바이트 3년, 부모님 폰 설정 담당" />
      <label className="f">성향</label><ChipsPick options={['차분해요', '말이 많아요', '조용해요', '활발해요']} value={f.personality} onChange={set('personality')} />
      <button className="btn big-cta" style={{ marginTop: 18 }} disabled={busy} onClick={() => run(async () => { await saveMe(me, f); await refresh(); nav(-1); toast('저장했어요'); })}>저장</button>
    </Screen>
  );
}

/* ---------- 봉사 신원 인증 (B-01) ---------- */
const VS = { NONE: null, PENDING: ['wait', '확인 중'], APPROVED: ['ok', '완료'], REJECTED: ['no', '반려'] };
export function Verify() {
  const nav = useNavigate();
  const { me, refresh } = useAuth();
  const { toast } = useUi();
  if (!me) return <Screen title="봉사 신원 인증" noTabs><Loading /></Screen>;
  const v = me.verify || { idStatus: me.idStatus, crimeStatus: me.crimeStatus };
  const row = (status, t, s, action) => {
    const tag = VS[status];
    const ok = status === 'APPROVED';
    return (
      <div className="card item">
        <div className="ic" style={ok ? { background: '#E6F7EE', color: '#1F9D63' } : null}><Ic n={ok ? 'check' : 'shield'} s={20} /></div>
        <div className="grow"><div className="b">{t}</div><div className="mute2">{s}</div>{action && status !== 'APPROVED' && status !== 'PENDING' && <div style={{ marginTop: 8 }}>{action}</div>}</div>
        {tag && <span className={'st ' + tag[0]}>{tag[1]}</span>}
      </div>
    );
  };
  const up = (kind) => async (url) => { try { await post(`/me/verify/${kind}`, { fileUrl: url }); await refresh(); toast(kind === 'id' ? '제출했어요. 운영자가 확인해요' : '제출했어요. 운영자 확인 후 신뢰 배지가 붙어요'); } catch { /* 토스트 */ } };
  return (
    <Screen title="봉사 신원 인증" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>봉사하려면 신분증 인증이 필요해요. 범죄경력 회보서는 선택이고, 내면 신뢰 배지가 붙어요.</p>
      {row(v.idStatus, '신분증 인증', v.idStatus === 'REJECTED' ? '반려됐어요. 다시 올려 주세요' : '운영자가 확인해요', <Upload label="신분증 사진 올리기" accept="image/*,application/pdf" style={{ minHeight: 64 }} onUploaded={up('id')} />)}
      {row(v.crimeStatus, '범죄경력 회보서 (선택)', '제출하면 ✓ 신뢰 배지', <Upload label="회보서 올리기" style={{ minHeight: 64 }} onUploaded={up('crime')} />)}
      <div className="card" style={{ marginTop: 14 }}><div className="b">방문 중 안전 규칙</div><ul className="small muted" style={{ margin: '6px 0 0', paddingLeft: 18 }}><li>신체 접촉, 신체 돌봄·의료 행위·돈 관리는 하지 않아요</li><li>방문 외 개인 연락, 연락처 교환은 금지예요</li><li>문제가 생기면 활동 상세에서 바로 신고해요</li></ul></div>
      <button className="btn ghost" style={{ marginTop: 12 }} onClick={() => nav('/edu')}>첫 활동 연습 해 보기 (선택)</button>
    </Screen>
  );
}

/* ---------- 가족 연결 (B-44, P-06) ---------- */
export function Family() {
  const nav = useNavigate();
  const { toast, confirm } = useUi();
  const [list, reload] = useLoad(() => api('/family'), []);
  const [q, setQ] = useState('');
  const [rel, setRel] = useState('엄마');
  const [busy, run] = useBusy();
  const linked = (list || []).filter((f) => f.status === 'ACCEPTED');
  return (
    <Screen title="가족 연결" noTabs>
      {list === undefined ? <Loading /> : !list || !list.length ? <Empty>아직 연결된 가족이 없어요</Empty> : list.map((f) => (
        <div className="card" key={f.id}>
          <div className="row" style={{ gap: 12 }}>
            <Avatar name={f.relation || f.user.name} />
            <div className="grow"><div className="b">{f.relation ? `${f.relation} ` : ''}{f.user.name}</div><div className="mute2">{f.status === 'ACCEPTED' ? '연결됨 · 시간 선물 · 리포트' : f.status === 'PENDING' ? (f.direction === 'in' ? '나에게 연결을 신청했어요' : '상대 승인 대기 중') : '거절됨'}</div></div>
            {f.status === 'PENDING' && f.direction === 'out' && <span className="st wait">대기</span>}
          </div>
          {f.status === 'PENDING' && f.direction === 'in' && <div className="btns" style={{ marginTop: 10 }}><button className="btn ghost sm" onClick={() => run(async () => { await post(`/family/${f.id}/answer`, { accept: false }); reload(); toast('거절했어요'); })}>거절</button><button className="btn sm" onClick={() => run(async () => { await post(`/family/${f.id}/answer`, { accept: true }); reload(); toast('가족으로 연결했어요'); })}>승인</button></div>}
          {f.status === 'ACCEPTED' && (
            <div className="ml" style={{ marginTop: 10, background: '#fff' }}>
              <div role="switch" aria-checked={!!f.shareReport} tabIndex={0} onClick={() => run(async () => { await put(`/family/${f.id}/share`, { share: !f.shareReport }); reload(); toast(!f.shareReport ? '이 가족에게 내 리포트를 공유해요' : '리포트 공유를 껐어요'); })}><div className="grow">내 생활 변화 리포트 공유<span>{f.shareReport ? '공유 중 · 언제든 철회할 수 있어요' : '공유 안 함'}</span></div><span className={'toggle ' + (f.shareReport ? 'on' : '')} /></div>
              <div role="button" tabIndex={0} onClick={() => nav(`/my/family/${f.user.id}/report`)}><div className="grow">{f.user.name} 님 리포트 보기<span>{f.theyShare === false ? '상대가 아직 공유에 동의하지 않았어요' : '상대가 공유에 동의한 경우만 보여요'}</span></div>›</div>
              <div role="button" tabIndex={0} onClick={async () => { if (await confirm('연결을 끊을까요?', '시간 선물과 리포트 공유가 멈춰요.', '연결 끊기', true)) run(async () => { await del(`/family/${f.id}`); reload(); toast('연결을 끊었어요'); }); }}><div className="grow danger">연결 끊기</div></div>
            </div>
          )}
        </div>
      ))}
      <label className="f">새 가족 연결 신청</label>
      <div className="row"><select className="in" style={{ width: 110, flex: '0 0 auto' }} value={rel} onChange={(e) => setRel(e.target.value)}>{['엄마', '아빠', '할머니', '할아버지', '자녀', '배우자', '형제자매', '가족'].map((r) => <option key={r}>{r}</option>)}</select><input className="in grow" placeholder="상대 아이디 또는 휴대폰" value={q} onChange={(e) => setQ(e.target.value)} /></div>
      <button className="btn sm" style={{ marginTop: 8 }} disabled={busy} onClick={() => run(async () => { if (!q.trim()) return toast('상대 아이디나 휴대폰 번호를 적어 주세요'); await post('/family', { loginIdOrPhone: q.trim(), relation: rel }); setQ(''); reload(); toast('연결 신청을 보냈어요. 상대가 승인하면 연결돼요'); })}>신청</button>
      {linked[0] && <button className="btn soft" style={{ marginTop: 12 }} onClick={() => nav(`/write/need?family=${encodeURIComponent(linked[0].user.name)}`)}>{linked[0].user.name} 님 대신 봉사 요청하기</button>}
      <div className="notice" style={{ marginTop: 12 }}>상대가 승인해야 연결돼요. 기관 등록 대상은 계정이 없어 연결할 수 없어요.</div>
    </Screen>
  );
}

/* ---------- 가족 생활 변화 리포트 (P-04) ---------- */
export function ReportView({ rep, who }) {
  if (!rep) return null;
  let stats = rep.stats || rep.statsJson || {};
  if (typeof stats === 'string') { try { stats = JSON.parse(stats); } catch { stats = {}; } }
  const KL = { count: '이번 달 활동', prevCount: '지난달 활동', receivedCount: '도움 받은 횟수', helpCount: '도와준 횟수', hours: '활동 시간', logCount: '일지 수', newCategory: '새로 생긴 도움' };
  const entries = Object.entries(stats).filter(([k, v]) => KL[k] && v != null && typeof v !== 'object');
  return (
    <>
      <div className="card"><div className="mute2">{rep.subjectLabel || who} · {rep.month}</div><div className="b" style={{ fontSize: 18 }}>이번 달 도움 받은 기록</div>
        {entries.length > 0 && <div className="stat2" style={{ marginTop: 10 }}>{entries.slice(0, 4).map(([k, v]) => <div key={k}><b>{String(v)}</b><span>{KL[k] || k}</span></div>)}</div>}
      </div>
      <div className={'ai ' + (rep.bigChange ? 'warn' : '')} style={{ marginTop: 10 }}>
        <div className="ah"><Spk />지난달과 달라진 점<Mod n={5} /></div>
        <div className="small">{rep.narrative || '아직 리포트 내용이 없어요.'}</div>
        <div className="mute2" style={{ marginTop: 8 }}>숫자는 시스템이 집계하고, AI는 일지 내용만 정리해요. 건강 판단은 하지 않아요.</div>
        <RuleNote source={rep.source} />
      </div>
    </>
  );
}
export function FamReport() {
  const { userId } = useParams();
  const [rep, , err] = useLoad(() => api(`/family/${userId}/report`, { quiet: true }), [userId]);
  const list = rep && Array.isArray(rep.reports) ? rep.reports : Array.isArray(rep) ? rep : [];
  const who = rep && rep.user ? `${rep.user.name} 님` : '가족';
  return (
    <Screen title="가족 생활 변화 리포트" noTabs>
      {rep === undefined ? <Loading /> : err ? <Empty>{err.status === 403 ? '가족이 리포트 공유에 동의하지 않았어요' : err.message}</Empty> : list.length ? list.map((r, i) => <div key={r.id || i} style={{ marginTop: i ? 16 : 0 }}><ReportView rep={r} who={who} /></div>) : <Empty>아직 만들어진 리포트가 없어요</Empty>}
    </Screen>
  );
}

/* ---------- 관심 공고 알림 (B-21) ---------- */
export function AlertSet() {
  const nav = useNavigate();
  const { toast } = useUi();
  const [list, reload] = useLoad(() => api('/interest-alerts'), []);
  const [edit, setEdit] = useState(null);
  const [busy, run] = useBusy();
  const start = (a) => setEdit(a ? { id: a.id, area: a.area || '', categories: arr(a.categories), dayType: a.dayType || '', dateFrom: a.dateFrom || '', dateTo: a.dateTo || '', enabled: a.enabled !== false } : { area: '행복동', categories: ['디지털 도움'], dayType: '주말', dateFrom: '', dateTo: '', enabled: true });
  if (list === undefined) return <Screen title="관심 공고 알림" noTabs><Loading /></Screen>;
  if (!edit) {
    return (
      <Screen title="관심 공고 알림" noTabs>
        <p className="su-p" style={{ marginTop: 0 }}>조건에 맞는 공고가 올라오면 알림을 보내요.</p>
        {(list || []).map((a) => (
          <div key={a.id} className="card item">
            <div className="grow" role="button" tabIndex={0} onClick={() => start(a)}><div className="b">{a.area || '모든 지역'} · {a.dayType || '모든 요일'}{a.dateFrom || a.dateTo ? ` · ${a.dateFrom ? dlabel(a.dateFrom) : '오늘'}~${a.dateTo ? dlabel(a.dateTo) : ''}` : ''}</div><div className="mute2">{arr(a.categories).join(', ') || '모든 도움'}</div></div>
            <button className={'toggle ' + (a.enabled !== false ? 'on' : '')} aria-label="알림 켜기" onClick={() => run(async () => { await put(`/interest-alerts/${a.id}`, { ...a, categories: arr(a.categories), enabled: a.enabled === false }); reload(); })} />
          </div>
        ))}
        <button className="btn soft" style={{ marginTop: 12 }} onClick={() => start(null)}>+ 조건 추가</button>
      </Screen>
    );
  }
  return (
    <Screen title="관심 공고 알림" noTabs>
      <label className="f">지역</label><ChipsPick options={['', ...AREAS]} value={edit.area} onChange={(v) => setEdit({ ...edit, area: v })} label={(x) => x || '전체'} />
      <label className="f">날짜 (선택)</label>
      <div className="row" style={{ gap: 8 }}><input className="in grow" type="date" aria-label="시작 날짜" min={todayISO()} value={edit.dateFrom || ''} onChange={(e) => setEdit({ ...edit, dateFrom: e.target.value })} /><span className="mute2">~</span><input className="in grow" type="date" aria-label="끝 날짜" min={edit.dateFrom || todayISO()} value={edit.dateTo || ''} onChange={(e) => setEdit({ ...edit, dateTo: e.target.value })} /></div>
      <div className="mute2" style={{ margin: '6px 2px' }}>비워 두면 날짜와 상관없이 알려 드려요.</div>
      <label className="f">요일</label><ChipsPick options={['상관없음', '평일', '주말']} value={edit.dayType || '상관없음'} onChange={(v) => setEdit({ ...edit, dayType: v })} />
      <label className="f">도움 종류</label><ChipsPick multi options={CATS.map((c) => c.k)} value={edit.categories} onChange={(v) => setEdit({ ...edit, categories: v })} />
      <button className="btn big-cta" style={{ marginTop: 18 }} disabled={busy} onClick={() => run(async () => {
        if (edit.dateFrom && edit.dateTo && edit.dateTo < edit.dateFrom) return toast('끝 날짜가 시작 날짜보다 빨라요');
        const body = { area: edit.area, categories: edit.categories, dayType: edit.dayType, dateFrom: edit.dateFrom || null, dateTo: edit.dateTo || null, enabled: edit.enabled };
        if (edit.id) await put(`/interest-alerts/${edit.id}`, body); else await post('/interest-alerts', body);
        toast('저장했어요'); setEdit(null); reload();
      })}>저장</button>
      {edit.id && <button className="btn ghost" style={{ marginTop: 8 }} onClick={() => run(async () => { await del(`/interest-alerts/${edit.id}`); toast('지웠어요'); setEdit(null); reload(); })}>이 조건 지우기</button>}
      <button className="au-demo" onClick={() => (list && list.length ? setEdit(null) : nav(-1))}>취소</button>
    </Screen>
  );
}

/* ---------- 알림 설정 (B-58) · 어르신 대체 알림 (B-43) — 서버에 저장하고 실제 알림에 적용. 담당자도 같은 화면 ---------- */
const NOTI_KINDS = [['req', '새 요청·제안'], ['msg', '새 메시지'], ['result', '수락·거절 결과'], ['remind', '시작 전 리마인드 (1시간 전)'], ['interest', '관심 공고']];
export function NotiSet() {
  const { me, refresh } = useAuth();
  const { toast } = useUi();
  const [st, reload] = useLoad(() => api('/me/noti-settings'), []);
  const [notis] = useLoad(() => api('/notifications', { quiet: true }), []);
  const [busy, run] = useBusy();
  // LLM 추가 A(7.12, B-43): 서버가 알림마다 만든 "전화로 읽을 문장"(voiceText, 숫자는 한글 읽기)을 미리 듣기에 쓴다
  const [vp] = useLoad(() => api('/me/voice-preview', { quiet: true }).catch(() => null), [notis && notis.length]);
  if (!me || st === undefined) return <Screen title="알림 설정" noTabs><Loading /></Screen>;
  if (!st) return <Screen title="알림 설정" noTabs><Empty>알림 설정을 불러오지 못했어요</Empty></Screen>;
  const org = me.role === 'ORG';
  const sample = (notis || [])[0];
  const text = (vp && vp.text) || (sample ? `안녕하세요, 시간 적금입니다. ${sample.title}. ${sample.body || ''}` : '안녕하세요, 시간 적금입니다. 김하늘 님이 수요일 오후 2시 병원 동행을 수락했어요. 앱에서 최종 수락을 눌러 주세요.');
  const set = (k, v, msg) => run(async () => { await put('/me/noti-settings', { [k]: v }); await reload(); if (k === 'voiceAlert' || k === 'smsAlert') await refresh(); if (msg) toast(msg); });
  const sw = (k, t, sub, msg) => (
    <div key={k} role="switch" aria-checked={!!st[k]} aria-disabled={busy} tabIndex={0} onClick={() => !busy && set(k, !st[k], msg && msg(!st[k]))} onKeyDown={(e) => { if ((e.key === 'Enter' || e.key === ' ') && !busy) { e.preventDefault(); set(k, !st[k], msg && msg(!st[k])); } }}>
      <div className="grow">{t}{sub ? <span>{sub}</span> : null}</div><span className={'toggle ' + (st[k] ? 'on' : '')} aria-hidden="true" />
    </div>
  );
  return (
    <Screen title="알림 설정" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>{org ? '담당 도움 대상과 관련된 알림을 종류별로 켜고 꺼요.' : '알림을 종류별로 켜고 꺼요.'} 꺼 둔 종류는 알림이 오지 않아요.</p>
      <div className="ml">{NOTI_KINDS.filter(([k]) => !(org && k === 'interest')).map(([k, t]) => sw(k, t))}</div>
      <div className="mute2" style={{ margin: '6px 2px' }}>설정은 계정에 저장돼서 다른 기기에서도 같아요. 일정 확정·지각·노쇼 의심·완료 확인·적립 알림은 항상 와요.</div>
      {!org && (
        <>
          <div className="sec-t">폰이 어려운 분을 위한 알림</div>
          <div className="ml">
            {sw('voiceAlert', 'AI 음성 전화 안내', '중요한 알림을 소리로 읽어 드려요 (전화 연결은 준비 중)', (v) => (v ? '중요 알림을 음성으로 읽어 드려요' : '음성 안내를 껐어요'))}
            {sw('smsAlert', '문자로도 받기', '알림을 문자로도 함께 보내요 (문자 발송은 준비 중)', (v) => (v ? '문자 병행을 켰어요' : '문자 병행을 껐어요'))}
          </div>
          <SttServerToggle />
          <button className="btn ghost" style={{ marginTop: 14 }} onClick={async () => { const ok = await speak(text, { force: true }); if (!ok) toast('이 브라우저는 소리로 읽기를 지원하지 않아요'); }}><Ic n="mic" s={20} /> AI 음성 안내 미리 듣기</button>
          <div className="ai" style={{ marginTop: 10 }}><div className="ah"><Spk />전화로 이렇게 읽어 드려요{vp && vp.title ? ` · 최근 알림 “${vp.title}”` : ''}</div><div className="voice-line">“{text}”</div><div className="mute2" style={{ marginTop: 6, fontSize: 12 }}>숫자는 소리 내 읽기 좋게 바꿔요 · 전화 연결은 아직 모의예요</div></div>
        </>
      )}
    </Screen>
  );
}

/* ---------- 봉사 확인서 (B-56): 기간 선택 · 독거노인 대상 횟수 · AI 자기소개용 요약 · 인쇄(PDF로 저장) ---------- */
const CERT_RANGES = [['all', '전체 기간'], ['m1', '최근 1개월'], ['m3', '최근 3개월'], ['m6', '최근 6개월'], ['y', '올해'], ['custom', '직접 고르기']];
function certRange(k) {
  const to = todayISO();
  if (k === 'm1') return [addDays(-30), to];
  if (k === 'm3') return [addDays(-91), to];
  if (k === 'm6') return [addDays(-182), to];
  if (k === 'y') return [`${to.slice(0, 4)}-01-01`, to];
  return ['', ''];
}
export function Cert() {
  const { me } = useAuth();
  const { toast } = useUi();
  const [kind, setKind] = useState('all');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const qs = [from && `from=${from}`, to && `to=${to}`].filter(Boolean).join('&');
  const [c] = useLoad(() => api(`/certificate${qs ? `?${qs}` : ''}`), [qs]);
  const pick = (k) => { setKind(k); if (k !== 'custom') { const [a, b] = certRange(k); setFrom(a); setTo(b); } };
  return (
    <Screen title="봉사 확인서" noTabs>
      <label className="f" style={{ marginTop: 0 }}>기간 선택</label>
      <div className="chips" role="tablist" aria-label="확인서 기간">{CERT_RANGES.map(([k, t]) => <button key={k} role="tab" aria-selected={kind === k} className={'chip ' + (kind === k ? 'on' : '')} onClick={() => pick(k)}>{t}</button>)}</div>
      {kind === 'custom' && <div className="row" style={{ gap: 8, marginTop: 8 }}><input className="in grow" type="date" aria-label="시작 날짜" value={from} max={to || todayISO()} onChange={(e) => setFrom(e.target.value)} /><span className="mute2">~</span><input className="in grow" type="date" aria-label="끝 날짜" value={to} min={from} max={todayISO()} onChange={(e) => setTo(e.target.value)} /></div>}
      {c === undefined ? <Loading /> : !c ? <Empty>확인서를 불러오지 못했어요</Empty> : (
        <>
          <div className="cert" style={{ marginTop: 12 }}>
            <div className="cert-h"><div className="lf-mark" style={{ width: 40, height: 40, padding: 8, margin: 0 }}><Ic n="logo" s={24} /></div><div><b>봉사 활동 확인서</b><div className="mute2">시간 적금 · {c.period || '활동 기록 없음'}</div></div></div>
            <dl className="kv" style={{ marginTop: 12 }}>
              <dt>이름</dt><dd>{c.name || (me && me.name)}</dd>
              <dt>통장 번호</dt><dd>{c.accountNo || '-'}</dd>
              <dt>활동 횟수</dt><dd>{c.count}회</dd>
              <dt>활동 시간</dt><dd>{fmt(c.totalHours)}시간 (예정 시간 기준)</dd>
              <dt>적립 시간</dt><dd>{fmt(c.totalEarned)}시간 (난이도 가중치 포함)</dd>
              <dt>주요 활동</dt><dd>{(c.byCategory || []).map((b) => `${SHORT[b.category] || b.category} ${b.count}`).join(' · ') || '-'}</dd>
              <dt>독거노인 대상</dt><dd>{c.elderCount || 0}회</dd>
              <dt>발급일</dt><dd>{c.issuedAt}</dd>
            </dl>
            {c.summary && (
              <div className="ai" style={{ marginTop: 12 }}>
                <div className="ah"><Spk />자기소개용 활동 요약<Mod n={3} /></div>
                <div className="small">{c.summary}</div>
                <div className="mute2" style={{ marginTop: 6 }}>횟수·시간은 시스템이 집계하고, 문장만 AI가 써요.</div>
                <RuleNote source={c.summarySource} />
              </div>
            )}
            {(c.items || []).length > 0 && <div style={{ marginTop: 12 }}>{c.items.map((it, i) => <div key={i} className="between small" style={{ padding: '6px 0', borderTop: '1px dashed var(--line)' }}><span>{dlabel(it.date)} · {it.category} · {it.recipient || it.recipientLabel || ''}</span><b>{it.hours}시간</b></div>)}</div>}
          </div>
          <div className="mute2" style={{ margin: '10px 2px' }}>고른 기간에 적립이 확정된 활동(일지 제출·확인 완료)만 들어가요. 받는 분 이름은 가려서 표시돼요.</div>
          {c.summary && <button className="btn ghost" style={{ marginBottom: 8 }} onClick={async () => { try { await navigator.clipboard.writeText(c.summary); toast('요약 문장을 복사했어요'); } catch { toast('복사하지 못했어요. 문장을 길게 눌러 복사해 주세요'); } }}>요약 문장 복사</button>}
          <button className="btn big-cta" disabled={!c.count} onClick={() => window.print()}>{c.count ? 'PDF로 받기 (인쇄 › PDF로 저장)' : '이 기간에는 확정된 활동이 없어요'}</button>
        </>
      )}
    </Screen>
  );
}

/* ---------- 내 정보 수정 · 비밀번호 변경 (A-09) ---------- */
export function InfoEdit() {
  const nav = useNavigate();
  const { me, refresh } = useAuth();
  const { toast } = useUi();
  const [name, setName] = useState(me ? me.name : '');
  const [phone, setPhone] = useState(me ? me.phone || '' : '');
  const [cur, setCur] = useState('');
  const [np, setNp] = useState('');
  const [np2, setNp2] = useState('');
  const [busy, run] = useBusy();
  if (!me) return <Screen title="내 정보 수정" noTabs><Loading /></Screen>;
  return (
    <Screen title="내 정보 수정" noTabs>
      <label className="f">아이디</label><input className="in" value={me.loginId || ''} disabled />
      <label className="f">이름</label><input className="in" value={name} onChange={(e) => setName(e.target.value)} />
      <label className="f">연락처</label><input className="in" value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" />
      <button className="btn" style={{ marginTop: 14 }} disabled={busy} onClick={() => run(async () => { if (!name.trim()) return toast('이름을 적어 주세요'); await saveMe(me, { name: name.trim(), phone: phone.trim() }); await refresh(); toast('저장했어요'); })}>기본 정보 저장</button>
      <div className="sec-t">비밀번호 변경</div>
      <input className="in" type="password" placeholder="지금 비밀번호" value={cur} onChange={(e) => setCur(e.target.value)} autoComplete="current-password" />
      <input className="in" type="password" placeholder="새 비밀번호 (4자 이상)" style={{ marginTop: 8 }} value={np} onChange={(e) => setNp(e.target.value)} autoComplete="new-password" />
      <input className="in" type="password" placeholder="새 비밀번호 확인" style={{ marginTop: 8 }} value={np2} onChange={(e) => setNp2(e.target.value)} autoComplete="new-password" />
      <button className="btn" style={{ marginTop: 12 }} disabled={busy} onClick={() => run(async () => { if (np.length < 4 || np !== np2) return toast('새 비밀번호를 확인해 주세요'); await put('/me/password', { currentPassword: cur, newPassword: np }); setCur(''); setNp(''); setNp2(''); toast('비밀번호를 바꿨어요'); nav(-1); })}>비밀번호 바꾸기</button>
    </Screen>
  );
}

/* ---------- 회원 탈퇴 (A-04) ---------- */
export function Withdraw() {
  const nav = useNavigate();
  const { logout, me } = useAuth();
  const { toast, confirm } = useUi();
  const isUser = !me || me.role === 'USER'; // 시간 통장·가족 선물은 사용자 계정에만 있다
  const [c] = useLoad(() => api('/me/withdraw-check'), []);
  const [pw, setPw] = useState('');
  const [busy, run] = useBusy();
  const active = (c && c.activeRequests) || [];
  return (
    <Screen title="회원 탈퇴" noTabs>
      {c === undefined ? <Loading /> : (
        <>
          <h2 className="su-h">탈퇴하기 전에<br />확인해 주세요</h2>
          {c && c.message && <p className="su-p">{c.message}</p>}
          <div className="sum" style={{ marginTop: 10 }}>
            <div className="between"><span className="muted">진행 중인 요청·활동</span><b className={active.length ? 'warnc' : ''}>{active.length}건</b></div>
            {isUser && <div className="between"><span className="muted">남은 시간</span><b>{fmt(c && c.balance)}시간</b></div>}
            {isUser && <div className="between"><span className="muted">보류 중</span><b>{fmt(c && c.hold)}시간</b></div>}
          </div>
          {active.length > 0 && <><div className="sec-t">먼저 정리할 요청</div>{active.map((r) => <div key={r.id} className="card item click" role="button" tabIndex={0} onClick={() => nav(me && me.role === 'ORG' ? `/o/reqs/${r.id}` : `/req/${r.id}`)}><div className="grow"><div className="b">{r.content || r.postTitle || `요청 #${r.id}`}</div><div className="mute2">{r.date ? dlabel(r.date) : ''} · {(ST[r.status] || [0, r.status])[1]}</div></div>›</div>)}</>}
          <div className="notice" style={{ marginTop: 12 }}>{isUser ? '탈퇴하면 남은 시간은 사라지고 되돌릴 수 없어요. 가족에게 먼저 선물할 수 있어요. ' : '탈퇴하면 이 계정으로 다시 로그인할 수 없어요. '}활동 기록은 법에 따라 정해진 기간만 보관해요.</div>
          {isUser && Number(c && c.balance) > 0 && <button className="btn soft" style={{ marginTop: 10 }} onClick={() => nav('/gift')}>남은 시간 가족에게 선물하기</button>}
          <label className="f">비밀번호 확인</label>
          <input className="in" type="password" value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="current-password" />
          <button className="btn warn big-cta" style={{ marginTop: 14 }} disabled={busy || active.length > 0 || (c && c.canWithdraw === false)} onClick={async () => {
            if (!pw) return toast('비밀번호를 적어 주세요');
            if (!(await confirm('정말 탈퇴할까요?', '탈퇴하면 계정과 남은 시간을 되돌릴 수 없어요.', '탈퇴하기', true))) return;
            run(async () => { await post('/me/withdraw', { password: pw }); await logout(); toast('탈퇴했어요. 그동안 고마웠어요'); nav('/login', { replace: true }); });
          }}>{active.length ? '진행 중인 요청을 먼저 정리해 주세요' : '탈퇴하기'}</button>
        </>
      )}
    </Screen>
  );
}
export const _d = dshort;

/* ---------- 카테고리 건의 (2026-11-11) — 사용자·담당자 공용 ---------- */
export function Suggest() {
  return <Screen title="카테고리 건의" noTabs><MySuggestions /></Screen>;
}
