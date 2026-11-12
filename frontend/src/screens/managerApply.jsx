// 기능 C-04 담당자 계정 신청: 이미 승인된 기관을 골라 그 소속 담당자로 신청한다.
// 휴대폰 본인인증이 되면 자동 승인되어 담당자 계정이 바로 생긴다(기관 서비스 신청과 별도의 길).
// 자동 승인이라 재직 증빙 파일을 함께 받고, 운영자와 같은 기관의 기존 담당자에게 알림이 간다.
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Loading, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api } from '../api.js';
import { PhoneVerify } from './auth.jsx';

export function ManagerApply() {
  const nav = useNavigate();
  const { loginWithToken } = useAuth();
  const { toast, confirm } = useUi();
  const [orgs] = useLoad(() => api('/auth/manager-apply/orgs', { quiet: true }).catch(() => []), []);
  const [f, setF] = useState({ orgId: '', name: '', phone: '', loginId: '', password: '', password2: '' });
  const [vt, setVt] = useState(null);
  const [file, setFile] = useState(null);
  const [done, setDone] = useState(null);
  const [busy, run] = useBusy();
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  const org = (orgs || []).find((o) => String(o.id) === String(f.orgId));

  if (done) {
    return (
      <Screen noHeader noTabs cls="full">
        <div className="sp-in" style={{ color: 'var(--ink)' }}>
          <div className="done-ic">✓</div>
          <h2 className="su-h center" style={{ marginTop: 20 }}>담당자 계정이 만들어졌어요</h2>
          <p className="su-p center">{done.orgName} 소속 담당자로 자동 승인됐어요.<br />아이디 <b>{done.loginId}</b> 로 로그인돼 있어요.</p>
        </div>
        <div className="su-bottom stack"><button className="btn big-cta" onClick={() => nav('/o/home', { replace: true })}>담당자 화면으로 가기</button></div>
      </Screen>
    );
  }

  const submit = async () => {
    if (!f.orgId) return toast('소속 기관을 골라 주세요');
    if (!f.name.trim()) return toast('이름을 적어 주세요');
    if (!vt) return toast('휴대폰 본인인증을 해 주세요');
    if (!/^[a-zA-Z0-9]{4,20}$/.test(f.loginId)) return toast('아이디는 영문·숫자 4자 이상이에요');
    if (f.password.length < 4) return toast('비밀번호를 4자 이상 적어 주세요');
    if (f.password !== f.password2) return toast('비밀번호가 서로 달라요');
    if (!file) return toast('재직 증빙(재직증명서·사원증) 파일을 올려 주세요');
    if (!(await confirm('담당자 계정을 신청할까요?', `${org ? org.name : '고른 기관'} 소속 담당자로 신청해요. 본인 확인이 끝났으니 바로 계정이 만들어지고, 운영자와 같은 기관 담당자에게 알림이 가요.`, '신청하기'))) return undefined;
    return run(async () => {
      // 로그인 전이라 신청 내용과 증빙 파일을 한 번에(multipart) 보낸다
      const fd = new FormData();
      fd.append('orgId', f.orgId); fd.append('name', f.name.trim()); fd.append('phone', f.phone.trim()); fd.append('verifyToken', vt);
      fd.append('loginId', f.loginId.trim()); fd.append('password', f.password); fd.append('proof', file);
      const r = await api('/auth/manager-apply', { method: 'POST', form: fd });
      await loginWithToken(r.token);
      setDone(r);
    });
  };

  return (
    <Screen title="담당자 계정 신청" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>이미 승인된 기관의 담당자라면 여기서 신청해요. 휴대폰 본인인증이 되면 바로 담당자 계정이 만들어져요.</p>
      <label className="f" htmlFor="ma_org">소속 기관</label>
      {orgs === undefined ? <Loading /> : (
        <select id="ma_org" className="in" value={f.orgId} onChange={set('orgId')}>
          <option value="">기관을 골라 주세요</option>
          {(orgs || []).map((o) => <option key={o.id} value={o.id}>{o.name}{o.orgType ? ` (${o.orgType})` : ''}</option>)}
        </select>
      )}
      {org && org.address && <div className="mute2" style={{ marginTop: 6 }}>{org.address}</div>}
      <div className="mute2" style={{ marginTop: 6 }}>기관이 목록에 없나요? <a role="button" tabIndex={0} style={{ textDecoration: 'underline', cursor: 'pointer' }} onClick={() => nav('/org-apply')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/org-apply'); }}>기관 서비스 신청</a>을 먼저 해 주세요.</div>
      <label className="f" htmlFor="ma_nm">이름</label><input id="ma_nm" className="in" placeholder="이름" value={f.name} onChange={set('name')} />
      <PhoneVerify phone={f.phone} setPhone={(v) => setF({ ...f, phone: v })} onVerified={setVt} verified={!!vt} />
      <label className="f" htmlFor="ma_id">아이디</label><input id="ma_id" className="in" placeholder="영문·숫자 4자 이상" value={f.loginId} onChange={set('loginId')} autoComplete="username" />
      <label className="f" htmlFor="ma_p1">비밀번호</label><input id="ma_p1" className="in" type="password" placeholder="4자 이상" value={f.password} onChange={set('password')} autoComplete="new-password" />
      <label className="f" htmlFor="ma_p2">비밀번호 확인</label><input id="ma_p2" className="in" type="password" placeholder="한 번 더 입력" value={f.password2} onChange={set('password2')} autoComplete="new-password" />
      <label className="f" htmlFor="ma_pf">재직 증빙 (필수)</label>
      <input id="ma_pf" className="in" type="file" accept="image/*,.pdf" aria-label="재직 증빙 파일" onChange={(e) => setFile((e.target.files && e.target.files[0]) || null)} />
      <div className="mute2" style={{ marginTop: 6 }}>{file ? `${file.name} · 신청할 때 함께 보내요` : '재직증명서나 사원증 사진(jpg·png), PDF를 올려 주세요. 운영자만 볼 수 있어요.'}</div>
      <div className="notice" style={{ marginTop: 14 }}>본인 확인이 되면 자동으로 승인돼요. 운영자가 재직 증빙을 나중에 확인하고, 사실과 다르면 계정을 취소할 수 있어요.</div>
      <button className="btn big-cta" style={{ marginTop: 16 }} disabled={busy} onClick={submit}>{busy ? '신청하는 중…' : '신청하기'}</button>
    </Screen>
  );
}
