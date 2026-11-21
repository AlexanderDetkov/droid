#!/usr/bin/env python3
"""
Writes Droid's icons into app/src/main/res/drawable: the launcher icon's layers (field, Dash, themed one-color
Dash), the status bar icon and mark (ic_notify, ic_logo) and the splash. Dash is drawn from his proportions in the
app (Dash.kt, drawDash: the creature's body, hooked antenna and eyes), with his features a little larger, as the
app draws him at small sizes. Run it after changing those: python3 tools/icons.py
"""
import os
import math
K = 0.5523
def f(v): return f'{v:.2f}'.rstrip('0').rstrip('.')
def ell(cx, cy, rx, ry):
    return f'M{f(cx-rx)},{f(cy)} a{f(rx)},{f(ry)} 0 1,0 {f(2*rx)},0 a{f(rx)},{f(ry)} 0 1,0 {f(-2*rx)},0 Z'
def body_d(cx, ground, w, h):
    """His body (Dash.kt, drawDash at frame 0): a dome on top, a soft, slightly flat base."""
    x0, x1, y0, y1 = cx - w/2, cx + w/2, ground - h, ground
    rtx, rty, rbx, rby = w/2, h*.53, w*.45, h*.41
    p = [f'M{f(x0+rtx)},{f(y0)}',
         f'C{f(x1-rtx+rtx*K)},{f(y0)} {f(x1)},{f(y0+rty-rty*K)} {f(x1)},{f(y0+rty)}',
         f'L{f(x1)},{f(y1-rby)}',
         f'C{f(x1)},{f(y1-rby+rby*K)} {f(x1-rbx+rbx*K)},{f(y1)} {f(x1-rbx)},{f(y1)}',
         f'L{f(x0+rbx)},{f(y1)}',
         f'C{f(x0+rbx-rbx*K)},{f(y1)} {f(x0)},{f(y1-rby+rby*K)} {f(x0)},{f(y1-rby)}',
         f'L{f(x0)},{f(y0+rty)}',
         f'C{f(x0)},{f(y0+rty-rty*K)} {f(x0+rtx-rtx*K)},{f(y0)} {f(x0+rtx)},{f(y0)} Z']
    return ''.join(p)
def antenna(bx, by, length, a0=.15, curl=.5, n=12):
    """His antenna (Dash.kt, antenna()): a stalk that hooks more toward its tip, through its points' midpoints."""
    x, y = bx, by; pts = [(x, y)]
    for i in range(1, n+1):
        a = a0 + curl*2*((i - .5)/n)**1.4
        x += math.sin(a)*length/n; y -= math.cos(a)*length/n; pts.append((x, y))
    d = f'M{f(bx)},{f(by)}'
    for i in range(1, n):
        d += f' Q{f(pts[i][0])},{f(pts[i][1])} {f((pts[i][0]+pts[i+1][0])/2)},{f((pts[i][1]+pts[i+1][1])/2)}'
    d += f' L{f(pts[n][0])},{f(pts[n][1])}'
    return d, pts[-1]
