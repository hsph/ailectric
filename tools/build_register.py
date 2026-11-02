#!/usr/bin/env python3
"""Turns the Bundesnetzagentur CSV into the tiles in app/src/main/assets/register.

Usage: tools/build_register.py Ladesaeulenregister_BNetzA_YYYY-MM-DD.csv
Download: https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenkarte/start.html
The format is documented in RegisterProvider.kt.
"""
import csv
import math
import pathlib
import re
import shutil
import sys

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/register"
DAYS = {"Montag": "Mo", "Dienstag": "Di", "Mittwoch": "Mi", "Donnerstag": "Do",
        "Freitag": "Fr", "Samstag": "Sa", "Sonntag": "So"}


def clean(text):
    return re.sub(r"\s+", " ", text).strip()


def number(text):
    try:
        return float(text.replace(",", "."))
    except ValueError:
        return None


def connector(plug):
    p = plug.lower()
    if "ccs" in p or "combo 2" in p:
        return "CCS2"
    if "chademo" in p:
        return "CHADEMO"
    if "typ 2" in p and "kupplung" in p:
        return "TYPE2_TETHERED"
    if "typ 2" in p:
        return "TYPE2"
    if "typ 1" in p:
        return "TYPE1"
    if "schuko" in p:
        return "SCHUKO"
    if "cee" in p:
        return "CEE"
    return "OTHER_DC" if plug.startswith("DC") else "OTHER_AC" if plug.startswith("AC") else "OTHER"


def charge_point(row, column):
    """One Ladepunkt: `reference~TYPE:kW+TYPE:kW`. Plugs and powers are parallel `;` lists."""
    plugs = [clean(p) for p in row[column].split(";") if p.strip()]
    if not plugs:
        return None
    powers = [clean(p) for p in row[column + 1].split(";")]
    if len(powers) != len(plugs):
        powers = powers[:1] * len(plugs) if len(powers) == 1 else [""] * len(plugs)
    return clean(row[column + 2]) + "~" + "+".join(
        f"{connector(plug)}:{power.replace(',', '.') if number(power) else ''}"
        for plug, power in zip(plugs, powers)
    )


def hours(kind, days, times):
    if kind == "247":
        return "24/7"
    if kind != "Eingeschränkt":
        return ""
    pairs = [(DAYS.get(clean(d), clean(d)), clean(t)) for d, t in zip(days.split(";"), times.split(";"))]
    runs = []
    for day, time in pairs:
        if runs and runs[-1][2] == time:
            runs[-1][1] = day
        else:
            runs.append([day, day, time])
    return ", ".join(f"{a} {t}" if a == b else f"{a}-{b} {t}" for a, b, t in runs)


def read(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f, delimiter=";")
        for row in reader:
            if row and row[0].startswith("Letzte Aktualisierung vom:"):
                d, m, y = row[0].split(":")[1].strip().split(".")
                stand = f"{y}-{m}-{d}"
            if row and row[0] == "Ladeeinrichtungs-ID":
                return stand, row, [r for r in reader if len(row) <= len(r) and r[0]]
    raise SystemExit("Kopfzeile nicht gefunden: Format geändert?")


def main():
    stand, header, rows = read(sys.argv[1])
    col = {name: i for i, name in enumerate(header)}
    plug_columns = [i for i, name in enumerate(header) if name.startswith("Steckertypen")]
    sites = {}
    skipped = 0
    for row in rows:
        lat, lon = number(row[col["Breitengrad"]]), number(row[col["Längengrad"]])
        points = [p for p in (charge_point(row, c) for c in plug_columns) if p]
        if lat is None or lon is None or not (47 <= lat <= 56 and 5 <= lon <= 16):
            skipped += 1
            continue
        operator = clean(row[col["Anzeigename (Karte)"]]) or clean(row[col["Betreiber"]])
        # Rows are per charging device; a site is everything of one operator on one spot.
        site = sites.setdefault((round(lat, 4), round(lon, 4), operator), {
            "id": row[0], "operator": operator, "lat": lat, "lon": lon, "points": [], "working": False,
            "name": clean(row[col["Standortbezeichnung"]]),
            "street": clean(f'{row[col["Straße"]]} {row[col["Hausnummer"]]}'),
            "postcode": clean(row[col["Postleitzahl"]]), "town": clean(row[col["Ort"]]),
            "hours": hours(row[col["Öffnungszeiten"]], row[col["Öffnungszeiten: Wochentage"]],
                           row[col["Öffnungszeiten: Tageszeiten"]]),
            "access": clean(row[col["Informationen zum Parkraum"]]),
        })
        site["points"] += points
        site["working"] |= row[col["Status"]] == "In Betrieb"

    tiles = {}
    for s in sites.values():
        name = f"{math.floor(s['lat'] * 2)}_{math.floor(s['lon'] * 2)}.tsv"
        tiles.setdefault(name, []).append("\t".join([
            s["id"], s["operator"], s["name"], s["street"], s["postcode"], s["town"],
            f"{s['lat']:.6f}", f"{s['lon']:.6f}", "1" if s["working"] else "0",
            s["hours"], s["access"], "|".join(s["points"]),
        ]))
    shutil.rmtree(OUT, ignore_errors=True)
    OUT.mkdir(parents=True)
    (OUT / "stand.txt").write_text(stand + "\n", encoding="utf-8")
    for name, lines in tiles.items():
        (OUT / name).write_text("\n".join(sorted(lines)) + "\n", encoding="utf-8")
    print(f"Stand {stand}: {len(rows)} Einrichtungen, {len(sites)} Standorte, "
          f"{len(tiles)} Kacheln, {skipped} ohne gültige Lage übersprungen")


main()
