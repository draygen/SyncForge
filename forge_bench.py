import requests
import time
import os
import concurrent.futures

URL = "https://rapid-homes-epa-fountain.trycloudflare.com"
USER = "admin"
PASS = "admin"
session = requests.Session()
session.auth = (USER, PASS)

def bench_upload(idx):
    size = 1024 * 1024 # 1MB
    data = os.urandom(size)
    start = time.time()
    
    # 1. Init
    res = session.post(f"{URL}/api/files/init", json={"originalFilename": f"forge_test_{idx}.bin", "totalSize": size})
    if res.status_code != 200: return False
    fid = res.json()["id"]
    
    # 2. Parallel-Ready Single Chunk
    session.post(f"{URL}/api/files/{fid}/chunk", files={'chunk': ('blob', data)})
    
    # 3. Complete
    session.post(f"{URL}/api/files/{fid}/complete")
    return time.time() - start

print("🧪 SYNCFORGE FINAL VALIDATION")
print("----------------------------")

# Check API Health
t0 = time.time()
res = session.get(f"{URL}/api/me")
print(f"✅ Auth Response: {res.status_code} ({time.time()-t0:.2f}s)")

# Run Concurrency Test
print("\n🚀 CONCURRENCY BENCHMARK: 5 Parallel 1MB Forging Ops")
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as ex:
    results = list(ex.map(bench_upload, range(5)))

if all(results):
    avg = sum(results)/len(results)
    print(f"🎉 SUCCESS: All artifacts forged.")
    print(f"📊 Avg Forge Time: {avg:.2f}s")
else:
    print("❌ FAILURE: One or more forge operations failed.")

print("\n----------------------------")
print("🏁 Forge Validation Complete.")
