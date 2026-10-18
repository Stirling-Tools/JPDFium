from pathlib import Path
from math import sqrt
import sys
import zipfile
import numpy as np
from scipy.optimize import minimize
import cairosvg
from matplotlib.textpath import TextPath
from matplotlib.font_manager import FontProperties, findfont
from matplotlib.path import Path as MPath

OUT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parent
OUT.mkdir(parents=True, exist_ok=True)

# Resolve the wordmark font explicitly. FontProperties alone falls back to
# another font with a warning, which would change the generated paths.
WORDMARK_FONT = findfont(FontProperties(family='DejaVu Sans', weight='bold'), fallback_to_default=False)


def plane(name, points, color):
    d = 'M' + 'L'.join(f'{x:.5f} {y:.5f}' for x, y in points) + 'Z'
    return f'<path id="{name}" fill="{color}" d="{d}"/>'


width = .210
visible_length = .145
segments = 90
ds = visible_length / segments
EI = 4e9 * width * .000100**3 / 12
load = .080 * width * 9.81
weights = np.arange(segments, 0, -1, dtype=float) - .5


def energy(a):
    q = np.r_[0., a]
    return .5 * EI / ds * np.sum(np.diff(q)**2) - load * ds**2 * np.sum(weights * np.sin(q))


def jac(a):
    q = np.r_[0., a]
    d = np.diff(q)
    g = np.zeros_like(q)
    g[:-1] -= EI / ds * d
    g[1:] += EI / ds * d
    g -= load * ds**2 * weights * np.cos(q)
    return g[1:] / (EI / ds)


result = minimize(lambda a: energy(a) / (EI / ds), np.linspace(.01, 1., segments - 1), jac=jac, method='L-BFGS-B', bounds=[(0, np.pi / 2)] * (segments - 1), options={'ftol': 1e-13, 'gtol': 1e-8, 'maxiter': 5000, 'maxls': 40})
assert result.success
angles = np.r_[0., result.x]
travel = np.r_[0, np.cumsum(np.cos(angles) * ds)]
sag = np.r_[0, np.cumsum(np.sin(angles) * ds)]
scale = 124 / width
left_x = 156 - .72 * travel * scale
page_y = 262 + (.44 * travel + sag) * scale
light = np.array([-3., -4., 7.])
light /= np.linalg.norm(light)
shade = .24 + .8 * np.maximum(0, -np.sin(angles) * light[1] + np.cos(angles) * light[2])
base = np.array([240., 229., 212.]) / 255
stops = []
for i in np.linspace(0, segments - 1, 14).astype(int):
    t = (page_y[i] - page_y[0]) / (page_y[-1] - page_y[0])
    color = np.clip(base * shade[i], 0, 1)
    c = '#' + ''.join(f'{int(v * 255 + .5):02x}' for v in color)
    stops.append(f'<stop offset="{t * 100:.4f}%" stop-color="{c}"/>')
defs = '<defs><linearGradient id="paper-light" x1="0%" y1="0%" x2="0%" y2="100%">' + ''.join(stops) + '</linearGradient><clipPath id="input-visible"><rect x="0" y="0" width="512" height="172"/></clipPath></defs>'


def roof_center(y):
    return 218 + (186 - y) * 72 / 44


def roof_strip(yfront, yback, half):
    a, b = roof_center(yfront), roof_center(yback)
    return [(a - half, yfront), (a + half, yfront), (b + half, yback), (b - half, yback)]


base_planes = [
    ('recessed-base-front', [(176, 309), (340, 309), (340, 341), (176, 341)], '#35465B'),
    ('recessed-base-right', [(340, 309), (405, 269), (405, 301), (340, 341)], '#253549'),
    ('recessed-base-top', [(176, 309), (241, 269), (405, 269), (340, 309)], '#53677E'),
    ('housing-right', [(352, 186), (424, 142), (424, 268), (352, 312)], '#692B33'),
    ('housing-fold-under', [(84, 186), (352, 186), (352, 312), (84, 312)], '#8E3131'),
    ('folded-top', [(84, 186), (156, 142), (424, 142), (352, 186)], '#B25A50')]
front_planes = [
    ('left-front-fold', [(84, 186), (225, 186), (246, 216), (144, 254), (144, 312), (84, 312)], '#9F423D'),
    ('right-front-fold', [(225, 186), (352, 186), (352, 312), (292, 312), (292, 254), (246, 216)], '#853137'),
    ('centered-output-opening', [(136, 244), (300, 244), (300, 280), (136, 280)], '#303B49')]
