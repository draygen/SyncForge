import requests
import time
import os

URL = "https://rapid-homes-epa-fountain.trycloudflare.com"
session = requests.Session()
session.auth = ("admin", "admin")

print("DEBUG: Initializing 1MB upload...")
res = session.post(f"{URL}/api/files/init", json={"originalFilename": "debug.bin", "totalSize": 1024*1024})
print(f"DEBUG: Init status: {res.status_code}")
if res.status_code == 200:
    file_id = res.json()["id"]
    print(f"DEBUG: Sending chunks (128KB)...")
    for i in range(8):
        start = time.time()
        c_res = session.post(f"{URL}/api/files/{file_id}/chunk", files={'chunk': ('blob', os.urandom(128*1024))})
        print(f"DEBUG: Chunk {i} status: {c_res.status_code} in {time.time()-start:.2f}s")
    session.post(f"{URL}/api/files/{file_id}/complete")
    print("DEBUG: Complete.")
