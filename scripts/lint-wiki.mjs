#!/usr/bin/env node
// 위키 린트. 외부 의존성 없음. 수동·에이전트 실행용(훅 아님).
// 사용법: node scripts/lint-wiki.mjs [--root <저장소 루트>]
// 오류(error)가 있으면 종료 코드 1, 경고(warn)만 있으면 0.
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const DEFAULT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const rootIdx = argv.indexOf('--root');
const ROOT = rootIdx >= 0 && argv[rootIdx + 1] ? path.resolve(argv[rootIdx + 1]) : DEFAULT_ROOT;
const WIKI = path.join(ROOT, 'wiki');

const REQUIRED = ['title', 'type', 'tags', 'sources', 'updated', 'confidence', 'status'];
const ENUMS = {
  type: ['concept', 'decision', 'research', 'gotcha', 'glossary', 'index'],
  confidence: ['high', 'medium', 'low'],
  status: ['draft', 'stable', 'stale', 'superseded'],
};
const STALE_DAYS = 90;
const LOG_RE = /^## \[(\d{4}-\d{2}-\d{2})\] (ingest|query|lint|init) \| \S.*$/;

const errors = [];
const warns = [];
const rel = (f) => path.relative(ROOT, f).split(path.sep).join('/');
const err = (f, line, msg) => errors.push(`${rel(f)}${line ? ':' + line : ''}  ${msg}`);
const warn = (f, line, msg) => warns.push(`${rel(f)}${line ? ':' + line : ''}  ${msg}`);

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = path.join(dir, name);
    if (statSync(p).isDirectory()) out.push(...walk(p));
    else if (name.toLowerCase().endsWith('.md')) out.push(p);
  }
  return out;
}

