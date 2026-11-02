"""Builds overlays.pmtiles with the bike and hiking layers from Geofabrik extracts.

Classification and tiling follow https://github.com/hsph/runair/blob/main/docs/overlays.md.
Usage: docker run -v <in>:/in -v <out>:/out ailectric-overlays /in/germany.osm.pbf /in/netherlands.osm.pbf
"""

import json
import re
import subprocess
import sys
from collections.abc import Iterable, Iterator
from pathlib import Path

BIKE_FILTERS = [
    "w/highway=cycleway", "w/bicycle=designated", "w/bicycle_road=yes", "w/cyclestreet=yes",
    "w/cycleway=lane,track", "w/cycleway:both=lane,track",
    "w/cycleway:left=lane,track", "w/cycleway:right=lane,track",
]
ROADSIDE_KEYS = ("cycleway", "cycleway:both", "cycleway:left", "cycleway:right")
SHARED_PATHS = {"path", "footway", "track", "bridleway", "pedestrian"}
# Lower is more important. Ways shared by several routes keep the most important one.
NETWORK_RANK = {"iwn": 0, "nwn": 1, "rwn": 2, "lwn": 3}
UNKNOWN_NETWORK_RANK = 4
OPL_ESCAPE = re.compile(r"%([0-9a-fA-F]+)%")
EXPORT = ["osmium", "export", "-O", "-f", "geojsonseq", "-x", "print_record_separator=false", "--geometry-types=linestring"]


def run(*args: object) -> None:
    subprocess.run([str(a) for a in args], check=True)


def bike_class(tags: dict[str, str]) -> str | None:
    if tags.get("access") in ("private", "no"):
        return None
    highway = tags.get("highway")
    if highway == "cycleway":
        return "path"
    if tags.get("bicycle") == "designated" and highway in SHARED_PATHS:
        return "path"
    if any(tags.get(key) in ("lane", "track") for key in ROADSIDE_KEYS):
        return "lane"
    if tags.get("bicycle_road") == "yes" or tags.get("cyclestreet") == "yes":
        return "lane"
    return None


def decode_opl(text: str) -> str:
    return OPL_ESCAPE.sub(lambda match: chr(int(match[1], 16)), text)


def parse_relation(line: str) -> tuple[dict[str, str], list[str]]:
    tags: dict[str, str] = {}
    ways: list[str] = []
    for field in line.split():
        if field.startswith("T") and len(field) > 1:
            for pair in field[1:].split(","):
                key, _, value = pair.partition("=")
                tags[decode_opl(key)] = decode_opl(value)
        elif field.startswith("M") and len(field) > 1:
            ways = [member.split("@", 1)[0] for member in field[1:].split(",") if member.startswith("w")]
    return tags, ways


def route_index(lines: Iterable[str]) -> dict[str, tuple[int, str, str]]:
    index: dict[str, tuple[int, str, str]] = {}
    for line in lines:
        tags, ways = parse_relation(line)
        rank = NETWORK_RANK.get(tags.get("network", ""), UNKNOWN_NETWORK_RANK)
        for way in ways:
            if way not in index or rank < index[way][0]:
                index[way] = (rank, tags.get("name", ""), tags.get("ref", ""))
    return index


def read_features(path: Path) -> Iterator[dict]:
    with path.open(encoding="utf-8") as lines:
        for line in lines:
            if line.strip():
                yield json.loads(line)


def write_features(path: Path, features: Iterable[dict]) -> int:
    count = 0
    with path.open("w", encoding="utf-8") as out:
        for feature in features:
            out.write(json.dumps(feature, ensure_ascii=False) + "\n")
            count += 1
    return count


def bike_features(path: Path) -> Iterator[dict]:
    for feature in read_features(path):
        kind = bike_class(feature["properties"])
        if kind:
            yield {"type": "Feature", "geometry": feature["geometry"], "properties": {"class": kind}}


def hike_features(path: Path, routes: dict[str, tuple[int, str, str]]) -> Iterator[dict]:
    for feature in read_features(path):
        route = routes.get(str(feature.get("id")))
        if route:
            rank, name, ref = route
            yield {"type": "Feature", "geometry": feature["geometry"], "properties": {"rank": rank, "name": name, "ref": ref}}


def filtered(regions: list[Path], filters: list[str], target: Path) -> None:
    """Extracts overlap at borders, so the parts are merged rather than concatenated."""
    parts = [target.with_suffix(f".{i}.pbf") for i in range(len(regions))]
    for region, part in zip(regions, parts):
        run("osmium", "tags-filter", "-O", region, *filters, "-o", part)
    run("osmium", "merge", "-O", *parts, "-o", target)


def main() -> None:
    regions = [Path(arg) for arg in sys.argv[1:]]
    work = Path("/tmp/overlays")
    work.mkdir(exist_ok=True)

    filtered(regions, BIKE_FILTERS, work / "bike.osm.pbf")
    run(*EXPORT, "-o", work / "bike.geojsonseq", work / "bike.osm.pbf")
    bikes = write_features(work / "bike.geojson", bike_features(work / "bike.geojsonseq"))

    filtered(regions, ["r/route=hiking,foot"], work / "hike.osm.pbf")
    run(*EXPORT, "--add-unique-id=type_id", "-o", work / "hike.geojsonseq", work / "hike.osm.pbf")
    run("osmium", "cat", "-O", "-t", "relation", "-f", "opl", "-o", work / "hike.opl", work / "hike.osm.pbf")
    with (work / "hike.opl").open(encoding="utf-8") as lines:
        routes = route_index(lines)
    hikes = write_features(work / "hike.geojson", hike_features(work / "hike.geojsonseq", routes))
    print(f"{bikes} bike and {hikes} hiking ways")

    target = Path("/out/overlays.pmtiles")
    run(
        "tippecanoe", "--quiet", "--force", "--read-parallel", "--no-feature-limit", "--no-tile-size-limit",
        "--minimum-zoom=9", "--maximum-zoom=14", f"--temporary-directory={work}",
        "-L", f"bike:{work / 'bike.geojson'}", "-L", f"hike:{work / 'hike.geojson'}", "-o", target,
    )
    run("pmtiles", "verify", target)


if __name__ == "__main__":
    main()
