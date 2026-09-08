# BÀI TẬP 2: CHUYỂN ĐỔI RESTTEMPLATE SANG FEIGNCLIENT

---

### 1. `ProductClient.java`
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

### 2. `ProductClientFallbackFactory.java`
```java
package com.example.orderservice.client;

import com.example.orderservice.dto.ProductInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
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
                log.error("Lỗi khi gọi product-service getById(id={}): {}", id, cause.getMessage(), cause);
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

### 3. `UserClient.java`
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

### 4. `application.yml`
```yaml
spring:
  application:
    name: order-service
  cloud:
    openfeign:
      circuitbreaker:
        enabled: true # Kích hoạt hỗ trợ Fallback / Circuit Breaker cho Feign Client

# Cấu hình Eureka Discovery (nếu sử dụng Service Discovery)
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

---

### 5. Báo cáo so sánh & Phân tích lựa chọn

#### A. So sánh số dòng code (LOC - Lines of Code)
* **ProductServiceClientRT (RestTemplate)**: ~38 dòng code.
  * Phải tự inject `RestTemplate`, viết thủ công logic URL, try-catch `ResourceAccessException`, `HttpClientErrorException.NotFound`, ép kiểu mảng `ProductInfo[]` sang `List<ProductInfo>`.
* **ProductClient (FeignClient)**: ~15 dòng code (Interface + Annotaions).
  * Chỉ khai báo interface và các Annotation `@GetMapping`, `@PathVariable`. Toàn bộ code xử lý HTTP Client được Spring Cloud OpenFeign tự động sinh ra dưới dạng Proxy Class.

#### B. Khi nào dùng mỗi cách?

| Tiêu chí | RestTemplate | FeignClient |
| :--- | :--- | :--- |
| **Cấu trúc code** | Imperative (viết thủ công các bước thực hiện) | Declarative (chỉ khai báo interface và annotation) |
| **Tích hợp Spring Cloud** | Cần cấu hình thêm với LoadBalancer | Tích hợp sẵn Service Discovery (Eureka), Load Balancer, Circuit Breaker |
| **Khả năng mở rộng** | Tốn công viết code boilerplate khi mở rộng nhiều API | Rất nhanh chóng, chỉ cần thêm method vào interface |
| **Khi nào nên dùng?** | 1. Tương tác với các REST API bên ngoài (Third-party APIs) không cùng trong hệ thống Spring Cloud.<br>2. Cần tùy biến sâu request (custom HTTP headers, connection pooling phức tạp, streaming data).<br>3. Dự án legacy không muốn nhúng thư viện Spring Cloud OpenFeign. | 1. Giao tiếp đồng bộ giữa các Microservices nội bộ trong cùng hệ thống Spring Cloud.<br>2. Muốn viết code gọn gàng, giảm boilerplate code.<br>3. Muốn tích hợp dễ dàng Fallback/Circuit Breaker (Resilience4j). |