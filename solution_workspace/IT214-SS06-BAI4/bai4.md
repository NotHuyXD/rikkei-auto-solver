# BÁO CÁO PHÂN TÍCH HỢP ĐỒNG API VÀ TÁC ĐỘNG KHI FEIGNCLIENT INTERFACE THAY ĐỔI
**Môn học:** Microservice in Action  
**Bài tập:** Bài tập 4 - Phân tích hợp đồng API và tác động khi FeignClient interface thay đổi  

---

## PHẦN 1: PHÂN TÍCH TÁC ĐỘNG KHI ĐỔI TÊN TRƯỜNG "name" THÀNH "productName"

### 1. Triệu chứng kỹ thuật tổng quan
Mặc định trong Spring Boot (dùng Jackson Deserializer), thuộc tính `FAIL_ON_UNKNOWN_PROPERTIES` được đặt là `false`. Khi `product-service` đổi tên trường JSON response từ `"name"` thành `"productName"`:
* Jackson khi deserialize JSON mới (`{"id": 1, "productName": "Laptop", "price": 1000}`) vào DTO `ProductInfo(Long id, String name, Long price)` sẽ **không tìm thấy key `"name"`**.
* Jackson sẽ bỏ qua field `"productName"` và gán giá trị **`null`** cho trường `name` trong DTO `ProductInfo`.
* Không có ngoại lệ (Exception) nào được ném ra ở tầng giao tiếp HTTP/Feign Client.

### 2. Triệu chứng và tác động cụ thể ở từng Service
* **`order-service`**:
  * Khi tạo đơn hàng mới, `order-service` gọi FeignClient lấy thông tin sản phẩm. Trường `name` bị `null`.
  * *Tác động:* Dữ liệu đơn hàng/hóa đơn lưu vào database bị thiếu thông tin tên sản phẩm (tên bị `null`). Nếu hệ thống có đoạn code gọi phương thức trên chuỗi (ví dụ: `productInfo.name().toUpperCase()`), hệ thống sẽ ném ngay lỗi **`java.lang.NullPointerException`** làm giao dịch đặt hàng thất bại hoàn toàn.
* **`inventory-service`**:
  * Nhận DTO với `name = null`.
  * *Tác động:* Việc ghi log, kiểm kê kho hoặc gửi cảnh báo tồn kho sẽ hiển thị tên sản phẩm là `null` hoặc `"N/A"`. Trường hợp `inventory-service` có logic validation yêu cầu `name` khác `null`, giao dịch nhập/xuất kho sẽ bị ném `IllegalArgumentException`.
* **`report-service`**:
  * Nhận DTO với `name = null` để tổng hợp báo cáo.
  * *Tác động:* Dữ liệu báo cáo thống kê, doanh thu trả về cho quản trị viên bị sai lệch hoặc hiển thị trống tên sản phẩm, ảnh hưởng trực tiếp đến nghiệp vụ của doanh nghiệp.

---

## PHẦN 2: PHÂN TÍCH TÁC ĐỘNG KHI ĐỔI PATH TỪ `/api/products/{id}` SANG `/api/v2/products/{id}`

### 1. Triệu chứng kỹ thuật
* Khi `product-service` thay đổi URL endpoint mà không hỗ trợ backward compatibility (tức là xóa hẳn route cũ `/api/products/{id}`):
* Ba service client (`order-service`, `inventory-service`, `report-service`) thông qua FeignClient vẫn gửi HTTP GET Request đến đường dẫn cũ: `/api/products/{id}`.
* `product-service` không tìm thấy controller xử lý đường dẫn này và trả về phản hồi **`HTTP 404 Not Found`**.

### 2. Ngoại lệ và hành vi ở Client
* FeignClient ở 3 client service sẽ bắt phản hồi `404` và ném ra ngoại lệ:
  `feign.FeignException$NotFound: [404 Not Found] during [GET] to [http://product-service/api/products/1]...`
* *Tác động:* Nếu các client service không cấu hình Circuit Breaker (Resilience4j) hoặc không có `Fallback` handler, toàn bộ các API/luồng xử lý liên quan tại 3 client service sẽ bị đổ vỡ dây chuyền (**Cascading Failure**) và trả về lỗi `HTTP 500 Internal Server Error` cho người dùng cuối.

---

## PHẦN 3: ĐỀ XUẤT CHIẾN LƯỢC API VERSIONING

