import re

def extract_links():
    try:
        with open("course_dom.html", "r", encoding="utf-8") as f:
            html = f.read()
            
        links = re.findall(r'<a\s+[^>]*href=["\']([^"\']+)["\'][^>]*>(.*?)</a>', html, re.IGNORECASE | re.DOTALL)
        print(f"Tổng số thẻ <a> tìm thấy: {len(links)}")
        
        count = 0
        for href, text_html in links:
            # Strip tags from text
            text = re.sub(r'<[^>]+>', '', text_html).strip()
            text = re.sub(r'\s+', ' ', text).strip() # Xóa khoảng trắng thừa
            print(f"[{text}] -> {href}")
            count += 1
            if count > 100:
                print("... (Cắt bớt)")
                break
    except Exception as e:
        print(f"Lỗi: {e}")

if __name__ == "__main__":
    extract_links()
