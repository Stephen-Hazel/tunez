# launcher.py - makes the app's launcher icons from the same cassette as
# art.py: an adaptive icon (vector foreground + solid background) for
# android 8+, n plain webp icons (square n round) for android 7
# usage: python3 docs/store/launcher.py   (from the project root)
import os
from PIL import Image, ImageDraw

RES  = "app/src/main/res"
BG   = "#FFD1E4FF"                   # theme primaryContainer (light)
BODY = "#FF128FDD";  BODY2 = "#FF0B6FB8";  LABEL = "#FF35C1F1"
REEL = "#FF3A3D42";  WIN   = "#FFE8ECF0";  WHITE = "#FFFFFFFF"


def parts (x, y, w):
   # the cassette as (kind, color, numbers) in a w wide box at x,y - same
   # proportions as art.py
   h = w * .66;  cy = y + h*.52;  rr = h*.17
   out = [("rrect", BODY,  (x, y, x+w, y+h, w*.06)),
          ("rrect", LABEL, (x+w*.30, y+h*.10, x+w*.70, y+h*.24, w*.015)),
          ("rect",  WIN,   (x+w*.36, cy-rr*.75, x+w*.64, cy+rr*.75))]
   for cx in (x+w*.27, x+w*.73):
      out += [("oval", REEL,  (cx-rr*1.15, cy-rr, cx+rr*1.15, cy+rr)),
              ("oval", WHITE, (cx-rr*.45, cy-rr*.45, cx+rr*.45, cy+rr*.45))]
   out += [("poly", BODY2, ((x+w*.12, y+h), (x+w*.20, y+h*.80),
                            (x+w*.80, y+h*.80), (x+w*.88, y+h)))]
   return out


def f (n):  return ("%.2f" % n).rstrip ("0").rstrip (".")


def path (kind, v):
   # vector drawable pathData for one part
   if kind == "rect":
      x1, y1, x2, y2 = v
      return f"M{f(x1)},{f(y1)}H{f(x2)}V{f(y2)}H{f(x1)}Z"
   if kind == "rrect":
      x1, y1, x2, y2, r = v
      a = f"{f(r)},{f(r)} 0 0 1"
      return (f"M{f(x1+r)},{f(y1)}H{f(x2-r)}A{a} {f(x2)},{f(y1+r)}"
              f"V{f(y2-r)}A{a} {f(x2-r)},{f(y2)}H{f(x1+r)}"
              f"A{a} {f(x1)},{f(y2-r)}V{f(y1+r)}A{a} {f(x1+r)},{f(y1)}Z")
   if kind == "oval":
      x1, y1, x2, y2 = v
      rx, ry, cy = (x2-x1)/2, (y2-y1)/2, (y1+y2)/2
      a = f"{f(rx)},{f(ry)} 0 1 1"
      return f"M{f(x1)},{f(cy)}A{a} {f(x2)},{f(cy)}A{a} {f(x1)},{f(cy)}Z"
   pts = v
   return "M" + "L".join (f"{f(px)},{f(py)}" for px, py in pts) + "Z"


def vector (body):
   return ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- made by docs/store/launcher.py - edit that, not this -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="108dp"\n    android:height="108dp"\n'
           '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
           + body + '</vector>\n')


# adaptive foreground: 108 square, launchers may cut it down to a 66
# circle in the middle - a 54 wide cassette's corners just fit inside it
fg = "".join (f'    <path android:fillColor="{c}"\n'
              f'        android:pathData="{path (k, v)}" />\n'
              for k, c, v in parts (27, 54 - 54*.66/2, 54))
open (f"{RES}/drawable/ic_launcher_foreground.xml", "w").write (vector (fg))
open (f"{RES}/drawable/ic_launcher_background.xml", "w").write (vector (
   f'    <path android:fillColor="{BG}"\n'
   f'        android:pathData="M0,0h108v108h-108z" />\n'))
for nm in ("ic_launcher", "ic_launcher_round"):
   open (f"{RES}/mipmap-anydpi-v26/{nm}.xml", "w").write (
      '<?xml version="1.0" encoding="utf-8"?>\n'
      '<adaptive-icon xmlns:android='
      '"http://schemas.android.com/apk/res/android">\n'
      '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
      '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
      '</adaptive-icon>\n')


def rgb (c):  return tuple (int (c [i:i+2], 16) for i in (3, 5, 7))


def bitmap (px, round_):
   # android 7 icon: drawn 4x n scaled down for smooth edges
   S = px * 4
   im = Image.new ("RGBA", (S, S), (0, 0, 0, 0));  d = ImageDraw.Draw (im)
   if round_:  d.ellipse ((0, 0, S-1, S-1), fill=rgb (BG))
   else:       d.rounded_rectangle ((0, 0, S-1, S-1), S*.18, fill=rgb (BG))
   w = S * .70
   for k, c, v in parts ((S-w)/2, (S - w*.66)/2, w):
      if   k == "rrect":  d.rounded_rectangle (v [:4], v [4], fill=rgb (c))
      elif k == "rect":   d.rectangle (v, fill=rgb (c))
      elif k == "oval":   d.ellipse (v, fill=rgb (c))
      else:               d.polygon (v, fill=rgb (c))
   return im.resize ((px, px), Image.LANCZOS)


for dpi, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                ("xxhdpi", 144), ("xxxhdpi", 192)):
   bitmap (px, False).save (f"{RES}/mipmap-{dpi}/ic_launcher.webp",
                            lossless=True)
   bitmap (px, True).save (f"{RES}/mipmap-{dpi}/ic_launcher_round.webp",
                           lossless=True)
   old = f"{RES}/mipmap-{dpi}/ic_launcher_foreground.webp"
   if os.path.exists (old):  os.remove (old)          # the old blurry one
