import re, json, sys
h = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', h, re.S)
print("next_data", bool(m), "html bytes", len(h))
if not m:
    sys.exit(0)
d = json.loads(m.group(1))
seen = set()
def walk(o, p=""):
    if isinstance(o, dict):
        for k, v in o.items():
            walk(v, p + "." + k)
    elif isinstance(o, list):
        print(p, "len", len(o))
        if o:
            walk(o[0], p + "[0]")
    else:
        if p not in seen:
            seen.add(p)
            print(p, "=", ("<token %d chars>" % len(str(o))) if "oken" in p else str(o)[:70])
walk(d)
