from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
import os
import time

# SyncForge Test Configuration
URL = "https://rapid-homes-epa-fountain.trycloudflare.com/"
USER = "admin"
PASS = "admin"

chrome_options = Options()
chrome_options.add_argument("--headless")
chrome_options.add_argument("--no-sandbox")
chrome_options.add_argument("--disable-dev-shm-usage")
chrome_options.add_argument("--disable-gpu")
chrome_options.add_argument("--window-size=1920,1080")
chrome_options.add_argument("--remote-debugging-port=9222")

driver = webdriver.Chrome(options=chrome_options)

def handle_alert():
    try:
        WebDriverWait(driver, 3).until(EC.alert_is_present())
        alert = driver.switch_to.alert
        print(f"🔔 Alert handled: {alert.text}")
        alert.accept()
        return True
    except:
        return False

def wait_for_text(element_id, text, timeout=20):
    start_time = time.time()
    while time.time() - start_time < timeout:
        try:
            element = driver.find_element(By.ID, element_id)
            if text in element.text:
                return True
        except:
            pass
        time.sleep(2)
    return False

try:
    print(f"🚀 Navigating to {URL}...")
    driver.get(URL)

    # 1. Login
    print(f"🔑 Authenticating as {USER}...")
    WebDriverWait(driver, 15).until(EC.presence_of_element_located((By.ID, "login-user")))
    driver.find_element(By.ID, "login-user").send_keys(USER)
    driver.find_element(By.ID, "login-pass").send_keys(PASS)
    driver.find_element(By.CSS_SELECTOR, ".btn-glow").click()

    # Handle potential "Invalid Authentication" alert immediately
    time.sleep(3)
    if handle_alert():
        print("❌ Login failed via UI alert.")
        # Try lowercase just in case
        driver.refresh()
        WebDriverWait(driver, 15).until(EC.presence_of_element_located((By.ID, "login-user")))
        driver.find_element(By.ID, "login-user").send_keys("admin")
        driver.find_element(By.ID, "login-pass").send_keys(PASS)
        driver.find_element(By.CSS_SELECTOR, ".btn-glow").click()
        time.sleep(3)
        handle_alert()

    # 2. Verify Dashboard Load
    print("📊 Waiting for dashboard...")
    WebDriverWait(driver, 20).until(EC.presence_of_element_located((By.ID, "dashboard")))
    print("✅ Dashboard loaded.")
    
    # 3. Verify Activity Terminal exists
    print("📟 Checking Live Activity Terminal...")
    terminal = WebDriverWait(driver, 10).until(EC.visibility_of_element_located((By.ID, "activity-terminal")))
    if terminal:
        print("✅ Terminal is visible.")

    # 4. Verify LOGIN event in Terminal
    print("🔎 Searching for LOGIN event in terminal...")
    if wait_for_text("activity-body", "LOGIN"):
        print("✅ LOGIN event detected in real-time stream!")
    else:
        print("⚠️ Warning: LOGIN event not found in terminal yet.")

    # 5. Perform Upload
    print("📤 Performing Quick Upload...")
    test_file_path = os.path.abspath("selenium_live_test.txt")
    with open(test_file_path, "w") as f:
        f.write("Selenium live activity test content.")

    upload_input = driver.find_element(By.ID, "web-upload-input")
    upload_input.send_keys(test_file_path)

    # 6. Wait for Upload Alert
    print("⏳ Waiting for upload completion alert...")
    WebDriverWait(driver, 30).until(EC.alert_is_present())
    alert = driver.switch_to.alert
    print(f"✅ Alert received: {alert.text}")
    alert.accept()

    # 7. Verify UPLOAD_INIT/COMPLETE in Terminal
    print("🔎 Searching for UPLOAD events in terminal...")
    if wait_for_text("activity-body", "UPLOAD_INIT"):
        print("🎉 SUCCESS: Real-time UPLOAD_INIT detected in Terminal!")
    else:
        print("❌ FAILURE: Upload event never appeared in Terminal.")

finally:
    driver.quit()
    if os.path.exists("selenium_live_test.txt"):
        os.remove("selenium_live_test.txt")