Để `product-service` tiến hóa API mà không làm "gãy" 3 client service, cần áp dụng chiến lược API Versioning kết hợp với quy trình Deprecation Plan.

### Cách 1: URI Path Versioning (Phổ biến nhất)
Duy trì cả phiên bản cũ và mới thông qua đường dẫn URL trong giai đoạn chuyển tiếp (Migration Window):
* Endpoint cũ (v1): `/api/v1/products/{id}` (hoặc `/api/products/{id}`) -> Trả về JSON cũ chứa `"name"`.
* Endpoint mới (v2): `/api/v2/products/{id}` -> Trả về JSON mới chứa `"productName"`.

* **Tradeoffs (Ưu & Nhược điểm):**
  * *Ưu điểm:* Rõ ràng, trực quan, dễ cấu hình routing trên API Gateway, dễ kiểm thử và cache.
  * *Nhược điểm:* `product-service` phải duy trì 2 controller/DTO khác nhau trong một khoảng thời gian, gây lặp code nhẹ (code duplication) cho đến khi ngắt hoàn toàn v1.

### Cách 2: Header / Content Negotiation Versioning
Giữ nguyên URI `/api/products/{id}`, phân biệt phiên bản thông qua Request Header (VD: `X-API-VERSION: 1` vs `X-API-VERSION: 2`) hoặc `Accept` Header (VD: `application/vnd.company.app-v1+json`).

* **Tradeoffs (Ưu & Nhược điểm):**
  * *Ưu điểm:* Giữ URI sạch sẽ, tuân thủ đúng nguyên lý RESTful (URI đại diện cho tài nguyên, Header đại diện cho định dạng/phiên bản của tài nguyên).
  * *Nhược điểm:* Khó test trực tiếp trên trình duyệt, cấu hình Caching phức tạp hơn (phải dựa vào header `Vary`), FeignClient phía client cần bổ sung cấu hình Header.

---

## PHẦN 4: THIẾT KẾ MÃ NGUỒN DTO MIGRATION DESIGN (JAVA)

Dưới đây là mã nguồn Java thiết kế lại DTO `ProductInfo` phía client bằng cách sử dụng `@JsonAlias` của Jackson. Giúp DTO nhận thành công **CẢ HAI** trường `"name"` (cũ) và `"productName"` (mới) từ JSON response mà không bị `null`.

### 1. File DTO: `ProductInfo.java`

```java
package com.example.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO thiết kế cho quá trình Migration.
 * Sử dụng @JsonAlias để mapping linh hoạt cả trường cũ ("name") và trường mới ("productName").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductInfo(
    Long id,
    
    // @JsonAlias cho phép deserializer đọc từ "productName" HOẶC "name".
    // Khi serialize lại thành JSON, nó sẽ dùng tên biến mặc định là "name".
    @JsonAlias({"productName", "name"}) 
    String name,
    
    Long price
) {}
```

---

### 2. File Minh Họa Kiểm Thử Deserialization: `ProductInfoTest.java`

```java
package com.example.client;

import com.example.client.dto.ProductInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ProductInfoTest {

    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        // 1. Giả lập JSON Format CŨ từ product-service (V1)
        String jsonOldFormat = """
            {
                "id": 101,
                "name": "Bàn phím cơ Logitech",
                "price": 1500000
            }
            """;

        // 2. Giả lập JSON Format MỚI từ product-service (V2)
        String jsonNewFormat = """
            {
                "id": 101,
                "productName": "Bàn phím cơ Logitech",
                "price": 1500000
            }
            """;

        // Deserialize JSON CŨ
        ProductInfo productInfoFromOld = objectMapper.readValue(jsonOldFormat, ProductInfo.class);
        System.out.println("=== Kết quả đọc JSON Format CŨ ===");
        System.out.println("ID: " + productInfoFromOld.id());
        System.out.println("Name: " + productInfoFromOld.name()); // Không bị null
        System.out.println("Price: " + productInfoFromOld.price());

        // Deserialize JSON MỚI
        ProductInfo productInfoFromNew = objectMapper.readValue(jsonNewFormat, ProductInfo.class);
        System.out.println("\n=== Kết quả đọc JSON Format MỚI ===");
        System.out.println("ID: " + productInfoFromNew.id());
        System.out.println("Name: " + productInfoFromNew.name()); // Không bị null
        System.out.println("Price: " + productInfoFromNew.price());
    }
}
```