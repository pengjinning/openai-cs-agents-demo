import { copyFileSync, mkdirSync } from "node:fs";

const target = new URL("../src/main/resources/static/vendor/", import.meta.url);
mkdirSync(target, { recursive: true });
for (const [source, name] of [
  ["marked/lib/marked.umd.js", "marked.js"],
  ["marked/LICENSE.md", "marked-LICENSE.md"],
  ["dompurify/dist/purify.min.js", "purify.js"],
  ["dompurify/LICENSE", "dompurify-LICENSE"],
]) {
  copyFileSync(new URL(`../node_modules/${source}`, import.meta.url), new URL(name, target));
}
