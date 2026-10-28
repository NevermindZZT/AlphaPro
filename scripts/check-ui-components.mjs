import { readdir, readFile } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const workspace = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const root = join(workspace, 'app/src/main/java/com/letter/alphapro/ui');
const common = join(root, 'components/AlphaButtons.kt');
const material = '(?:Button|OutlinedButton|FilledTonalButton|TextButton|IconButton|FilledIconButton|OutlinedIconButton|FilterChip|SegmentedButton|SingleChoiceSegmentedButtonRow)';
const direct = new RegExp('(^|[^A-Za-z0-9_])' + material + '\\s*[(]', 'gm');
const wrappers = /\bAlpha(?:Button|TonalButton|OutlinedButton|TextButton|IconButton|FilledIconButton|FilterChip|SegmentedButton|SingleChoiceSegmentedButtonRow|ShutterButton)\s*[(]/g;
const problems = [];
// Small contract probes keep the source guard honest without touching compiled Kotlin files.
direct.lastIndex = 0;
if (!direct.test('Button(onClick = {})')) throw new Error('Raw-button detection is broken');
direct.lastIndex = 0;
if (direct.test('AlphaButton(onClick = {})')) throw new Error('Shared buttons are incorrectly rejected');
direct.lastIndex = 0;
if (direct.test(mask('// Button(onClick = {})'))) throw new Error('Comments must not count as controls');

let calls = 0;
let screens = 0;

function mask(source) {
  return source.replace(/"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\/[^\n]*|\/\*[\s\S]*?\*\//g,
    value => value.replace(/[^\n]/g, ' '));
}
function argumentsAt(source, open) {
  let depth = 1, braces = 0, brackets = 0, start = open + 1;
  const args = [];
  for (let i = start; i < source.length; i++) {
    const c = source[i];
    if (c === '(') depth++;
    if (c === ')') depth--;
    if (c === '{') braces++;
    if (c === '}') braces--;
    if (c === '[') brackets++;
    if (c === ']') brackets--;
    if ((c === ',' && depth === 1 && braces === 0 && brackets === 0) || depth === 0) {
      args.push(source.slice(start, i)); start = i + 1;
    }
    if (depth === 0) return args;
  }
  return args;
}
async function walk(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) { await walk(path); continue; }
    if (!entry.name.endsWith('.kt') || path === common) continue;
    const code = mask(await readFile(path, 'utf8'));
    const name = relative(workspace, path);
    const line = index => code.slice(0, index).split('\n').length;
    direct.lastIndex = 0;
    for (const hit of code.matchAll(direct)) problems.push(name + ':' + line(hit.index) + ' uses a raw Material action constructor');
    let local = 0;
    wrappers.lastIndex = 0;
    for (const hit of code.matchAll(wrappers)) {
      calls++; local++;
      const args = argumentsAt(code, hit.index + hit[0].lastIndexOf('('));
      if (args.some(value => /^\s*(?:shape|contentPadding)\s*=/.test(value))) {
        problems.push(name + ':' + line(hit.index) + ' overrides shared button geometry');
      }
      // Dimensions are contextual; style consistency does not impose a universal height.

    }
    if (local > 0) screens++;
  }
}
await walk(root);
if (problems.length) {
  console.error(problems.join('\n'));
  process.exitCode = 1;
} else {
  console.log('UI component contract passed: ' + calls + ' shared action/selection calls across ' + screens + ' files.');
}
