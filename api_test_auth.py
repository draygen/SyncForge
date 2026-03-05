import requests
import base64

URL = "https://rapid-homes-epa-fountain.trycloudflare.com/api/me"
USER = "admin"
PASS = "admin"

print(f"Testing API Auth at {URL} with {USER}:{PASS}")

# We use session to track JSESSIONID
session = requests.Session()
auth_str = f"{USER}:{PASS}"
encoded_auth = base64.b64encode(auth_str.encode()).decode()

headers = {
    "Authorization": f"Basic {encoded_auth}"
}

try:
    response = session.get(URL, headers=headers)
    print(f"Status Code: {response.status_code}")
    if response.status_code == 200:
        print("✅ SUCCESS: API Authenticated!")
        print("User Profile:", response.json())
    else:
        print(f"❌ FAILED: Status {response.status_code}")
        print("Response:", response.text)
except Exception as e:
    print(f"💥 Error: {e}")
