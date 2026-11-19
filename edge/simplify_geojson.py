#!/usr/bin/env python3
"""Simplify Yemen boundary GeoJSON for bundling in the app.

Input: HDX COD-AB Yemen boundaries (OCHA / Central Statistical
Organisation Yemen, CC BY-IGO):
  - yem_admin1.geojson (22 governorates, ~2.8 MB)
  - yem_admin0.geojson (country outline, ~1.6 MB)
Output (assets/geo/):
  - yemen-adm1.geojson  (~200-300 KB, all 22 features)
  - yemen-outline.geojson (country outline)

Stdlib only. Douglas-Peucker per ring (tolerance in degrees),
coordinates rounded to 4 decimals (~11 m — far finer than the
display need). Ring closure is preserved: the duplicate closing
point is dropped before simplification and re-added after, so a
ring never loses its closure. Properties are trimmed to the fields
the app needs (adm1_name / adm1_name1 / adm1_pcode for ADM1).

Shared borders are simplified per feature, so neighbouring
governorates can diverge by up to ~tolerance at a shared border —
fine for a display map, not for analysis. The source files are
left untouched in /tmp/hdx_yem/ (re-download from
https://data.humdata.org/dataset/6b2656e2-b915-4671-bfed-468d5edcd80a/resource/c79a3728-44cd-4d20-8ea4-ecfab88c1450/download/yem_admin_boundaries.geojson.zip
if they are gone).

Usage:
  python3 edge/simplify_geojson.py [--tolerance 0.02] [--adm1-in PATH]
      [--adm0-in PATH] [--out-dir DIR]
"""

import argparse
import json
import math
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(SCRIPT_DIR)
DEFAULT_ADM1 = "/tmp/hdx_yem/yem_admin1.geojson"
DEFAULT_ADM0 = "/tmp/hdx_yem/yem_admin0.geojson"
DEFAULT_OUT = os.path.join(APP_ROOT, "assets", "geo")

ADM1_KEEP = ("adm1_name", "adm1_name1", "adm1_pcode")
ADM0_KEEP = ("adm0_name", "adm0_name1", "adm0_pcode", "iso3")


def _perp_dist_sq(px, py, ax, ay, bx, by):
    """Squared perpendicular distance from P to segment AB (degrees)."""
    dx, dy = bx - ax, by - ay
    seg_sq = dx * dx + dy * dy
    if seg_sq == 0:
        return (px - ax) ** 2 + (py - ay) ** 2
    t = ((px - ax) * dx + (py - ay) * dy) / seg_sq
    t = max(0.0, min(1.0, t))
    cx, cy = ax + t * dx, ay + t * dy
    return (px - cx) ** 2 + (py - cy) ** 2


def douglas_peucker(points, tol_sq):
    """Iterative Douglas-Peucker on an open polyline (list of [x, y]).
    Endpoints are always kept."""
    n = len(points)
    if n <= 2:
        return list(points)
    keep = [False] * n
    keep[0] = keep[n - 1] = True
    stack = [(0, n - 1)]
    while stack:
        a, b = stack.pop()
        if b - a < 2:
            continue
        ax, ay = points[a]
        bx, by = points[b]
        worst_i, worst_d = -1, 0.0
        for i in range(a + 1, b):
            d = _perp_dist_sq(points[i][0], points[i][1], ax, ay, bx, by)
            if d > worst_d:
                worst_d, worst_i = d, i
        if worst_d > tol_sq and worst_i != -1:
            keep[worst_i] = True
            stack.append((a, worst_i))
            stack.append((worst_i, b))
    return [p for p, k in zip(points, keep) if k]


def simplify_ring(ring, tol_sq):
    """Simplify one closed ring, preserving closure."""
    if len(ring) < 4:
        return ring
    closed = ring[0] == ring[-1]
    pts = ring[:-1] if closed else ring
    # Rotate so the simplification starts at the most extreme point
    # (avoids a degenerate straight-line collapse at the seam).
    if len(pts) > 2:
        k = max(range(len(pts)), key=lambda i: pts[i][0] ** 2 + pts[i][1] ** 2)
        pts = pts[k:] + pts[:k]
    simp = douglas_peucker(pts, tol_sq)
    if closed:
        simp = simp + [simp[0]]
    return [[round(x, 4), round(y, 4)] for x, y in simp]


def simplify_geometry(geom, tol_sq):
    gtype = geom["type"]
    coords = geom["coordinates"]
    if gtype == "Polygon":
        return {"type": "Polygon",
                "coordinates": [simplify_ring(r, tol_sq) for r in coords]}
    if gtype == "MultiPolygon":
        return {"type": "MultiPolygon",
                "coordinates": [[simplify_ring(r, tol_sq) for r in poly]
                                for poly in coords]}
    raise ValueError("unsupported geometry type: %s" % gtype)


def count_coords(coords):
    if isinstance(coords[0], (int, float)):
        return 1
    return sum(count_coords(c) for c in coords)


def simplify_file(in_path, keep_props, tolerance):
    with open(in_path, encoding="utf-8") as f:
        doc = json.load(f)
    tol_sq = tolerance * tolerance
    out_features = []
    total_in = total_out = 0
    for feat in doc["features"]:
        total_in += count_coords(feat["geometry"]["coordinates"])
        geom = simplify_geometry(feat["geometry"], tol_sq)
        total_out += count_coords(geom["coordinates"])
        props = {k: feat["properties"].get(k) for k in keep_props}
        out_features.append({
            "type": "Feature",
            "properties": props,
            "geometry": geom,
        })
    out = {
        "type": "FeatureCollection",
        "name": os.path.splitext(os.path.basename(in_path))[0],
        "crs": {"type": "name",
                "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
        "features": out_features,
    }
    return out, total_in, total_out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tolerance", type=float, default=0.02,
                    help="Douglas-Peucker tolerance in degrees")
    ap.add_argument("--adm1-in", default=DEFAULT_ADM1)
    ap.add_argument("--adm0-in", default=DEFAULT_ADM0)
    ap.add_argument("--out-dir", default=DEFAULT_OUT)
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    for in_path, keep, out_name in (
            (args.adm1_in, ADM1_KEEP, "yemen-adm1.geojson"),
            (args.adm0_in, ADM0_KEEP, "yemen-outline.geojson")):
        if not os.path.exists(in_path):
            print("MISSING: %s — re-download the HDX zip (see docstring)"
                  % in_path, file=sys.stderr)
            return 1
        doc, n_in, n_out = simplify_file(in_path, keep, args.tolerance)
        out_path = os.path.join(args.out_dir, out_name)
        text = json.dumps(doc, ensure_ascii=False, separators=(",", ":"))
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print("%s: features=%d coords %d -> %d, size=%d bytes"
              % (out_name, len(doc["features"]), n_in, n_out,
                 os.path.getsize(out_path)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
