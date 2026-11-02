const E=require("./engine.js"); const G=require("./golden.json");
const nzp=(p,T)=>({z1:G.noise.z1[p],z2:G.noise.z2[p],u_onset:G.noise.u_onset[p],u_dur:G.noise.u_dur[p],u_invol:G.noise.u_invol[p],u_shock:G.noise.u_shock[p],u_amt:G.noise.u_amt[p]});
let worst=0, ok=true;
for(const c of G.cases){
  const pol={...E.POLICY,rejoin:c.opt.rejoin===undefined?true:c.opt.rejoin};
  const r=E.simulate(c.pr,E.PARAMS,nzp,G.N,G.T,{collect:true,bufferFirst:c.opt.bufferFirst||0,policy:pol});
  const d=E.simulate(c.pr,E.PARAMS,E.zeroNoise(),1,G.T,{collect:true,bufferFirst:c.opt.bufferFirst||0,policy:pol});
  let md=0; for(let p=0;p<G.N;p++) for(let t=0;t<=G.T;t++){ const a=r.saved[p*(G.T+1)+t], b=c.saved[p][t]; md=Math.max(md,Math.abs(a-b)/Math.max(1,Math.abs(b))); }
  let dd=0; for(let t=0;t<=G.T;t++) dd=Math.max(dd,Math.abs(d.saved[t]-c.det[t])/Math.max(1,Math.abs(c.det[t])));
  let fm=0; for(const k in c.flags) for(let p=0;p<G.N;p++) if(r.flags[k][p]!==c.flags[k][p]) fm++;
  let bm=0, mm=0; for(let p=0;p<G.N;p++){ if(r.firstBrk[p]!==c.firstBrk[p]) bm++; mm=Math.max(mm,Math.abs(r.matchPaid[p]-c.match[p])); }
  worst=Math.max(worst,md,dd); const pass=md<1e-9&&dd<1e-9&&fm===0&&bm===0&&mm<1e-9; ok=ok&&pass;
  console.log(`${pass?"통과":"실패"} ${c.name.padEnd(14)} 경로 상대오차 ${md.toExponential(1)} · 확정 ${dd.toExponential(1)} · 플래그 불일치 ${fm} · 첫 해지 시점 불일치 ${bm} · 지원금 오차 ${mm.toExponential(1)}`);
}
console.log(ok?`\n${G.cases.length}개 프로필 × ${G.N}경로 × ${G.T}개월 전부 일치 (최대 상대오차 ${worst.toExponential(1)})`:"\n불일치 있음");
