import os
import time
from playwright.sync_api import sync_playwright

def run_test():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1920, 'height': 1080})
        page = context.new_page()
        
        # Capture console logs
        page.on("console", lambda msg: print(f"BROWSER CONSOLE: {msg.text}"))
        
        base_url = "https://drayhub.org"
        print(f"--- Initiating Playwright E2E on {base_url} ---")
        
        try:
            page.goto(base_url)
            page.wait_for_selector("#login-user", state="visible", timeout=10000)
            
            page.fill("#login-user", "admin")
            page.fill("#login-pass", "hello5")
            page.click("button:has-text('INITIATE CONNECTION')")
            
            time.sleep(5)
            
            error_visible = page.is_visible("#login-err")
            if error_visible:
                print("❌ LOGIN FAILED UI SIDE")
                page.screenshot(path="login_failed_ui.png")
            else:
                dashboard_visible = page.is_visible("#dashboard")
                if dashboard_visible:
                    print("✅ SUCCESS: Dashboard visible")
                    page.screenshot(path="dashboard_ok.png")
                else:
                    print("❓ UNKNOWN STATE: Neither error nor dashboard visible")
                    page.screenshot(path="unknown_state.png")
                    
        except Exception as e:
            print(f"❌ TEST ERROR: {e}")
        finally:
            browser.close()

if __name__ == "__main__":
    run_test()
