import json,glob,sys
E=json.load(open('e_avg.json'))
P={ "1조 기준(지금 가중치)":[1.4,1.3,1.2,1.1,0],
    "2조 LLM 활용 중시":[2.0,1.2,1.0,1.0,0],
    "3조 사용자 도움 중시":[1.0,0.8,1.4,2.0,0],
    "4조 필요성 중시(없어도 되면 크게 감점)":[1.0,0.8,2.0,1.4,0],
    "5조 차별화 중시":[1.0,2.0,1.0,1.0,0] }
res={}
for f in sorted(glob.glob('result_*.json')):
    d=json.load(open(f)); c=d["candidate"]; res[c]=d
def run(W):
    out=[]
    for c,d in res.items():
        t=lambda v,e: W[0]*v["a"]+W[1]*v["b"]-W[2]*v["c"]+W[3]*v["d"]+W[4]*e
        w=[]; 
        for m in d["matches"]:
            o=m["opponent"]; sc=m["scores"]; tc=t(sc["candidate"],E.get(c,sc["candidate"]["e"])); to=t(sc["opponent"],E.get(o,sc["opponent"]["e"]))
            w.append((o,tc>to,tc-to))
        out.append((sum(x[1] for x in w), min([x[2] for x in w if not x[1]] or [0]), d["name"], w))
    return sorted(out,key=lambda r:(-r[0], -sum(x[2] for x in r[3])))
if __name__=="__main__":
    for name,W in P.items():
        print("==",name,W)
        for n,_,nm,w in run(W)[:7]:
            print(f"  {n}승 {nm}: "+" ".join(f"{o}{'승' if ok else '패'}({d:+.1f})" for o,ok,d in w))
