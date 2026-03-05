import time
import os
import unittest
from selenium import webdriver
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC

class SyncForgeE2ETest(unittest.TestCase):
    def setUp(self):
        chrome_options = Options()
        chrome_options.add_argument("--headless")
        chrome_options.add_argument("--no-sandbox")
        chrome_options.add_argument("--disable-dev-shm-usage")
        
        # Use the found chromium path
        self.driver = webdriver.Chrome(options=chrome_options)
        self.base_url = "http://localhost:8888"
        self.wait = WebDriverWait(self.driver, 10)

    def tearDown(self):
        self.driver.quit()

    def test_login_and_dashboard(self):
        self.driver.get(self.base_url)
        
        # Verify login page
        self.wait.until(EC.presence_of_element_located((By.ID, "login-user")))
        print("Login page loaded.")

        # Perform login
        user_input = self.driver.find_element(By.ID, "login-user")
        pass_input = self.driver.find_element(By.ID, "login-pass")
        login_button = self.driver.find_element(By.XPATH, "//button[contains(text(), 'INITIATE CONNECTION')]")

        user_input.send_keys("admin")
        pass_input.send_keys("Renoise28!")
        login_button.click()

        # Wait for dashboard
        self.wait.until(EC.presence_of_element_located((By.ID, "dashboard")))
        print("Logged in successfully.")

        # Verify filename display fix (check if originalFilename is prominent and ID is gone)
        # We need a file to be present. For now just check if the list exists.
        file_list = self.driver.find_element(By.ID, "my-file-list")
        self.assertTrue(file_list.is_displayed())
        print("Dashboard file list is visible.")

        # Check user tag
        user_tag = self.driver.find_element(By.ID, "user-tag")
        self.assertEqual(user_tag.text, "admin")
        print("User tag verified.")

    def test_upload_flow_ui(self):
        # This test simulates the UI side of the upload
        self.driver.get(self.base_url)
        
        # Login
        self.driver.find_element(By.ID, "login-user").send_keys("admin")
        self.driver.find_element(By.ID, "login-pass").send_keys("Renoise28!")
        self.driver.find_element(By.XPATH, "//button[contains(text(), 'INITIATE CONNECTION')]").click()
        
        self.wait.until(EC.presence_of_element_located((By.ID, "dashboard")))
        
        # Create a dummy file for upload
        dummy_file_path = os.path.abspath("selenium_test_artifact.txt")
        with open(dummy_file_path, "w") as f:
            f.write("Draygen Forge E2E Test Content")
            
        try:
            file_input = self.driver.find_element(By.ID, "file-input")
            # We have to bypass the 'display:none' to use send_keys on file input
            self.driver.execute_script("arguments[0].style.display = 'block';", file_input)
            file_input.send_keys(dummy_file_path)
            
            # Wait for upload overlay
            self.wait.until(EC.visibility_of_element_located((By.ID, "upload-overlay")))
            print("Upload overlay triggered.")
            
            # Since the backend might not be running or might fail in this env,
            # we just check if the filename displayed is correct (DUMMY_FILE_PATH base)
            upload_filename = self.driver.find_element(By.ID, "upload-filename")
            self.assertIn("SELENIUM_TEST_ARTIFACT.TXT", upload_filename.text)
            print("Upload UI filename verification passed.")
            
        finally:
            if os.path.exists(dummy_file_path):
                os.remove(dummy_file_path)

if __name__ == "__main__":
    unittest.main()
