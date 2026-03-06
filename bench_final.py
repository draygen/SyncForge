import requests
import time
import os

URL = os.environ.get("MFT_SERVER_URL", "https://rapid-homes-epa-fountain.trycloudflare.com")
USER = os.environ.get("MFT_USERNAME", "admin")
PASS = os.environ.get("MFT_PASSWORD", "admin")

session = requests.Session()
session.auth = (USER, PASS)

def run():
    print("Testing 10MB Parallel-Aware Upload (2MB chunks)...")
    total_size = 10 * 1024 * 1024
    data = os.urandom(total_size)
    chunk_size = 2 * 1024 * 1024 # Larger chunks to reduce CF rate limiting
    
    start = time.time()
    try:
        res = session.post(f"{URL}/api/files/init", json={"originalFilename": "final.bin", "totalSize": total_size}, timeout=15)
        if res.status_code != 200:
            print(f"Failed Init: {res.status_code}")
            return
        fid = res.json()["id"]
        
        for i in range(0, total_size, chunk_size):
            chunk = data[i:i+chunk_size]
            c_res = session.post(f"{URL}/api/files/{fid}/chunk", files={'chunk': ('blob', chunk)}, timeout=30)
            print(f"  Chunk {i//chunk_size} uploaded: {c_res.status_code}")
            
        session.post(f"{URL}/api/files/{fid}/complete")
        duration = time.time() - start
        print(f"\n📊 RESULTS")
        print(f"Speed: {10/duration:.2f} MB/s")
        print(f"Total: {duration:.2f}s")
    except Exception as e:
        print(f"Benchmark Error: {e}")

run()
