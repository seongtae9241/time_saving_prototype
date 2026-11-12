import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth, useUi } from './ctx.jsx';
import { josa } from '../lib/format.js';

/* ---------- 위치 도우미 (B-59) ----------
   위치가 필요한 기능(공고 가까운 순 정렬 B-20, 공공 서비스 안내 B-67)을 쓰는 그 순간에만 브라우저에 위치를 묻는다.
   - 위치 정보 사용 동의(가입 때의 선택 동의, 마이 > 설정 > 위치 정보)가 꺼져 있으면 브라우저에 묻지 않고 켜는 곳을 안내한다.
   - 처음: 브라우저의 위치 권한 요청 창이 뜬다.
   - 거절한 뒤 같은 기능을 다시 쓰면: 위치가 필요한 기능임을 알리고 허용 방법을 안내한다.
   - 좌표는 돌려주기만 하고 어디에도 저장하지 않는다(기억하는 것은 "거절했다"는 사실 하나, 이 탭이 열려 있는 동안만). */
const DENIED_KEY = 'ts-loc-denied';
const wasDenied = () => { try { return sessionStorage.getItem(DENIED_KEY) === '1'; } catch { return false; } };
const markDenied = (v) => { try { v ? sessionStorage.setItem(DENIED_KEY, '1') : sessionStorage.removeItem(DENIED_KEY); } catch { /* 무시 */ } };

/** 브라우저가 기억하는 권한 상태: granted | prompt | denied | unknown */
async function permissionState() {
  try {
    if (!navigator.permissions || !navigator.permissions.query) return 'unknown';
    const p = await navigator.permissions.query({ name: 'geolocation' });
    return p.state;
  } catch { return 'unknown'; }
}

/** 위치를 한 번 읽는다. { ok:true, lat, lng } 또는 { ok:false, reason:'unsupported'|'denied'|'unavailable' } */
export function readPosition() {
  return new Promise((resolve) => {
    if (!navigator.geolocation) return resolve({ ok: false, reason: 'unsupported' });
    navigator.geolocation.getCurrentPosition(
      (pos) => { markDenied(false); resolve({ ok: true, lat: pos.coords.latitude, lng: pos.coords.longitude }); },
      (err) => { const denied = err && err.code === 1; if (denied) markDenied(true); resolve({ ok: false, reason: denied ? 'denied' : 'unavailable' }); },
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 600000 },
    );
  });
}

/** 허용 방법 안내 시트의 내용 */
function LocationGuide({ feature, onRetry, onClose }) {
  return (
    <>
      <h2>위치 정보가 필요한 기능이에요</h2>
      <p className="muted" style={{ marginTop: 6 }}><b>{feature}</b>{josa(feature, '은', '는').slice(String(feature).length)} 지금 계신 곳을 알아야 쓸 수 있어요. 앞에서 위치 이용을 허용하지 않으셔서 지금은 쓸 수 없어요.</p>
      <div className="notice" style={{ marginTop: 12 }}>
        <b>허용하는 방법</b>
        <div style={{ marginTop: 6 }}>1. 주소창 왼쪽의 자물쇠(또는 사이트 설정) 표시를 눌러요.</div>
        <div>2. ‘위치’를 ‘허용’으로 바꿔요.</div>
        <div>3. 아래 ‘다시 허용하기’를 눌러요.</div>
        <div style={{ marginTop: 6 }}>휴대폰에서는 설정 → 앱 → 쓰는 브라우저 → 권한 → 위치를 허용해 주세요.</div>
      </div>
      <div className="mute2" style={{ marginTop: 10 }}>위치는 이 기능을 쓰는 순간에만 한 번 쓰고, 저장하거나 계속 따라가지 않아요.</div>
      <div className="btns" style={{ marginTop: 14 }}><button className="btn ghost" onClick={onClose}>닫기</button><button className="btn" onClick={onRetry}>다시 허용하기</button></div>
    </>
  );
}

/** 위치 정보 사용 동의(가입 때의 선택 동의 = 마이 > 설정 > 위치 정보)가 꺼져 있을 때의 안내. 브라우저 권한은 묻지 않는다. */
function ConsentGuide({ feature, onClose, onGo }) {
  return (
    <>
      <h2>위치 정보 사용이 꺼져 있어요</h2>
      <p className="muted" style={{ marginTop: 6 }}><b>{feature}</b>{josa(feature, '은', '는').slice(String(feature).length)} 위치 정보 사용에 동의해야 쓸 수 있어요.</p>
      <div className="notice" style={{ marginTop: 12 }}><b>마이 &gt; 설정 &gt; 위치 정보</b>에서 켤 수 있어요. 허용하지 않아도 사는 동네 기준으로 쓸 수 있어요.</div>
      <div className="btns" style={{ marginTop: 14 }}><button className="btn ghost" onClick={onClose}>닫기</button><button className="btn" onClick={onGo}>마이 설정으로 가기</button></div>
    </>
  );
}

/**
 * const askLocation = useLocationAsk();
 * const pos = await askLocation('가까운 순 정렬');  // { lat, lng } 또는 null(못 얻음)
 * quiet: true 면 거절한 뒤에도 안내 시트를 띄우지 않고 null 만 돌려준다(질문할 때마다 시트가 뜨지 않게).
 * 사용자 계정의 위치 정보 동의(me.locConsent)가 꺼져 있으면 브라우저 권한을 묻지 않고, 켜는 곳을 안내한 뒤 null 을 돌려준다.
 */
export function useLocationAsk() {
  const { sheet, closeSheet, toast } = useUi();
  const { me } = useAuth();
  const nav = useNavigate();
  const consentOff = !!me && me.role === 'USER' && me.locConsent !== true; // 담당자·운영자 계정에는 이 동의 항목이 없다
  return useCallback(async (feature, { quiet = false } = {}) => {
    if (consentOff) {
      if (!quiet) sheet(() => <ConsentGuide feature={feature} onClose={closeSheet} onGo={() => { closeSheet(); nav('/my'); }} />);
      return null;
    }
    if (!navigator.geolocation) { if (!quiet) toast('이 기기에서는 위치를 쓸 수 없어요'); return null; }
    const state = await permissionState();
    const deniedBefore = state === 'denied' || (wasDenied() && state !== 'granted');
    if (deniedBefore) {
      if (quiet) return null;
      // 거절한 뒤 다시 쓰려 할 때: 필요한 기능임을 알리고 허용 방법을 안내한다
      return new Promise((resolve) => {
        sheet(() => (
          <LocationGuide feature={feature}
            onClose={() => { closeSheet(); resolve(null); }}
            onRetry={async () => {
              const r = await readPosition();
              if (r.ok) { closeSheet(); resolve({ lat: r.lat, lng: r.lng }); } else toast(r.reason === 'denied' ? '아직 위치가 허용되지 않았어요. 위 방법대로 바꾼 뒤 다시 눌러 주세요' : '위치를 읽지 못했어요. 잠시 뒤 다시 눌러 주세요');
            }} />
        ));
      });
    }
    const r = await readPosition(); // 처음이면 여기서 브라우저의 위치 권한 요청 창이 뜬다
    if (r.ok) return { lat: r.lat, lng: r.lng };
    if (!quiet) toast(r.reason === 'denied' ? `위치를 허용하지 않아 ${josa(feature, '을', '를')} 쓸 수 없어요` : '위치를 읽지 못했어요. 잠시 뒤 다시 시도해 주세요');
    return null;
  }, [sheet, closeSheet, toast, consentOff, nav]);
}
