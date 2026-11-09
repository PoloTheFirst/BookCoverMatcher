import zipfile, re
def strings_near(blob, needle, pad=90):
    out = []
    start = 0
    while True:
        i = blob.find(needle, start)
        if i < 0:
            break
        seg = blob[max(0, i - pad): i + len(needle) + pad]
        out.append(seg.decode("utf-8", "replace").replace("\x00", "|"))
        start = i + len(needle)
        if len(out) >= 3:
            break
    return out
z = zipfile.ZipFile("app/build/outputs/apk/debug/app-debug.apk")
for entry in ("classes19.dex", "classes4.dex"):
    blob = z.read(entry)
    for needle in (b"storePassword", b"client_secret"):
        hits = strings_near(blob, needle)
        print("===", entry, needle.decode(), "count", len(hits))
        for h in hits:
            print("   ...", h[:160])
