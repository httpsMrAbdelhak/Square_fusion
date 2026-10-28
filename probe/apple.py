import re, json, sys
h = open(sys.argv[1], encoding="utf-8").read()
seen = set()
def walk(o, p="", depth=0):
    if depth > 9: return
    if isinstance(o, dict):
        for k, v in o.items():
            if k in ("impressionMetrics", "actionMetrics", "videoArtwork", "tallVideoArtwork", "pageMetrics"): continue
            walk(v, p + "." + k, depth + 1)
    elif isinstance(o, list):
        print(p, "len", len(o))
        if o: walk(o[0], p + "[0]", depth + 1)
    else:
        key = re.sub(r"\[\d+\]", "[]", p)
        if key not in seen:
            seen.add(key); print(p, "=", str(o)[:70])
m = re.search(r'<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>', h, re.S)
d = json.loads(m.group(1))
for i, sec in enumerate(d["data"][0]["data"]["sections"]):
    print("== section", i, sec.get("id"), sec.get("itemKind"), "items", len(sec.get("items", [])))
    if i == 1:
        walk(sec["items"][0], "item0")
        print("second title:", sec["items"][1].get("title"), "|", sec["items"][1].get("subtitleLinks", [{}])[0].get("title"))
m = re.search(r'<script[^>]*id="schema:music-playlist"[^>]*>(.*?)</script>', h, re.S)
print("== LD"); seen.clear(); walk(json.loads(m.group(1)))
