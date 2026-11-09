// 모든 API 호출은 여기 한 곳을 거친다. 토큰은 localStorage, 오류 문장은 토스트로.
const TOKEN_KEY = 'ts-token';
let onError = (m) => console.warn(m);
let onUnauthorized = () => {};

export const setErrorHandler = (f) => { onError = f; };
export const setUnauthorizedHandler = (f) => { onUnauthorized = f; };
export const getToken = () => { try { return localStorage.getItem(TOKEN_KEY); } catch { return null; } };
export const setToken = (t) => { try { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY); } catch { /* 무시 */ } };

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
