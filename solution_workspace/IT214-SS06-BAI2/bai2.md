# BÀI LÀM: CHUYỂN ĐỔI PRODUCTSERVICECLIENTRT SANG FEIGNCLIENT

---

### 1. File: `ProductClient.java`
```java
package com.example.orderservice.client;

import com.example.orderservice.dto.ProductInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@FeignClient(name = "product-service", fallbackFactory = ProductClientFallbackFactory.class)
public interface ProductClient {

    @GetMapping("/api/products/{id}")
    ProductInfo getById(@PathVariable("id") Long id);

    @GetMapping("/api/products")
    List<ProductInfo> getAll();
}
```

---

### 2. File: `ProductClientFallbackFactory.java`
```java
package com.example.orderservice.client;

import com.example.orderservice.dto.ProductInfo;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class ProductClientFallbackFactory implements FallbackFactory<ProductClient> {

    private static final Logger log = LoggerFactory.getLogger(ProductClientFallbackFactory.class);

    @Override
    public ProductClient create(Throwable cause) {
        return new ProductClient() {
            @Override
            public ProductInfo getById(Long id) {
                log.error("Lỗi khi gọi product-service getById() với id = {}: {}", id, cause.getMessage(), cause);
                return ProductInfo.fallback(id);
            }

            @Override
            public List<ProductInfo> getAll() {
                log.error("Lỗi khi gọi product-service getAll(): {}", cause.getMessage(), cause);
                return Collections.emptyList();
            }
        };
    }
}
```

---

### 3. File: `UserClient.java`
```java
package com.example.orderservice.client;

import com.example.orderservice.dto.UserInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-service")
public interface UserClient {

    @GetMapping("/api/users/{userId}")
    UserInfo getUserById(@PathVariable("userId") Long userId);
}
```

---

### 4. Báo cáo so sánh & Lập luận (Comparison Report)

#### 4.1. So sánh số dòng code (Line of Code - LOC)

| Tiêu chí | RestTemplate (`ProductServiceClientRT`) | FeignClient (`ProductClient`) |
| :--- | :--- | :--- |
| **Bản chất** | Lớp triển khai cụ thể (Imperative) | Interface khai báo (Declarative) |
| **Số dòng code khai báo Client** | Khai báo class, tiêm phụ thuộc `RestTemplate`, viết try-catch thủ công cho từng method (~25 - 40 dòng). | Chỉ khai báo interface và các annotation `@FeignClient`, `@GetMapping` (~10 - 15 dòng). |
| **Xử lý ngoại lệ / Fallback** | Phải viết lặp lại try-catch `ResourceAccessException`, `HttpClientErrorException` trong từng hàm. | Tách riêng vào `FallbackFactory`, tái sử dụng và quản lý tập trung. |
| **Đánh giá tổng thể** | Viết dài, lặp code boilerplate, tốn công bảo trì URL và HTTP Method. | **Giảm 50-60% số dòng code giao tiếp**, loại bỏ hoàn toàn mã nguồn lặp lại. |

#### 4.2. Lập luận khi nào nên dùng mỗi phương pháp

1. **Nên sử dụng FeignClient khi:**
   - **Giao tiếp Microservice nội bộ (Service-to-Service):** Khi các service nằm trong cùng hệ thống Spring Cloud, sử dụng Eureka Service Discovery và Load Balancer.
   - **Code phong cách Declarative:** Giúp định nghĩa hợp đồng API nhanh chóng, rõ ràng, giảm mã giả (boilerplate code).
   - **Tích hợp sẵn Circuit Breaker:** Cần cấu hình xử lý sự cố (Fallback/FallbackFactory) một cách đơn giản và đồng bộ trên toàn team.

2. **Nên sử dụng RestTemplate / RestClient khi:**
   - **Gọi External / Third-party API:** Gọi các dịch vụ ngoài không nằm trong Service Discovery.
   - **Cần kiểm soát chi tiết (Low-level control):** Cần tùy biến sâu HTTP Headers, Cookie, Request Body Streaming, xử lý Response Status đặc thù hoặc các phương thức HTTP phức tạp.
   - **Hệ thống cũ (Legacy Applications):** Các dự án chưa nâng cấp lên Spring Cloud OpenFeign.