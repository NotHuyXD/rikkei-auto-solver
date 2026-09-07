import os
from dotenv import load_dotenv

def main():
    print("==================================================")
    print("🚀 Khởi động Rikkei Auto Solver (Full-Course Version)")
    print("==================================================")
    
    # Tải các biến môi trường
    load_dotenv()
    gemini_api_key = os.getenv("GEMINI_API_KEY")
    github_token = os.getenv("GITHUB_TOKEN")
    
    if not gemini_api_key or not github_token:
        print("❌ LỖI: Vui lòng cấu hình GEMINI_API_KEY và GITHUB_TOKEN trong file .env")
        return
        
    course_url = os.getenv("COURSE_URL", "https://portal.rikkei.edu.vn/learn/220")
    course_prefix = os.getenv("COURSE_PREFIX", "IT213")
    
    # Chạy hệ thống dò đường và tự học (Spider Bot)
    from lms_crawler import LmsCrawler
    crawler = LmsCrawler(course_url=course_url, course_prefix=course_prefix, headless=False)
    crawler.start_crawling()

if __name__ == "__main__":
    main()
