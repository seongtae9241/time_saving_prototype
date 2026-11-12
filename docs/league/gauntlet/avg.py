import multi
def run(W):
    t=lambda v: W[0]*v["a"]+W[1]*v["b"]-W[2]*v["c"]+W[3]*v["d"]
    out=[]
    for c,d in multi.res.items():
        tc=t(d["matches"][0]["scores"]["candidate"]); to=sum(t(m["scores"]["opponent"]) for m in d["matches"])/4
        out.append((tc-to,d["name"],tc,to))
    return sorted(out,reverse=True)
if __name__=="__main__":
    for name,W in multi.P.items():
        print("==",name,W[:4])
        for diff,nm,tc,to in run(W): print(f"  {'생존' if diff>0 else '탈락'} {nm}: {tc:.1f} 대 평균 {to:.1f} ({diff:+.1f})")
