import json
import requests
import os

class AiSolver:
    def __init__(self, api_key):
        self.api_key = api_key
        # Sử dụng REST API thay vì SDK để tránh triệt để lỗi treo gRPC
        self.model_name = "gemini-3.6-flash"

    def solve(self, raw_assignment_text):
        prompt = f"""
        Bạn là một lập trình viên xuất sắc. Dưới đây là nội dung văn bản quét được từ một trang bài tập lập trình cụ thể.
        Nhiệm vụ của bạn:
        1. Phân tích nội dung và bóc tách YÊU CẦU DUY NHẤT của bài tập này. KHÔNG tự sáng tác thêm đề bài.
        2. Viết mã nguồn hoàn chỉnh hoặc câu trả lời văn bản để giải quyết bài tập đó.
        3. Chọn tên file phù hợp (vd: bai1.java, bai2.js, script.sql...).
        
        Nội dung trang web:
        ---
        {raw_assignment_text}
        ---
        
        Hãy trả về kết quả theo cấu trúc thẻ (XML-like) dưới đây. KHÔNG dùng định dạng JSON vì rất dễ lỗi ngoặc kép:
        
        <FILENAME>tên_file_ở_đây.txt</FILENAME>
        <CODE>
        viết_mã_nguồn_hoặc_câu_trả_lời_ở_đây
        </CODE>
        <EXPLANATION>
        giải_thích_ngắn_gọn_ở_đây
        </EXPLANATION>
        """
        print("🧠 Đang gửi đề bài cho AI phân tích và giải (Gemini 3.6 Flash - REST API)...")
        
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.model_name}:generateContent?key={self.api_key}"
        headers = {'Content-Type': 'application/json'}
        data = {
            "contents": [{"parts": [{"text": prompt}]}]
        }
        
        import time
        import re
        for attempt in range(3):
            try:
                # Tăng timeout lên 120s vì đôi khi AI mất nhiều thời gian để viết code dài
                response = requests.post(url, headers=headers, json=data, timeout=120)
                response.raise_for_status()
                
                res_json = response.json()
                content = res_json['candidates'][0]['content']['parts'][0]['text'].strip()
                
                # Bóc tách bằng Regex thay vì parse JSON
                filename_match = re.search(r'<FILENAME>(.*?)</FILENAME>', content, re.IGNORECASE | re.DOTALL)
                code_match = re.search(r'<CODE>(.*?)</CODE>', content, re.IGNORECASE | re.DOTALL)
                
                if filename_match and code_match:
                    result = [{
                        "filename": filename_match.group(1).strip(),
                        "code": code_match.group(1).strip(),
                        "exercise_name": "BAI"
                    }]
                else:
                    # Nếu AI quên thẻ, fallback lấy toàn bộ content làm code và tự chế filename
                    result = [{
                        "filename": "bai_tap.txt",
                        "code": content,
                        "exercise_name": "BAI"
                    }]
                
                # Cố tình ngủ 10s sau mỗi lần gọi thành công để nhịp độ gọi API không bị quá dồn dập (tránh 429)
                print("⏳ Nghỉ ngơi 10s để tránh làm Google quá tải...")
                time.sleep(10)
                
                return result
            except requests.exceptions.Timeout:
                print(f"⚠️ Cảnh báo: Lần thử {attempt + 1}/3 bị quá thời gian (Timeout). Đang thử lại sau 5s...")
                time.sleep(5)
            except requests.exceptions.HTTPError as e:
                if response.status_code == 429:
                    print(f"⏳ Cảnh báo: Bị giới hạn số lượng truy cập từ Google (429 Too Many Requests).")
                    print(f"Chi tiết từ Google: {response.text}")
                    print("Đang đợi 60s trước khi thử lại...")
                    time.sleep(60)
                elif response.status_code in [500, 502, 503, 504]:
                    print(f"⚠️ Cảnh báo: Máy chủ Google đang quá tải (Lỗi {response.status_code}). Đang đợi 10s để thử lại...")
                    time.sleep(10)
                else:
                    print(f"❌ Lỗi HTTP khi gọi API Google AI: {e}")
                    break
            except requests.exceptions.RequestException as e:
                print(f"❌ Lỗi mạng khi gọi API Google AI: {e}")
                break
            except Exception as e:
                print(f"❌ Lỗi khi bóc tách kết quả từ AI: {e}")
                # In ra log để debug nếu cần
                print(f"Raw response: {content if 'content' in locals() else 'None'}")
                break
        
        print("❌ Thất bại sau 3 lần thử gọi AI.")
        return None
