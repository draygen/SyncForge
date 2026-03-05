import os
import asyncio
from playwright.sync_api import sync_playwright

def run_test():
    with sync_playwright() as p:
        # Launch browser (headless by default in most CLI environments)
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1920, 'height': 1080})
        page = context.new_page()
        
        base_url = "http://localhost:8888"
        
        print("--- Initiating Playwright E2E Protocol ---")
        
        # 1. Login Phase
        page.goto(base_url)
        print(f"Navigated to {base_url}")
        
        page.fill("#login-user", "admin")
        page.fill("#login-pass", "Renoise28!")
        page.click("button:has-text('INITIATE CONNECTION')")
        
        # Playwright auto-waits for the selector to be available
        page.wait_for_selector("#dashboard")
        print("Authentication successful. Dashboard online.")
        
        # 2. Dashboard Validation
        # Verify the user tag
        user_tag = page.wait_for_selector("#user-tag")
        # Ensure text is not empty (handles the async fetch)
        page.wait_for_function('document.getElementById("user-tag").innerText === "admin"')
        print(f"User identity verified: {user_tag.inner_text()}")
        
        # Verify table presence
        table = page.query_selector(".table-responsive")
        if table:
            print("Artifact telemetry table detected.")
        
        # 3. Upload Flow UI Simulation
        print("Testing Artifact Ingestion Overlay...")
        dummy_file = os.path.abspath("playwright_test_artifact.txt")
        with open(dummy_file, "w") as f:
            f.write("Draygen Systems - Playwright E2E Test Payload")
            
        try:
            # Playwright handles hidden file inputs gracefully with set_input_files
            page.set_input_files("#file-input", dummy_file)
            
            # Verify overlay trigger
            overlay = page.wait_for_selector("#upload-overlay", state="visible")
            print("Upload overlay triggered successfully.")
            
            # Verify filename nomenclature conversion (uppercase display check)
            filename_display = page.inner_text("#upload-filename")
            if "PLAYWRIGHT_TEST_ARTIFACT.TXT" in filename_display:
                print("UI Filename nomenclature verification: PASSED")
            else:
                print(f"UI Filename nomenclature verification: FAILED (Got: {filename_display})")
                
        finally:
            if os.path.exists(dummy_file):
                os.remove(dummy_file)
                
        print("--- Playwright E2E Protocol Complete ---")
        browser.close()

if __name__ == "__main__":
    run_test()
