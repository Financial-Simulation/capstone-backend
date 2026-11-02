/* params.json 검증: 엔진 값 일치 → 정답 파일 재현 → 근거 완비 를 확인하고 validation_report.md를 쓴다
   사용: node check_params.js   (params.json을 다시 만들었으면 꼭 다시 돌린다) */
const fs = require("fs"), path = require("path");
const E = require("../engine/engine.js");
const J = JSON.parse(fs.readFileSync(path.join(__dirname, "params.json"), "utf8"));
const G = require("../engine/golden.json"), A = require("../engine/analysis_golden.json");
const rows = [], fail = [];
const check = (name, ok, detail) => { rows.push([name, ok ? "통과" : "실패", detail]); if (!ok) fail.push(name); };

// 1. 값 일치: params.json = engine.js (= kernel.py, build_params.py가 왕복 확인)
const diffs = [];
function same(a, b, p) {
  if (typeof a === "number" || typeof b === "number") { if (a !== b) diffs.push(`${p}: ${a} ≠ ${b}`); return; }
  if (Array.isArray(a) || Array.isArray(b)) { if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) { diffs.push(`${p}: 배열 길이`); return; } a.forEach((x, i) => same(x, b[i], `${p}[${i}]`)); return; }
  if (a && typeof a === "object") { const ks = new Set([...Object.keys(a), ...Object.keys(b || {})]); for (const k of ks) same(a[k], (b || {})[k], `${p}.${k}`); return; }
  if (a !== b) diffs.push(`${p}: ${a} ≠ ${b}`);
}
same(J.params, E.PARAMS, "params"); const { name, ...pol } = J.policy; same(pol, E.POLICY, "policy"); same(J.general, E.GENERAL, "general");
check("엔진 값 일치 (params.json = engine.js = kernel.py)", diffs.length === 0, diffs.length ? diffs.slice(0, 5).join("; ") : "params·policy·general 모든 숫자가 double까지 같음");

// 2. 정답 파일 재현: 엔진에 params.json 값을 넣어도 같은 결과가 나오는가
const nzp = (p, T) => ({ z1: G.noise.z1[p], z2: G.noise.z2[p], u_onset: G.noise.u_onset[p], u_dur: G.noise.u_dur[p], u_invol: G.noise.u_invol[p], u_shock: G.noise.u_shock[p], u_amt: G.noise.u_amt[p] });
let worst = 0, flagMis = 0;
for (const c of G.cases) {
  const pol1 = { ...pol, rejoin: c.opt.rejoin === undefined ? true : c.opt.rejoin };
  const r = E.simulate(c.pr, J.params, nzp, G.N, G.T, { collect: true, bufferFirst: c.opt.bufferFirst || 0, policy: pol1, general: J.general });
  for (let p = 0; p < G.N; p++) for (let t = 0; t <= G.T; t++) { const b = c.saved[p][t]; worst = Math.max(worst, Math.abs(r.saved[p * (G.T + 1) + t] - b) / Math.max(1, Math.abs(b))); }
  for (const k in c.flags) for (let p = 0; p < G.N; p++) if (r.flags[k][p] !== c.flags[k][p]) flagMis++;
}
check(`엔진 정답 파일 ${G.cases.length}개 재현`, worst <= 1e-9 && flagMis === 0, `최대 상대오차 ${worst.toExponential(1)}, 플래그 불일치 ${flagMis}`);
const noise = E.randomNoise(J.simulation.defaultSeed, J.simulation.paths), N = J.simulation.paths, T = J.simulation.horizonMonths;
const hitMis = [];
for (const c of A.cases) {
  const { goal, goalMonths, ...pr } = c.engineProfile;
  const r = E.simulate(pr, J.params, noise, N, T, { collect: true, policy: pol, general: J.general });
  let h = 0; for (let p = 0; p < N; p++) if (r.saved[p * (T + 1) + goalMonths] >= goal) h++;
  if (h !== c.simulate.goal.hits) hitMis.push(`${c.key} ${h}≠${c.simulate.goal.hits}`);
}
check(`분석 정답 파일 ${A.cases.length}개 입력의 달성 경로 수 재현`, hitMis.length === 0, hitMis.length ? hitMis.join(", ") : `${A.cases.map(c => `${c.key} ${c.simulate.goal.hits}`).join(", ")} (N = ${N}, seed ${J.simulation.defaultSeed})`);

// 3. 가능성 범위 표가 정답 생성 코드와 같은가
const src = fs.readFileSync(path.join(__dirname, "..", "engine", "make_analysis_golden.js"), "utf8");
const qtab = JSON.parse(src.match(/const QTAB = (\{[^}]*\})/)[1]);
same(J.robust.shockProbByStructureAmountCohort, qtab, "QTAB");
check("가능성 범위 급전 확률표 24칸 = make_analysis_golden.js", !diffs.some(d => d.startsWith("QTAB")), "24칸 모두 같음");

