# Draws the BiteCheck logo (shield, zombie bite, check mark) with Pillow + numpy.
# Run it in an empty folder: python draw-logo.py. It writes bitecheck-<size>.png, bitecheck.ico
# and preview.png. The files in this folder and the launcher icons (icon-<size>.png) come from
# bitecheck-1024.png.
import math, random
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops
random.seed(7)
S = 2048
def bez(p0,p1,p2,p3,n=80):
    return [(((1-t)**3)*p0[0]+3*((1-t)**2)*t*p1[0]+3*(1-t)*t*t*p2[0]+t**3*p3[0],
             ((1-t)**3)*p0[1]+3*((1-t)**2)*t*p1[1]+3*(1-t)*t*t*p2[1]+t**3*p3[1]) for t in [i/n for i in range(n+1)]]
def shield_pts(scale, cx, top):
    u = lambda x,y: (cx+(x-0.5)*scale, top+y*scale)
    p=[]
    p+=bez(u(0.5,0.0),u(0.68,0.09),u(0.86,0.11),u(1.0,0.10))
    p+=bez(u(1.0,0.10),u(1.0,0.58),u(0.90,0.93),u(0.5,1.13))
    p+=bez(u(0.5,1.13),u(0.10,0.93),u(0.0,0.58),u(0.0,0.10))
    p+=bez(u(0.0,0.10),u(0.14,0.11),u(0.32,0.09),u(0.5,0.0))
    return p
def mask_poly(pts):
    m=Image.new("L",(S,S),0); ImageDraw.Draw(m).polygon(pts,fill=255); return m
def A(m): return np.asarray(m,dtype=np.float32)/255
def L(a): return Image.fromarray(np.clip(a*255,0,255).astype(np.uint8))
def shift(m,dx,dy): return ImageChops.offset(m,dx,dy)
yy,xx=np.mgrid[0:S,0:S].astype(np.float32)/S

scale=S*0.76; cx=S*0.5; top=S*0.06
outer=mask_poly(shield_pts(scale,cx,top))
rimw=0.085
inner=mask_poly(shield_pts(scale*(1-2*rimw),cx,top+scale*rimw*1.05))

# --- zombie bite: deep round jaw, ragged scalloped tooth marks ---
bx,by,br = cx+scale*0.46, top+scale*0.02, scale*0.33
def ragged_circle(x0,y0,r,j=0.06,n=90):
    return [(x0+r*(1+random.uniform(-j,j))*math.cos(2*math.pi*k/n), y0+r*(1+random.uniform(-j,j))*math.sin(2*math.pi*k/n)) for k in range(n)]
bm=Image.new("L",(S,S),0); db=ImageDraw.Draw(bm)
db.polygon(ragged_circle(bx,by,br,0.012,360),fill=255)
teeth=[(96,0.050),(118,0.058),(141,0.054),(164,0.060),(187,0.055),(209,0.048)]
tips={}
for ta,rt in teeth:
    x=bx+br*math.cos(math.radians(ta)); y=by+br*math.sin(math.radians(ta))
    db.polygon(ragged_circle(x,y,scale*rt,0.09,70),fill=255)
    R=br+scale*rt*0.9; tips[ta]=(bx+R*math.cos(math.radians(ta)), by+R*math.sin(math.radians(ta)))
bite=bm.filter(ImageFilter.GaussianBlur(1.2))
ob=A(outer)*(1-A(bite)); ib=A(inner)*(1-A(bite))
# wound zone: band along the bite edge inside the shield
dil=A(bite.filter(ImageFilter.MaxFilter(55)).filter(ImageFilter.GaussianBlur(10)))
wound=np.clip(dil*ob,0,1)

img=np.zeros((S,S,4),np.float32)
def over(rgb,alpha):
    a=alpha[...,None]; img[...,:3]=img[...,:3]*(1-a)+np.array(rgb,np.float32)[None,None,:]*a if np.ndim(rgb)==1 else img[...,:3]*(1-a)+rgb*a
    img[...,3]=img[...,3]*(1-alpha)+alpha
