"""Import WebUI statistics thumbnails and fixed translations from an AzurPilot checkout.

从 AzurPilot 工作区导入 WebUI 的统计缩略图与固定译文。
"""

import argparse
import ast
import io
import json
import re
import subprocess
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from PIL import Image


def main():
    """按 WebUI 的模板优先级生成资源包。 Build an archive using WebUI template precedence."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] /
                        "app/src/main/assets/statistics.zip")
    args = parser.parse_args()
    root = args.upstream.resolve()
    stats = root / "assets/stats"
    icons = {}
    for prefix, sources in (
        ("research", [stats / "research_items", root / "assets/stats_basic"]),
        ("opsi", [stats / "opsi_reward_items", stats / "opsi_items"]),
    ):
        for source in sources:
            for path in sorted(source.glob("*.png")):
                icons.setdefault(f"{prefix}/{path.stem}.webp", path)
    catalog = json.loads((stats / "storage_items/catalog.json").read_text(encoding="utf-8"))
    for item in catalog["items"]:
        template = Path(item["templates"][0])
        icons[f"storage/{template.relative_to('assets/stats').with_suffix('.webp').as_posix()}"] = root / template
    public = root / "frontend/public"
    for name in ("action_asset", "cognitive_chips", "core_data", "cube", "diamond", "gold", "guild_coin",
                 "honor_medal", "merit", "nautical_miles", "oil", "pt", "special_token", "stamina", "supply_token"):
        icons[f"{name}.webp"] = public / f"{name}.webp"

    source = (root / "frontend/src/components/statisticsText.ts").read_text(encoding="utf-8")
    terms = {}
    for block in re.findall(r"const (?:TERMS|SENTENCES): [^\n]+ = \[\n(.*?)\n\]", source, re.S):
        for row in re.findall(r"^\s*(\['(?:\\.|[^'\\])*'\s*,\s*'(?:\\.|[^'\\])*'\s*,\s*"
                              r"'(?:\\.|[^'\\])*'\s*,\s*'(?:\\.|[^'\\])*'\])", block, re.M):
            zh, en, ja, tw = ast.literal_eval(row)
            terms.setdefault(zh, {"en-US": en, "ja-JP": ja, "zh-TW": tw})
    revision = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    out = args.output
    with ZipFile(out, "w") as archive:
        archive.writestr(ZipInfo("source.txt"), f"AzurPilot WebUI {revision}\n")
        archive.writestr(ZipInfo("terms.json"), json.dumps(terms, ensure_ascii=False), compress_type=ZIP_DEFLATED)
        for name, path in sorted(icons.items()):
            with Image.open(path) as image:
                thumb = image.convert("RGBA")
                thumb.thumbnail((112, 112), Image.Resampling.LANCZOS)
                data = io.BytesIO()
                thumb.save(data, "WEBP", quality=88, method=6)
            archive.writestr(ZipInfo(name), data.getvalue())
    print(f"{len(icons)} icons and {len(terms)} translations in {out}")


if __name__ == "__main__":
    main()
