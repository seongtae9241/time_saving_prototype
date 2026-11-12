// 모든 API 호출은 여기 한 곳을 거친다. 토큰은 localStorage, 오류 문장은 토스트로.
const TOKEN_KEY = 'ts-token';
let onError = (m) => console.warn(m);
let onUnauthorized = () => {};

export const setErrorHandler = (f) => { onError = f; };
export const setUnauthorizedHandler = (f) => { onUnauthorized = f; };
export const getToken = () => { try { return localStorage.getItem(TOKEN_KEY); } catch { return null; } };
export const setToken = (t) => { clearFileCache(); try { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY); } catch { /* 무시 */ } };

/* ---------- 올린 파일 (/api/files/…) ----------
   파일은 로그인하고 볼 수 있는 사람만 열 수 있다. <img src> 나 새 창 링크는 인증 머리글을 못 붙이므로,
   머리글을 붙여 받아 온 뒤 이 탭 안에서만 쓰는 임시 주소(blob:)로 보여 준다. 로그인이 바뀌면 받아 둔 것을 버린다. */
const fileCache = new Map(); // 파일 주소 → Promise<blob 주소>
function clearFileCache() {
  fileCache.forEach((p) => p.then((u) => URL.revokeObjectURL(u)).catch(() => {}));
  fileCache.clear();
}
export const isProtectedFile = (u) => typeof u === 'string' && u.startsWith('/api/files/');
export function fileBlobUrl(url) {
  if (!fileCache.has(url)) {
    const token = getToken();
    const p = fetch(url, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
      .then((res) => { if (!res.ok) throw new ApiError(res.status === 403 ? '이 파일을 볼 수 있는 권한이 없어요' : '파일을 열 수 없어요', res.status); return res.blob(); })
      .then((b) => URL.createObjectURL(b));
    p.catch(() => fileCache.delete(url));
    fileCache.set(url, p);
  }
  return fileCache.get(url);
}
/** 파일을 새 창으로 연다(운영자의 제출 서류 보기). 창을 먼저 열어 두고 받아 온 뒤 주소를 넣는다(팝업 차단을 피하려고). */
export async function openFile(url) {
  const w = window.open('', '_blank');
  try {
    const b = await fileBlobUrl(url);
    if (w) w.location.href = b; else window.location.href = b;
  } catch (e) {
    if (w) w.close();
    onError(e.message || '파일을 열 수 없어요');
  }
}

export class ApiError extends Error {
  constructor(message, status, code, data) { super(message); this.status = status; this.code = code; this.data = data; }
}

/**
 * api('/me') · api('/posts', { method:'POST', body:{…} }) · api('/x', { quiet:true }) 는 토스트 없이 오류만 던진다.
 */
export async function api(path, opts = {}) {
  const { method = 'GET', body, quiet = false, form } = opts;
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let payload;
  if (form) payload = form;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  let res;
  try {
    res = await fetch(`/api${path}`, { method, headers, body: payload });
  } catch (e) {
    const err = new ApiError('서버에 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요', 0, 'NETWORK');
    if (!quiet) onError(err.message);
    throw err;
  }
  const text = await res.text();
  let data = null;
  if (text) { try { data = JSON.parse(text); } catch { data = text; } }
  if (!res.ok) {
    const msg = (data && data.error) || (res.status === 403 ? '권한이 없어요' : res.status === 404 ? '찾을 수 없어요' : `요청을 처리하지 못했어요 (${res.status})`);
    const err = new ApiError(msg, res.status, data && data.code, data);
    if (res.status === 401 && token) { setToken(null); onUnauthorized(); }
    if (!quiet) onError(msg);
    throw err;
  }
  return data;
}

export const get = (p, o) => api(p, o);
export const post = (p, body, o = {}) => api(p, { ...o, method: 'POST', body: body ?? {} });
export const put = (p, body, o = {}) => api(p, { ...o, method: 'PUT', body: body ?? {} });
export const del = (p, o = {}) => api(p, { ...o, method: 'DELETE' });

export async function uploadFile(file) {
  const fd = new FormData();
  fd.append('file', file);
  return api('/files', { method: 'POST', form: fd });
}

/** AI 상태 {provider, model, stt, mode} — 한 번만 불러 나눠 쓴다(실패하면 규칙 모드로 본다). */
let aiStatusP = null;
export function aiStatus() {
  if (!aiStatusP) aiStatusP = api('/ai/status', { quiet: true }).catch(() => { aiStatusP = null; return { provider: 'rule', stt: false, mode: 'rule' }; });
  return aiStatusP;
}

/** 녹음 파일을 서버 음성 인식(/api/stt)으로 보낸다. 서버는 저장하지 않는다. */
export function transcribe(blob, filename = 'voice.webm') {
  const fd = new FormData();
  fd.append('audio', blob, filename);
  fd.append('lang', 'ko');
  return api('/stt', { method: 'POST', form: fd, quiet: true });
}
