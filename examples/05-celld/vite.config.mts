import {defineConfig} from 'vite';

export default defineConfig({
  publicDir: false,
  build: {
    outDir: 'target',
    emptyOutDir: false, // Preserve the ClojureScript compiler output.
    target: 'es2022',
    lib: {entry: 'entry.mjs', formats: ['es'], fileName: () => 'worker.mjs'},
  },
});
