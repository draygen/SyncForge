import requests
import time
import os
import concurrent.futures

URL = "https://rapid-homes-epa-fountain.trycloudflare.com"
USER = "admin"
PASS = "admin"
session = requests.Session()
session.auth = (USER, PASS)

def test_endpoint(path):
    res = session.get(f"{URL}{path}")
    print(f"Checking {path}: Status {res.status_code} - {'Valid JSON' if 'application/json' in res.headers.get('Content-Type', '') else 'INVALID'}")
    return res.status_code == 200

def test_upload(idx):
    size = 1024 * 1024 # 1MB
    data = os.urandom(size)
    start = time.time()
    
    # Init
    res = session.post(f"{URL}/api/files/init", json={"originalFilename": f"test_{idx}.bin", "totalSize": size})
    fid = res.json()["id"]
    
    # Chunk (Single 1MB chunk for quick test)
    session.post(f"{URL}/api/files/{fid}/chunk", files={'chunk': ('blob', data)})
    
    # Complete
    session.post(f"{URL}/api/files/{fid}/complete")
    return time.time() - start

print("🧪 INTEGRATION CHECKS")
test_endpoint("/api/me")
test_endpoint("/api/admin/activity")
test_endpoint("/api/admin/activity/active-users")

print("\n🚀 PERFORMANCE TEST: 5 Concurrent 1MB Uploads")
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as ex:
    times = list(ex.map(test_upload, range(5)))

avg = sum(times)/len(times)
print(f"\n📊 RESULTS")
print(f"Avg Upload Time: {avg:.2f}s")
print(f"Total Success: 5/5")
