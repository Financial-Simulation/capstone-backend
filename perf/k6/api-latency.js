// 배포된 서버의 API 응답 시간을 잰다. 부하 테스트가 아니라 "한 사용자가 쓸 때 얼마나 걸리나"를 본다.
//   로컬:  k6 run -e BASE_URL=http://localhost:8080 perf/k6/api-latency.js
//   배포:  GitHub Actions의 Perf 워크플로가 매일 돌리고 결과를 디스코드로 보낸다.
// 무료 서버(CPU 0.1개)를 괴롭히지 않도록 사용자 1명이 순서대로 10번만 요청한다.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const ITERATIONS = Number(__ENV.ITERATIONS || 10);
const HEAVY_ITERATIONS = Number(__ENV.HEAVY_ITERATIONS || 3);
// 워크플로가 k6 전에 curl로 잰 첫 요청 시간(초). 잠든 서버가 깨어나는 시간이다
const FIRST_REQUEST_S = __ENV.FIRST_REQUEST_S;

const s1 = JSON.parse(open('../../src/main/resources/examples/S1.json'));

// 목표는 NFR-03: /simulate 1초, /compare(계획 3개)·/reverse 3초. 목표가 없는 API는 기록만 한다
const ENDPOINTS = [
  { key: 'simulate', label: 'POST /api/simulate', targetMs: 1000,
    path: '/api/simulate', body: { input: s1, seed: null } },
  { key: 'preview', label: 'POST /api/simulate/preview', targetMs: 1000,
    path: '/api/simulate/preview', body: { input: s1, adjustments: { monthlyVariableExpense: 850000 } } },
  { key: 'compare', label: 'POST /api/compare (계획 3개)', targetMs: 3000,
    path: '/api/compare', body: { input: s1, plans: [
      { name: '변동지출 10만 원 줄이기', adjustments: { monthlyVariableExpense: 850000 } },
      { name: '비상금 300만 원 먼저', adjustments: { emergencyFirstAmount: 3000000 } },
      { name: '적금 10만 원 늘리기', adjustments: { monthlySaving: 600000 } },
    ] } },
  { key: 'reverse', label: 'POST /api/reverse', targetMs: 3000,
    path: '/api/reverse', body: { input: s1, targetProbability: 70.0 } },
  { key: 'recommend', label: 'POST /api/allocations/recommend', targetMs: null, heavy: true,
    path: '/api/allocations/recommend', body: { input: s1 } },
];

const trends = Object.fromEntries(ENDPOINTS.map((e) => [e.key, new Trend(`latency_${e.key}`, true)]));

const thresholds = { http_req_failed: ['rate<0.01'] };
for (const e of ENDPOINTS) {
  if (e.targetMs) thresholds[`latency_${e.key}`] = [`p(95)<${e.targetMs}`];
}

export const options = {
  vus: 1,
  iterations: ITERATIONS,
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
};

const params = { headers: { 'Content-Type': 'application/json' }, timeout: '120s' };

export default function () {
  for (const e of ENDPOINTS) {
    if (e.heavy && __ITER >= HEAVY_ITERATIONS) continue;
    const res = http.post(`${BASE_URL}${e.path}`, JSON.stringify(e.body), { ...params, tags: { endpoint: e.key } });
    check(res, { [`${e.key} 200`]: (r) => r.status === 200 });
    trends[e.key].add(res.timings.duration);
  }
}

// ---- 결과 정리: 터미널 표, GitHub 요약(markdown), 디스코드 메시지(JSON)를 만든다 ----

const sec = (ms) => (ms == null ? '-' : `${(ms / 1000).toFixed(2)}초`);

function rows(data) {
  return ENDPOINTS.map((e) => {
    const m = data.metrics[`latency_${e.key}`];
    const v = m ? m.values : {};
    const th = m && m.thresholds ? Object.values(m.thresholds)[0] : null;
    const status = !m ? '측정 안 됨' : th == null ? '기록만' : th.ok ? '통과' : '초과';
    return { ...e, med: v.med, p95: v['p(95)'], max: v.max, status };
  });
}

export function handleSummary(data) {
  const r = rows(data);
  const failedRate = data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0;
  const allOk = r.every((x) => x.status !== '초과') && failedRate < 0.01;
  const first = FIRST_REQUEST_S ? `${Number(FIRST_REQUEST_S).toFixed(1)}초` : '-';

  const md = [
    `### API 응답 시간 — ${allOk ? '목표 안' : '목표 초과 있음'}`,
    '',
    `대상 \`${BASE_URL}\` · 사용자 1명 × ${ITERATIONS}회 · 첫 요청(잠든 서버 깨우기) ${first} · 실패율 ${(failedRate * 100).toFixed(1)}%`,
    '',
    '| API | 중앙값 | p95 | 최대 | 목표 | 결과 |',
    '|---|---|---|---|---|---|',
    ...r.map((x) => `| ${x.label} | ${sec(x.med)} | ${sec(x.p95)} | ${sec(x.max)} | ${x.targetMs ? sec(x.targetMs) : '-'} | ${x.status} |`),
    '',
  ].join('\n');

  const discord = {
    username: 'capstone-perf',
    embeds: [{
      title: `API 응답 시간 ${allOk ? '✅ 목표 안' : '⚠️ 목표 초과'}`,
      description: `${BASE_URL}\n사용자 1명 × ${ITERATIONS}회 · 첫 요청 **${first}** · 실패율 ${(failedRate * 100).toFixed(1)}%`,
      color: allOk ? 0x2ecc71 : 0xe74c3c,
      fields: r.map((x) => ({
        name: x.label,
        value: `p95 **${sec(x.p95)}** (중앙값 ${sec(x.med)})${x.targetMs ? ` / 목표 ${sec(x.targetMs)} → ${x.status}` : ''}`,
        inline: false,
      })),
      footer: { text: __ENV.RUN_URL ? `실행 기록: ${__ENV.RUN_URL}` : 'local run' },
      timestamp: new Date().toISOString(),
    }],
  };

  return {
    stdout: `\n${md}\n`,
    'perf-summary.md': md,
    'discord-payload.json': JSON.stringify(discord),
    'perf-summary.json': JSON.stringify(data),
  };
}
