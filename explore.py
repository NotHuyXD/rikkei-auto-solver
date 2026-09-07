import os
from playwright.sync_api import sync_playwright

def explore():
    with sync_playwright() as p:
        user_data_dir = os.path.join(os.getcwd(), "browser_profile")
        context = p.chromium.launch_persistent_context(
            user_data_dir=user_data_dir,
            headless=False
        )
        page = context.pages[0] if context.pages else context.new_page()
        
        print("Đang vào trang home...")
        page.goto("https://portal.rikkei.edu.vn/home")
        page.wait_for_timeout(5000)
        
        print("Đang tìm và click vào môn IT-213...")
        try:
            # Tìm component chứa chữ IT-213 và click nút Học ngay bên trong đó
            card = page.locator("div").filter(has_text="[IT-213]").last
            card.get_by_text("Học ngay").click()
            print("Đã click Học ngay!")
        except Exception as e:
            print("Lỗi khi click:", e)
            
        print("Đang đợi trang bài học load...")
        page.wait_for_timeout(5000)
        
        print(f"URL hiện tại: {page.url}")
        
        with open("course_url.txt", "w", encoding="utf-8") as f:
            f.write(page.url)
            
        context.close()

if __name__ == "__main__":
    explore()
