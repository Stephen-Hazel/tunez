# draws the play store icon n feature graphic - same cassette as tunz.png,
# just crisp.  drawn 4x big n scaled down for smooth edges
# usage: python3 art.py <out dir> <dir holding Inter-Bold.otf n
#        Inter-Regular.otf>  (inter: github.com/rsms/inter, OFL license)
import sys
from PIL import Image, ImageDraw, ImageFont
out   = sys.argv[1]
FONTS = sys.argv[2].rstrip ("/") + "/"
BG     = (0xD1, 0xE4, 0xFF)      # theme primaryContainer (light)
NAVY   = (0x00, 0x1D, 0x36)      # onPrimaryContainer
BODY   = (0x12, 0x8F, 0xDD)
BODY2  = (0x0B, 0x6F, 0xB8)
LABEL  = (0x35, 0xC1, 0xF1)
REEL   = (0x3A, 0x3D, 0x42)
WIN    = (0xE8, 0xEC, 0xF0)
WHITE  = (0xFF, 0xFF, 0xFF)

def cassette (d, x, y, w):
   # cassette drawn in a w wide box at x,y (h = .66 w)
   h = int (w * .66);  r = int (w * .06)
   d.rounded_rectangle ((x, y, x+w, y+h), r, fill=BODY)
   d.rounded_rectangle ((x+w*.30, y+h*.10, x+w*.70, y+h*.24), int (w*.015),
                        fill=LABEL)
   cy = y + h*.52;  rr = h*.17
   d.rectangle ((x+w*.36, cy-rr*.75, x+w*.64, cy+rr*.75), fill=WIN)
   for cx in (x+w*.27, x+w*.73):
      d.ellipse ((cx-rr*1.15, cy-rr, cx+rr*1.15, cy+rr), fill=REEL)
      d.ellipse ((cx-rr*.45, cy-rr*.45, cx+rr*.45, cy+rr*.45), fill=WHITE)
   d.polygon ([(x+w*.12, y+h), (x+w*.20, y+h*.80), (x+w*.80, y+h*.80),
               (x+w*.88, y+h)], fill=BODY2)

S = 4
# 512x512 icon - full bleed square, play rounds the corners itself
ic = Image.new ("RGB", (512*S, 512*S), BG);  d = ImageDraw.Draw (ic)
cassette (d, 64*S, 104*S, 384*S)
ic.resize ((512, 512), Image.LANCZOS).save (f"{out}/icon-512.png")

# 1024x500 feature graphic
fg = Image.new ("RGB", (1024*S, 500*S), BG);  d = ImageDraw.Draw (fg)
cassette (d, 70*S, 120*S, 380*S)
# biggest title that still fits right of the cassette
sz = 104
while True:
   big = ImageFont.truetype (
            FONTS + "Inter-Bold.otf", sz*S)
   if d.textlength ("SHazTunez", font=big) <= (1024-500-40)*S:  break
   sz -= 2
sub = ImageFont.truetype (FONTS + "Inter-Regular.otf",
                          40*S)
d.text ((500*S, 150*S), "SHazTunez", font=big, fill=NAVY)
d.text ((506*S, 290*S), "your music folder,", font=sub, fill=NAVY)
d.text ((506*S, 342*S), "shuffled your way", font=sub, fill=NAVY)
fg.resize ((1024, 500), Image.LANCZOS).save (f"{out}/feature-1024x500.png")
