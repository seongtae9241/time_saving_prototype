import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider, UiProvider, useAuth, HOME_OF } from './ui/ctx.jsx';
import { Splash, Onboard, LoginForm, Signup, PwReset, OrgApply } from './screens/auth.jsx';
import { Notif, Notice, NoticeDetail, Qna, QnaNew } from './screens/common.jsx';
import { UHome } from './screens/home.jsx';
import { Board, PostDetail } from './screens/board.jsx';
import { ReqForm, Regular, RequestDetail, LogWrite, Rate } from './screens/request.jsx';
import { Write, WriteOffer, WriteNeed, PostEdit } from './screens/write.jsx';
import { Act, Bank, Gift } from './screens/bank.jsx';
import { My, ProfileEdit, Verify, Family, FamReport, AlertSet, NotiSet, Cert, InfoEdit, Withdraw } from './screens/my.jsx';
import { Recommend, Public } from './screens/ai.jsx';
import { VoiceReq } from './screens/voice.jsx';
import { OHome, OElders, EDetail, EForm, OReqs, OReport, OMy } from './screens/org.jsx';
import { Cls, ClsDetail, ClsNote, ClsNew, OSpace } from './screens/cls.jsx';
import { Edu, EduCard, EduQuiz, EduRpList, EduRp, EduRpRes, EduCert } from './screens/edu.jsx';
import { ADash, AApprove, ASafety, AManage, AMembers, AManagers, AInquiries, ANotices, APub, APersona } from './screens/admin.jsx';

/** 로그인·역할 확인 */
function Guard({ roles, children }) {
  const { me, ready } = useAuth();
  const loc = useLocation();
  if (!ready) return null;
  if (!me) return <Navigate to="/login" replace state={{ from: loc.pathname }} />;
  if (roles && !roles.includes(me.role)) return <Navigate to={HOME_OF[me.role] || '/'} replace />;
  return children;
}
const U = ['USER'], O = ['ORG'], A = ['ADMIN'], UO = ['USER', 'ORG'];
const g = (el, roles) => <Guard roles={roles}>{el}</Guard>;

function Shell() {
  return (
    <div id="app">
      <Routes>
        <Route path="/" element={<Splash />} />
        <Route path="/login" element={<Onboard />} />
        <Route path="/login/form" element={<LoginForm />} />
        <Route path="/signup" element={<Signup />} />
        <Route path="/pw-reset" element={<PwReset />} />
        <Route path="/org-apply" element={<OrgApply />} />

        <Route path="/notif" element={g(<Notif />)} />
        <Route path="/notice" element={g(<Notice />)} />
        <Route path="/notice/:id" element={g(<NoticeDetail />)} />
        <Route path="/qna" element={g(<Qna />, UO)} />
        <Route path="/qna/new" element={g(<QnaNew />, UO)} />

        <Route path="/u/home" element={g(<UHome />, U)} />
        <Route path="/voice" element={g(<VoiceReq />, UO)} />
        <Route path="/board" element={g(<Board />, UO)} />
        <Route path="/post/:id" element={g(<PostDetail />)} />
        <Route path="/post/:id/edit" element={g(<PostEdit />, UO)} />
        <Route path="/req/new" element={g(<ReqForm />, UO)} />
        <Route path="/req/:id" element={g(<RequestDetail />, UO)} />
        <Route path="/req/:id/log" element={g(<LogWrite />, U)} />
        <Route path="/req/:id/rate" element={g(<Rate />, UO)} />
        <Route path="/regular" element={g(<Regular />, UO)} />
        <Route path="/write" element={g(<Write />, U)} />
        <Route path="/write/offer" element={g(<WriteOffer />, U)} />
        <Route path="/write/need" element={g(<WriteNeed />, UO)} />
        <Route path="/act" element={g(<Act />, U)} />
        <Route path="/bank" element={g(<Bank />, U)} />
        <Route path="/gift" element={g(<Gift />, U)} />
        <Route path="/my" element={g(<My />, U)} />
        <Route path="/my/profile" element={g(<ProfileEdit />, U)} />
        <Route path="/my/verify" element={g(<Verify />, U)} />
        <Route path="/my/family" element={g(<Family />, U)} />
        <Route path="/my/family/:userId/report" element={g(<FamReport />, U)} />
        <Route path="/my/alerts" element={g(<AlertSet />, U)} />
        <Route path="/my/noti" element={g(<NotiSet />, U)} />
        <Route path="/my/cert" element={g(<Cert />, U)} />
        <Route path="/my/info" element={g(<InfoEdit />)} />
        <Route path="/my/withdraw" element={g(<Withdraw />, U)} />
        <Route path="/recommend" element={g(<Recommend />, U)} />
        <Route path="/public" element={g(<Public />, UO)} />

        <Route path="/edu" element={g(<Edu />, U)} />
        <Route path="/edu/card" element={g(<EduCard />, U)} />
        <Route path="/edu/quiz" element={g(<EduQuiz />, U)} />
        <Route path="/edu/rp" element={g(<EduRpList />, U)} />
        <Route path="/edu/rp/:id" element={g(<EduRp />, U)} />
        <Route path="/edu/rp/:id/result" element={g(<EduRpRes />, U)} />
        <Route path="/edu/cert" element={g(<EduCert />, U)} />

        <Route path="/cls" element={g(<Cls />, U)} />
        <Route path="/cls/new" element={g(<ClsNew />, UO)} />
        <Route path="/cls/:id" element={g(<ClsDetail />, UO)} />
        <Route path="/cls/:id/note" element={g(<ClsNote />, UO)} />

        <Route path="/o/home" element={g(<OHome />, O)} />
        <Route path="/o/elders" element={g(<OElders />, O)} />
        <Route path="/o/elders/new" element={g(<EForm />, O)} />
        <Route path="/o/elders/:id" element={g(<EDetail />, O)} />
        <Route path="/o/elders/:id/edit" element={g(<EForm />, O)} />
        <Route path="/o/reqs" element={g(<OReqs />, O)} />
        <Route path="/o/reqs/:id" element={g(<RequestDetail />, O)} />
        <Route path="/o/report" element={g(<OReport />, O)} />
        <Route path="/o/my" element={g(<OMy />, O)} />
        <Route path="/o/space" element={g(<OSpace />, O)} />

        <Route path="/a/dash" element={g(<ADash />, A)} />
        <Route path="/a/approve" element={g(<AApprove />, A)} />
        <Route path="/a/safety" element={g(<ASafety />, A)} />
        <Route path="/a/manage" element={g(<AManage />, A)} />
        <Route path="/a/members" element={g(<AMembers />, A)} />
        <Route path="/a/managers" element={g(<AManagers />, A)} />
        <Route path="/a/inquiries" element={g(<AInquiries />, A)} />
        <Route path="/a/notices" element={g(<ANotices />, A)} />
        <Route path="/a/pub" element={g(<APub />, A)} />
        <Route path="/a/persona" element={g(<APersona />, A)} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  );
}

export default function App() {
  return (
    <UiProvider>
      <AuthProvider>
        <Shell />
      </AuthProvider>
    </UiProvider>
  );
}