C = dict(top='#BAC0FF', bot='#A3ABFF', stalk='#8A91EC', tip='#D2C4FF', ink='#16151B', shade='#2A1E6E')
def dash(cx, ground, w, small=.8, col=C, a0=.15, curl=.5, look=(0, 0), eyes=1.0, mono=False, tipglow=False, sw_min=0, tr_min=0, light=True,
         mark=False, stalk=1.0):
    """Elements for Dash standing on [ground], body [w] wide. small: the app's small-size feature boost (0..1). stalk: his
    antenna's length, as a share of the usual.
    mark: symmetric, as Droid's mark (the site's favicon): his light and catchlights centred (pass a0=0, curl=0 too)."""
    h = w*1.02; y0 = ground - h; E = []
    # antenna (behind the body)
    by = y0 + max(.05*w, 0)
    length = w*.27*(1 + .2*small)*stalk; sw = max(sw_min, w*.037*(1 + .45*small)); tr = max(tr_min, w*.058*(1 + .35*small))
    ad, (tx, ty) = antenna(cx, by, length, a0, curl)
    E.append(dict(d=ad, stroke=('#FFFFFF' if mono else col['stalk']), sw=sw, cap='round'))
    if tipglow and not mono:
        E.append(dict(d=ell(tx, ty, tr*2.6, tr*2.6), fill=('radial', tx, ty, tr*2.6, [(0, '#FFF0C8', .55), (.55, '#FFF0C8', .3), (1, '#FFF0C8', 0)])))
    E.append(dict(d=ell(tx, ty, tr, tr), fill=('solid', '#FFFFFF' if mono else col['tip'])))
    if not mono: E.append(dict(d=ell(tx - (0 if mark else tr*.3), ty - tr*.32, tr*.3, tr*.3), fill=('solid', '#CCFFFFFF')))
    bd = body_d(cx, ground, w, h)
    # eyes: two dark ovals low on his face, a catchlight in each (drawEye)
    rx = w*.076*(1 + .45*small)*eyes; ry = rx*1.32; sep = w*(.175 + .02*small); ey = ground - h*.43
    lx, ly = look; ox = lx*w*.085; oy = ly*h*.065
    eyesd = ell(cx - sep + ox, ey + oy, rx, ry) + ' ' + ell(cx + sep + ox, ey + oy, rx, ry)
    if mono:
        E.append(dict(d=bd + ' ' + eyesd, fill=('solid', '#FFFFFF'), evenodd=True)); return E
    E.append(dict(d=bd, fill=('linear', cx, y0, cx, ground, [(0, col['top'], 1), (1, col['bot'], 1)])))
    if light: E.append(dict(d=bd, fill=('radial', cx - (0 if mark else w*.18), ground - h*.76, w*.62, [(0, '#FFFFFF', .42), (.5, '#FFFFFF', .12), (1, '#FFFFFF', 0)])))
    E.append(dict(d=eyesd, fill=('solid', col['ink'])))
    for s in (-1, 1):
        ex = cx + s*sep + ox; eyy = ey + oy
        E.append(dict(d=ell(ex + (0 if mark else rx*.3 - lx*rx*.06), eyy - ry*.42 - ly*ry*.04, rx*.36, rx*.36), fill=('solid', '#F2FFFFFF')))
    return E
def hexa(c, a=1.0):
    c = c.lstrip('#')
    if len(c) == 8: a *= int(c[:2], 16)/255; c = c[2:]
    return c, a
def to_svg(els, vb=108, bg=None):
    defs, out = [], []
    for i, e in enumerate(els):
        at = []
        fl = e.get('fill')
        if fl:
            if fl[0] == 'solid':
                c, a = hexa(fl[1], e.get('fop', 1)); at.append(f'fill="#{c}" fill-opacity="{a:.3f}"')
            elif fl[0] == 'linear':
                _, x1, y1, x2, y2, st = fl
                defs.append(f'<linearGradient id="l{i}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">' + ''.join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in st) + '</linearGradient>')
                at.append(f'fill="url(#l{i})"')
            else:
                _, cx, cy, r, st = fl
                defs.append(f'<radialGradient id="l{i}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">' + ''.join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in st) + '</radialGradient>')
                at.append(f'fill="url(#l{i})"')
        else: at.append('fill="none"')
        if e.get('stroke'):
            c, a = hexa(e['stroke'], e.get('sop', 1))
            at.append(f'stroke="#{c}" stroke-opacity="{a:.3f}" stroke-width="{e["sw"]:.3f}" stroke-linecap="{e.get("cap","butt")}" stroke-linejoin="round"')
        if e.get('evenodd'): at.append('fill-rule="evenodd"')
        out.append(f'<path d="{e["d"]}" {" ".join(at)}/>')
    b = f'<rect width="{vb}" height="{vb}" fill="{bg}"/>' if bg else ''
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vb} {vb}"><defs>{"".join(defs)}</defs>{b}{"".join(out)}</svg>'
def argb(c, a):
    return '#' + f'{round(a*255):02X}' + c.upper()
