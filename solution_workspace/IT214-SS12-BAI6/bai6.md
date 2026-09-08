# BÀI TẬP 6: CHIẾN LƯỢC CỬA SỔ TRƯỢT: TIME-BASED VS COUNT-BASED

---

## PHẦN 1 – PHÂN TÍCH

| Tiêu chí | COUNT_BASED (Dựa trên số request) | TIME_BASED (Dựa trên thời gian) |
| :--- | :--- | :--- |
| **Cơ chế thu thập** | Lưu trữ và phân tích kết quả của $N$ request gần nhất (ví dụ: 100 request gần nhất). | Lưu trữ và phân tích kết quả của các request trong $N$ giây gần nhất (ví dụ: 60 giây gần nhất). |
| **Đơn vị cửa sổ** | Số lượng request (`slidingWindowSize = N`). | Thời gian tính bằng giây (`slidingWindowSize = N`). |
| **Tốc độ phản ứng khi Traffic tăng vọt** | **Rất nhanh.** Khi traffic tăng cao, cửa sổ lấp đầy cực nhanh giúp phát hiện lỗi và ngắt mạch chỉ trong vài millisecond. | **Tùy thuộc khoảng thời gian.** Nếu khoảng thời gian lớn, hệ thống mất nhiều thời gian hơn để cập nhật/tính toán thống kê theo bucket thời gian. |
| **Trường hợp sử dụng phù hợp** | Các hệ thống có lưu lượng truy cập biến động lớn, spike traffic cao (Flash Sale, Event ticketing...). | Các hệ thống có lưu lượng truy cập ổn định, đều đặn theo thời gian. |

---

## PHẦN 2 – LỰA CHỌN VÀ GIẢI THÍCH

### 1. Rủi ro của `TIME_BASED` (60 giây) khi Server chết ở giây đầu tiên của Flash Sale:
* **Hậu quả ngập lụt Request:** Lúc 12h00, traffic tăng đột biến lên **50.000 requests/giây**. Nếu server bị chết/lỗi ngay ở giây đầu tiên, trong đúng 1 giây đó đã có 50.000 request bị thất bại hoặc timeout.
* **Độ trễ khi ngắt mạch:** Với cửa sổ `TIME_BASED` 60 giây, dữ liệu được chia nhỏ thành các phân đoạn thời gian (time buckets). Circuit Breaker sẽ cần thời gian để tích lũy và tổng hợp dữ liệu trong chu kỳ thời gian. Trong 1 giây đầu tiên đó, trước khi Circuit Breaker kịp tính toán xong tỷ lệ lỗi và chuyển sang trạng thái `OPEN`, hàng chục nghìn request tiếp theo vẫn tràn vào tiếp tục đánh sập hoàn toàn hệ thống và các dịch vụ liên quan (Cascading Failure).

### 2. Giải pháp lựa chọn:
* **Lựa chọn loại Cửa sổ trượt:** **`COUNT_BASED`**
* **Lý do:** 
  * Khi chọn `COUNT_BASED` với kích thước cửa sổ ví dụ là `100` request và `minimumNumberOfCalls = 20`:
  * Lúc 12h00 traffic tràn vào 50.000 req/s, chỉ cần **20 đến 100 request đầu tiên bị lỗi** (xảy ra chỉ trong khoảng 1-2 milliseconds), Circuit Breaker đã có đủ dữ liệu để tính ra tỷ lệ lỗi $100\% > threshold$ và ngay lập tức **NGẮT MẠCH (OPEN)**.
  * Việc ngắt mạch chỉ sau vài millisecond sẽ bảo vệ server ngay lập tức, từ chối sớm (Fail-Fast) 49.900+ request còn lại trong giây đó, tránh làm sập toàn bộ hạ tầng.

---

## PHẦN 3 – TRIỂN KHAI CẤU HÌNH (`application.yml`)

Dưới đây là cấu hình Resilience4j tối ưu cho `FlashSale-Service` sử dụng chiến lược `COUNT_BASED`:

```yaml
spring:
  application:
    name: FlashSale-Service

resilience4j:
  circuitbreaker:
    configs:
      default:
        registerHealthIndicator: true
    instances:
      FlashSale-Service:
        # 1. Chọn chiến lược Cửa sổ trượt dựa trên số lượng Request
        slidingWindowType: COUNT_BASED
        
        # 2. Kích thước cửa sổ: Lưu 100 request gần nhất
        slidingWindowSize: 100
        
        # 3. Số request tối thiểu để bắt đầu tính toán tỷ lệ lỗi (Giúp ngắt mạch cực nhanh)
        minimumNumberOfCalls: 20
        
        # 4. Ngưỡng tỷ lệ thất bại (%): Nếu > 50% request lỗi -> OPEN Circuit
        failureRateThreshold: 50
        
        # 5. Ngưỡng tỷ lệ phản hồi chậm (%)
        slowCallRateThreshold: 50
        
        # 6. Thời gian coi là 1 request chậm (2 giây)
        slowCallDurationThreshold: 2000ms
        
        # 7. Thời gian giữ ở trạng thái OPEN trước khi chuyển sang HALF-OPEN (10 giây)
        waitDurationInOpenState: 10000ms
        
        # 8. Số lượng request thử nghiệm ở trạng thái HALF-OPEN
        permittedNumberOfCallsInHalfOpenState: 10
        
        # 9. Tự động chuyển từ OPEN sang HALF-OPEN khi hết thời gian chờ
        automaticTransitionFromOpenToHalfOpenEnabled: true
```