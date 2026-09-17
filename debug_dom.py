"""Script debug: Chụp lại nội dung DOM của Session 02 để phân tích."""
import os
from dotenv import load_dotenv
from playwright.sync_api import sync_playwright

load_dotenv()
course_url = os.getenv("COURSE_URL")

print(f"Đang mở trang: {course_url}")

with sync_playwright() as p:
    user_data_dir = os.path.join(os.getcwd(), "browser_profile")
    context = p.chromium.launch_persistent_context(
        user_data_dir=user_data_dir,
        headless=False
    )
    page = context.pages[0] if context.pages else context.new_page()
    
    page.goto(course_url)
    page.wait_for_timeout(5000)
    
    # 1. Tìm tất cả text có chứa "Session" trên trang
    session_texts = page.evaluate(r'''() => {
        const results = [];
        const els = Array.from(document.querySelectorAll('*')).filter(el => 
            el.innerText && el.innerText.includes('Session') && el.children.length === 0
        );
        els.forEach(el => {
            results.push({
                tag: el.tagName,
                text: el.innerText.substring(0, 100),
                visible: el.offsetWidth > 0 || el.offsetHeight > 0
            });
        });
        return results;
    }''')
    
    print("\n=== CÁC THẺ CHỨA CHỮ 'Session' (thẻ lá) ===")
    for i, item in enumerate(session_texts[:30]):
        print(f"  [{i}] <{item['tag']}> visible={item['visible']} text='{item['text']}'")
    
    # 2. Click vào Session 02 và xem nội dung con
    debug_info = page.evaluate(r'''async () => {
        const delay = ms => new Promise(res => setTimeout(res, ms));
        const sessionName = "Session 02";
        
        // Tìm thẻ chứa "Session 02"
        const els = Array.from(document.querySelectorAll('*')).filter(el => 
            el.innerText && el.innerText.includes(sessionName)
        );
        if (els.length === 0) return {error: "Không tìm thấy Session 02"};
        
        const sessionNode = els[els.length - 1];
        
        // Click mở Session
        sessionNode.click();
        await delay(3000);
        
        // Lấy toàn bộ innerText của container cha (3 cấp)
        let container = sessionNode.parentElement;
        let parentTexts = [];
        for (let i = 0; i < 5; i++) {
            if (!container) break;
            parentTexts.push({
                level: i,
                tag: container.tagName,
                childCount: container.children.length,
                textSnippet: container.innerText ? container.innerText.substring(0, 300) : "(rỗng)"
            });
            container = container.parentElement;
        }
        
        // Quét lại từ gốc: tìm tất cả text trong vùng Session 02
        container = sessionNode.parentElement;
        let allTexts = [];
        for (let i = 0; i < 10; i++) {
            if (!container) break;
            const children = Array.from(container.querySelectorAll('*')).filter(el => 
                el.children.length === 0 && el.innerText && el.innerText.trim().length > 0
            );
            if (children.length > 5) {
                children.slice(0, 30).forEach(el => {
                    allTexts.push(el.innerText.trim().substring(0, 80));
                });
                break;
            }
            container = container.parentElement;
        }
        
        return {
            sessionNodeTag: sessionNode.tagName,
            sessionNodeText: sessionNode.innerText.substring(0, 100),
            parentTexts: parentTexts,
            allLeafTexts: allTexts
        };
    }''')
    
    print("\n=== DEBUG SESSION 02 ===")
    if 'error' in debug_info:
        print(f"LỖI: {debug_info['error']}")
    else:
        print(f"Session Node: <{debug_info['sessionNodeTag']}> text='{debug_info['sessionNodeText']}'")
        print(f"\n--- Các cấp cha ---")
        for p_info in debug_info['parentTexts']:
            print(f"  Level {p_info['level']}: <{p_info['tag']}> children={p_info['childCount']}")
            print(f"    Text: {p_info['textSnippet'][:200]}")
        print(f"\n--- Tất cả text lá trong vùng ---")
        for t in debug_info['allLeafTexts']:
            print(f"  • {t}")
    
    context.close()
    print("\n✅ Debug xong!")
