"""
Liest Hüte, Visiere, Skins und Haustiere aus den Spieldateien von Among Us (für die Stats-Website).

  pip install UnityPy pillow
  python catalog.py "<Among Us>/Among Us_Data"          -> guid.json (Addressables-GUID -> Asset-Pfad)
  python extract.py "<Among Us>/Among Us_Data" <ausgabe> -> <ausgabe>/cosmetics.json + <ausgabe>/img/*.png

Ergebnis nach src/main/resources/web-cosmetics/ kopieren. Nach Spiel-Updates mit neuen Kosmetik-Bundles erneut ausführen.
"""
import UnityPy, json, os, glob, sys
D=sys.argv[1].rstrip("/")+"/"
B=D+"StreamingAssets/aa/Steam/StandaloneWindows/"
OUT=sys.argv[2]; os.makedirs(OUT+"/img",exist_ok=True)
guid=json.load(open("guid.json"))
ref=UnityPy.load(glob.glob(B+"referencedatagroup_assets_all_*.bundle")[0])
items=[]
for o in ref.objects:
    if o.type.name!="MonoBehaviour": continue
    d=o.read(check_read=False); cls=d.m_Script.read().m_ClassName
    if cls not in ("HatData","VisorData","SkinData","PetData"): continue
    t=o.read_typetree()
    g=(t.get("ViewDataRef") or t.get("PetPrefabRef") or {}).get("m_AssetGUID")
    items.append(dict(kind=cls[:-4].lower(),id=t["ProductId"],name=t["m_Name"],guid=g,inFront=t.get("InFront"),behindHats=t.get("behindHats"),blocksVisors=t.get("BlocksVisors")))
print(len(items))
files=[f for f in glob.glob(B+"*.bundle") if "initialmaps" not in f and "referencedata" not in f and "builtinshaders" not in f]
env=UnityPy.load(*files)
cont={}
for o in env.objects:
    if o.type.name=="AssetBundle":
        ab=o.read()
        for path,info in ab.m_Container: cont[path]=info.asset
print("container",len(cont))
def sprite(ptr, fname):
    if ptr is None or ptr.path_id==0: return None
    try: s=ptr.read()
    except Exception as e: print("ERR read",fname,e); return None
    img=s.image
    if img.width<=1: return None
    img.save(OUT+"/img/"+fname+".png", optimize=True)
    r=s.m_Rect
    return dict(file=fname, w=img.width, h=img.height, px=round(s.m_Pivot.x*img.width,2), py=round((1-s.m_Pivot.y)*img.height,2), ppu=s.m_PixelsToUnits)
result={}; missing=[]
import re
def safe(x): return re.sub(r'[^A-Za-z0-9_.-]','_',x)
for it in items:
    paths=guid.get(it["guid"] or "",[])
    ptr=next((cont[p] for p in paths if p in cont),None)
    if ptr is None: missing.append(it["id"]); continue
    try: v=ptr.read_typetree() if False else ptr.read()
    except Exception as e: missing.append(it["id"]); continue
    k=it["kind"]; base=safe(it["id"]); e=dict(kind=k)
    try:
        if k=="hat":
            tt=ptr.deref_parse_as_dict() if hasattr(ptr,'deref_parse_as_dict') else None
            e["front"]=sprite(v.MainImage,base); e["back"]=sprite(v.BackImage,base+"_back")
            e["match"]=bool(v.MatchPlayerColor); e["inFront"]=it["inFront"]; e["blocksVisors"]=it["blocksVisors"]
        elif k=="visor":
            e["front"]=sprite(v.IdleFrame,base); e["match"]=bool(v.MatchPlayerColor); e["behindHats"]=it["behindHats"]
        elif k=="skin":
            e["front"]=sprite(v.IdleFrame,base); e["match"]=bool(v.MatchPlayerColor)
        elif k=="pet":
            # Prefab: GameObject -> PetBehaviour.renderers[0].m_Sprite
            go=v; pb=None
            for c in go.m_Component:
                cc=c.component.read() if hasattr(c,'component') else c[1].read()
                if cc.object_reader.type.name=="MonoBehaviour":
                    try:
                        tt=cc.object_reader.read_typetree()
                        if "renderers" in tt: pb=tt; break
                    except Exception: pass
            if pb and pb["renderers"]:
                rp=pb["renderers"][0]
                rend=[c for c in [env.assets_dict] ] if False else None
            # simpler: walk transforms for first SpriteRenderer
            def first_sr(g):
                for c in g.m_Component:
                    p=c.component if hasattr(c,'component') else c[1]
                    obj=p.read()
                    t=obj.object_reader.type.name
                    if t=="SpriteRenderer" and obj.m_Sprite.path_id: return obj, None
                tr=[p for p in (c.component if hasattr(c,'component') else c[1] for c in g.m_Component) if p.read().object_reader.type.name=="Transform"][0].read()
                for ch in tr.m_Children:
                    r=first_sr(ch.read().m_GameObject.read())
                    if r: return r
                return None
            r=first_sr(go)
            e["front"]=sprite(r[0].m_Sprite,base) if r else None
            e["match"]=False
    except Exception as ex:
        print("ERR",it["id"],ex); missing.append(it["id"]); continue
    if e.get("front") or e.get("back"): result[it["id"]]=e
    else: missing.append(it["id"])
# body
sh=UnityPy.load(D+"sharedassets2.assets")
for o in sh.objects:
    if o.type.name=="Sprite":
        s=o.read()
        if s.m_Name=="idle":
            img=s.image; img.save(OUT+"/img/_body.png")
            result["_body"]=dict(kind="body",front=dict(file="_body",w=img.width,h=img.height,px=round(s.m_Pivot.x*img.width,2),py=round((1-s.m_Pivot.y)*img.height,2),ppu=s.m_PixelsToUnits)); break
json.dump(result,open(OUT+"/cosmetics.json","w"),indent=0)
print("ok",len(result),"missing",len(missing),missing[:20])

# Bodenschatten (graue Pixel unter den Füßen) entfernen – die Website zeigt Crewmates auch schwebend
from PIL import Image
im = Image.open(OUT + "/img/_body.png").convert("RGBA"); px = im.load()
for y in range(150, im.height):
    for x in range(im.width):
        r, g, b, a = px[x, y]
        if a and abs(r - g) < 10 and abs(g - b) < 10 and 30 <= r <= 80: px[x, y] = (0, 0, 0, 0)
im.save(OUT + "/img/_body.png", optimize=True)
