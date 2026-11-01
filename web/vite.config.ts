import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Gebaut wird direkt in die Ressourcen des Bots: das StatsWebsite.jar liefert die Seite aus.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/web',
    emptyOutDir: true,
    assetsInlineLimit: 0,
    chunkSizeWarningLimit: 900
  },
  server: {
    // npm run dev: API vom lokal gestarteten StatsWebsite.jar (gradlew runWebsite)
    proxy: { '/api': 'http://localhost:8080', '/auth': 'http://localhost:8080' }
  }
});
