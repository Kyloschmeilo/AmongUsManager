import json, base64, struct, sys
# Addressables-Katalog entschlüsseln: GUID -> Asset-Pfad (Aufruf: python catalog.py "<Among Us>/Among Us_Data")
c=json.load(open(sys.argv[1].rstrip("/")+"/StreamingAssets/aa/catalog.json"))
print(c.keys())
kd=base64.b64decode(c["m_KeyDataString"]); bd=base64.b64decode(c["m_BucketDataString"]); ed=base64.b64decode(c["m_EntryDataString"])
def key(off):
    t=kd[off]; off+=1
    if t in (0,1,4):
        n=struct.unpack_from("<i",kd,off)[0]; off+=4
        return kd[off:off+n].decode("utf-16-le" if t==1 else "ascii", "replace")
    if t==3: return struct.unpack_from("<i",kd,off)[0]
    return None
n=struct.unpack_from("<i",bd,0)[0]; off=4; buckets=[]
for i in range(n):
    doff,cnt=struct.unpack_from("<ii",bd,off); off+=8
    ents=struct.unpack_from("<%di"%cnt,bd,off); off+=4*cnt
    buckets.append((key(doff),ents))
ne=struct.unpack_from("<i",ed,0)[0]
entries=[struct.unpack_from("<7i",ed,4+28*i) for i in range(ne)]
ids=c["m_InternalIds"]
out={}
for k,ents in buckets:
    if isinstance(k,str) and len(k)==32:
        out[k]=[ids[entries[e][0]] for e in ents]
json.dump(out,open("guid.json","w"))
print(len(out), list(out.items())[:3])
