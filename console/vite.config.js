import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";
import { fileURLToPath } from "node:url";

// Two builds from one codebase:
//   npm run build          -> dist/          (normal static site, relative paths, served by `npm run preview`)
//   npm run build:single   -> dist-single/   (ONE self-contained index.html that opens from file:// with no server)
// The console reads the tracker catalogue, fix templates and DPDP passages straight from the TraceVeil core
// resources (../core/src/main/resources) at build time, so it never drifts from what the phone app ships.
export default defineConfig(({ mode }) => {
  const single = mode === "single";
  return {
    base: "./",
    plugins: [react(), single && viteSingleFile({ removeViteModuleLoader: true })].filter(Boolean),
    resolve: {
      alias: {
        "@fonts": fileURLToPath(new URL("./node_modules/@fontsource-variable", import.meta.url)),
        "@core-resources": fileURLToPath(new URL("../core/src/main/resources", import.meta.url)),
      },
    },
    define: { __SINGLE_FILE__: JSON.stringify(single) },
    server: { port: 5191, fs: { allow: [".."] } },
    preview: { port: 4191 },
    build: {
      target: "es2022",
      outDir: single ? "dist-single" : "dist",
      emptyOutDir: true,
      chunkSizeWarningLimit: 1500,
    },
  };
});