// 4. 근거 완비: 모든 근거 항목에 등급·출처·기간이 있고, 가리키는 값이 실제로 있는가
const get = (o, p) => p.split(".").reduce((x, k) => (x == null ? x : x[k]), o);
const missing = [];
for (const m of J.meta.parameters) { if (!m.grade || !m.source || !m.asOf) missing.push(`${m.key} 근거 빈칸`); for (const p of m.paths) if (get(J, p) === undefined) missing.push(`${m.key} → ${p} 없음`); }
check(`근거 항목 ${J.meta.parameters.length}개 (등급·출처·기간, 가리키는 값)`, missing.length === 0, missing.length ? missing.join("; ") : "빈칸 없음, 가리키는 값 모두 있음");
const covered = new Set(J.meta.parameters.flatMap(m => m.paths)), leaf = [];
const walk = (o, p) => { if (o && typeof o === "object" && !Array.isArray(o)) for (const k in o) walk(o[k], `${p}.${k}`); else leaf.push(p); };
walk(J.params, "params"); walk(J.policy, "policy"); walk(J.general, "general");
const isCovered = p => [...covered, ...Object.keys(J.meta.otherGrades)].some(c => p === c || p.startsWith(c + "."));
const uncovered = leaf.filter(p => !isCovered(p) && p !== "policy.name");
check("엔진 값마다 근거 연결", uncovered.length === 0, uncovered.length ? `근거 없는 값: ${uncovered.join(", ")}` : `${leaf.length - 1}개 값 모두 근거 항목 또는 otherGrades에 연결됨`);

// 5. 숫자 이상값: 모든 비율이 [0, 1], 금액·기간이 양수
const bad = [];
const rate = ["inflation.mu_y", "inflation.sigma_y", "inflation.phi", "wage.macro_mu", "wage.macro_sd", "wage.idio_sd", "wage.rho_macro", "unemp.exit_m", "unemp.invol_share", "unemp.contract_share_pop", "unemp.scar_per_year", "ui.replace", "spend.raise_share", "spend.unemp_cut_ui", "spend.unemp_cut_noui", "shock.prob_m", "overdraft.annual_rate", "liquid.annual_rate"];
for (const k of rate) { const v = get(J.params, k); if (!(v >= 0 && v <= 1)) bad.push(k); }
for (const v of J.params.unemp.u_by_age) if (!(v > 0 && v < 1)) bad.push("u_by_age");
if (Math.abs(J.params.shock.amounts.reduce((s, [, w]) => s + w, 0) - 1) > 1e-12) bad.push("shock.amounts 비율 합 ≠ 1");
if (!(J.params.ui.floor <= J.params.ui.cap)) bad.push("ui.floor > ui.cap");
check("값 범위 (비율 0~1, 급전 비율 합 1, 실업급여 하한 ≤ 상한)", bad.length === 0, bad.length ? bad.join(", ") : "이상 없음");

// 리포트
const today = new Date().toISOString().slice(0, 10);
const md = `# params.json 검증 리포트

${today} · \`node params/check_params.js\`로 자동 생성 · 모델 ${J.modelVersion}

결과: **${fail.length ? `실패 ${fail.length}건` : `${rows.length}개 검사 모두 통과`}**

| 검사 | 결과 | 내용 |
| --- | --- | --- |
${rows.map(r => `| ${r[0]} | ${r[1]} | ${r[2]} |`).join("\n")}

## 근거 등급과 출처 (결과 화면 '근거', GET /api/meta)

| 항목 | 값 | 등급 | 출처 | 자료 기간 |
| --- | --- | --- | --- | --- |
${J.meta.parameters.map(m => `| ${m.label} | ${m.value} | ${m.grade} | ${m.source} | ${m.asOf} |`).join("\n")}

등급: ${Object.entries(J.meta.gradeLegend).map(([k, v]) => `${k} ${v}`).join(" · ")}

그 밖의 값: ${Object.entries(J.meta.otherGrades).map(([k, v]) => `${k} (${v.grade}, ${v.source})`).join("; ")}

## 이 리포트가 확인하지 않는 것

- 원자료에서 값을 다시 추정하는 과정은 이번 묶음에 넣지 않았다. 값은 기준 구현 kernel.py v1.6에서 가져왔고, 원자료 품질 관문·추정·보정 결과는 설계 보고서 7.1(품질 관문)·7.3(보정·백테스트)이 근거다.
- 실업급여 상·하한과 지원금 상한은 2026년 명목값으로 고정되어 있다 (보고서 8.1 한계).
`;
fs.writeFileSync(path.join(__dirname, "validation_report.md"), md);
console.log(rows.map(r => `${r[1]}  ${r[0]} — ${r[2]}`).join("\n"));
console.log(fail.length ? `\n실패 ${fail.length}건` : `\n${rows.length}개 검사 모두 통과 → validation_report.md 저장`);
process.exitCode = fail.length ? 1 : 0;
