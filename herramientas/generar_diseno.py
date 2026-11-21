#!/usr/bin/env python3
"""Genera los diseños SVG para Librería (SVG puro, sin dependencias)."""
import os

IMG = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "static", "img")
B, B2, PR, PH, CLR, S, T = "#0F172A", "#1E3A8A", "#1D4ED8", "#2563EB", "#EFF6FF", "#FFFFFF", "#64748B"
os.makedirs(IMG, exist_ok=True)


def save(nombre, svg):
    p = os.path.join(IMG, nombre)
    with open(p, "w", encoding="utf-8") as f:
        f.write(svg)
    print("generado:", p)


def svg(body, view="0 0 24 24", fill="none", wh="24", stroke=T, sw=None):
    s = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}" width="{wh}" height="{wh}" fill="{fill}"'
    if stroke:
        s += f' stroke="{stroke}"' + (f" stroke-width=\"{sw}\"" if sw else "")
    s += ">" + body + "</svg>"
    return s


save("logo-libreria.svg", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 90" width="80" height="60" fill="none">'
     f'<rect x="6" y="10" width="18" height="60" rx="3" fill="{B2}"/>'
     f'<path d="M6 40L12 8a12 12 0 0 1 24 0" stroke="{B2}" stroke-width="6" stroke-linecap="round"/>'
     f'<line x1="12" y1="20" x2="30" y2="20" stroke="{B2}" stroke-width="6" stroke-linecap="round"/>'
     f'<text x="46" y="58" font-family="system-ui,-apple-system,Segoe UI,Roboto,sans-serif" font-size="40" font-weight="800" letter-spacing="-1.5" fill="{B}">Librería</text></svg>')

save("icono-panel.svg", svg(f'<path d="M3 3h18v4H3z" fill="{PR}"/>', view="0 0 24 24", fill="none", wh="24", stroke="none"))
save("icono-llegada.svg", svg('<circle cx="12" cy="12" r="9" fill="{CLR}"/>'.format(CLR=CLR) + '<path d="M12 22v-8M12 6V2M2 12h4M10 12h4" stroke="%s" stroke-width="2" stroke-linecap="round"/>' % B))
save("icono-salida.svg", svg('<circle cx="12" cy="12" r="9" fill="{CLR}"/>'.format(CLR=CLR) + '<path d="M12 2v8M12 16v4M2 12h4M18 12h4" stroke="%s" stroke-width="2" stroke-linecap="round"/>' % B))
save("icono-inventario.svg", svg(f'<rect x="3" y="5" width="18" height="16" rx="2" fill="{B2}" stroke="{B}" stroke-width="2"/><path d="M3 9h12" stroke="{B}" stroke-width="2" stroke-linecap="round"/>', fill="none"))
save("icono-historial.svg", svg(f'<circle cx="12" cy="12" r="8.5" fill="{CLR}" stroke="{B}" stroke-width="2"/><path d="M12 8v4l2.5 1.5M2.5 12h19" stroke="{B}" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="12" r="1.2" fill="{B}"/>', fill="none"))
save("icono-libro.svg", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="40" height="40" fill="none">'
     f'<path d="M12 8h8a5 5 0 0 1 5 5v11H12z" fill="{CLR}" stroke="{B}" stroke-width="1.6"/></svg>')
save("icono-caja.svg", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="40" height="40" fill="none">'
     f'<path d="M6 15h20v12H6z" fill="{CLR}" stroke="{B}" stroke-width="1.6"/><path d="M6 15l4-3h16l4 3" stroke="{B}" stroke-width="1.6" stroke-linecap="round"/></svg>')
save("icono-alerta.svg", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="40" height="40" fill="none">'
     f'<circle cx="16" cy="16" r="13" fill="{CLR}" stroke="{B}" stroke-width="1.6"/><path d="M16 12v6M16 22v2" stroke="{B}" stroke-width="2" stroke-linecap="round"/></svg>')
save("fondo-libreria.svg", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 60 40" width="200" height="133" fill="none">'
     f'<path d="M0 0h60v8H0z" fill="{B2}" opacity="0.12"/><path d="M5 15h12" stroke="{B2}" stroke-width="3" stroke-linecap="round"/><rect x="6" y="12" width="10" height="9" rx="2" fill="{B2}" opacity="0.22"/>'
     f'<rect x="22" y="13" width="13" height="8" rx="2" fill="{B2}" opacity="0.15"/><rect x="18" y="20" width="10" height="9" rx="2" fill="{B2}" opacity="0.20"/><rect x="31" y="18" width="12" height="8" rx="2" fill="{B2}" opacity="0.14"/>'
     f'<path d="M2 32h24M6 27h14M16 27h10M26 27h8" stroke="{B2}" stroke-width="2.5" stroke-linecap="round" opacity="0.45"/><rect x="4" y="32.5" width="30" height="2.5" rx="1.25" fill="{B2}" opacity="0.4"/></svg>')
print("9 diseños generados")
