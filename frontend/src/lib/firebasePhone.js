/*
 * Firebase Phone Auth (실제 문자 인증). 설정은 빌드 환경변수만 쓴다:
 * VITE_FIREBASE_API_KEY, VITE_FIREBASE_AUTH_DOMAIN, VITE_FIREBASE_PROJECT_ID, VITE_FIREBASE_APP_ID
 * 넷 중 하나라도 없으면 Firebase 를 쓰지 않는다(데모 코드만). SDK 는 쓸 때만 불러온다.
 */
const env = import.meta.env || {};
const cfg = {
  apiKey: env.VITE_FIREBASE_API_KEY,
  authDomain: env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: env.VITE_FIREBASE_PROJECT_ID,
  appId: env.VITE_FIREBASE_APP_ID,
};
export const firebaseConfigured = !!(cfg.apiKey && cfg.authDomain && cfg.projectId && cfg.appId);

/** 010-1234-5678 → +821012345678 */
export function toE164(phone) {
  let d = String(phone || '').replace(/\D/g, '');
  if (d.startsWith('82')) d = '0' + d.slice(2);
  return d.startsWith('0') ? `+82${d.slice(1)}` : `+${d}`;
}

let mods = null;
async function load() {
  if (!mods) {
    const [app, auth] = await Promise.all([import('firebase/app'), import('firebase/auth')]);
    const a = app.getApps().length ? app.getApp() : app.initializeApp(cfg);
    const au = auth.getAuth(a);
    au.languageCode = 'ko';
    mods = { auth, au };
  }
  return mods;
}

let verifier = null;
/** 보이지 않는 reCAPTCHA 를 붙여 문자를 보낸다. 반환: confirmationResult */
export async function sendSms(phone, container) {
  const { auth, au } = await load();
  if (verifier) { try { verifier.clear(); } catch { /* 무시 */ } verifier = null; }
  verifier = new auth.RecaptchaVerifier(au, container, { size: 'invisible' });
  try {
    return await auth.signInWithPhoneNumber(au, toE164(phone), verifier);
  } catch (e) {
    try { verifier.clear(); } catch { /* 무시 */ } verifier = null;
    throw e;
  }
}

/** 문자 코드 확인 → Firebase ID 토큰. 받은 뒤 바로 로그아웃(우리 서버 인증만 남긴다). */
export async function confirmSms(confirmation, code) {
  const { auth, au } = await load();
  const cred = await confirmation.confirm(String(code).trim());
  const token = await cred.user.getIdToken();
  try { await auth.signOut(au); } catch { /* 무시 */ }
  return token;
}

export function clearSms() { if (verifier) { try { verifier.clear(); } catch { /* 무시 */ } verifier = null; } }

/** Firebase 오류 → 쉬운 문장 */
export function smsErrorText(e) {
  const c = (e && e.code) || '';
  if (/quota-exceeded|billing|too-many-requests/.test(c)) return '지금은 문자를 보낼 수 없어요(사용량·결제 설정).';
  if (/invalid-phone-number|missing-phone-number/.test(c)) return '휴대폰 번호를 확인해 주세요.';
  if (/invalid-verification-code|missing-verification-code/.test(c)) return '인증번호가 맞지 않아요.';
  if (/code-expired|session-expired/.test(c)) return '인증번호 시간이 지났어요. 다시 받아 주세요.';
  if (/captcha|app-not-authorized|unauthorized-domain/.test(c)) return '이 주소에서는 문자 인증이 막혀 있어요(승인된 도메인 확인).';
  if (/operation-not-allowed/.test(c)) return 'Firebase에서 전화 인증이 꺼져 있어요.';
  if (/network/.test(c)) return '네트워크가 불안정해요. 잠시 뒤 다시 해 주세요.';
  return '문자를 보내지 못했어요.';
}
