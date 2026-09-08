import os
import time
from playwright.sync_api import sync_playwright

class LmsCrawler:
    def __init__(self, course_url, course_prefix="IT213", headless=False):
        self.course_url = course_url
        self.course_prefix = course_prefix
        self.headless = headless
        
    def start_crawling(self):
        print("🚀 Khởi động Bot vượt ải LMS Rikkei...")
        with sync_playwright() as p:
            user_data_dir = os.path.join(os.getcwd(), "browser_profile")
            context = p.chromium.launch_persistent_context(
                user_data_dir=user_data_dir,
                headless=self.headless
            )
            page = context.pages[0] if context.pages else context.new_page()
            
            # Vòng lặp từ Session 1 đến 15
            for session_num in range(1, 16):
                self._process_session(page, session_num)
                
            print("🎉 HOÀN THÀNH TOÀN BỘ KHÓA HỌC!")
            context.close()
            

    def _process_session(self, page, session_num):
        session_name = f"Session {session_num:02d}"
        print(f"\n--- Đang xử lý {session_name} ---")
        
        # Quay về trang chủ khóa học để reset giao diện
        page.goto(self.course_url)
        page.wait_for_timeout(5000)
        
        try:
            # 1. Thuật toán quét DOM (DOM Traversal) để mở Session và lấy SỐ LƯỢNG bài tập
            num_hw = page.evaluate(rf'''async (sessionName) => {{
                const delay = ms => new Promise(res => setTimeout(res, ms));
                function isVisible(el) {{ return el && (el.offsetWidth > 0 || el.offsetHeight > 0); }}
                
                const els = Array.from(document.querySelectorAll('*')).filter(el => el.innerText && el.innerText.includes(sessionName));
                if (els.length === 0) return 0;
                
                const sessionNode = els[els.length - 1]; // Lấy thẻ hiển thị tên Session
                let container = sessionNode.parentElement;
                
                // Đi tìm nút "Bài tập về nhà"
                let btvnNode = null;
                for (let i = 0; i < 10; i++) {{
                    if (!container) break;
                    const btvnCandidates = Array.from(container.querySelectorAll('*')).filter(el => el.innerText && el.innerText.includes('Bài tập về nhà'));
                    if (btvnCandidates.length > 0) {{
                        btvnNode = btvnCandidates[btvnCandidates.length - 1];
                        break;
                    }}
                    container = container.parentElement;
                }}
                
                // Nếu chưa mở Session thì click để mở
                if (!btvnNode || !isVisible(btvnNode)) {{
                    sessionNode.click();
                    await delay(2000);
                    
                    container = sessionNode.parentElement;
                    for (let i = 0; i < 10; i++) {{
                        if (!container) break;
                        const btvnCandidates = Array.from(container.querySelectorAll('*')).filter(el => el.innerText && el.innerText.includes('Bài tập về nhà'));
                        if (btvnCandidates.length > 0) {{
                            btvnNode = btvnCandidates[btvnCandidates.length - 1];
                            break;
                        }}
                        container = container.parentElement;
                    }}
                }}
                
                if (!btvnNode) return 0;
                
                // Hàm trích xuất các dòng "Bài X:" hoặc "Bài tập X" (Là các thẻ lá không có thẻ con)
                let getHwItems = () => Array.from(container.querySelectorAll('*')).filter(el => 
                    el.innerText && el.innerText.match(/^Bài\s*(?:tập\s*)?\d+/i) && el.children.length === 0
                );
                
                let hwItems = getHwItems();
                if (hwItems.length > 0 && isVisible(hwItems[0])) return hwItems.length;
                
                // Nếu chưa thấy danh sách "Bài X:", click vào Bài tập về nhà để thả xuống
                btvnNode.click();
                await delay(2000);
                
                return getHwItems().length;
            }}''', session_name)
            
        except Exception as e:
            print(f"Lỗi khi thực thi thuật toán quét DOM cho {session_name}: {e}")
            return
            
        if num_hw == 0:
            print(f"🔒 {session_name} chưa mở khóa Bài tập về nhà, hoặc rỗng.")
            return
            
        print(f"✅ {session_name} đã mở khóa bài tập! Tìm thấy {num_hw} bài tập. Bắt đầu giải...")
        
        from ai_solver import AiSolver
        from git_manager import GitManager
        
        gemini_api_key = os.getenv("GEMINI_API_KEY")
        github_token = os.getenv("GITHUB_TOKEN")
        
        solver = AiSolver(gemini_api_key)
        git_mgr = GitManager(github_token)
        
        for index in range(num_hw):
            print(f"\\n--- Đang xử lý bài tập {index + 1}/{num_hw} ---")
            
            # Tạo tên thư mục lưu bài
            repo_name = f"{self.course_prefix}-SS{session_num:02d}-BAI{index+1}"
            workspace_dir = os.path.join(os.getcwd(), "solution_workspace", repo_name)
            
            # Kiểm tra xem bài này đã được giải và lưu trên máy hoặc Github chưa
            if os.path.exists(workspace_dir) or git_mgr.check_repo_exists(repo_name):
                print(f"⏭️ Bỏ qua {repo_name} vì đã tồn tại cục bộ hoặc trên GitHub (tránh lãng phí token).")
                continue
                
            print(f"Đang mô phỏng Click vào bài tập...")
            try:
                # Click bằng JS để khóa chặt mục tiêu vào đúng Session này, ngăn triệt để lỗi click chéo Session
                success = page.evaluate(rf'''async ([sessionName, idx]) => {{
                    const els = Array.from(document.querySelectorAll('*')).filter(el => el.innerText && el.innerText.includes(sessionName));
                    if (els.length === 0) return false;
                    
                    const sessionNode = els[els.length - 1];
                    let container = sessionNode.parentElement;
                    for (let i = 0; i < 10; i++) {{
                        if (!container) break;
                        const hwItems = Array.from(container.querySelectorAll('*')).filter(el => 
                            el.innerText && el.innerText.match(/^Bài\s*(?:tập\s*)?\d+/i) && el.children.length === 0
                        );
                        if (hwItems.length > 0 && idx < hwItems.length) {{
                            hwItems[idx].click();
                            return true;
                        }}
                        container = container.parentElement;
                    }}
                    return false;
                }}''', [session_name, index])
                
                if not success:
                    print(f"❌ Lỗi: Không thể tìm thấy mục Bài tập thứ {index + 1} để click.")
                    continue
                    
                print("Đang đợi trang tải nội dung đề bài...")
                page.wait_for_timeout(4000) 
                
                # Quét nội dung toàn trang, AI sẽ tự lọc ra đề bài ở màn hình chính
                assignment_text = page.evaluate("document.body.innerText")
                
                # Nếu chữ trên màn hình quá ngắn (lỗi load), bỏ qua
                if len(assignment_text) < 100:
                    print("❌ Đề bài quá ngắn, có thể trang chưa load xong. Bỏ qua.")
                    continue
                    
                print(f"Đã lấy được nội dung chữ trên màn hình (dài {len(assignment_text)} ký tự).")
                
                # Gửi cho AI giải
                solutions = solver.solve(assignment_text)
                
                if not solutions or not isinstance(solutions, list):
                    print("❌ AI trả về dữ liệu không hợp lệ cho bài này.")
                    continue
                    
                import re
                
                # Lấy phần tử đầu tiên
                sol = solutions[0]
                
                # Lấy phần đuôi mở rộng file từ AI
                ext = os.path.splitext(sol.get('filename', ''))[1]
                if not ext:
                    ext = ".txt"
                
                print(f"\\n--- Đang lưu cục bộ và đẩy lên GitHub: {repo_name} ---")
                
                os.makedirs(workspace_dir, exist_ok=True)
                
                file_path = os.path.join(workspace_dir, f"bai{index+1}{ext}")
                with open(file_path, "w", encoding="utf-8") as f:
                    f.write(sol['code'])
                    
                repo_link = git_mgr.create_repo(repo_name)
                if not repo_link:
                    print(f"❌ Lỗi: Repo {repo_name} không tạo được (Dù đã check chưa tồn tại).")
                    continue
                    
                success = git_mgr.push_code(workspace_dir, repo_link)
                if success:
                    print(f"🎉 Đã đẩy code thành công lên: {repo_link}")
                else:
                    print(f"❌ Không tạo được Repo cho {repo_name}")
                    
            except Exception as e:
                print(f"Lỗi khi xử lý bài tập {index + 1}: {e}")
                
        # Quay lại trang chính sau khi hoàn thành Session
        page.goto(self.course_url)
        page.wait_for_timeout(2000)