# drop shadow
sh=A(L(ob).filter(ImageFilter.GaussianBlur(30))); sh=A(shift(L(sh),18,30))
over(np.array([0,0,0],np.float32),sh*0.55)
# metal rim: steel gradient
g=np.clip(1-(xx*0.55+yy*0.75),0,1)[...,None]
steel=np.array([70,76,82])*(1-g)+np.array([205,212,218])*g
over(steel,ob)
# rim bevel highlights/shadows
bev_hi=np.clip(A(L(ob))-A(shift(L(ob),10,14)),0,1); bev_hi=A(L(bev_hi).filter(ImageFilter.GaussianBlur(5)))
bev_lo=np.clip(A(L(ob))-A(shift(L(ob),-10,-14)),0,1); bev_lo=A(L(bev_lo).filter(ImageFilter.GaussianBlur(5)))
over(np.array([255,255,255]),bev_hi*0.8); over(np.array([25,28,30]),bev_lo*0.8)
# green field: radial gradient
d=np.sqrt((xx-0.38)**2+(yy-0.30)**2)
t=np.clip(d/0.65,0,1)[...,None]
green=np.array([88,200,96])*(1-t)+np.array([10,70,28])*t
# inner groove (dark line) around field
groove=A(L(ib).filter(ImageFilter.MaxFilter(15)))-ib
over(np.array([15,30,18]),np.clip(groove,0,1)*0.9)
over(green,ib)
# inner shadow on field (top-left lit, so shadow from top-left edge)
ins=np.clip(ib-A(shift(L(ib),14,20).filter(ImageFilter.GaussianBlur(18))),0,1)
over(np.array([0,25,8]),ins*0.6)
# wound: dark red rotten edge + torn rim
wcol=np.array([95,10,12])*np.ones((S,S,1))
over(wcol,wound*0.85)
edge=A(bite.filter(ImageFilter.MaxFilter(13)))*ob*(1-A(bite))
over(np.array([45,4,6]),np.clip(edge,0,1))
# short teardrop blood drips hanging from two tooth marks
dr=Image.new("L",(S,S),0); dd=ImageDraw.Draw(dr)
for ta,ln,w in [(141,0.10,0.042),(187,0.07,0.034)]:
    x,y=tips[ta]; W=scale*w; Lh=scale*ln
    pts=[]
    for k in range(41):
        t=k/40; half=W*0.5*(0.35+0.65*math.sin(t*math.pi*0.5)**2)
        pts.append((x+half,y-W*0.3+Lh*t))
    for k in range(40,-1,-1):
        t=k/40; half=W*0.5*(0.35+0.65*math.sin(t*math.pi*0.5)**2)
        pts.append((x-half,y-W*0.3+Lh*t))
    dd.polygon(pts,fill=255)
    dd.ellipse([x-W*0.5,y+Lh-W*0.75,x+W*0.5,y+Lh+W*0.25],fill=255)
# a few spatter dots
for (ox,oy,rr) in [(-0.06,0.05,0.012),(-0.10,0.12,0.008),(0.02,0.16,0.009)]:
    x,y=tips[164]; x+=scale*ox; y+=scale*oy; rr*=scale
    dd.ellipse([x-rr,y-rr,x+rr,y+rr],fill=255)
drm=A(dr.filter(ImageFilter.GaussianBlur(2)))*ob
over(np.array([0,0,0]),A(shift(L(drm),4,7).filter(ImageFilter.GaussianBlur(5)))*0.45*ob)
dg=np.clip((yy-0.1)/0.3,0,1)[...,None]
over(np.array([150,12,16])*(1-dg)+np.array([95,4,8])*dg,drm)
spec=np.clip(drm-A(shift(L(drm),7,0)),0,1)
over(np.array([255,150,150]),A(L(spec).filter(ImageFilter.GaussianBlur(2)))*0.7)
# gloss on upper-left half of field
gl=Image.new("L",(S,S),0); ImageDraw.Draw(gl).ellipse([cx-scale*0.9,top-scale*0.6,cx+scale*0.25,top+scale*0.55],fill=255)
glm=A(gl.filter(ImageFilter.GaussianBlur(40)))*ib*np.clip(1-yy*1.6,0,1)
over(np.array([255,255,255]),glm*0.22)
# checkmark: smaller, with shadow and slight gradient
ck=Image.new("L",(S,S),0); dc=ImageDraw.Draw(ck); w=int(scale*0.07)
p=[(cx-scale*0.14,top+scale*0.64),(cx-scale*0.045,top+scale*0.735),(cx+scale*0.12,top+scale*0.54)]
dc.line(p,fill=255,width=w,joint="curve")
for q in (p[0],p[2]): dc.ellipse([q[0]-w/2,q[1]-w/2,q[0]+w/2,q[1]+w/2],fill=255)
ck=ck.filter(ImageFilter.GaussianBlur(1.5))
cs=A(shift(ck,8,12).filter(ImageFilter.GaussianBlur(10)))
over(np.array([0,30,10]),cs*0.65)
ckg=np.clip((yy-0.45)/0.35,0,1)[...,None]
over(np.array([255,255,255])*(1-ckg)+np.array([200,225,205])*ckg,A(ck))
out=Image.fromarray(np.clip(img,0,255).astype(np.uint8)*0+np.dstack([np.clip(img[...,:3],0,255),np.clip(img[...,3]*255,0,255)]).astype(np.uint8),"RGBA")
out.resize((1024,1024),Image.LANCZOS).save("bitecheck-1024.png")
sizes=[256,128,64,48,32,24,16]
for s in sizes: out.resize((s,s),Image.LANCZOS).save(f"bitecheck-{s}.png")
out.resize((256,256),Image.LANCZOS).save("bitecheck.ico",sizes=[(s,s) for s in sizes])
sheet=Image.new("RGBA",(1400,640))
for bg,y0 in [((240,240,240,255),0),((32,32,40,255),320)]:
    ImageDraw.Draw(sheet).rectangle([0,y0,1400,y0+320],fill=bg); x=20
    for s in [256,64,48,32,16]:
        sheet.alpha_composite(Image.open(f"bitecheck-{s}.png"),(x,y0+30)); x+=s+40
sheet.alpha_composite(Image.open("bitecheck-1024.png").resize((560,560),Image.LANCZOS),(820,40))
sheet.save("preview.png")
