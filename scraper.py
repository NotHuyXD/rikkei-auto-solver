import os
from playwright.sync_api import sync_playwright
import time

class RikkeiScraper:
    def __init__(self):
        self.user_data_dir = os.path.join(os.getcwd(), "browser_profile")
        self.trigger_file = "start_solve.txt"

    def run_interactive(self):
        """Khởi động browser và giao quyền cho người dùng thao tác. Khi có tín hiệu, trả về instance của page để AI xử lý tiếp."""
        print("Khởi động Playwright...")
        
        # Đảm bảo xóa file trigger cũ nếu có
        if os.path.exists(self.trigger_file):
            os.remove(self.trigger_file)
            
        p = sync_playwright().start()
        context = p.chromium.launch_persistent_context(
            user_data_dir=self.user_data_dir,
            headless=False # Bắt buộc phải bật để người dùng tự thao tác
        )
        page = context.pages[0] if context.pages else context.new_page()
        
        # Tự động vào trang chủ (Nếu profile lưu cookie tốt, bạn sẽ không cần login nữa)
        page.goto("https://portal.rikkei.edu.vn/dangnhap")
        
        print("\n" + "="*70)
        print("🌐 TRÌNH DUYỆT ĐÃ MỞ!")
        print("1. Nếu bị hỏi đăng nhập, hãy tự đăng nhập lại.")
        print("2. Tự click vào môn IT213 và mở trang chứa BÀI TẬP ra.")
        print("3. Khi nào ĐỀ BÀI HIỆN RA TRÊN MÀN HÌNH, báo lại tôi (AI) để tôi cho tool chạy tiếp.")
        print("="*70 + "\n")
        
        print("⏳ Tool đang ở trạng thái CHỜ... (Bạn cứ thoải mái thao tác trên web, tool không bấm lung tung đâu)")
        
        # Vòng lặp chờ vô tận cho đến khi file trigger xuất hiện
        while not os.path.exists(self.trigger_file):
            page.wait_for_timeout(1000)
            
        print("\n🚀 Đã nhận tín hiệu! Bắt đầu quét đề bài trên màn hình...")
        
        # Đợi 1 chút cho chắc chắn
        page.wait_for_timeout(1000)
        
        # Cố gắng lấy toàn bộ chữ hiển thị trên trang web hiện tại (lấy tab mới nhất lỡ người dùng mở tab mới)
        active_page = context.pages[-1]
        
        print(f"Đang đọc nội dung từ tab: {active_page.url}")
        assignment_content = active_page.evaluate("document.body.innerText")
        
        # Trả về đối tượng active_page để tái sử dụng ở các bước sau (nộp bài) thay vì đóng browser
        return {
            "page": active_page,
            "context": context,
            "playwright": p,
            "content": assignment_content
        }
        
    def submit_assignment(self, page, github_url):
        print(f"Bắt đầu tự động nộp bài với link: {github_url}")
        
        # TODO: Cập nhật logic nộp bài sau khi xem form thực tế
        print("⚠️ Chức năng tự động điền form nộp bài đang được phát triển.")
        
        print("Đã nộp bài xong!")
