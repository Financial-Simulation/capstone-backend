/* 결과 화면 확장 (v1.7) — 기존 화면 빌드에 덧붙이는 스크립트.
 * 하는 일
 *  1) 입력 01-1에 '더 정확하게'(세전 연봉·무주택 세대주)와 '그중 월세' 칸을 붙인다
 *  2) 계산 API 요청에 그 값과 상품별 배분을 넣는다 (fetch를 감싸서 input에 칸 추가)
 *  3) 결과 화면에 블록을 붙인다
 *     - 첫 결과 아래: 가능성 범위 (/api/robust, 비동기 작업)
 *     - '위험' 아래: 세금, 어디에 얼마나 (배분 비교 /api/compare, 자동 추천 /api/allocations/recommend)
 *     - '이렇게 바꿔 보세요' 아래: 직접 바꿔 보기 (/api/simulate/preview, /api/compare, /api/robust)
 *     - 위쪽 버튼: 리포트 저장 (/api/reports → 인쇄·PDF)
 * 값은 이 브라우저(localStorage 'taxAddon.v1')에만 저장한다. 앱 소스로 옮길 때는 이 파일의 화면·규칙을 그대로 컴포넌트로 옮기면 된다.
 */
(() => {
  "use strict";
  const KEY = "taxAddon.v1";
  const load = () => { try { return JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) { return {}; } };
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(st)); } catch (e) { /* 저장 못 해도 화면은 동작 */ } };
  let st = load();
  const last = { input: null, result: null, compare: null, busy: false, error: null };

  // ───────── 숫자·문구 ─────────
  const comma = n => Math.round(n).toLocaleString("ko-KR");
  const manwon = w => {   // 원 → "12만 3,400원" 대신 화면과 같은 '만 원' 표기
    const neg = w < 0, a = Math.abs(w);
    if (a < 10000) return (neg ? "−" : "") + comma(a) + "원";
    const man = Math.round(a / 1000) / 10;   // 소수 1자리 만 원
    const s = man >= 10000 ? `${Math.floor(man / 10000)}억 ${comma(man % 10000)}만 원` : `${man.toLocaleString("ko-KR")}만 원`;
    return (neg ? "−" : "") + s;
  };
  const parseWon = v => { const d = String(v || "").replace(/[^\d]/g, ""); return d === "" ? null : Number(d); };
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  // ───────── 연말정산 어림 (엔진과 같은 규칙, 첫해·상품 없을 때 내는 세금) ─────────
  const MAN = 10000;
  const BR = [[1400 * MAN, .06, 0], [5000 * MAN, .15, 84 * MAN], [8800 * MAN, .24, 624 * MAN], [15000 * MAN, .35, 1536 * MAN], [30000 * MAN, .38, 3760 * MAN], [50000 * MAN, .40, 9406 * MAN], [1e11, .42, 17406 * MAN]];
  function taxNoProduct(gross) {   // 원/년 → 결정세액 + 지방세 (특별공제·표준세액공제 중 작은 쪽)
    if (!(gross > 0)) return 0;
    const g = Math.floor(gross);
    const d = g <= 500 * MAN ? g * .7 : g <= 1500 * MAN ? 350 * MAN + (g - 500 * MAN) * .4 : g <= 4500 * MAN ? 750 * MAN + (g - 1500 * MAN) * .15 : g <= 10000 * MAN ? 1200 * MAN + (g - 4500 * MAN) * .05 : 1475 * MAN + (g - 10000 * MAN) * .02;
    const inc = g - Math.floor(Math.min(d, 2000 * MAN, g)), m = g / 12;
    const nps = Math.floor(Math.min(Math.max(m, 41 * MAN), 659 * MAN) * .0475 * 12), oth = Math.floor(m * (.03595 * (1 + .9448 / 7.19) + .009) * 12);
    let best = Infinity;
    for (const sp of [true, false]) {
      const base = Math.max(0, inc - 150 * MAN - nps - (sp ? oth : 0));
      let lo = 0, calc = 0; for (const [hi, r, acc] of BR) { if (base <= hi) { calc = Math.floor(acc + (base - lo) * r); break; } lo = hi; }
      const c = calc <= 130 * MAN ? calc * .55 : 71.5 * MAN + (calc - 130 * MAN) * .3;
      const cap = g <= 3300 * MAN ? 74 * MAN : g <= 7000 * MAN ? Math.max(66 * MAN, 74 * MAN - (g - 3300 * MAN) * .008) : Math.max(50 * MAN, 66 * MAN - (g - 7000 * MAN) * .5);
      best = Math.min(best, Math.max(0, calc - Math.floor(Math.min(c, cap)) - (sp ? 0 : 13 * MAN)));
    }
    return best + Math.floor(best / 10);
  }
  const grossOf = input => st.annualGrossSalary || (input ? input.income.monthlyNetIncome * 12 / 0.88 : 0);

  // ───────── API 요청에 칸 넣기 ─────────
  const CALC = ["/api/simulate", "/api/simulate/preview", "/api/compare", "/api/reverse", "/api/inputs/validate"];
  const isExample = () => /example/i.test(location.hash);
  function inject(input) {
    if (!input || isExample()) return input;
    const o = JSON.parse(JSON.stringify(input));
    if (st.annualGrossSalary && o.income) o.income.annualGrossSalary = st.annualGrossSalary;
    if (st.monthlyRent && o.expenses) o.expenses.monthlyRent = st.monthlyRent;
    if (st.homeless === false && o.person) o.person.homelessHouseholder = false;
    if (o.savingPlan) for (const k of ["policyMonthly", "housingMonthly", "pensionMonthly"]) if (st[k] != null) o.savingPlan[k] = st[k];
    return o;
  }
  const rawFetch = window.fetch.bind(window);
  window.fetch = async (url, opt = {}) => {
    let path = "";
    try { path = new URL(typeof url === "string" ? url : url.url, location.href).pathname; } catch (e) { /* 그대로 보냄 */ }
    if (CALC.includes(path) && (opt.method || "GET").toUpperCase() === "POST" && typeof opt.body === "string") {
      try {
        const b = JSON.parse(opt.body);
        if (b && b.input) { b.input = inject(b.input); opt = { ...opt, body: JSON.stringify(b) }; if (path === "/api/simulate") last.input = b.input; }
      } catch (e) { /* JSON이 아니면 그대로 보냄 */ }
    }
    const res = await rawFetch(url, opt);
    if (path === "/api/simulate" && res.ok) {
      res.clone().json().then(j => {
        if (j && j.result) {
          last.result = j.result; last.compare = null; last.error = null; last.rec = null;
          rb.result = null; rb.error = null; Object.assign(pl, { draft: null, preview: null, basket: [], compare: null, robust: null, error: null });
          schedule();
        }
      }).catch(() => {});
    }
    return res;
  };

  /** 이 스크립트가 직접 부르는 API (input은 이미 칸이 들어간 last.input을 그대로 씀) */
  async function api(path, body, method = "POST") {
    const res = await rawFetch(path, { method, headers: { "Content-Type": "application/json", Accept: "application/json" }, body: body == null ? undefined : JSON.stringify(body) });
    let j = null; try { j = await res.json(); } catch (e) { /* 아래에서 처리 */ }
    if (!res.ok || !j || !j.isSuccess) {
      const fields = j && j.result && typeof j.result === "object" ? Object.values(j.result).filter(v => typeof v === "string") : [];
      throw new Error([j && j.message, ...fields].filter(Boolean).join(" ") || `요청 실패 (${res.status})`);
    }
    return j.result;
  }
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  /** /api/robust 작업을 맡기고 끝날 때까지 기다림. onProgress(0~100) */
  async function runRobust(plans, onProgress) {
    const job = await api("/api/robust", { input: last.input, plans });
    for (;;) {
      await sleep(1500);
      const r = await api(`/api/robust/${job.jobId}`, null, "GET");
      onProgress(r.progress);
      if (r.status === "DONE") return r;
      if (r.status === "FAILED") throw new Error("계산 중 오류가 났어요");
    }
  }

  // ───────── 스타일 (앱 색 토큰을 그대로 씀) ─────────
  const css = `
  .tx-sec{border:1px solid var(--color-line);border-radius:.75rem;background:var(--color-surface);padding:1.25rem}
  .tx-sec[hidden]{display:none!important} [data-tx="plan"]{scroll-margin-top:5rem}
  .tx-h{font-size:1rem;font-weight:700;margin:0}
  .tx-sub{margin:.25rem 0 0;font-size:.875rem;color:var(--color-ink-soft)}
  .tx-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr));gap:.75rem;margin-top:1rem}
  .tx-card{border:1px solid var(--color-line);border-radius:.75rem;padding:.875rem 1rem;min-width:0}
  .tx-card .l{font-size:.8125rem;color:var(--color-ink-soft)}
  .tx-card .v{font-size:1.375rem;font-weight:700;margin-top:.25rem;font-variant-numeric:tabular-nums}
  .tx-card .d{font-size:.8125rem;color:var(--color-muted);margin-top:.25rem}
  .tx-card.good{border-color:var(--color-good);background:var(--color-good-soft)} .tx-card.good .v{color:var(--color-good)}
  .tx-card.bad{border-color:var(--color-danger-line);background:var(--color-danger-soft)} .tx-card.bad .v{color:var(--color-danger)}
  .tx-notes{margin:1rem 0 0;padding:.75rem 1rem .75rem 2rem;background:var(--color-canvas);border-radius:.5rem;font-size:.875rem;line-height:1.6}
  .tx-alloc{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:.75rem;margin-top:1rem}
  .tx-f label{display:block;font-size:.875rem;font-weight:600;color:var(--color-ink)}
  .tx-in{display:flex;align-items:center;height:2.75rem;border:1px solid var(--color-line-strong);border-radius:.5rem;background:var(--color-surface);padding:0 .75rem;margin-top:.375rem}
  .tx-in:focus-within{outline:2px solid var(--color-primary);outline-offset:2px}
  .tx-in input{flex:1;min-width:0;border:0;background:transparent;text-align:right;font-size:15px;font-variant-numeric:tabular-nums;outline:none;color:inherit}
  .tx-in span{margin-left:.5rem;font-size:.875rem;color:var(--color-muted)}
  .tx-hint{margin-top:.375rem;font-size:.8125rem;color:var(--color-muted)}
  .tx-err{margin-top:.375rem;font-size:.8125rem;color:var(--color-danger)}
  .tx-btns{display:flex;flex-wrap:wrap;gap:.5rem;margin-top:1rem}
  .tx-btn{border-radius:.5rem;padding:.625rem 1rem;font-size:.875rem;font-weight:600;border:1px solid var(--color-line-strong);background:var(--color-surface);color:var(--color-ink);cursor:pointer}
  .tx-btn.pri{background:var(--color-primary);border-color:var(--color-primary);color:#fff}
  .tx-btn:disabled{opacity:.5;cursor:default}
  .tx-tbl-wrap{overflow-x:auto;margin-top:1rem}
  .tx-tbl{width:100%;border-collapse:collapse;font-size:.875rem;font-variant-numeric:tabular-nums;min-width:640px}
  .tx-tbl th{font-size:.75rem;font-weight:600;color:var(--color-ink-soft);text-align:right;padding:.5rem;border-bottom:1px solid var(--color-line)}
  .tx-tbl td{text-align:right;padding:.625rem .5rem;border-bottom:1px solid var(--color-line);vertical-align:top}
  .tx-tbl th:first-child,.tx-tbl td:first-child{text-align:left}
  .tx-tbl tr.base td{background:var(--color-system-soft)}
  .tx-up{color:var(--color-good)} .tx-down{color:var(--color-danger)} .tx-flat{color:var(--color-muted)}
  .tx-more{border-top:1px solid var(--color-line);padding-top:.75rem}
  .tx-more summary{cursor:pointer;font-size:.875rem;font-weight:600;color:var(--color-primary)}
  .tx-chk{display:flex;gap:.5rem;align-items:flex-start;margin-top:.75rem;font-size:.875rem}
  .tx-chk input{margin-top:.2rem;width:1rem;height:1rem;accent-color:var(--color-primary)}
  .tx-range{position:relative;height:.75rem;border-radius:999px;background:var(--color-canvas);margin:1.75rem 0 .5rem}
  .tx-range .band{position:absolute;top:0;bottom:0;border-radius:999px;background:var(--color-primary-soft);border:1px solid var(--color-info-line)}
  .tx-range .mk{position:absolute;top:-.375rem;width:2px;height:1.5rem;background:var(--color-primary)}
  .tx-range .mk span{position:absolute;bottom:1.6rem;left:50%;transform:translateX(-50%);white-space:nowrap;font-size:.75rem;color:var(--color-primary);font-weight:600}
  .tx-scale{display:flex;justify-content:space-between;font-size:.75rem;color:var(--color-muted)}
  .tx-bar{height:.375rem;border-radius:999px;background:var(--color-canvas);overflow:hidden;margin-top:.75rem}
  .tx-bar i{display:block;height:100%;background:var(--color-primary);transition:width .3s}
  .tx-rec{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:.75rem;margin-top:1rem}
  .tx-rec .tx-card .v{font-size:1.125rem}
  .tx-basket{display:flex;flex-wrap:wrap;gap:.5rem;margin-top:.75rem}
  .tx-chip{display:inline-flex;align-items:center;gap:.375rem;border:1px solid var(--color-line-strong);border-radius:999px;padding:.25rem .75rem;font-size:.8125rem}
  .tx-chip button{border:0;background:none;color:var(--color-muted);cursor:pointer;font-size:.9rem;padding:0}
  .tx-sel{height:2.75rem;border:1px solid var(--color-line-strong);border-radius:.5rem;padding:0 .75rem;margin-top:.375rem;width:100%;background:var(--color-surface);color:inherit;font-size:15px}
  .tx-report{position:fixed;inset:0;z-index:1000;overflow:auto;background:var(--color-canvas);padding:1rem}
  .tx-report .paper{max-width:820px;margin:0 auto;background:#fff;color:#1e2026;border-radius:.75rem;padding:2rem;font-size:.875rem;line-height:1.6}
  .tx-report h1{font-size:1.375rem;margin:0} .tx-report h2{font-size:1rem;margin:1.5rem 0 .5rem;padding-bottom:.25rem;border-bottom:2px solid #1e2026}
  .tx-report table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums} .tx-report td,.tx-report th{border-bottom:1px solid #d9dce1;padding:.375rem .25rem;text-align:right;vertical-align:top}
  .tx-report td:first-child,.tx-report th:first-child{text-align:left} .tx-report th{font-size:.75rem;color:#5b616b}
  .tx-report .bar{position:sticky;top:0;display:flex;gap:.5rem;justify-content:flex-end;max-width:820px;margin:0 auto .75rem}
  @media print{body.tx-printing>*:not(.tx-report){display:none!important} body.tx-printing .tx-report{position:static;overflow:visible;background:#fff;padding:0}
    body.tx-printing .tx-report .bar{display:none} body.tx-printing .tx-report .paper{border-radius:0;padding:0;max-width:none}}
  .tx-tag{display:inline-block;font-size:.75rem;font-weight:600;color:var(--color-primary);background:var(--color-primary-soft);border-radius:999px;padding:.125rem .5rem;margin-left:.375rem;vertical-align:middle}
  `;
  const styleEl = document.createElement("style"); styleEl.textContent = css; document.head.appendChild(styleEl);

  // ───────── 입력 칸 ─────────
  function moneyField(id, label, hint, value, onChange) {
    const w = document.createElement("div"); w.className = "tx-f"; w.dataset.txField = id;
    w.innerHTML = `<label for="${id}">${label}</label><div class="tx-in"><input id="${id}" inputmode="numeric" autocomplete="off" type="text" value="${value ? comma(value) : ""}"><span>원</span></div><p class="tx-hint">${hint}</p><p class="tx-err" hidden></p>`;
    const inp = w.querySelector("input");
    inp.addEventListener("input", () => { const v = parseWon(inp.value); inp.value = v == null ? "" : comma(v); onChange(v, w); });
    return w;
  }
  function fixedTotal() { const el = document.querySelector("#f-expenses-fixed-total"); if (el) return parseWon(el.value); return null; }
  function ensureInputs() {
    const inc = document.querySelector('[data-field="income.monthlyNetIncome"]');
    if (inc && !document.querySelector('[data-tx="more"]')) {
      const d = document.createElement("details"); d.className = "tx-more"; d.dataset.tx = "more"; d.style.marginTop = "1rem";
      if (st.annualGrossSalary || st.homeless === false) d.open = true;
      d.innerHTML = `<summary>더 정확하게 (선택)</summary>`;
      const box = document.createElement("div"); box.style.marginTop = ".75rem";
      box.appendChild(moneyField("tx-gross", "세전 연봉", "비우면 월 실수령액 × 12 ÷ 0.88로 어림해요. 연말정산과 청년미래적금 지원금(총급여 6,000만 원 이하) 판정에 써요.",
        st.annualGrossSalary, (v, w) => {
          const e = w.querySelector(".tx-err"); const bad = v != null && v > 1e9; e.hidden = !bad; e.textContent = "10억 원 이하로 입력해 주세요";
          st.annualGrossSalary = bad ? st.annualGrossSalary : v; save();
        }));
      const ck = document.createElement("label"); ck.className = "tx-chk";
      ck.innerHTML = `<input type="checkbox" id="tx-homeless" ${st.homeless === false ? "" : "checked"}><span>무주택 세대주예요<br><span class="tx-hint" style="margin:0">청약 소득공제·월세 세액공제를 받으려면 집이 없는 세대의 세대주여야 해요. 혼자 살면 대부분 해당돼요.</span></span>`;
      ck.querySelector("input").addEventListener("change", e => { st.homeless = e.target.checked; save(); });
      box.appendChild(ck); d.appendChild(box);
      inc.after(d);
    }
    const fx = document.querySelector('[data-field="expenses.fixed"]');
    if (fx && !document.querySelector('[data-tx-field="tx-rent"]')) {
      const f = moneyField("tx-rent", "그중 월세 (선택)", "고정지출에 들어 있는 월세예요. 무주택 세대주라면 낸 월세의 15~17%를 연말정산에서 돌려받을 수 있어요 (총급여 8,000만 원 이하).",
        st.monthlyRent, (v, w) => {
          const ft = fixedTotal(), e = w.querySelector(".tx-err"), bad = v != null && ft != null && v > ft;
          e.hidden = !bad; e.textContent = "그중 월세는 고정지출보다 클 수 없어요";
          st.monthlyRent = bad ? null : v; save();
        });
      f.style.marginTop = ".25rem";
      fx.appendChild(f);
    }
  }

  // ───────── 결과: ② 세금 ─────────
  const pctTxt = v => (v == null ? "—" : `${Number(v).toFixed(1)}%`);
  function taxSection(r) {
    const t = r.tax, a = t.allocation, input = last.input, gm = r.goal.goalMonths;
    const goalDate = (() => { const [y, m] = r.startMonth.split("-").map(Number), v = y * 12 + m - 1 + gm; return `${Math.floor(v / 12)}년 ${v % 12 + 1}월`; })();
    const reTax = t.clawback + t.pensionTax;
    const notes = [];
    const g = grossOf(input), owed = taxNoProduct(g);
    if (a.pensionMonthly > 0) {
      const credit = Math.min(a.pensionMonthly * 12, 600 * MAN) * (g <= 5500 * MAN ? .165 : .132);
      if (owed < credit) notes.push(`내는 세금이 연 약 ${manwon(owed)}이라, 연금저축 세액공제 ${manwon(credit)} 중 ${manwon(owed)}까지만 돌려받아요 (첫해 기준 어림).`);
      if (t.pensionBreakProbability >= 5) notes.push(`목표 시점까지 연금저축을 중간에 꺼낼 확률이 ${pctTxt(t.pensionBreakProbability)}예요. 꺼내면 공제받은 돈과 수익의 16.5%를 다시 내요.`);
    }
    if (a.housingMonthly > 0 && t.housingBreakProbability >= 5)
      notes.push(`목표 시점까지 청약을 깰 확률이 ${pctTxt(t.housingBreakProbability)}예요. 가입 5년 안에 깨면 그동안 청약으로 줄어든 세금을 다시 내요.`);
    const ps = r.risks.policySaving;
    if (ps.eligible && ps.expectedSubsidy > 0 && g <= 6000 * MAN)
      notes.push(`중소기업에 다니면(총급여 3,600만 원 이하 재직자, 또는 6,000만 원 이하 신규 취업자) 청년미래적금 우대형으로 정부 지원금이 두 배가 돼요. 지금 계획 기준으로 약 ${manwon(ps.expectedSubsidy)}을 더 받아요.`);
    if (!st.monthlyRent) notes.push("월세를 내고 있다면 입력 화면의 '그중 월세'에 넣어 보세요. 낸 월세의 15~17%를 돌려받을 수 있어요.");
    if (!a.housingMonthly && !a.pensionMonthly && !t.refundTotal) notes.push(`지금 계획에는 세금을 돌려받는 상품이 없어요. 아래 '어디에 얼마나'에서 청약·연금저축을 넣어 비교해 보세요. 내는 세금은 연 약 ${manwon(owed)}예요 (첫해 기준 어림).`);
    const card = (l, v, d, cls = "") => `<div class="tx-card ${cls}"><div class="l">${l}</div><div class="v">${v}</div>${d ? `<div class="d">${d}</div>` : ""}</div>`;
    return `<h2 class="tx-h">세금</h2>
      <p class="tx-sub">연말정산으로 돌려받는 세금과 해지할 때 내는 세금이에요. 목표 시점(${goalDate})까지, 미래 2,000개의 평균이에요.</p>
      <div class="tx-grid">
        ${card("매년 돌려받는 세금", manwon(t.refundPerYear), "매년 2월 연말정산, 상품·월세가 없을 때와의 차이")}
        ${card("목표 시점까지 돌려받는 세금", manwon(t.refundTotal))}
        ${card("낸 이자소득세", manwon(t.interestTax), "적금·청약 이자의 15.4%")}
        ${card("해지로 다시 낸 세금", manwon(reTax), `청약 추징 ${manwon(t.clawback)} · 연금 ${manwon(t.pensionTax)}`)}
        ${card("세금으로 본 순이득", manwon(t.netBenefit), "돌려받은 세금 − 낸 세금", t.netBenefit > 0 ? "good" : t.netBenefit < 0 ? "bad" : "")}
      </div>
      ${notes.length ? `<ul class="tx-notes">${notes.map(n => `<li>${esc(n)}</li>`).join("")}</ul>` : ""}`;
  }

  // ───────── 결과: ③ 어디에 얼마나 ─────────
  function allocSection(r) {
    const a = r.tax.allocation, save = last.input.savingPlan.monthlySaving, eligible = r.risks.policySaving.eligible;
    const cur = { policyMonthly: a.policyMonthly, housingMonthly: a.housingMonthly, pensionMonthly: a.pensionMonthly };
    const draft = last.draft || (last.draft = { ...cur });
    const f = (k, label, hint) => `<div class="tx-f"><label for="tx-a-${k}">${label}</label><div class="tx-in"><input id="tx-a-${k}" data-k="${k}" inputmode="numeric" type="text" value="${comma(draft[k])}"><span>원</span></div><p class="tx-hint">${hint}</p></div>`;
    const sum = draft.policyMonthly + draft.housingMonthly + draft.pensionMonthly, general = save - sum;
    let body = `<h2 class="tx-h">어디에 얼마나</h2>
      <p class="tx-sub">월 저축액 ${manwon(save)}을 상품별로 나눠 봐요. 같은 미래 2,000개로 목표 달성 가능성과 세금을 함께 비교해요.</p>
      <div class="tx-alloc">
        ${eligible ? f("policyMonthly", "청년미래적금", "월 50만 원까지, 정부 지원금 6%") : `<div class="tx-f"><label>청년미래적금</label><p class="tx-hint">나이·소득 기준으로 대상이 아니에요.</p></div>`}
        ${f("housingMonthly", "주택청약", "월 50만 원까지. 납입액 연 300만 원까지 40% 소득공제, 5년 안에 깨면 추징")}
        ${f("pensionMonthly", "연금저축", "월 150만 원까지. 연 600만 원까지 세액공제, 55세 전에 꺼내면 16.5%")}
        <div class="tx-f"><label>일반 적금 (나머지)</label><div class="tx-in" style="background:var(--color-canvas)"><input readonly value="${comma(Math.max(general, 0))}" tabindex="-1"><span>원</span></div>
          <p class="${general < 0 ? "tx-err" : "tx-hint"}">${general < 0 ? `합이 월 저축액보다 ${manwon(-general)} 많아요. 계산할 때 연금저축 → 청약 순서로 줄여요.` : "남는 돈은 일반 적금(연 3.03%)에 넣어요."}</p></div>
      </div>
      <div class="tx-btns"><button class="tx-btn pri" data-act="compare" ${last.busy ? "disabled" : ""}>${last.busy ? "계산 중… (10~30초)" : "지금 계획과 비교하기"}</button>
        <button class="tx-btn" data-act="reset" ${last.busy ? "disabled" : ""}>지금 배분으로 되돌리기</button>
        <button class="tx-btn" data-act="recommend" ${last.busy ? "disabled" : ""}>${last.recBusy ? "찾는 중… (10~40초)" : "가장 좋은 배분 찾기"}</button></div>`;
    if (last.error) body += `<p class="tx-err">${esc(last.error)}</p>`;
    if (last.rec) {
      const rc = last.rec, alloc = x => `청년미래 ${manwon(x.policyMonthly)} · 청약 ${manwon(x.housingMonthly)} · 연금 ${manwon(x.pensionMonthly)} · 일반 ${manwon(x.generalMonthly)}`;
      const dd = x => { const d = Number(x.diffFromBase); return Math.abs(d) < 2 ? "지금과 거의 같음" : `${d > 0 ? "+" : ""}${d.toFixed(1)}%p`; };
      const recCard = (title, x, key) => !x ? "" : `<div class="tx-card ${key === "bestBenefit" ? "good" : ""}"><div class="l">${title}</div>
        <div class="v">${pctTxt(x.probability)}${key === "current" ? "" : ` <span style="font-size:.8125rem;font-weight:500">(${dd(x)})</span>`}</div>
        <div class="d">${alloc(x.allocation)}</div><div class="d">세금 순이득 ${manwon(x.taxNetBenefit)} + 정부 지원금 ${manwon(x.subsidy)} = <b>${manwon(x.totalBenefit)}</b></div>
        ${key === "current" ? "" : `<div class="tx-btns" style="margin-top:.5rem"><button class="tx-btn" data-act="apply-rec" data-k="${key}">이 배분으로</button></div>`}</div>`;
      body += `<div class="tx-rec">${recCard("지금 배분", rc.current, "current")}${recCard("목표 달성 가능성이 가장 높은 배분", rc.bestProbability, "bestProbability")}${recCard("세금·지원금이 가장 큰 배분", rc.bestBenefit, "bestBenefit")}</div>
        <p class="tx-hint">배분 ${rc.evaluated}가지를 같은 미래 2,000개로 계산했어요. ${esc(rc.rule)}. 세금·지원금은 목표 시점(${esc(last.input.goal.targetDate)})까지 받는 돈의 평균이에요. 위 '위험'의 정부 지원금은 4년 안에 받는 돈이라 숫자가 다를 수 있어요.</p>`;
    }
    if (last.compare) {
      const rows = last.compare.plans;
      const al = p => { const x = p.allocation; return `청년미래 ${manwon(x.policyMonthly)}<br>청약 ${manwon(x.housingMonthly)} · 연금 ${manwon(x.pensionMonthly)}<br>일반 ${manwon(x.generalMonthly)}`; };
      const diff = p => {
        if (p.isBase) return "";
        const d = Number(p.diffFromBase), cls = !p.meaningful ? "tx-flat" : d > 0 ? "tx-up" : "tx-down";
        return `<br><span class="${cls}">${p.meaningful ? `${d > 0 ? "+" : ""}${d.toFixed(1)}%p` : "거의 차이 없음"}</span>`;
      };
      body += `<div class="tx-tbl-wrap"><table class="tx-tbl"><thead><tr><th>계획</th><th>월 배분</th><th>목표 달성 가능성</th><th>세금으로 본 순이득</th><th>청년미래적금 중도해지</th><th>목표 시점 모은 돈 (중간값)</th><th></th></tr></thead><tbody>
        ${rows.map((p, i) => `<tr class="${p.isBase ? "base" : ""}"><td><b>${esc(p.name)}</b></td><td>${al(p)}</td><td><b>${pctTxt(p.probability)}</b>${diff(p)}</td>
          <td>${manwon(p.taxNetBenefit)}</td><td>${p.earlyTerminationProbability == null ? "대상 아님" : pctTxt(p.earlyTerminationProbability)}</td><td>${manwon(p.medianAtGoal)}</td>
          <td>${p.isBase ? "" : `<button class="tx-btn" data-act="apply" data-i="${i}">이 배분으로</button>`}</td></tr>`).join("")}
        </tbody></table></div>
        <p class="tx-hint">차이가 2%p보다 작거나 우연일 수 있으면 '거의 차이 없음'으로 보여요. '이 배분으로'를 누르면 그 배분으로 결과 전체를 다시 계산해요.</p>`;
    }
    return body;
  }
  async function runCompare() {
    const r = last.result, input = last.input; if (!r || !input) return;
    const save = input.savingPlan.monthlySaving, polMax = Math.min(save, 500000);
    const same = (x, y) => x.policyMonthly === y.policyMonthly && x.housingMonthly === y.housingMonthly && x.pensionMonthly === y.pensionMonthly;
    const base = r.tax.allocation, plans = [];
    if (!same(last.draft, base)) plans.push({ name: "바꾼 배분", adjustments: { ...last.draft } });
    // 비교용 예시 배분: 지금 계획·바꾼 배분과 같은 것은 빼고 최대 3개
    const presets = [
      { name: "청년미래·일반 적금만", adjustments: { policyMonthly: polMax, housingMonthly: 0, pensionMonthly: 0 } },
      { name: "청년미래 + 연금저축", adjustments: { policyMonthly: polMax, housingMonthly: 0, pensionMonthly: Math.min(Math.max(save - polMax, 0), 500000) } },
      { name: "청약·연금저축 10만 원씩", adjustments: { policyMonthly: Math.max(Math.min(save, 500000) - 200000, 0), housingMonthly: 100000, pensionMonthly: 100000 } },
    ];
    for (const pr of presets) if (plans.length < 3 && !same(pr.adjustments, base) && !plans.some(x => same(x.adjustments, pr.adjustments))
      && pr.adjustments.policyMonthly + pr.adjustments.housingMonthly + pr.adjustments.pensionMonthly <= save) plans.push(pr);
    if (!plans.length) return;
    last.busy = true; last.error = null; renderResult(true);
    try {
      const res = await rawFetch("/api/compare", { method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json" }, body: JSON.stringify({ input, plans }) });
      const j = await res.json();
      if (!res.ok || !j.isSuccess) throw new Error(j.message || "비교하지 못했어요");
      last.compare = j.result;
    } catch (e) { last.error = `비교하지 못했어요: ${e.message}`; }
    last.busy = false; renderResult(true);
  }

  function applyAlloc(p) {
    st.policyMonthly = p.policyMonthly; st.housingMonthly = p.housingMonthly; st.pensionMonthly = p.pensionMonthly; save();
    location.reload();   // 앱이 결과를 다시 계산할 때 새 배분이 요청에 들어감
  }
  async function runRecommend() {
    last.busy = true; last.recBusy = true; last.error = null; renderResult(true);
    try { last.rec = await api("/api/allocations/recommend", { input: last.input }); }
    catch (e) { last.error = `배분을 찾지 못했어요: ${e.message}`; }
    last.busy = false; last.recBusy = false; renderResult(true);
  }

  // ───────── 결과: 가능성 범위 ─────────
  const rb = { busy: false, progress: 0, result: null, error: null };
  function robustSection(r) {
    const p = Number(r.goal.probability);
    let body = `<h2 class="tx-h">이 숫자는 얼마나 믿을 수 있나요?</h2>
      <p class="tx-sub">물가·연봉·실직·급한 지출처럼 근거가 B~D 등급인 가정값을 60가지로 바꿔, 매번 미래 2,000개를 다시 계산해요. 10~40초쯤 걸려요.</p>`;
    if (rb.result) {
      const q = rb.result.probability, lo = Number(q.low), hi = Number(q.high), md = Number(q.median);
      body += `<div class="tx-range"><div class="band" style="left:${lo}%;width:${Math.max(hi - lo, .5)}%"></div>
          <div class="mk" style="left:${p}%"><span>지금 ${p.toFixed(1)}%</span></div></div>
        <div class="tx-scale"><span>0%</span><span>50%</span><span>100%</span></div>
        <p style="margin:.75rem 0 0;font-weight:600">${esc(rb.result.message)}</p>
        <p class="tx-hint">60번의 가운데 값 ${md.toFixed(1)}%, 95% 범위 ${lo.toFixed(1)}%~${hi.toFixed(1)}%. 파란 띠가 이 범위예요.</p>`;
    } else if (rb.busy) {
      body += `<div class="tx-bar"><i style="width:${rb.progress}%"></i></div><p class="tx-hint">계산 중… ${rb.progress}%</p>`;
    } else {
      body += `<div class="tx-btns"><button class="tx-btn pri" data-act="robust">가능성 범위 계산</button></div>`;
    }
    if (rb.error) body += `<p class="tx-err">${esc(rb.error)}</p>`;
    return body;
  }
  async function startRobust() {
    rb.busy = true; rb.progress = 0; rb.error = null; renderResult(true);
    try { rb.result = await runRobust([], p => { rb.progress = p; renderResult(true); }); }
    catch (e) { rb.error = `계산하지 못했어요: ${e.message}`; }
    rb.busy = false; renderResult(true);
  }

  // ───────── 결과: 직접 바꿔 보기 (계획 바꿔 보기) ─────────
  const pl = { open: false, draft: null, preview: null, basket: [], compare: null, robust: null, busy: "", progress: 0, error: null };
  const totalOf = g => (g.inputMode === "DETAIL" ? g.items.reduce((s, x) => s + (x.amount || 0), 0) : g.total);
  function baseValues() {
    const i = last.input;
    return { monthlyFixedExpense: totalOf(i.expenses.fixed), monthlyVariableExpense: totalOf(i.expenses.variable), monthlySaving: i.savingPlan.monthlySaving,
      emergencyFirstAmount: i.savingPlan.emergencyFirstAmount || 0, breakThreshold: i.emergency && i.emergency.breakThreshold != null ? i.emergency.breakThreshold : 500000,
      goalTargetAmount: i.goal.targetAmount, goalTargetDate: i.goal.targetDate };
  }
  const PLAN_FIELDS = [
    ["monthlyVariableExpense", "월 변동지출", "식비·쇼핑·여가"], ["monthlyFixedExpense", "월 고정지출", "월세·통신비·보험료"], ["monthlySaving", "월 저축액", "적금·청약·연금에 넣는 돈 합계"],
    ["breakThreshold", "버틸 금액", "마이너스가 이만큼 넘으면 적금을 깨요"], ["goalTargetAmount", "목표 금액", ""],
  ];
  const SHORT = { monthlyVariableExpense: "변동지출", monthlyFixedExpense: "고정지출", monthlySaving: "저축", breakThreshold: "버틸 금액", goalTargetAmount: "목표", goalTargetDate: "목표 시점", emergencyFirstAmount: "비상금 먼저" };
  function changes() {
    const b = baseValues(), adj = {};
    for (const k of Object.keys(b)) if (pl.draft[k] !== b[k] && pl.draft[k] != null && pl.draft[k] !== "") adj[k] = pl.draft[k];
    return adj;
  }
  const describe = adj => Object.entries(adj).map(([k, v]) => `${SHORT[k]} ${k === "goalTargetDate" ? v.replace("-", "년 ") + "월" : manwon(v)}`).join(" · ");
  function planSection() {
    const b = baseValues(); if (!pl.draft) pl.draft = { ...b };
    const adj = changes(), n = Object.keys(adj).length;
    const field = ([k, label, hint]) => `<div class="tx-f"><label for="tx-p-${k}">${label}</label><div class="tx-in"><input id="tx-p-${k}" data-pk="${k}" inputmode="numeric" type="text" value="${comma(pl.draft[k])}"><span>원</span></div>
      <p class="tx-hint">지금 ${manwon(b[k])}${hint ? " · " + hint : ""}</p></div>`;
    let body = `<h2 class="tx-h">직접 바꿔 보기</h2>
      <p class="tx-sub">값을 바꾸면 같은 미래 2,000개로 다시 계산해 지금 계획과 나란히 보여 줘요. 마음에 드는 계획은 담아 두고 최대 3개까지 함께 비교해요.</p>
      <div class="tx-alloc">${PLAN_FIELDS.map(field).join("")}
        <div class="tx-f"><label for="tx-p-goalTargetDate">목표 시점</label><input id="tx-p-goalTargetDate" data-pk="goalTargetDate" type="month" class="tx-sel" value="${pl.draft.goalTargetDate}"><p class="tx-hint">지금 ${b.goalTargetDate}</p></div>
        <div class="tx-f"><label for="tx-p-emergencyFirstAmount">비상금 먼저 모으기</label><select id="tx-p-emergencyFirstAmount" data-pk="emergencyFirstAmount" class="tx-sel">
          ${[0, 1000000, 2000000, 3000000, 5000000].map(v => `<option value="${v}" ${Number(pl.draft.emergencyFirstAmount) === v ? "selected" : ""}>${v ? manwon(v) + " 모은 뒤 적금" : "바로 적금 시작"}</option>`).join("")}</select>
          <p class="tx-hint">지금 ${b.emergencyFirstAmount ? manwon(b.emergencyFirstAmount) : "바로 시작"}</p></div>
      </div>
      <div class="tx-btns">
        <button class="tx-btn pri" data-pa="preview" ${n && !pl.busy ? "" : "disabled"}>${pl.busy === "preview" ? "계산 중…" : "바꾼 계획 미리보기"}</button>
        <button class="tx-btn" data-pa="add" ${n && pl.basket.length < 3 && !pl.busy ? "" : "disabled"}>비교에 담기 (${pl.basket.length}/3)</button>
        <button class="tx-btn" data-pa="reset" ${pl.busy ? "disabled" : ""}>지금 값으로 되돌리기</button>
      </div>`;
    if (pl.preview) {
      const pv = pl.preview, sig = pv.significance, d = Number(pv.table[0].diff);
      const fmt = (m, v) => v == null ? "—" : /PROBABILITY|TERMINATION/.test(m) ? pctTxt(v) : manwon(v);
      const lab = { PROBABILITY: "목표 달성 가능성", DEBT_PROBABILITY: "빚질 가능성", WORST_CASE_AT_GOAL: "최악의 경우 (하위 5%)", EARLY_TERMINATION: "청년미래적금 중도해지", EXPECTED_SUBSIDY: "정부 지원금" };
      body += `<div class="tx-grid"><div class="tx-card"><div class="l">지금 계획</div><div class="v">${pctTxt(pv.current.probability)}</div><div class="d">70%가 되는 때 ${pv.current.reach70Date || "10년 안에 없음"}</div></div>
        <div class="tx-card ${sig.meaningful ? (d > 0 ? "good" : "bad") : ""}"><div class="l">바꾼 계획 (${esc(describe(pl.previewAdj))})</div><div class="v">${pctTxt(pv.adjusted.probability)}</div>
          <div class="d">${sig.meaningful ? `${d > 0 ? "+" : ""}${d.toFixed(1)}%p` : "거의 차이 없음"} · 70%가 되는 때 ${pv.adjusted.reach70Date || "10년 안에 없음"}</div></div></div>
        <div class="tx-tbl-wrap"><table class="tx-tbl" style="min-width:480px"><thead><tr><th>항목</th><th>지금</th><th>바꾼 계획</th><th>차이</th></tr></thead><tbody>
        ${pv.table.map(t => `<tr><td>${lab[t.metric]}</td><td>${fmt(t.metric, t.current)}</td><td>${fmt(t.metric, t.adjusted)}</td><td>${t.diff == null ? "—" : /PROBABILITY|TERMINATION/.test(t.metric) ? `${Number(t.diff) > 0 ? "+" : ""}${Number(t.diff).toFixed(1)}%p` : manwon(t.diff)}</td></tr>`).join("")}
        </tbody></table></div>`;
    }
    if (pl.basket.length) {
      body += `<div class="tx-basket">${pl.basket.map((x, i) => `<span class="tx-chip">${esc(x.name)}<button data-pa="remove" data-i="${i}" aria-label="빼기">✕</button></span>`).join("")}</div>
        <div class="tx-btns"><button class="tx-btn pri" data-pa="compare" ${pl.busy ? "disabled" : ""}>${pl.busy === "compare" ? "비교 중…" : "담은 계획 비교"}</button>
        <button class="tx-btn" data-pa="robust" ${pl.busy ? "disabled" : ""}>${pl.busy === "robust" ? `가정을 바꿔 보는 중… ${pl.progress}%` : "가정을 바꿔도 결론이 같은지 보기"}</button></div>`;
    }
    if (pl.compare) {
      const rows = pl.compare.plans;
      body += `<div class="tx-tbl-wrap"><table class="tx-tbl"><thead><tr><th>계획</th><th>목표 달성 가능성</th><th>빚질 가능성</th><th>최악의 경우</th><th>청년미래 중도해지</th><th>정부 지원금</th><th>세금 순이득</th></tr></thead><tbody>
        ${rows.map(p => `<tr class="${p.isBase ? "base" : ""}"><td><b>${esc(p.name)}</b></td><td><b>${pctTxt(p.probability)}</b>${p.isBase ? "" : `<br><span class="${!p.meaningful ? "tx-flat" : Number(p.diffFromBase) > 0 ? "tx-up" : "tx-down"}">${p.meaningful ? `${Number(p.diffFromBase) > 0 ? "+" : ""}${Number(p.diffFromBase).toFixed(1)}%p` : "거의 차이 없음"}</span>`}</td>
          <td>${pctTxt(p.debtProbability)}</td><td>${manwon(p.worstCaseAtGoal)}</td><td>${p.earlyTerminationProbability == null ? "대상 아님" : pctTxt(p.earlyTerminationProbability)}</td>
          <td>${p.expectedSubsidy == null ? "—" : manwon(p.expectedSubsidy)}</td><td>${manwon(p.taxNetBenefit)}</td></tr>`).join("")}</tbody></table></div>`;
    }
    if (pl.robust) {
      body += `<ul class="tx-notes">${pl.robust.plans.map(x => `<li><b>${esc(x.name)}</b>: 가정을 60가지로 바꿔도 지금 계획보다 ${Number(x.diffMedian) >= 0 ? "나은" : "못한"} 결론이 <b>60번 중 ${x.conclusionKept}번</b> 유지돼요 (차이 가운데 ${Number(x.diffMedian).toFixed(1)}%p, 95% 범위 ${Number(x.diffLow).toFixed(1)}~${Number(x.diffHigh).toFixed(1)}%p).</li>`).join("")}</ul>`;
    }
    if (pl.error) body += `<p class="tx-err">${esc(pl.error)}</p>`;
    return body;
  }
  async function planAction(a, i) {
    pl.error = null;
    if (a === "reset") { pl.draft = null; pl.preview = null; renderResult(true); return; }
    if (a === "remove") { pl.basket.splice(i, 1); pl.compare = null; pl.robust = null; renderResult(true); return; }
    if (a === "add") { const adj = changes(); pl.basket.push({ name: describe(adj), adjustments: adj }); pl.compare = null; pl.robust = null; renderResult(true); return; }
    const plans = pl.basket.map(x => ({ name: x.name, adjustments: x.adjustments }));
    pl.busy = a; pl.progress = 0; renderResult(true);
    try {
      if (a === "preview") { pl.previewAdj = changes(); pl.preview = await api("/api/simulate/preview", { input: last.input, adjustments: pl.previewAdj }); }
      else if (a === "compare") pl.compare = await api("/api/compare", { input: last.input, plans });
      else if (a === "robust") pl.robust = await runRobust(plans, p => { pl.progress = p; renderResult(true); });
    } catch (e) { pl.error = `계산하지 못했어요: ${e.message}`; }
    pl.busy = ""; renderResult(true);
  }

  // ───────── 리포트 (인쇄·PDF 저장) ─────────
  async function openReport(btn) {
    btn.disabled = true; const old = btn.textContent; btn.textContent = "리포트 만드는 중…";
    try {
      const plans = pl.basket.map(x => ({ name: x.name, adjustments: x.adjustments }));
      const r = await api("/api/reports", { input: last.input, plans, seed: null });
      showReport(r);
    } catch (e) { alertBox(`리포트를 만들지 못했어요: ${e.message}`); }
    btn.disabled = false; btn.textContent = old;
  }
  function alertBox(msg) { const p = document.createElement("p"); p.className = "tx-err"; p.textContent = msg; document.querySelector('[data-tx="reportbtn"]').after(p); setTimeout(() => p.remove(), 6000); }
  function showReport(r) {
    document.querySelector(".tx-report") && document.querySelector(".tx-report").remove();
    const s = r.inputSummary, g = r.goal, t = r.tax, ps = r.risks.policySaving, dbt = r.risks.debt, cs = r.causes;
    const tr = (cells, th) => `<tr>${cells.map(c => `<${th ? "th" : "td"}>${c}</${th ? "th" : "td"}>`).join("")}</tr>`;
    const el = document.createElement("div"); el.className = "tx-report";
    el.innerHTML = `<div class="bar"><button class="tx-btn pri" data-r="print">인쇄·PDF로 저장</button><button class="tx-btn" data-r="close">닫기</button></div>
      <div class="paper">
        <h1>사회초년생 재무 시뮬레이션 리포트</h1>
        <p style="color:#5b616b;margin:.25rem 0 0">만든 시각 ${esc(r.generatedAt.replace("T", " "))} · 기준 연월 ${esc(r.startMonth)} · 모델 ${esc(r.modelVersion)} · 미래 2,000개 (seed ${r.seed})</p>
        <p style="font-size:1.0625rem;font-weight:700;margin:1rem 0 0">${esc(r.headline)}</p>
        <h2>입력 요약</h2><table>${tr(["월 실수령액", "고정지출", "변동지출", "대출 상환(첫 달)", "매달 남는 돈", "월 저축액"], true)}
          ${tr([s.monthlyNetIncome, s.fixedTotal, s.variableTotal, s.monthlyLoanPayment, s.monthlySurplus, s.monthlySaving].map(v => v == null ? "—" : manwon(v)))}</table>
        <h2>목표</h2><table>${tr(["목표 금액", "달성 가능성", "오차", "미래의 절반이 닿는 때", "70%가 되는 때", "계산기 결과(목표 시점)"], true)}
          ${tr([manwon(g.targetAmount), pctTxt(g.probability), `±${g.probabilityError}%p`, g.medianReachDate || "—", g.reach70Date || "—", manwon(r.forecast.deterministicAtGoal)])}</table>
        <h2>앞으로 모을 돈</h2><table>${tr(["시점", "최악(하위 5%)", "아래 10%", "중간값", "위 10%"], true)}
          ${r.forecast.checkpoints.map(c => tr([`${c.afterMonths / 12}년 뒤`, manwon(c.p5), manwon(c.p10), manwon(c.p50), manwon(c.p90)])).join("")}</table>
        <h2>위험</h2><table>${tr(["빚질 가능성", "처음 빚지는 때(중간값)", "최악의 경우", "청년미래 중도해지", "정부 지원금"], true)}
          ${tr([pctTxt(dbt.probability), dbt.medianFirstDate || "—", manwon(dbt.worstCaseAtGoal), ps.eligible ? pctTxt(ps.earlyTerminationProbability) : "대상 아님", ps.eligible ? manwon(ps.expectedSubsidy) : "—"])}</table>
        <p>적금을 처음 깬 이유: 실직 직후 ${pctTxt(cs.unemployment)}, 실업 장기화 ${pctTxt(cs.prolongedUnemployment)}, 급한 지출 ${pctTxt(cs.emergencyExpense)}, 매달 부족 ${pctTxt(cs.chronicDeficit)}</p>
        <h2>세금 (목표 시점까지 평균)</h2><table>${tr(["매년 돌려받는 세금", "돌려받는 세금 합계", "낸 이자소득세", "청약 추징", "연금 기타소득세", "세금 순이득"], true)}
          ${tr([t.refundPerYear, t.refundTotal, t.interestTax, t.clawback, t.pensionTax, t.netBenefit].map(manwon))}</table>
        <p>월 배분: 청년미래 ${manwon(t.allocation.policyMonthly)} · 청약 ${manwon(t.allocation.housingMonthly)} · 연금저축 ${manwon(t.allocation.pensionMonthly)} · 일반 적금 ${manwon(t.allocation.generalMonthly)}</p>
        <h2>이렇게 바꿔 보세요 (상위 3개)</h2><table>${tr(["바꿀 것", "가능성", "차이"], true)}
          ${r.levers.map(l => tr([esc(l.label), pctTxt(l.probability), l.flat ? "거의 차이 없음" : `${Number(l.diff) > 0 ? "+" : ""}${Number(l.diff).toFixed(1)}%p`])).join("")}</table>
        ${r.comparison.length ? `<h2>계획 비교</h2><table>${tr(["계획", "가능성", "빚질 가능성", "최악의 경우", "세금 순이득"], true)}
          ${r.comparison.map(p => tr([esc(p.name), pctTxt(p.probability) + (p.isBase ? "" : p.meaningful ? ` (${Number(p.diffFromBase) > 0 ? "+" : ""}${Number(p.diffFromBase).toFixed(1)}%p)` : " (거의 차이 없음)"), pctTxt(p.debtProbability), manwon(p.worstCaseAtGoal), manwon(p.taxNetBenefit)])).join("")}</table>` : ""}
        <h2>근거</h2><table>${tr(["항목", "값", "등급", "출처", "자료 기간"], true)}
          ${r.parameters.map(m => tr([esc(m.label), esc(m.value), esc(m.grade), esc(m.source), esc(m.asOf)])).join("")}</table>
        <p style="color:#5b616b">등급: A 법령·제도 · B 공공통계 직접 사용 · C 공공통계를 모델 형태로 변환 · D 보정 · E 가정</p>
        <p style="margin-top:1rem;font-weight:600">${esc(r.disclaimer)}</p>
      </div>`;
    document.body.appendChild(el); document.body.classList.add("tx-printing");
    el.querySelector('[data-r="print"]').addEventListener("click", () => window.print());
    el.querySelector('[data-r="close"]').addEventListener("click", () => { el.remove(); document.body.classList.remove("tx-printing"); });
  }

  // ───────── 결과 화면에 붙이기 ─────────
  function anchor() {   // '위험' 섹션
    for (const s of document.querySelectorAll("main section")) { const h = s.querySelector("h2"); if (h && h.textContent.trim() === "위험") return s; }
    return null;
  }
  function sectionByTitle(title) {
    for (const s of document.querySelectorAll("main section")) { const h = s.querySelector("h2"); if (h && h.textContent.trim() === title) return s; }
    return null;
  }
  function ensureSection(key, after) {
    let el = document.querySelector(`[data-tx="${key}"]`);
    if (!el || el.previousElementSibling !== after) { el && el.remove(); el = document.createElement("section"); el.className = "tx-sec"; el.dataset.tx = key; after.after(el); el.dataset.fresh = "1"; }
    return el;
  }
  function renderExtras(r, force) {
    // 가능성 범위: 첫 결과 카드 바로 아래
    const first = document.querySelector("main section");
    if (first) {
      const sec = ensureSection("robust", first);
      if (force || sec.dataset.fresh) { sec.innerHTML = robustSection(r); delete sec.dataset.fresh; const b = sec.querySelector('[data-act="robust"]'); if (b) b.addEventListener("click", startRobust); }
    }
    // 직접 바꿔 보기: '이렇게 바꿔 보세요' 아래
    const lev = sectionByTitle("이렇게 바꿔 보세요");
    if (lev) {
      const sec = ensureSection("plan", lev);
      sec.hidden = !pl.open;   // 처음엔 접어 두고 맨 아래 '계획 바꿔 보기'로 펼침
      if (force || sec.dataset.fresh) {
        delete sec.dataset.fresh; sec.innerHTML = planSection();
        sec.querySelectorAll("[data-pk]").forEach(inp => inp.addEventListener(inp.tagName === "SELECT" || inp.type === "month" ? "change" : "input", () => {
          const k = inp.dataset.pk;
          pl.draft[k] = k === "goalTargetDate" ? inp.value : (parseWon(inp.value) ?? 0);
          const pos = inp.selectionStart; renderResult(true);
          const n = document.getElementById(inp.id); if (n && inp.tagName === "INPUT" && inp.type !== "month") { n.focus(); try { n.setSelectionRange(pos, pos); } catch (e) { /* 무시 */ } }
        }));
        sec.querySelectorAll("[data-pa]").forEach(b => b.addEventListener("click", () => planAction(b.dataset.pa, Number(b.dataset.i))));
      }
    }
    // 아래쪽 '계획 바꿔 보기 (11/6)' 버튼 → '직접 바꿔 보기' 펼치기·접기
    for (const b of document.querySelectorAll("main button")) {
      if (!b.dataset.tx && /^계획 바꿔 보기/.test(b.textContent.trim()) && !b.dataset.txDone) {
        b.dataset.txDone = "1"; b.hidden = true;
        const nb = document.createElement("button"); nb.className = "tx-btn pri"; nb.dataset.tx = "planbtn";
        nb.addEventListener("click", () => {
          pl.open = !pl.open; renderResult(true);
          const s = document.querySelector('[data-tx="plan"]');
          if (pl.open && s) s.scrollIntoView({ behavior: "smooth", block: "start" });
        });
        b.after(nb);
      }
    }
    const pb = document.querySelector('[data-tx="planbtn"]');
    if (pb) { pb.textContent = pl.open ? "계획 바꿔 보기 접기" : "계획 바꿔 보기"; pb.setAttribute("aria-expanded", String(pl.open)); }
    // 위쪽 버튼 줄에 '리포트 저장'
    for (const b of document.querySelectorAll("main button, main a")) {
      if (b.textContent.trim() === "새로 시작" && !document.querySelector('[data-tx="reportbtn"]')) {
        const rb2 = document.createElement("button"); rb2.className = "tx-btn"; rb2.textContent = "리포트 저장"; rb2.dataset.tx = "reportbtn";
        rb2.addEventListener("click", () => openReport(rb2)); b.before(rb2); break;
      }
    }
  }
  function renderResult(force) {
    const r = last.result, at = anchor();
    if (!r || !r.tax || !last.input || !at || isExample()) return;
    renderExtras(r, force);
    let tax = document.querySelector('[data-tx="tax"]'), al = document.querySelector('[data-tx="alloc"]');
    if (!tax || tax.previousElementSibling !== at) {
      tax && tax.remove(); al && al.remove();
      tax = document.createElement("section"); tax.className = "tx-sec"; tax.dataset.tx = "tax";
      al = document.createElement("section"); al.className = "tx-sec"; al.dataset.tx = "alloc";
      at.after(tax); tax.after(al); force = true; last.draft = null;
    }
    if (!force && tax.dataset.for === r.headline) return;
    tax.dataset.for = r.headline;
    tax.innerHTML = taxSection(r);
    al.innerHTML = allocSection(r);
    al.querySelectorAll("input[data-k]").forEach(inp => inp.addEventListener("input", () => {
      const v = parseWon(inp.value) || 0, k = inp.dataset.k; last.draft[k] = k === "policyMonthly" ? Math.min(v, 500000) : v;
      const pos = inp.selectionStart; renderResult(true); const n = document.getElementById(inp.id); if (n) { n.focus(); try { n.setSelectionRange(pos, pos); } catch (e) { /* 무시 */ } }
    }));
    al.querySelectorAll("button[data-act]").forEach(b => b.addEventListener("click", () => {
      const act = b.dataset.act;
      if (act === "compare") runCompare();
      else if (act === "recommend") runRecommend();
      else if (act === "apply-rec") { applyAlloc(last.rec[b.dataset.k].allocation); }
      else if (act === "reset") { last.draft = null; last.compare = null; renderResult(true); }
      else if (act === "apply") {
        applyAlloc(last.compare.plans[Number(b.dataset.i)].allocation);
      }
    }));
  }

  // ───────── 화면 바뀔 때마다 ─────────
  let timer = null;
  function schedule() { clearTimeout(timer); timer = setTimeout(() => { if (/#basic/.test(location.hash)) ensureInputs(); renderResult(false); }, 60); }
  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  window.addEventListener("hashchange", schedule);
  // '새로 시작'으로 앱 저장값이 지워지면 이 값도 지운다
  let hadProgress = false;
  setInterval(() => {
    try {
      const has = !!localStorage.getItem("simProgress.v1");
      if (hadProgress && !has && Object.keys(st).length) { st = {}; save(); }
      hadProgress = has;
    } catch (e) { /* 무시 */ }
  }, 1000);
})();
