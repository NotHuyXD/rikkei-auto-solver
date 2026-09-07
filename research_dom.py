import os
from playwright.sync_api import sync_playwright

def explore():
    with sync_playwright() as p:
        user_data_dir = os.path.join(os.getcwd(), "browser_profile")
        context = p.chromium.launch_persistent_context(
            user_data_dir=user_data_dir,
            headless=False # Vẫn bật UI để tránh bị hệ thống chặn session
        )
        page = context.pages[0] if context.pages else context.new_page()
        
        print("Đang truy cập trang khóa học IT213...")
        page.goto("https://portal.rikkei.edu.vn/learn/220")
        
        page.wait_for_timeout(5000)
        
        print("Đang tìm và click vào Session 10...")
        try:
            # Click vào thẻ chứa chữ Session 10 để bung danh sách bài tập ra
            page.locator("text=/Session 10/").first.click()
            print("Đã click Session 10!")
        except Exception as e:
            print("Lỗi khi click:", e)
            
        page.wait_for_timeout(3000) # Chờ hiệu ứng bung ra
        
        inner_text = page.evaluate("document.body.innerText")
        with open("session10_text.txt", "w", encoding="utf-8") as f:
            f.write(inner_text)
            
        with open("session10_dom.html", "w", encoding="utf-8") as f:
            f.write(page.content())
            
        print("Đã tải xong mã nguồn sau khi click Session 10!")
        context.close()

if __name__ == "__main__":
    explore()
