# Rikkei Auto Solver (Full-Course Version)

Bot tự động làm bài tập trên hệ thống LMS Rikkei, sử dụng AI (Gemini 3.6 Flash) để sinh code tự động và đẩy thẳng lên GitHub theo đúng format `IT21X-SSXX-BAIYY`.

## 📌 Tính năng nổi bật
- **Mô phỏng trình duyệt:** Tự động đăng nhập, quét DOM và nhận diện các bài tập bị khóa/mở khóa ở mọi Session.
- **Tiết kiệm API (Chống lãng phí):** Tự động check GitHub và ổ cứng cục bộ. Nếu bài đã làm, sẽ Skip ngay lập tức.
- **Phân tích đề tự động:** Bóc tách yêu cầu bài tập và xử lý các đề bài dị dạng, bọc code trong cấu trúc XML vững chắc (tránh lỗi JSON parsing).
- **Auto Push GitHub:** Tự động tạo repo Public và đẩy code lên GitHub hoàn toàn tự động.

## 🛠 Hướng dẫn cài đặt

### Yêu cầu hệ thống:
- Cài đặt Python 3.10 trở lên.
- Có tài khoản Google AI Studio (để lấy Gemini API Key).
- Có tài khoản GitHub (để lấy Personal Access Token).

### Cách cài đặt nhanh nhất (Dành cho Windows):
Chỉ cần nháy đúp chuột vào file **`setup.bat`**, hệ thống sẽ tự động tạo môi trường và cài đặt mọi thứ.

### Cài đặt thủ công:
1. Mở Terminal / Command Prompt tại thư mục dự án.
2. Tạo môi trường ảo:
```bash
python -m venv venv
```
3. Kích hoạt môi trường ảo:
```bash
# Windows
.\venv\Scripts\activate
# MacOS/Linux
source venv/bin/activate
```
4. Cài đặt các thư viện cần thiết:
```bash
pip install -r requirements.txt
playwright install chromium
```

## ⚙ Cấu hình thông tin cá nhân
Tạo một file tên là `.env` trong thư mục dự án (có thể copy từ file `.env.example`) và điền đầy đủ thông tin:

```env
# Tài khoản Rikkei Portal
RIKKEI_USER=email_cua_ban@gmail.com
RIKKEI_PASS=mat_khau_cua_ban

# API Key cho Gemini (Google AI Studio)
GEMINI_API_KEY=AIzaSy... (Lấy tại aistudio.google.com)

# Cấu hình GitHub
GITHUB_TOKEN=ghp_... (Tạo Personal Access Token ở GitHub Settings > Developer settings > Tokens (classic), cấp full quyền repo)
GITHUB_USERNAME=username_github_cua_ban

# Cấu hình môn học
COURSE_URL=https://portal.rikkei.edu.vn/learn/220
COURSE_PREFIX=IT214
```

## 🚀 Cách sử dụng

Chỉ cần chạy lệnh sau, mở gói bắp rang bơ và ngồi nhìn Bot làm việc:
```bash
.\venv\Scripts\python.exe main.py
```

### Lưu ý:
- Lần đầu chạy, trình duyệt Chromium sẽ hiện lên yêu cầu bạn đăng nhập Rikkei (Bằng Google/Facebook...). Đăng nhập xong, tắt trình duyệt đi và chạy lại file `main.py`, hệ thống sẽ tự động quét mọi thứ.
- Những lần sau trình duyệt sẽ chạy nền (headless) hoặc hiện lên tự động giải quyết bài tập.
