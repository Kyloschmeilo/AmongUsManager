import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Gebaut wird direkt in die Ressourcen des Bots: das StatsWebsite.jar liefert die Seite aus.
// Die Stats-Website liegt unter /amongus/ (index.html), die Spiele-Startseite unter / (start.html).
export default defineConfig({
  base: '/amongus/',
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/web',
    emptyOutDir: true,
    assetsInlineLimit: 0,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      input: { app: 'index.html', start: 'start.html' }
    }
  },
  server: {
    // npm run dev: API vom lokal gestarteten StatsWebsite.jar (gradlew runWebsite); Startseite unter /amongus/start.html
    proxy: { '/api': 'http://localhost:8080', '/auth': 'http://localhost:8080', '/seiten': 'http://localhost:8080' }
  }
});
