import re, json, sys
h = open(sys.argv[1], encoding="utf-8").read()
print("bytes", len(h))
for m in re.finditer(r'<script([^>]*)>', h):
    attrs = m.group(1)
    end = h.find("</script>", m.end())
    print("script", attrs.strip()[:90], "len", end - m.end())
seen = set()
def walk(o, p="", depth=0):
    if depth > 14: return
    if isinstance(o, dict):
        for k, v in o.items(): walk(v, p + "." + k, depth + 1)
    elif isinstance(o, list):
        print(p, "len", len(o))
        if o: walk(o[0], p + "[0]", depth + 1)
    else:
        key = re.sub(r"\[\d+\]", "[]", p)
        if key not in seen:
            seen.add(key); print(p, "=", str(o)[:80])
for sid in ("serialized-server-data", "schema:music-playlist"):
    m = re.search(r'<script[^>]*id="%s"[^>]*>(.*?)</script>' % sid, h, re.S)
    if m:
        print("== JSON", sid)
        try: walk(json.loads(m.group(1)))
        except Exception as e: print("parse error", e)
for m in re.finditer(r'<script type="application/ld\+json"[^>]*>(.*?)</script>', h, re.S):
    print("== LD"); walk(json.loads(m.group(1)))
