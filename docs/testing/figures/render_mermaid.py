"""Renders a Mermaid file to SVG and PNG with the local Google Chrome, for the Lab 4 control flow graphs.

Usage: python3 -I docs/testing/figures/render_mermaid.py docs/testing/figures/<name>.mmd
Writes <name>.svg and <name>.png next to the .mmd file. Needs Google Chrome and internet access (Mermaid 10.9.1 is
loaded from cdn.jsdelivr.net); nothing is installed.
"""
import html
import pathlib
import re
import subprocess
import sys
import tempfile

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
MERMAID = "https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js"


def chrome(args, profile):
    return subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-first-run", "--hide-scrollbars",
                           f"--user-data-dir={profile}", *args], capture_output=True, text=True, timeout=120)


def main(source):
    src = pathlib.Path(source).resolve()
    code = src.read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory() as tmp:
        page = pathlib.Path(tmp, "draw.html")
        page.write_text(f"""<!doctype html><meta charset="utf-8"><body style="margin:0;background:#fff">
<pre class="mermaid">{html.escape(code)}</pre>
<script src="{MERMAID}"></script>
<script>mermaid.initialize({{startOnLoad: true, securityLevel: "strict", theme: "neutral",
  flowchart: {{htmlLabels: false, useMaxWidth: false}}}});</script>""", encoding="utf-8")
        dom = chrome(["--virtual-time-budget=15000", "--dump-dom", page.as_uri()], pathlib.Path(tmp, "p1")).stdout
        match = re.search(r"<svg[\s\S]*?</svg>", dom)
        if not match:
            sys.exit(f"No SVG was drawn for {src.name}: check the Mermaid syntax and the internet connection.")
        svg = match.group(0)
        box = re.search(r'viewBox="([-\d.]+) ([-\d.]+) ([\d.]+) ([\d.]+)"', svg)
        width, height = (int(float(box.group(3))) + 20, int(float(box.group(4))) + 20) if box else (1200, 1600)
        if 'xmlns="http://www.w3.org/2000/svg"' not in svg:
            svg = svg.replace("<svg", '<svg xmlns="http://www.w3.org/2000/svg"', 1)
        src.with_suffix(".svg").write_text(svg, encoding="utf-8")
        shot = pathlib.Path(tmp, "shot.html")
        shot.write_text(f'<!doctype html><meta charset="utf-8"><body style="margin:10px;background:#fff">{svg}',
                        encoding="utf-8")
        chrome([f"--window-size={width},{height}", "--force-device-scale-factor=2",
                f"--screenshot={src.with_suffix('.png')}", shot.as_uri()], pathlib.Path(tmp, "p2"))
    print(f"{src.with_suffix('.svg').name} and {src.with_suffix('.png').name}: {width} x {height}")


if __name__ == "__main__":
    for name in sys.argv[1:]:
        main(name)