mark = defs + ''.join(plane(*p) for p in base_planes)
slot = roof_strip(172, 160, 82)
mark += plane('intake-integrated-in-top-face', slot, '#713A38')
intake_center_x = sum(p[0] for p in slot) / 4
center = intake_center_x
input_width = 136
input_height = input_width * sqrt(2)
input_rect = [(center - 68, 28), (center + 68, 28), (center + 68, 28 + input_height), (center - 68, 28 + input_height)]
mark += '<g id="single-flat-input-sheet" clip-path="url(#input-visible)">' + plane('unfolded-A4-paper', input_rect, '#F1E6D6') + '</g>'
mark += plane('thin-recessed-front-edge', roof_strip(172, 170, 82), '#9B4942')
mark += ''.join(plane(*p) for p in front_planes)
mark += plane('single-continuous-output-sheet', list(zip(left_x, page_y, strict=True)) + list(zip(left_x + 124, page_y, strict=True))[::-1], 'url(#paper-light)')
mark += plane('top-fold-edge', [(84, 186), (352, 186), (352, 187.3), (84, 187.3)], '#C87C69')
assert np.isclose((input_rect[0][0] + input_rect[1][0]) / 2, intake_center_x)
# The mark occupies x 84..424 and y 28..342.12 in user space. README export: cropped
# to that box with an even 12px margin. Avatar export: the same box fitted into a
# square canvas so the mark is centred and fills it.
logo = f'<svg xmlns="http://www.w3.org/2000/svg" width="364" height="339" viewBox="72 16 364 339"><title>JPDFium document press logo</title>{mark}</svg>'
(OUT / 'jpdfium-logo.svg').write_text(logo)
cairosvg.svg2png(bytestring=logo.encode(), write_to=str(OUT / 'jpdfium-logo.png'), output_width=1536)
avatar = f'<svg xmlns="http://www.w3.org/2000/svg" width="364" height="364" viewBox="72 3 364 364"><title>JPDFium document press app icon</title>{mark}</svg>'
(OUT / 'jpdfium-avatar.svg').write_text(avatar)
cairosvg.svg2png(bytestring=avatar.encode(), write_to=str(OUT / 'jpdfium-avatar.png'), output_width=1024, output_height=1024)
tp = TextPath((0, 0), 'JPDFium', size=124, prop=FontProperties(fname=WORDMARK_FONT))
commands = []
for values, code in tp.iter_segments(curves=True, simplify=False):
    cmd = {MPath.MOVETO: 'M', MPath.LINETO: 'L', MPath.CURVE3: 'Q', MPath.CURVE4: 'C', MPath.CLOSEPOLY: 'Z'}[code]
    commands.append(cmd + ('' if code == MPath.CLOSEPOLY else ' '.join(f'{v:.3f}' for v in values)))
word = ' '.join(commands)
lockup = f'<svg xmlns="http://www.w3.org/2000/svg" width="1560" height="512" viewBox="0 0 1560 512">{mark}<path fill="#2D3440" d="{word}" transform="translate(560,310) scale(1,-1)"/></svg>'
(OUT / 'jpdfium-lockup.svg').write_text(lockup)
cairosvg.svg2png(bytestring=lockup.encode(), write_to=str(OUT / 'jpdfium-lockup.png'), scale=2)
hero = f'<svg xmlns="http://www.w3.org/2000/svg" width="1850" height="1000" viewBox="0 0 1850 1000"><rect width="1850" height="1000" fill="#FAF9F6"/><g transform="translate(75,145) scale(1.65)">{mark}</g><path fill="#2D3440" d="{word}" transform="translate(935,564) scale(1,-1)"/></svg>'
(OUT / 'jpdfium-hero.svg').write_text(hero)
cairosvg.svg2png(bytestring=hero.encode(), write_to=str(OUT / 'jpdfium-hero.png'))
SCRIPT = Path(__file__).resolve()


def add_to_zip(z, path, name):
    """Add one file to the archive with a fixed timestamp.

    ``ZipFile.write`` stores each file's modification time, so regenerating the
    assets rewrote the ZIP even when nothing changed. A fixed timestamp keeps the
    archive byte-identical across runs.
    """
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    z.writestr(info, path.read_bytes())


with zipfile.ZipFile(OUT / 'jpdfium-brand-source.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for name in ['jpdfium-logo.svg', 'jpdfium-avatar.svg', 'jpdfium-lockup.svg',
                 'jpdfium-hero.svg']:
        add_to_zip(z, OUT / name, name)
    add_to_zip(z, SCRIPT, SCRIPT.name)
