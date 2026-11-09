import zipfile
apk = r"app/build/outputs/apk/debug/app-debug.apk"
z = zipfile.ZipFile(apk)
names = z.namelist()
print("entries", len(names))
dexes = [n for n in names if n.endswith(".dex")]
print("dex files", len(dexes))
target = b"com/microsoft/device/display/DisplayMask"
hits = [n for n in dexes if target in z.read(n)]
print("display-mask class in dex:", hits)
msal = [n for n in names if "msal" in n.lower()]
print("msal-related entries:", msal)
lic = [n for n in names if n.startswith("assets/licenses/")]
print("packaged licence assets:", lic)
env = {}
for line in open(".env", encoding="utf-8"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip()
print("env keys present:", sorted(env))
directory_id = env.get("Directory_ID", "")
print("has Directory_ID value:", bool(directory_id))
scan = [n for n in names if n.endswith((".dex", ".json", ".xml", ".arsc", ".properties")) or n.startswith("assets/")]
found_dir = [n for n in scan if directory_id and directory_id.encode() in z.read(n)]
print("Directory_ID present in APK entries:", found_dir)
secret_hits = [n for n in names if n.endswith((".dex", ".json")) and b"client_secret" in z.read(n)]
print("client_secret markers:", secret_hits)
key_hits = [n for n in names if n.endswith((".dex", ".json", ".txt")) and b"storePassword" in z.read(n)]
print("storePassword markers:", key_hits)
print("dotenv entries:", [n for n in names if n.endswith(".env") or n.split("/")[-1] in (".env", ".env.example")])
