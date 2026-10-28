#!/usr/bin/env python3
"""Validate resource parity and format arguments without Android/runtime dependencies."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
FORMAT = re.compile(r'%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])')
problems = []
counts = []

def signature(value):
    cleaned = value.replace('%%', '')
    result = []
    for match in FORMAT.finditer(cleaned):
        index, kind = match.groups()
        if kind == 'n':
            continue
        result.append((index or 'implicit', kind.lower()))
    return sorted(result)

def collect(folder):
    result = {}
    for path in sorted(folder.glob('*.xml')):
        try:
            resources = ET.parse(path).getroot()
        except ET.ParseError as error:
            problems.append(f'{path.relative_to(ROOT)}: {error}')
            continue
        for entry in resources:
            if entry.tag not in ('string', 'plurals'):
                continue
            key = (entry.tag, entry.get('name'))
            if key in result:
                problems.append(f'{path.relative_to(ROOT)}: duplicate {key}')
            result[key] = (entry, path)
    return result

for module in [ROOT / 'app', *sorted((ROOT / 'core').iterdir())]:
    res = module / 'src/main/res'
    if not (res / 'values').exists():
        continue
    source = collect(res / 'values')
    if not source:
        continue
    translated = collect(res / 'values-zh')
    required = {key: value for key, value in source.items() if value[0].get('translatable') != 'false'}
    for key in sorted(required.keys() - translated.keys()):
        problems.append(f'{module.relative_to(ROOT)}: missing Chinese {key}')
    for key in sorted(translated.keys() - source.keys()):
        problems.append(f'{module.relative_to(ROOT)}: unknown Chinese {key}')
    for key in sorted(required.keys() & translated.keys()):
        base, _ = source[key]
        zh, path = translated[key]
        if key[0] == 'string':
            base_formats = signature(''.join(base.itertext()))
            zh_formats = signature(''.join(zh.itertext()))
            if base_formats != zh_formats:
                problems.append(f'{path.relative_to(ROOT)}: format mismatch {key}: {base_formats} vs {zh_formats}')
        else:
            quantities = {item.get('quantity'): item for item in base}
            localized = {item.get('quantity'): item for item in zh}
            if 'other' not in quantities or 'other' not in localized:
                problems.append(f'{path.relative_to(ROOT)}: plurals require other: {key}')
                continue
            for quantity, item in localized.items():
                default = quantities.get(quantity, quantities['other'])
                if signature(''.join(default.itertext())) != signature(''.join(item.itertext())):
                    problems.append(f'{path.relative_to(ROOT)}: plural format mismatch {key}/{quantity}')
    counts.append((str(module.relative_to(ROOT)), len(required)))

# No translated copy should remain embedded in application/library Kotlin strings.
quoted = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"')
for module in [ROOT / 'app', *sorted((ROOT / 'core').iterdir())]:
    for path in (module / 'src/main/java').rglob('*.kt'):
        source = path.read_text(encoding='utf-8')
        if module.name == 'app':
            visible_literals = [
                r'Text\s*\(\s*(?:text\s*=\s*)?"([^"\n]*)"',
                r'contentDescription\s*=\s*"([^"\n]*)"',
                r'userNotice\s*=\s*"([^"\n]*)"',
                r'paneTitle\s*=\s*"([^"\n]*)"',
            ]
            for pattern in visible_literals:
                for hit in re.finditer(pattern, source):
                    if hit.group(1) not in {'AlphaPro'}:
                        line = source.count('\n', 0, hit.start()) + 1
                        problems.append(f'{path.relative_to(ROOT)}:{line}: hardcoded visible label')
        for literal in quoted.finditer(source):
            if re.search(r'[\u4e00-\u9fff]', literal.group()):
                line = source.count('\n', 0, literal.start()) + 1
                problems.append(f'{path.relative_to(ROOT)}:{line}: hardcoded Chinese string')

if problems:
    print('\n'.join(problems), file=sys.stderr)
    sys.exit(1)
print('Localization contract passed: ' + ', '.join(f'{name} {count} translated keys' for name, count in counts))
