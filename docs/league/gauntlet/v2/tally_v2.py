import json,glob
W=[1.0,1.0,1.0]
def t(v): return W[0]*v["o"]+W[1]*v["n"]+W[2]*v["l"]
rows=[]
for f in sorted(glob.glob('result_*.json')):
    d=json.load(open(f)); s=d["matches"][0]["scores"]["candidate"]
    tc=t(s); ops=[t(m["scores"]["opponent"]) for m in d["matches"]]
    wins=sum(tc>o for o in ops)
    rows.append((tc-sum(ops)/4,d["candidate"],d["name"],s,tc,sum(ops)/4,wins," ".join(m["opponent"]+("승" if tc>o else "패")+f"({tc-o:+.0f})" for m,o in zip(d["matches"],ops))))
for diff,c,nm,s,tc,to,w,det in sorted(rows,reverse=True):
    print(f"{'생존' if diff>0 else '탈락'} {c} {nm}: 독창 {s['o']} 필요 {s['n']} LLM {s['l']} = {tc:.0f} 대 평균 {to:.1f} ({diff:+.1f}) | {w}승 {det}")
