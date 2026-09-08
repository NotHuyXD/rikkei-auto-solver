# BÁO CÁO PHÂN TÍCH HỢP ĐỒNG API VÀ THIẾT KẾ MIGRATION CHO FEIGNCLIENT

---

## 1. Phân tích tác động khi đổi tên trường "name" thành "productName" (Không có Migration Plan)

Khi `product-service` tự ý đổi tên key trong JSON Response từ `name` thành `productName`:

### Triệu chứng kỹ thuật chung:
- Mặc định, thư viện Jackson Deserializer ở phía 3 Client (`order-service`, `inventory-service`, `report-service`) map dữ liệu JSON vào Java Object dựa trên tên trường.
- JSON trả về từ `product-service`: `{"id": 1, "productName": "Laptop", "price": 1000}`.
- DTO `ProductInfo` ở phía Client kỳ vọng trường `name`.
- Do không tìm thấy key `"name"` trong JSON, Jackson sẽ thiết lập giá trị mặc định cho `String name` là **`null`** mà **KHÔNG ném ra Exception** trong cấu hình mặc định.

### Hậu quả cụ thể trên từng Service:
1. **`order-service`**:
   - Khi tạo đơn hàng hoặc lấy thông tin chi tiết đơn hàng, `productInfo.name()` trả về `null`.
   - Nếu hệ thống lưu `productName` vào bảng `orders` hoặc hiển thị hóa đơn, giá trị tên sản phẩm sẽ bị rỗng (`null`).
   - Gây lỗi `NullPointerException` (NPE) khi thực hiện các thao tác chuỗi trên `name` (ví dụ: `productInfo.name().toUpperCase()` hoặc gửi mail xác nhận đơn hàng).

2. **`inventory-service`**:
   - Khi ghi log tồn kho hoặc thực hiện nghiệp vụ kiểm tra sản phẩm, tên sản phẩm bị `null`.
   - Nếu có logic validation kiểm tra `Assert.notNull(productInfo.name())`, service sẽ quăng ngoại lệ nghiệp vụ (`BusinessException`), khiến quy trình nhập/xuất kho bị gián đoạn hoàn toàn.

3. **`report-service`**:
   - Các báo cáo doanh thu, sản phẩm bán chạy sẽ xuất hiện các dòng dữ liệu với tên sản phẩm là `null` hoặc bỏ trống.
   - Làm sai lệch báo cáo kinh doanh cung cấp cho Ban quản lý/Kế toán.

---

## 2. Phân tích tác động khi đổi Path `/api/products/{id}` thành `/api/v2/products/{id}`

Khi `product-service` thay đổi Endpoint Path mà không hỗ trợ tương thích ngược (Backward Compatibility):

### Triệu chứng kỹ thuật:
- Ba client service vẫn gửi HTTP GET Request tới đường dẫn cũ: `GET /api/products/{id}`.
- `product-service` trả về HTTP Status Code **`404 Not Found`** vì route cũ không còn tồn tại trong Controller.

### Hậu quả hệ thống:
- OpenFeign ở các client service sẽ bắt HTTP Status 404 và ném ra ngoại lệ `feign.FeignException$NotFound`.
- **Cascading Failure (Thất bại dây chuyền)**:
  - Nếu các client service không cấu hình Fallback / Circuit Breaker (Resilience4j), ngoại lệ `FeignException` sẽ lan truyền lên lớp Service/Controller của client, khiến các API của `order-service`, `inventory-service`, và `report-service` bị ngắt đột ngột và trả về HTTP `500 Internal Server Error` cho người dùng cuối.

---

## 3. Đề xuất chiến lược API Versioning

Để `product-service` nâng cấp format mà không gây sụp đổ hệ thống (zero-downtime migration), có 2 chiến lược phổ biến:

### Cách 1: URI Path Versioning (Phổ biến nhất)
 Duy trì song song 2 endpoint trên `product-service` trong thời gian chuyển giao (Migration Period).
 - Endpoint cũ (v1): `/api/v1/products/{id}` (hoặc `/api/products/{id}`)
 - Endpoint mới (v2): `/api/v2/products/{id}`

- **Ưu điểm**:
  - Rõ ràng, dễ nhận biết và dễ routing ở API Gateway.
  - Tách biệt rõ ràng logic xử lý giữa các phiên bản.
- **Nhược điểm (Trade-off)**:
  - Tăng chi phí bảo trì mã nguồn tại `product-service` do phải duy trì cả 2 controller/method cho đến khi tất cả client migrate xong.

### Cách 2: Header-based / Content Negotiation Versioning
 Sử dụng Header để chỉ định phiên bản API (ví dụ: `X-API-VERSION: 2` hoặc `Accept: application/vnd.company.app-v2+json`).
 - Đường dẫn giữ nguyên: `/api/products/{id}`.
 - Phía client truyền Header để chọn phiên bản response mong muốn.

- **Ưu điểm**:
  - URI giữ nguyên đúng chuẩn RESTful.
  - Linh hoạt thay đổi cấu trúc dữ liệu trả về theo từng client.
- **Nhược điểm (Trade-off)**:
  - Dễ gây phức tạp cho cơ chế Caching (phải cấu hình cache key dựa trên Header).
  - Khó kiểm thử trực tiếp trên trình duyệt web thông thường.

---

## 4. Thiết kế mã nguồn DTO Migration (Java Code Minh Hoạ)

Sử dụng `@JsonAlias` của thư viện Jackson để Java Record / Class có thể deserialize linh hoạt: nhận được CẢ `"name"` (format cũ) lẫn `"productName"` (format mới) mà không bị `null`.

### Code minh họa: `ProductInfo.java` (Đặt ở phía Client Services)

```java
package com.example.clientservice.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO đại diện cho thông tin sản phẩm nhận từ product-service.
 * Sử dụng @JsonAlias để hỗ trợ Backward Compatibility trong quá trình Migration.
 */
public record ProductInfo(
    Long id,

    /*
     * @JsonAlias cho phép Jackson đọc dữ liệu từ một trong các key JSON liệt kê.
     * - Khi product-service trả về {"name": "Sản phẩm A"}: map vào trường name.
     * - Khi product-service trả về {"productName": "Sản phẩm A"}: vẫn map đúng vào trường name.
     * @JsonProperty("name") đảm bảo khi serialize ngược lại JSON (nếu có) vẫn giữ nguyên key "name".
     */
    @JsonProperty("name")
    @JsonAlias({"productName", "name"})
    String name,

    Long price
) {}
```

### Kịch bản hoạt động chi tiết của DTO trên:
1. **Giai đoạn 1 (Chưa migrate product-service)**:
   - JSON Response: `{"id": 101, "name": "Bàn phím Cơ", "price": 500000}`
   - Outcome: Jackson gặp key `"name"` -> Map vào field `name` -> Value: `"Bàn phím Cơ"`.
2. **Giai đoạn 2 (product-service đã đổi sang productName)**:
   - JSON Response: `{"id": 101, "productName": "Bàn phím Cơ", "price": 500000}`
   - Outcome: Jackson gặp key `"productName"` (khớp 1 trong các alias) -> Map vào field `name` -> Value: `"Bàn phím Cơ"`.
3. **Kết quả**: Cả 3 service client đều chạy bình thường mà không bị `null` hay gẫy hệ thống trong suốt thời gian chuyển đổi.