def to_vd(els, comment, vb=108, size=108):
    L = [f'<!-- {comment} -->', '<vector xmlns:android="http://schemas.android.com/apk/res/android"']
    needs_aapt = any(e.get('fill') and e['fill'][0] != 'solid' for e in els)
    if needs_aapt: L.append('    xmlns:aapt="http://schemas.android.com/aapt"')
    L.append(f'    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{vb}" android:viewportHeight="{vb}">')
    for e in els:
        at = [f'android:pathData="{e["d"]}"']
        fl = e.get('fill'); inner = ''
        if fl and fl[0] == 'solid':
            c, a = hexa(fl[1], e.get('fop', 1)); at.append(f'android:fillColor="{argb(c, a) if a < .999 else "#" + c.upper()}"')
        elif fl:
            if fl[0] == 'linear':
                _, x1, y1, x2, y2, st = fl
                g = f'<gradient android:type="linear" android:startX="{f(x1)}" android:startY="{f(y1)}" android:endX="{f(x2)}" android:endY="{f(y2)}">'
            else:
                _, cx, cy, r, st = fl
                g = f'<gradient android:type="radial" android:centerX="{f(cx)}" android:centerY="{f(cy)}" android:gradientRadius="{f(r)}">'
            items = ''.join(f'\n                    <item android:offset="{o}" android:color="{argb(hexa(c)[0], a)}" />' for o, c, a in st)
            inner = f'\n            <aapt:attr name="android:fillColor">\n                {g}{items}\n                </gradient>\n            </aapt:attr>\n        '
        if e.get('stroke'):
            c, a = hexa(e['stroke'], e.get('sop', 1))
            at.append(f'android:strokeColor="{argb(c, a) if a < .999 else "#" + c.upper()}" android:strokeWidth="{f(e["sw"])}" android:strokeLineCap="{e.get("cap","butt")}" android:strokeLineJoin="round"')
        if e.get('evenodd'): at.append('android:fillType="evenOdd"')
        if inner: L.append(f'    <path {" ".join(at)}>{inner}</path>')
        else: L.append(f'    <path {" ".join(at)} />')
    L.append('</vector>')
    return '\n'.join(L) + '\n'

LIGHT = dict(C, top='#C9CDFF', bot='#B3BAFF', stalk='#E2E4FF', tip='#F1E9FF', shade='#2A1E6E')

R = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res', 'drawable') + '/'
DEEP = dict(LIGHT, top='#CDD1FF', bot='#B9BFFF')
FIELD = ('#6A70EE', '#474CC4')
bg = [dict(d='M0,0 H108 V108 H0 Z', fill=('linear', 54, 18, 54, 90, [(0, FIELD[0], 1), (1, FIELD[1], 1)]))]

def main():
    """Writes Droid's icons: launcher layers, themed layer, status bar icon, logo, splash."""
    # Peek: a big Dash rising from the bottom edge, looking up at you; masks cut his body off at the bottom, his face and
    # hook stay inside the 66 dp safe zone
    fg = dash(54, 100, 54, small=.6, col=DEEP, look=(0, -.15))
    mono = dash(54, 100, 54, small=.6, mono=True, look=(0, -.15))
    open(R + 'ic_launcher_background.xml', 'w').write(to_vd(bg, "Launcher icon, back layer: Droid's periwinkle field."))
    open(R + 'ic_launcher_foreground.xml', 'w').write(to_vd(fg, "Launcher icon, front layer: Dash peeking up from the bottom edge. Generated from his own proportions in the app (Dash.kt), features a little larger, as the app draws him small."))
    open(R + 'ic_launcher_monochrome.xml', 'w').write(to_vd(mono, "Themed icon layer: the same peeking Dash as one color, his eyes cut out."))
    # status bar / logo: all of him as a silhouette, eyes cut out, drawn as the site's mark (antenna straight up). His body
    # takes the whole box (20 x 23 dp) so he holds his own beside the system's icons: the antenna is only its tip, sitting
    # on his head, and his eyes are big
    small = dash(12, 23.6, 20, small=1, mono=True, a0=0, curl=0, stalk=.3, sw_min=1.9, tr_min=1.85, eyes=1.25)
    open(R + 'ic_notify.xml', 'w').write(to_vd(small, "Status bar icon and Droid's mark: Dash as one color, his eyes cut out.", vb=24, size=24))
    open(R + 'ic_logo.xml', 'w').write(to_vd(small, "Droid's mark: Dash as one color, his eyes cut out. Tinted where it's used.", vb=24, size=24))
    # splash (Android draws it in a 160 dp circle of a 240 dp box, on the window's background): all of Dash, as in the app
    splash = dash(54, 74, 34, small=.3)
    open(R + 'ic_splash.xml', 'w').write(to_vd(splash, "Splash screen: Dash standing, as he greets you in a new chat. The icon box is 108 dp; Android shows its middle 72 dp."))
    print('wrote', R)

if __name__ == '__main__':
    main()
