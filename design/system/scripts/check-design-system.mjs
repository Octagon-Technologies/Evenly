import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const siteRoot = path.resolve(here, "..");
const repoRoot = path.resolve(siteRoot, "..", "..");
const dist = path.join(siteRoot, "dist");
const uiRoot = path.join(repoRoot, "code/shared/src/commonMain/kotlin/app/splitevenly/ui");

const failures = [];
const requireFile = file => {
  if (!fs.existsSync(file)) failures.push(`Missing file: ${path.relative(repoRoot, file)}`);
};

["index.html", "styles.css", "catalog.js", "app.js"].forEach(file => requireFile(path.join(dist, file)));

const sandbox = { window: {} };
vm.runInNewContext(fs.readFileSync(path.join(dist, "catalog.js"), "utf8"), sandbox, { filename: "catalog.js" });
const catalog = sandbox.window.EVENLY_CATALOG;
if (!catalog) failures.push("catalog.js did not define EVENLY_CATALOG");

const enumValues = (file, enumName) => {
  const source = fs.readFileSync(file, "utf8");
  const match = source.match(new RegExp(`enum class ${enumName}\\s*\\{([^}]+)\\}`));
  if (!match) return [];
  return match[1].split(",").map(x => x.trim().match(/^[A-Za-z0-9_]+/)?.[0]).filter(Boolean);
};

const sameMembers = (actual, expected, label) => {
  const a = [...actual].sort();
  const e = [...expected].sort();
  if (JSON.stringify(a) !== JSON.stringify(e)) failures.push(`${label} mismatch. Kotlin: ${e.join(", ")}. Catalog: ${a.join(", ")}.`);
};

if (catalog) {
  const buttons = catalog.components.find(x => x.id === "buttons")?.variants || [];
  sameMembers(buttons, enumValues(path.join(uiRoot, "components/EvButton.kt"), "ButtonVariant"), "Button variants");

  const chips = catalog.components.find(x => x.id === "chips")?.variants || [];
  sameMembers(chips, enumValues(path.join(uiRoot, "components/EvChip.kt"), "ChipVariant"), "Chip variants");

  const componentText = JSON.stringify(catalog.components);
  const componentFiles = fs.readdirSync(path.join(uiRoot, "components"), { recursive: true })
    .filter(file => file.endsWith(".kt") && !String(file).includes("gallery"));
  const publicComponents = new Set();
  for (const relative of componentFiles) {
    const source = fs.readFileSync(path.join(uiRoot, "components", relative), "utf8");
    for (const match of source.matchAll(/^fun\s+(?:<[^>]+>\s+)?(Ev[A-Z][A-Za-z0-9]+)\s*\(/gm)) publicComponents.add(match[1]);
  }
  for (const name of [...publicComponents].sort()) {
    if (!componentText.includes(name)) failures.push(`Public component is not documented: ${name}`);
  }

  const routesSource = fs.readFileSync(path.join(uiRoot, "navigation/Route.kt"), "utf8");
  const routes = [...routesSource.matchAll(/@Serializable\s+data\s+(?:object|class)\s+([A-Za-z0-9_]+)/g)].map(x => x[1]);
  const routeText = catalog.screens.map(x => x.route).join(" ");
  for (const name of routes) {
    if (!routeText.includes(name)) failures.push(`Navigation route is not represented by a screen: ${name}`);
  }

  for (const screen of catalog.screens) {
    requireFile(path.join(uiRoot, "screen", screen.source));
  }

  if (catalog.foundations.principles.length < 4) failures.push("Foundations need product principles");
  if (catalog.components.length < 25) failures.push("Component inventory is unexpectedly small");
  if (catalog.patterns.length < 10) failures.push("Pattern inventory is unexpectedly small");
  if (catalog.screens.length < 30) failures.push("Screen inventory is unexpectedly small");
  if (catalog.flows.length < 8) failures.push("Flow inventory is unexpectedly small");
}

const appSource = fs.readFileSync(path.join(dist, "app.js"), "utf8");
try { new vm.Script(appSource, { filename: "app.js" }); } catch (error) { failures.push(`app.js syntax error: ${error.message}`); }

if (failures.length) {
  console.error(`Evenly design-system check failed (${failures.length})`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`Evenly design system is in sync: ${catalog.components.length} components, ${catalog.patterns.length} patterns, ${catalog.screens.length} screens, ${catalog.flows.length} flows.`);
