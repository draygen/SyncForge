import requests
import time
import os
import concurrent.futures

URL = "https://rapid-homes-epa-fountain.trycloudflare.com"
USER = "admin"
PASS = "admin"

def run_upload_benchmark(file_name, size_mb):
    session = requests.Session()
    session.auth = (USER, PASS)
    
    chunk_size = 128 * 1024 # 128KB
    total_bytes = int(size_mb * 1024 * 1024)
    data = os.urandom(total_bytes)
    
    start = time.time()
    try:
        # 1. Init
        res = session.post(f"{URL}/api/files/init", json={"originalFilename": file_name, "totalSize": total_bytes})
        if res.status_code != 200: return False, 0
        file_id = res.json()["id"]
        
        # 2. Sequential Chunks
        offset = 0
        while offset < total_bytes:
            chunk = data[offset:offset+chunk_size]
            c_res = session.post(f"{URL}/api/files/{file_id}/chunk", files={'chunk': ('blob', chunk)})
            if c_res.status_code != 200: return False, 0
            offset += chunk_size
            
        # 3. Complete
        session.post(f"{URL}/api/files/{file_id}/complete")
        duration = time.time() - start
        return True, duration
    except Exception as e:
        return False, 0

print("🚀 Rapid Test: 5 Concurrent 1MB Uploads...")
global_start = time.time()

with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
    futures = [executor.submit(run_upload_benchmark, f"rapid_test_{i}.bin", 1) for i in range(5)]
    results = [f.result() for f in concurrent.futures.as_completed(futures)]

total_time = time.time() - global_start
successes = sum(1 for r, d in results if r)
avg_speed = (successes * 1) / total_time if successes > 0 else 0

print(f"\n📊 RAPID RESULTS")
print(f"------------------")
print(f"Total Successful Transferred: {successes} MB")
print(f"Total Time:        {total_time:.2f}s")
print(f"Success Rate:      {successes}/5")
print(f"Effective Speed:   {avg_speed:.2f} MB/s")
print(f"------------------")
