# Yemen boundary GeoJSON — provenance & license

Source: **HDX COD-AB Yemen** — `yem_admin_boundaries.geojson.zip`
(Humanitarian Data Exchange, OCHA), administrative boundaries compiled
by the **Central Statistical Organisation (CSO) Yemen**, valid 2019-11-22,
version v01, WGS84 (CRS84, lon/lat).

- `yemen-adm1.geojson` — 22 governorates (ADM1), simplified from the
  2.8 MB source to **207,731 bytes** with a Douglas–Peucker tolerance of
  0.0008° (~90 m) and coordinates rounded to 4 decimals. Properties kept:
  `adm1_name` (English), `adm1_name1` (Arabic), `adm1_pcode` (e.g. YE12).
  Bounds: lon 41.81–54.54, lat 12.11–19.00. All rings closed.
- `yemen-outline.geojson` — country outline (ADM0), same simplification,
  **90,590 bytes**, 1 feature. Properties kept: `adm0_name`, `adm0_name1`,
  `adm0_pcode`, `iso3`.

License: **CC BY-IGO** (HDX / OCHA). Attribution: "Yemen administrative
boundaries, OCHA HDX COD-AB, Central Statistical Organisation Yemen".

Simplification: `edge/simplify_geojson.py` (stdlib only, reproducible —
re-run it to regenerate both files from `/tmp/hdx_yem/`; if that copy
is gone, re-download the HDX zip from
https://data.humdata.org/dataset/6b2656e2-b915-4671-bfed-468d5edcd80a/resource/c79a3728-44cd-4d20-8ea4-ecfab88c1450/download/yem_admin_boundaries.geojson.zip).

Note: shared governorate borders are simplified per feature, so
neighbouring borders can diverge by up to ~tolerance. These files are
for display mapping only, not for analysis or legal boundaries.
