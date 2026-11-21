// Community-Bereich (/community): Galerien, Regeln & FAQ, Neuigkeiten – dritter Einstieg neben Startseite und Stats-Website.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import '@fontsource/chakra-petch/latin-600.css';
import '@fontsource/chakra-petch/latin-700.css';
import '@fontsource/hanken-grotesk/latin-400.css';
import '@fontsource/hanken-grotesk/latin-600.css';
import '@fontsource/martian-mono/latin-400.css';
import './community.css';
import { CommunityApp } from './Community';

createRoot(document.getElementById('community')!).render(
  <StrictMode>
    <BrowserRouter basename="/community">
      <CommunityApp />
    </BrowserRouter>
  </StrictMode>
);
