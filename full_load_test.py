import requests
import base64
import time
import os
import concurrent.futures
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC

URL = "https://rapid-homes-epa-fountain.trycloudflare.com"
USER = "admin"
PASS = "admin"

print("=========================================")
print("🚀 SyncForge Full Load Benchmark Suite 🚀")
print("=========================================\n")

# --- 1. UI LOAD & AUTH TEST (Selenium) ---
print("[1/3] Testing Web UI Performance (Selenium)...")
chrome_options = Options()
chrome_options.add_argument("--headless")
chrome_options.add_argument("--no-sandbox")
chrome_options.add_argument("--disable-dev-shm-usage")
chrome_options.add_argument("--disable-gpu")
chrome_options.add_argument("--window-size=1920,1080")
chrome_options.add_argument("--remote-debugging-port=9222")

driver = webdriver.Chrome(options=chrome_options)
ui_start = time.time()
try:
    driver.get(URL)
    WebDriverWait(driver, 15).until(EC.presence_of_element_located((By.ID, "login-user")))
    driver.find_element(By.ID, "login-user").send_keys(USER)
    driver.find_element(By.ID, "login-pass").send_keys(PASS)
    
    login_click_time = time.time()
    driver.find_element(By.CSS_SELECTOR, ".btn-glow").click()
    WebDriverWait(driver, 15).until(EC.presence_of_element_located((By.ID, "dashboard")))
    login_duration = time.time() - login_click_time
    
    # Wait for NOC monitor to load data
    WebDriverWait(driver, 10).until(EC.visibility_of_element_located((By.ID, "noc-monitor")))
    ui_total = time.time() - ui_start
    print(f"✅ UI Load & Login Success. Total Time: {ui_total:.2f}s (Auth processing: {login_duration:.2f}s)")
except Exception as e:
    print(f"❌ UI Test Failed: {e}")
finally:
    driver.quit()

# --- 2. API CONCURRENCY TEST (Requests) ---
print("\n[2/3] Testing API Concurrency (100 Simultaneous Auth Requests)...")
auth_str = f"{USER}:{PASS}"
encoded_auth = base64.b64encode(auth_str.encode()).decode()
headers = {"Authorization": f"Basic {encoded_auth}"}

def make_request():
    start = time.time()
    res = requests.get(f"{URL}/api/me", headers=headers, timeout=10)
    return res.status_code, time.time() - start

concurrency_start = time.time()
successes = 0
failures = 0
latencies = []

with concurrent.futures.ThreadPoolExecutor(max_workers=50) as executor:
    futures = [executor.submit(make_request) for _ in range(100)]
    for future in concurrent.futures.as_completed(futures):
        try:
            status, latency = future.result()
            latencies.append(latency)
            if status == 200:
                successes += 1
            else:
                failures += 1
        except Exception:
            failures += 1

concurrency_total = time.time() - concurrency_start
avg_latency = sum(latencies) / len(latencies) if latencies else 0
print(f"✅ API Test Complete. Time: {concurrency_total:.2f}s")
print(f"📊 Results - Success: {successes}/100, Failures: {failures}, Avg Latency: {avg_latency:.3f}s")


# --- 3. HIGH-THROUGHPUT FILE TRANSFER TEST ---
print("\n[3/3] Testing High-Throughput Chunked File Transfer...")
FILE_SIZE_MB = 50
CHUNK_SIZE = 5 * 1024 * 1024 # 5MB

print(f"Generating {FILE_SIZE_MB}MB payload...")
test_data = os.urandom(FILE_SIZE_MB * 1024 * 1024)

upload_start = time.time()
# Init
res = requests.post(
    f"{URL}/api/files/init",
    headers={"Authorization": headers["Authorization"], "Content-Type": "application/json"},
    json={"originalFilename": "benchmark_payload.bin", "totalSize": len(test_data)}
)
if res.status_code == 200:
    file_id = res.json()["id"]
    print(f"✅ Upload Initialized. ID: {file_id}")
    
    # Upload chunks sequentially to test raw single-thread throughput through Cloudflare
    offset = 0
    chunk_index = 0
    while offset < len(test_data):
        chunk = test_data[offset:offset+CHUNK_SIZE]
        files = {'chunk': ('blob', chunk)}
        
        chunk_start = time.time()
        c_res = requests.post(f"{URL}/api/files/{file_id}/chunk", headers={"Authorization": headers["Authorization"]}, files=files)
        chunk_time = time.time() - chunk_start
        
        speed_mbps = (len(chunk) / 1024 / 1024) / chunk_time
        print(f"   -> Chunk {chunk_index} ({len(chunk)/1024/1024:.1f}MB) uploaded in {chunk_time:.2f}s [{speed_mbps:.1f} MB/s]")
        
        offset += CHUNK_SIZE
        chunk_index += 1
    
    # Complete
    requests.post(f"{URL}/api/files/{file_id}/complete", headers={"Authorization": headers["Authorization"]})
    upload_total = time.time() - upload_start
    total_speed = FILE_SIZE_MB / upload_total
    print(f"✅ Full Transfer Complete. Total Time: {upload_total:.2f}s, Avg Speed: {total_speed:.1f} MB/s")
else:
    print(f"❌ Upload Init Failed: {res.status_code} - {res.text}")

print("\n=========================================")
print("🏁 Benchmark Suite Finished.")
