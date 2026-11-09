# Firebase 휴대폰 인증 켜는 법 (실제 문자 인증)

시간 적금은 설정이 없으면 **프로토타입 데모 인증번호 `123456`**으로 가입(A-01)·비밀번호 찾기(A-03)를 합니다.
Firebase Phone Auth를 켜면 "인증번호 받기"를 누를 때 **실제 문자**가 가고, 데모 코드도 함께 쓸 수 있습니다(끌 수도 있음).

> 키와 설정값은 **파일에 적지 않고 환경변수로만** 넣습니다. 웹 API 키는 원래 브라우저에 공개되는 값이지만, 그래도 저장소에는 올리지 않습니다.

## 1. Firebase 프로젝트 만들기
1. 구글 계정으로 **Firebase 콘솔**(https://console.firebase.google.com)에 들어갑니다.
2. **프로젝트 추가** → 이름(예: `time-savings-proto`) → Google 애널리틱스는 꺼도 됩니다 → 만들기.

## 2. 웹 앱 등록(설정값 받기)
1. 프로젝트 개요 화면에서 **웹(`</>`) 아이콘** → 앱 닉네임 입력 → 앱 등록. (Firebase 호스팅은 체크하지 않아도 됩니다.)
2. 화면에 나오는 `firebaseConfig`에서 아래 네 값을 메모합니다. 나중에 **프로젝트 설정(톱니바퀴) → 일반 → 내 앱**에서도 다시 볼 수 있습니다.
   - `apiKey` → `VITE_FIREBASE_API_KEY`, 그리고 백엔드 `FIREBASE_WEB_API_KEY`(같은 값)
   - `authDomain` → `VITE_FIREBASE_AUTH_DOMAIN`
   - `projectId` → `VITE_FIREBASE_PROJECT_ID`
   - `appId` → `VITE_FIREBASE_APP_ID`

## 3. 전화 로그인 켜기
1. 왼쪽 메뉴 **빌드 → Authentication → 시작하기**.
2. **로그인 방법(Sign-in method)** 탭 → **전화(Phone)** → 사용 설정 → 저장.

## 4. 승인된 도메인
**Authentication → 설정(Settings) → 승인된 도메인(Authorized domains)**에 서비스 주소를 넣습니다.
- `localhost`는 보통 처음부터 들어 있습니다.
- 배포 주소(예: `내서비스.onrender.com`, 임시 터널 주소)를 **도메인만**(https://, 경로 없이) 추가합니다.
- 목록에 없는 주소에서 열면 문자가 안 가고 "이 주소에서는 문자 인증이 막혀 있어요"가 나옵니다.

## 5. 요금 — 꼭 읽어 주세요
- **실제 문자를 보내려면 Blaze 요금제(종량제, 결제 계정 등록)가 필요합니다.** 무료(Spark) 요금제에서는 실제 번호로 문자가 가지 않습니다.
- 문자 한 통마다 요금이 붙고, **나라마다 값이 다릅니다.** 한국(+82) 요금은 Firebase/Google Cloud 가격표(Identity Platform·전화 인증 항목)에서 직접 확인하세요. 값이 바뀔 수 있어 여기 적지 않습니다.
- 예상 밖 요금을 막으려면 Google Cloud 콘솔 → 결제 → **예산 및 알림**을 먼저 걸어 두세요.
- 문자 발송이 실패하면(할당량 초과·결제 미설정 등) 화면에 이유와 함께 데모 코드 안내가 나오므로 서비스는 멈추지 않습니다.

## 6. 돈 안 들이고 시험하기 — "테스트용 전화번호"
실제 문자를 보내지 않고 Firebase 흐름 전체(reCAPTCHA → 코드 확인 → 서버 토큰 확인)를 시험할 수 있습니다. **Spark(무료)에서도 됩니다.**
1. **Authentication → 로그인 방법 → 전화** 항목을 엽니다.
2. 아래 **테스트용 전화번호(Phone numbers for testing)**를 펼칩니다.
3. 가짜 번호와 고정 코드를 넣습니다. 예: `+82 10-5555-0101` / `654321` (실제로 쓰는 번호는 넣지 마세요).
4. 가입 화면에서 `010-5555-0101`을 넣고 "인증번호 받기" → 문자는 가지 않습니다 → `654321` 입력 → 인증 완료.
   - 고정 코드를 `123456`으로 정하면 데모 코드와 구분이 안 되니 다른 숫자를 쓰세요.

## 7. 환경변수 넣기
프론트 값은 **빌드할 때** 들어가므로, 값을 넣은 뒤 `bash scripts/build.sh`(또는 `npm run build`)를 다시 해야 합니다.

**프론트 (둘 중 하나)**
- `frontend/.env.example`을 `frontend/.env.local`로 복사해 네 값을 채웁니다(`.env*`는 git에 올라가지 않음).
- 또는 아래처럼 빌드하는 창에서 환경변수로 넣습니다.

**Windows (PowerShell)** — 그 창에서만 유효합니다.
```bash
$env:VITE_FIREBASE_API_KEY="복사한_apiKey"
$env:VITE_FIREBASE_AUTH_DOMAIN="프로젝트.firebaseapp.com"
$env:VITE_FIREBASE_PROJECT_ID="프로젝트ID"
$env:VITE_FIREBASE_APP_ID="복사한_appId"
bash scripts/build.sh
$env:FIREBASE_WEB_API_KEY="복사한_apiKey"
java -jar backend/target/time-savings-0.1.0.jar
```

**Mac / Linux**
```bash
export VITE_FIREBASE_API_KEY="복사한_apiKey"
export VITE_FIREBASE_AUTH_DOMAIN="프로젝트.firebaseapp.com"
export VITE_FIREBASE_PROJECT_ID="프로젝트ID"
export VITE_FIREBASE_APP_ID="복사한_appId"
bash scripts/build.sh
FIREBASE_WEB_API_KEY="복사한_apiKey" java -jar backend/target/time-savings-0.1.0.jar
```

**데모 코드 끄기**(실제 서비스로 갈 때): 백엔드 실행 때 `PHONE_DEMO=false`를 함께 넣습니다. 그러면 `123456`이 거절되고 화면의 "프로토타입 테스트" 안내도 사라집니다.

## 8. 잘 켜졌는지 확인
- 브라우저에서 `/api/auth/phone/config`를 열어 `"firebase": true`인지 봅니다(백엔드 키). `false`면 `FIREBASE_WEB_API_KEY`가 안 들어간 것입니다.
- 가입 화면 "인증번호 받기" 아래에 "프로토타입 테스트: 123456 입력으로도 인증돼요"가 보이면 프론트 설정도 들어간 것입니다(데모 켜짐일 때). 이 글씨 없이 "테스트용 인증번호: 123456" 상자가 나오면 프론트 `VITE_FIREBASE_*`가 빠진 것입니다.
- 테스트용 전화번호(6번)로 끝까지 인증되면 서버 확인(`accounts:lookup`)까지 정상입니다.

## 9. 알아 둘 것
- 휴대폰에서 카메라·마이크처럼, Firebase 문자 인증도 **https 주소나 localhost**에서 여는 것이 안전합니다.
- 서버는 Firebase가 확인한 번호와 화면에 입력한 번호가 같은지 확인한 뒤 10분짜리 인증 표(`verifyToken`)를 줍니다. Firebase 로그인 상태는 확인 직후 지웁니다(우리 서비스 계정만 남음).
- 문자 인증 기록·번호는 Firebase(구글) 쪽에도 남습니다. 실제 서비스 전에는 개인정보 처리방침에 "본인 확인 문자 발송: Google Firebase"를 적어야 합니다.
