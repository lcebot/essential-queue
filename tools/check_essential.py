#!/usr/bin/env python3
# Applies the same rules as EssentialScanner so a photo can be checked on a computer.
# Files are only read. Usage: python3 check_essential.py IMG_xxx.jpg ...
import re,hashlib,sys
itemRe=re.compile(r'<MiContainer:Item\b(.*?)/?>',re.S); attrRe=re.compile(r'MiItem:([A-Za-z_]+)="([^"]*)"')
outRe=re.compile(rb'legend_-?\d+_[0-9a-fA-F-]{36}'); dateRe=re.compile(rb'\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}')
def header(d):
    if d[:2]!=b'\xff\xd8': return None
    exif=xmp=None; sos=-1; pos=2
    while pos<1024*1024:
        if d[pos]!=0xFF: break
        m=d[pos+1]
        if m==0xDA: sos=pos;break
        if m==0xD9: break
        L=d[pos+2]<<8|d[pos+3]
        if m==0xE1:
            body=d[pos+4:pos+2+L]
            if body.startswith(b'Exif') and exif is None: exif=body
            elif body.startswith(b'http://ns.adobe.com/xap/1.0/') and xmp is None: xmp=body
        pos+=2+L
    if xmp is None and exif is not None:
        i=exif.find(b'<x:xmpmeta')
        if i>=0: xmp=exif[i:]
    return exif,xmp,sos
def sha_ok(d,start,ln,sha): return start+ln<=len(d) and hashlib.sha256(d[start:start+ln]).hexdigest()==sha.lower()
def tail(d,it):
    off=int(it.get('Offset',0) or 0); ln=int(it.get('SHA_length',0) or 0); sha=it.get('SHA','')
    st=len(d)-off
    if off<=0 or st<0: return False
    if ln<=0 or not sha: return True
    return sha_ok(d,st,ln,sha)
def inspect(d):
    h=header(d)
    if not h: return 'Other'
    exif,xmp,sos=h
    date=dateRe.search(exif).group().decode() if exif and dateRe.search(exif) else None
    if exif and outRe.search(exif): return ('Output',date)
    if xmp is None: return 'Other'
    x=xmp.decode('utf8','replace')
    if 'MiContainer' not in x: return 'Other'
    main=prim=meta=None
    for m in itemRe.finditer(x):
        a=dict(attrRe.findall(m.group(1))); n=a.get('name',''); mi=a.get('Mime','')
        if 'SHA_Start' in a: prim=a
        elif mi.lower()=='meta/protobuf' or 'meta' in n.lower(): meta=a
        elif main is None: main=a
    if not main or not main.get('name','').startswith('Legend.'): return 'Other'
    mode={'Legend.MONOPAN':'M3','Legend.M9':'M9'}.get(main['name'],main['name'][7:])
    if prim:
        ln=int(prim.get('SHA_length',0) or 0); sha=prim.get('SHA','')
        if ln>0 and sha and (sos<0 or not sha_ok(d,sos,ln,sha)): return 'Broken'
    if main.get('UseMainImage')!='1' and not tail(d,main): return 'Broken'
    if meta and not tail(d,meta): return 'Broken'
    return ('Original',mode,date)
if __name__=='__main__':
    for f in sys.argv[1:]: print(f.split('/')[-1], inspect(open(f,'rb').read()))