function parseValue(raw) {
  let v = raw.trim();
  if (v.startsWith('[')) {
    const end = v.lastIndexOf(']');
    if (end < 0) return { bad: true };
    const inner = v.slice(1, end).trim();
    if (!inner) return [];
    return inner.split(',').map((s) => unquote(s.trim())).filter((s) => s !== '');
  }
  if (!/^["']/.test(v)) v = v.replace(/\s+#.*$/, '');
  return unquote(v);
}
function unquote(s) {
  const m = s.match(/^(["'])(.*)\1$/);
  return m ? m[2] : s;
}

// 간단한 YAML 서브셋: `key: 문자열` / `key: [a, b]`
function parseFrontmatter(lines) {
  if (lines[0] === undefined || lines[0].trim() !== '---') return null;
  const end = lines.findIndex((l, i) => i > 0 && l.trim() === '---');
  if (end < 0) return { error: '닫는 --- 가 없음' };
  const data = {};
  const lineOf = {};
  for (let i = 1; i < end; i++) {
    const l = lines[i];
    if (!l.trim() || l.trim().startsWith('#')) continue;
    const m = l.match(/^([A-Za-z_][\w-]*)\s*:\s*(.*)$/);
    if (!m) return { error: `해석할 수 없는 줄: ${l.trim()}`, line: i + 1 };
    data[m[1]] = parseValue(m[2]);
    lineOf[m[1]] = i + 1;
  }
  return { data, lineOf, endLine: end + 1 };
}

function extractLinks(lines, startLine) {
  const links = [];
  let fence = false;
  const re = /\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g;
  for (let i = startLine; i < lines.length; i++) {
    const l = lines[i];
    if (/^\s*(```|~~~)/.test(l)) { fence = !fence; continue; }
    if (fence) continue;
    const text = l.replace(/`[^`]*`/g, '');
    let m;
    while ((m = re.exec(text))) links.push({ target: m[1], line: i + 1 });
  }
  return links;
}

function isExternal(t) {
  return /^[a-z][a-z0-9+.-]*:/i.test(t) || t.startsWith('//');
}

function main() {
  if (!existsSync(WIKI) || !statSync(WIKI).isDirectory()) {
    console.log('검사할 페이지 없음 (wiki/ 폴더가 없음)');
    return 0;
  }
  const files = walk(WIKI);
  if (files.length === 0) {
    console.log('검사할 페이지 없음 (wiki/가 비어 있음)');
    return 0;
  }

  const indexPath = path.join(WIKI, 'index.md');
  const logPath = path.join(WIKI, 'log.md');
  const pages = files.filter((f) => f !== logPath);
  const content = new Map();
  const outLinks = new Map(); // 페이지 -> 위키 내 대상 파일 Set
  const fm = new Map();
  const today = new Date();
  today.setHours(0, 0, 0, 0);

  for (const f of pages) {
    const lines = readFileSync(f, 'utf8').replace(/^﻿/, '').split(/\r?\n/);
    content.set(f, lines);
    const parsed = parseFrontmatter(lines);
    let bodyStart = 0;
    if (parsed === null) {
      err(f, 1, 'frontmatter 없음');
    } else if (parsed.error) {
      err(f, parsed.line || 1, `frontmatter 오류: ${parsed.error}`);
    } else {
      bodyStart = parsed.endLine;
      fm.set(f, parsed.data);
      const { data, lineOf } = parsed;
      for (const k of REQUIRED) {
        if (!(k in data)) err(f, 1, `frontmatter 필수 키 누락: ${k}`);
      }
      for (const k of ['title', 'updated']) {
        if (k in data && (typeof data[k] !== 'string' || !data[k])) err(f, lineOf[k], `${k} 값이 비어 있음`);
      }
      for (const k of ['tags', 'sources']) {
        if (k in data && (!Array.isArray(data[k]))) err(f, lineOf[k], `${k}는 대괄호 배열이어야 함 (예: [a, b])`);
      }
      for (const [k, allowed] of Object.entries(ENUMS)) {
        if (k in data && !allowed.includes(data[k])) {
          err(f, lineOf[k], `${k} 값이 유효하지 않음: "${data[k]}" (허용: ${allowed.join('|')})`);
        }
      }
      if (typeof data.updated === 'string' && data.updated) {
        const m = data.updated.match(/^(\d{4})-(\d{2})-(\d{2})$/);
        const d = m ? new Date(+m[1], +m[2] - 1, +m[3]) : null;
        if (!m || d.getMonth() !== +m[2] - 1 || d.getDate() !== +m[3]) {
          err(f, lineOf.updated, `updated 형식 오류: "${data.updated}" (YYYY-MM-DD)`);
        } else {
          const age = Math.floor((today - d) / 86400000);
          if (age > STALE_DAYS) warn(f, lineOf.updated, `updated가 ${age}일 전 (${STALE_DAYS}일 초과) — 재확인 필요`);
          else if (age < 0) warn(f, lineOf.updated, `updated가 미래 날짜: ${data.updated}`);
        }
      }
    }

    // 링크 검사
    const targets = new Set();
    for (const { target, line } of extractLinks(lines, bodyStart)) {
      if (isExternal(target) || target.startsWith('#')) continue;
      let p = target.split('#')[0].split('?')[0];
      if (!p) continue;
      try { p = decodeURIComponent(p); } catch { /* 그대로 사용 */ }
      const abs = path.resolve(path.dirname(f), p);
      if (!existsSync(abs)) {
        err(f, line, `깨진 링크: ${target}`);
        continue;
      }
      if (statSync(abs).isFile()) targets.add(abs);
    }
    outLinks.set(f, targets);
  }

  // index 누락 / 고아
  const hasIndex = existsSync(indexPath);
  const indexTargets = hasIndex ? outLinks.get(indexPath) || new Set() : new Set();
  const subjects = pages.filter((f) => f !== indexPath);
  if (!hasIndex && subjects.length) err(indexPath, 0, 'wiki/index.md가 없음');
  for (const f of subjects) {
    let linkedFromOther = false;
    for (const [src, set] of outLinks) {
      if (src !== f && set.has(f)) { linkedFromOther = true; break; }
    }
    const inIndex = indexTargets.has(f);
    if (hasIndex && !inIndex) err(f, 0, 'index.md에서 링크되지 않음 (index 누락)');
    if (!inIndex && !linkedFromOther) err(f, 0, '고아 페이지: 어떤 페이지에서도 링크되지 않음');
  }

  // superseded가 여전히 링크됨
  for (const f of subjects) {
    if (fm.get(f)?.status !== 'superseded') continue;
    for (const [src, set] of outLinks) {
      if (src !== f && src !== indexPath && set.has(f)) {
        warn(src, 0, `superseded 페이지를 링크함: ${rel(f)} — 대체 페이지로 교체 필요`);
      }
    }
  }

  // log.md
  if (existsSync(logPath)) {
    const lines = readFileSync(logPath, 'utf8').replace(/^﻿/, '').split(/\r?\n/);
    let prev = '';
    let count = 0;
    lines.forEach((l, i) => {
      if (!l.startsWith('## ')) return;
      const m = l.match(LOG_RE);
      if (!m) {
        err(logPath, i + 1, '항목 형식 오류: "## [YYYY-MM-DD] <ingest|query|lint|init> | <제목>"이어야 함');
        return;
      }
      count++;
      if (prev && m[1] < prev) err(logPath, i + 1, `날짜가 오름차순이 아님: ${m[1]} < ${prev} (추가만 하는 파일)`);
      prev = m[1] > prev ? m[1] : prev;
    });
    if (count === 0 && errors.every((e) => !e.startsWith('wiki/log.md'))) warn(logPath, 0, '항목이 하나도 없음');
  } else {
    warn(logPath, 0, 'wiki/log.md가 없음');
  }

  // 출력
  for (const e of errors) console.log(`[오류] ${e}`);
  for (const w of warns) console.log(`[경고] ${w}`);
  console.log(`검사 ${pages.length}개 페이지 + log: 오류 ${errors.length}건, 경고 ${warns.length}건`);
  return errors.length ? 1 : 0;
}

process.exitCode = main();
