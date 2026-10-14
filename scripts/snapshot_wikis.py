#!/usr/bin/env python3
"""Save a snapshot of real wikis for the store screenshots.

For each wiki this writes app/src/test/resources/wikis/<key>.json and <key>.ico:
the site's name and namespaces, its latest changes and logs, one recently edited
page with its text, history and the diff of its latest edit, and a few search
results. The screenshot test serves these through the fake wiki.

Everything saved is public on the wiki. IP addresses of logged-out editors are
replaced with ones from the range reserved for examples.

usage: scripts/snapshot_wikis.py
"""
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/test/resources/wikis"
UA = "WikiMop snapshot script (https://github.com/ganyuke/WikiMop)"

# key -> (host, only keep pages in a category whose name contains this)
WIKIS = {
    "wandering-witch": ("wandering-witch.fandom.com", None),
    "witch-hat-atelier": ("witch-hat-atelier.fandom.com", None),
    "virtualyoutuber": ("virtualyoutuber.fandom.com", "hololive"),
}
IP = re.compile(r"^(\d{1,3}\.){3}\d{1,3}$|^[0-9A-Fa-f:]+:[0-9A-Fa-f:]+$")


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def api(host, **params):
    params.update(format="json", formatversion="2")
    return json.loads(fetch(f"https://{host}/api.php?" + urllib.parse.urlencode(params)))


def in_category(host, titles, word):
    """The titles that are in a category with [word] in its name."""
    kept = set()
    titles = sorted(set(titles))
    for i in range(0, len(titles), 40):
        pages = api(host, action="query", prop="categories", cllimit="max", titles="|".join(titles[i:i + 40]))["query"]["pages"]
        for page in pages:
            if any(word in c["title"].lower() for c in page.get("categories", [])):
                kept.add(page["title"])
    return kept


def snapshot(key, host, category):
    site = api(host, action="query", meta="siteinfo", siprop="general|namespaces",
               titles="File:Site-favicon.ico", prop="imageinfo", iiprop="url")["query"]
    general = site["general"]
    icon = site["pages"][0].get("imageinfo", [{}])[0].get("url")

    changes = api(host, action="query", list="recentchanges", rctype="edit|new|log", rclimit="500" if category else "40",
                  rcprop="title|ids|sizes|flags|user|comment|timestamp|tags|loginfo")["query"]["recentchanges"]
    if category:
        keep = in_category(host, [c["title"] for c in changes if c["ns"] == 0], category)
        changes = [c for c in changes if c["title"] in keep]
    changes = changes[:25]

    # The page shown in the diff, history and editor: the newest article edit with a summary that is not huge.
    sample = next((c for c in changes if c["type"] == "edit" and c["ns"] == 0 and c.get("comment") and c["newlen"] < 9000),
                  next(c for c in changes if c["type"] == "edit" and c["ns"] == 0))
    title = sample["title"]
    page = api(host, action="query", prop="revisions", titles=title, rvlimit="6", rvslots="main",
               rvprop="ids|user|timestamp|comment|size|tags|content")["query"]["pages"][0]
    revisions = page["revisions"]
    text = revisions[0]["slots"]["main"]["content"]
    for revision in revisions:
        revision.pop("slots", None)
    compare = api(host, action="compare", fromrev=str(revisions[1]["revid"]), torev=str(revisions[0]["revid"]),
                  prop="diff|ids|title|user|comment|size|timestamp")["compare"]
    sections = api(host, action="parse", page=title, prop="sections")["parse"]["sections"]

    word = title.split()[0]
    search = api(host, action="query", list="search", srsearch=word, srlimit="6", srprop="snippet|size")["query"]["search"]
    prefix = api(host, action="query", list="prefixsearch", pssearch=word[:3], pslimit="6")["query"]["prefixsearch"]
    logs = api(host, action="query", list="logevents", lelimit="8",
               leprop="ids|title|type|user|timestamp|comment|details")["query"]["logevents"]

    saved = {
        "host": host,
        "general": {k: general[k] for k in ("sitename", "mainpage")},
        "namespaces": [{"id": n["id"], "name": n["name"]} for n in site["namespaces"].values() if n["id"] >= 0],
        "changes": changes,
        "page": {"title": title, "pageid": page["pageid"], "text": text, "revisions": revisions,
                 "sections": [{"index": s["index"], "level": s["level"], "line": s["line"]} for s in sections]},
        "compare": compare,
        "search": search,
        "prefixsearch": [{"title": p["title"]} for p in prefix],
        "logs": logs,
    }
    # Logged-out editors are public on the wiki, but their addresses do not belong in a screenshot.
    text_form = json.dumps(saved, ensure_ascii=False, indent=1)
    names = {c.get("user") for c in changes} | {r.get("user") for r in revisions} | {l.get("user") for l in logs}
    names |= {compare.get("fromuser"), compare.get("touser")}
    for n, address in enumerate(sorted(a for a in names if a and IP.match(a))):
        text_form = text_form.replace(address, f"203.0.113.{40 + n}")

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{key}.json").write_text(text_form + "\n")
    if icon:
        (OUT / f"{key}.ico").write_bytes(fetch(icon))
    print(f"{key}: {general['sitename']}, {len(changes)} changes, page \"{title}\" ({len(text)} characters)" + ("" if icon else ", no icon"))


if __name__ == "__main__":
    for key, (host, category) in WIKIS.items():
        snapshot(key, host, category)
