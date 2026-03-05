from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
import time

URL = "https://rapid-homes-epa-fountain.trycloudflare.com/"
USER = "admin"
PASS = "admin"

opts = Options()
opts.add_argument("--headless")
opts.add_argument("--no-sandbox")
opts.add_argument("--disable-dev-shm-usage")
opts.add_argument("--disable-gpu")
driver = webdriver.Chrome(options=opts)

try:
    print(f"Testing {URL} with {USER}/{PASS}")
    driver.get(URL)
    WebDriverWait(driver, 10).until(EC.presence_of_element_located((By.ID, "login-user")))
    driver.find_element(By.ID, "login-user").send_keys(USER)
    driver.find_element(By.ID, "login-pass").send_keys(PASS)
    driver.find_element(By.CSS_SELECTOR, ".btn-glow").click()
    
    time.sleep(5)
    # Check for dashboard existence
    dash = driver.find_elements(By.ID, "dashboard")
    if dash and dash[0].is_displayed():
        print("✅ SUCCESS: Logged in!")
    else:
        print("❌ FAILED: Still on login or error page.")
        # Check for alerts
        try:
            alert = driver.switch_to.alert
            print("Alert found:", alert.text)
            alert.accept()
        except:
            print("No browser alert found.")
finally:
    driver.quit()
