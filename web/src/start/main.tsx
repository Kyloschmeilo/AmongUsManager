// Spiele-Startseite (/): eigener Einstieg neben der Stats-Website (/amongus), ohne Router.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@fontsource/lilita-one/latin-400.css';
import '@fontsource/barlow/latin-500.css';
import '@fontsource/barlow/latin-600.css';
import '@fontsource/barlow-condensed/latin-600.css';
import './start.css';
import { Start } from './Start';

createRoot(document.getElementById('start')!).render(
  <StrictMode>
    <Start />
  </StrictMode>
);
