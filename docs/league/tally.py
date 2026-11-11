import json,sys,collections
N={"F01":"전화 창구","F02":"주간 돌봄 편성표","F03":"돌봄 설계","F04":"봉사 개통","F05":"지금 물어보기","F06":"기관 이사 들이기","F07":"정책 실험실","F08":"분쟁 조정실","F09":"여기 눌러요","F10":"다녀간 뒤 한마디","F11":"말로 한 바퀴","F12":"같이 가요","F13":"부모님 모셔 오기","F14":"대타 잇기","F15":"안심 문답","F16":"단체 봉사 받기"}
ms=[]
ms=json.load(open("results.json"))
assert len(ms)==120, len(ms)
pairs={frozenset((m["first"],m["second"])) for m in ms}; assert len(pairs)==120
def run(W,show=False):
    win=collections.Counter(); tot=collections.Counter(); h2h={}; firstwin=0; disagree=0; ties=0
    avg=collections.defaultdict(lambda: collections.Counter())
    for m in ms:
        a,b=m["first"],m["second"]; s=m["scores"]
        def t(x): v=s[x]; return W[0]*v["a"]+W[1]*v["b"]-W[2]*v["c"]+W[3]*v["d"]+W[4]*v["e"]
        ta,tb=t(a),t(b); tot[a]+=ta; tot[b]+=tb
        for x in (a,b):
            for k in "abcde": avg[x][k]+=s[x][k]
        if abs(ta-tb)<1e-9:
            ties+=1; w=None
            for k,sg in (("a",1),("b",1),("c",-1),("d",1),("e",1)):
                if s[a][k]!=s[b][k]: w=a if sg*(s[a][k]-s[b][k])>0 else b; break
            if w is None: w=m["pick"]
        else: w=a if ta>tb else b
        if w!=m["pick"]: disagree+=1
        if w==a: firstwin+=1
        win[w]+=1; h2h[frozenset((a,b))]=(w,m["reason"],m["match"])
    order=sorted(N,key=lambda x:(-win[x],-tot[x]))
    if show:
        print("first-position wins:",firstwin,"/120  pick!=weighted:",disagree," ties:",ties)
        for i,x in enumerate(order,1):
            av=avg[x]; print(f"{i:2d} {x} {N[x]:10s} {win[x]:2d}승 {15-win[x]:2d}패  평균 a{av['a']/15:.1f} b{av['b']/15:.1f} c{av['c']/15:.1f} d{av['d']/15:.1f} e{av['e']/15:.1f}  총점{tot[x]:.0f}")
    return order,win,h2h
o,win,h2h=run([1.4,1.3,1.2,1.1,1.0],True)
print("--- 5/4/3/2/1:"); o2,w2,_=run([5,4,3,2,1]); print([(x,w2[x]) for x in o2[:6]])
print("--- equal:"); o3,w3,_=run([1,1,1,1,1]); print([(x,w3[x]) for x in o3[:6]])
print("--- by judge pick:"); c=collections.Counter(m["pick"] for m in ms); print(c.most_common(6))
top=o[:4]
for x in top[:3]:
    print("\n==",x,N[x])
    for y in N:
        if y==x: continue
        w,r,mn=h2h[frozenset((x,y))]
        if w!=x or y in top: print(("  패 vs " if w!=x else "  승 vs ")+y+" "+N[y]+": "+r)
