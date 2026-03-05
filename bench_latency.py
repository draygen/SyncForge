import requests
import time
import concurrent.futures

URL = "https://rapid-homes-epa-fountain.trycloudflare.com/api/me"
session = requests.Session()
session.auth = ("admin", "admin")

def get_latency():
    start = time.time()
    res = session.get(URL)
    return time.time() - start

with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
    latencies = list(ex.map(lambda _: get_latency(), range(50)))

print(f"Avg API Latency: {sum(latencies)/len(latencies):.3f}s")
