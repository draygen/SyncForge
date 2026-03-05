import requests
import time
import os

URL = "https://rapid-homes-epa-fountain.trycloudflare.com"
session = requests.Session()
session.auth = ("admin", "admin")

total_size = 25 * 1024 * 1024
data = os.urandom(total_size)
chunk_size = 1024 * 1024

start = time.time()
res = session.post(f"{URL}/api/files/init", json={"originalFilename": "bench.bin", "totalSize": total_size})
fid = res.json()["id"]

for i in range(0, total_size, chunk_size):
    session.post(f"{URL}/api/files/{fid}/chunk", files={'chunk': ('blob', data[i:i+chunk_size])})

session.post(f"{URL}/api/files/{fid}/complete")
duration = time.time() - start
print(f"Upload Speed (25MB): {25/duration:.2f} MB/s (Total: {duration:.2f}s)")
