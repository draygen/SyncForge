import os
import time
from playwright.sync_api import sync_playwright

def run_test():
    with sync_playwright() as p:
        # Launch browser
        browser = p.chromium.launch(headless=True)
        # Use credentials if Basic Auth popup appears, though we mainly use the form
        context = browser.new_context(
            viewport={'width': 1920, 'height': 1080}
        )
        page = context.new_page()
        
        base_url = "https://drayhub.org"
        
        print(f"--- Initiating Playwright E2E on {base_url} ---")
        
        try:
            # 1. Login Phase
            page.goto(base_url)
            print(f"Navigated to {base_url}")
            
            # Wait for the login overlay to be visible
            page.wait_for_selector("#login-user", state="visible", timeout=10000)
            
            print("Entering credentials...")
            page.fill("#login-user", "admin")
            page.fill("#login-pass", "hello5")
            
            print("Clicking 'INITIATE CONNECTION'...")
            page.click("button:has-text('INITIATE CONNECTION')")
            
            # Check for error message
            time.sleep(2)
            error_visible = page.is_visible("#login-err")
            if error_visible:
                print("❌ LOGIN FAILED: 'IDENTITY DENIED — Invalid credentials' is visible on page.")
                page.screenshot(path="login_failed.png")
                print("Screenshot saved to login_failed.png")
                return

            # Wait for dashboard
            page.wait_for_selector("#dashboard", state="visible", timeout=10000)
            print("✅ SUCCESS: Authentication successful. Dashboard online.")
            
            # Verify identity
            user_tag = page.inner_text("#user-tag")
            print(f"User identity verified: {user_tag}")
            
            page.screenshot(path="dashboard_success.png")
            print("Screenshot saved to dashboard_success.png")
            
        except Exception as e:
            print(f"❌ TEST CRASHED: {e}")
            page.screenshot(path="crash_report.png")
            print("Screenshot saved to crash_report.png")
        finally:
            browser.close()

if __name__ == "__main__":
    run_test()
