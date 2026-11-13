import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import './styles/proto.css';
import './styles/kept.css';
import './styles/app.css';
import './styles/u1113.css'; // 2026-11-13 대표 지시 · 사용자 화면
import App from './App.jsx';
import { applyBig, getBig } from './ui/ctx.jsx';

applyBig(getBig());
createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
);
