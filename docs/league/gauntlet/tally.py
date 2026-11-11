import json,sys
W=[1.4,1.3,1.2,1.1,1.0]
def t(v): return W[0]*v["a"]+W[1]*v["b"]-W[2]*v["c"]+W[3]*v["d"]+W[4]*v["e"]
for f in sys.argv[1:]:
    d=json.load(open(f)); c=d["candidate"]; wins=0; out=[]
    for m in d["matches"]:
        o=m["opponent"]; tc,to=t(m["scores"]["candidate"]),t(m["scores"]["opponent"])
        w=tc>to; wins+=w; out.append(f"{o}:{'승' if w else '패'}({tc:.1f}/{to:.1f},pick={m['pick']})")
    sc=d["matches"][0]["scores"]["candidate"]
    print(f"{c} [{d.get('name','')}] {wins}/4 {'생존' if wins>=3 else '탈락'} | "+" ".join(out))
