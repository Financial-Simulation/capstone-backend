import numpy as np, copy
from kernel import *
from validate_input import validate
ok=[]
# 1 해석해 (급전·실직 없음 → 변화 없어야 함)
T=60; p0=copy.deepcopy(PARAMS); p0["inflation"].update(mu_y=0,last=0); p0["wage"].update(macro_mu=0,exp_prem=[0,0,0,0]); p0["liquid"]["annual_rate"]=0
pr=dict(age=27,exp_years=2,net_income=250,fixed=95,variable=95,cash0=200,save=60,employment="정규",thr=50,invest0=300,invest_monthly=5)
r=simulate(pr,p0,zero_noise(T),T,collect=True); rP,rG=POLICY["rate"]/12,GENERAL["rate"]/12
CGx=0; IGx=0; hx=0
for t in range(T):
    CGx+= 10 if t<36 else 60
    IGx+=CGx*rG; hx+=1
    if hx>=12: CGx+=IGx*(1-GENERAL["tax"]); IGx=0; hx=0
closed=200+T*(250-190-60-5)+sum(53*(1+rP*(36-t)) for t in range(36))+CGx+IGx+300+5*T   # 입금일~만기 전일 단리, 일반 적금 1년 세후 재예치
ok.append(("해석해", abs(r['final'][0]-closed)<1e-6, f"{r['final'][0]-closed:.1e}"))
# 2 실업급여 수급 요건: 비자발 실직이 입사 2개월째면 미지급, 8개월째면 지급
def forced(month, exp0):
    nz=zero_noise(24); nz["u_onset"][:,month]=0.0; nz["u_invol"][:,month]=0.0; nz["u_dur"][:,month]=0.5
    q=dict(pr,exp_years=exp0,save=0,invest0=0,invest_monthly=0)
    a=simulate(q,p0,nz,24,collect=True)["saved"][0,-1]; b=simulate(q,p0,zero_noise(24),24,collect=True)["saved"][0,-1]
    return a,b
a2,_=forced(2,0.0); a8,_=forced(8,0.0)
ok.append(("실업급여 180일 요건", a8-a2>100, f"입사 2개월 실직 {a2:.0f} vs 8개월 실직 {a8:.0f}"))
# 3 자격 경계 (FR-03): 34세/35세, 총급여 7,500만 경계
net_at=7500*0.88/12
for nm,q,exp in [("34세",dict(pr,age=34),True),("35세",dict(pr,age=35),False),("총급여 7,500만",dict(pr,net_income=net_at),True),("총급여 7,501만",dict(pr,net_income=net_at*7501/7500),False)]:
    e=simulate(q,PARAMS,make_noise(2,12,1),12)["eligible"][0]; ok.append((f"자격 경계 {nm}", bool(e)==exp, f"{bool(e)}"))
# 4 불변식 (인구 3,000명)
rng=np.random.default_rng(3); N=3000
pop=dict(age=rng.integers(19,40,N).astype(float),exp_years=rng.uniform(0,10,N),net_income=rng.uniform(150,450,N),fixed=rng.uniform(40,150,N),
         variable=rng.uniform(30,130,N),cash0=rng.uniform(0,2000,N),save=rng.choice([0.,20,50,80],N),employment=rng.choice(["정규","계약"],N),
         thr=rng.choice([0.,50,200],N),invest0=rng.choice([0.,500],N),loans=[{"balance":1200,"rate":0.019,"months":96}],
         events=[{"t":12,"kind":"move","deposit":1000,"delta":-20},{"t":30,"kind":"income","pct":0.1,"gap":2}])
res=simulate(pop,PARAMS,make_noise(N,120,5,antithetic=False),120,collect=True); s=res["saved"]; f=res["flags"]; bad=[]
if not np.isfinite(s).all(): bad.append("nan")
if not np.allclose(s[:,0],pop["cash0"]+pop["invest0"]): bad.append("init")
if np.any((pop["save"]==0)&(f["brk1_gen"]|f["brkG"])): bad.append("nosave")
if np.any(~res["eligible"]&(f["brk1_sp"]|f["brk1_gen"])): bad.append("inelig")
c1=f["c1_sp"].astype(int)+f["c1_unemp"]+f["c1_shock"]+f["c1_deficit"]
if np.any(c1!=(res["first_brk_t"]>=0)): bad.append("cause-sum")
if np.any(f["rejoin"]&~(f["brk1_sp"]|f["brk1_gen"])): bad.append("rejoin")
if np.any(res["match_paid"]>res["contrib"]*0.06+1e-6*0+res["contrib"]*0.01): bad.append("match>6%+이자")
ok.append(("불변식 3,000명", not bad, str(bad or "위반 0")))
# 5 중도해지 이율 표
for h,exp in [(2,0.001/0.05),(4,max(0.05*0.5*4/36,0.0015)/0.05),(12,0.05*0.9*12/36/0.05)]:
    ok.append((f"중도해지 {h}개월", abs(early_interest_ratio(POLICY,np.array([h]))[0]-exp)<1e-12, ""))
for nm,b,d in ok: print(f"[{'통과' if b else '실패'}] {nm}  {d}")
print(f"\n{sum(b for _,b,_ in ok)}/{len(ok)}")
