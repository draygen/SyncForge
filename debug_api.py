import requests
import json

URL = "http://localhost:8888"
auth = ("admin", "Renoise28!")

print("--- Testing API Init Response ---")
res = requests.post(f"{URL}/api/files/init", 
                    json={"originalFilename": "DRYGEN_FORGE_TEST.BIN", "totalSize": 1024},
                    auth=auth)

print(f"Status: {res.status_code}")
print(f"Response JSON: {json.dumps(res.json(), indent=2)}")

file_id = res.json()["id"]

print("\n--- Testing API List Response ---")
res = requests.get(f"{URL}/api/files", auth=auth)
print(f"Status: {res.status_code}")
# Find our file
files = res.json()
for f in files:
    if f["id"] == file_id:
        print(f"Target File in List: {json.dumps(f, indent=2)}")
        break
