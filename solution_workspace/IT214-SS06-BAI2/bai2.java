// ==========================================
// 1. Interface: ProductClient.java
// ==========================================
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


// ==========================================
// 2. Interface: UserClient.java
// ==========================================
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


// ==========================================
// 3. Class: ProductClientFallbackFactory.java
// ==========================================
package com.example.orderservice.client;

import com.example.orderservice.dto.ProductInfo;
import org.slf.Logger;
import org.slf.LoggerFactory;
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
                log.error("Lỗi khi gọi product-service getById với id={}: {}", id, cause.getMessage(), cause);
                return ProductInfo.fallback(id);
            }

            @Override
            public List<ProductInfo> getAll() {
                log.error("Lỗi khi gọi product-service getAll: {}", cause.getMessage(), cause);
                return Collections.emptyList();
            }
        };
    }
}


/*
================================================================================
BÁO CÁO SO SÁNH: RestTemplate (ProductServiceClientRT) vs FeignClient (ProductClient)
================================================================================

1. So sánh số dòng code (Line of Code - LOC):
--------------------------------------------------------------------------------
- RestTemplate (ProductServiceClientRT):
  + Số dòng code: ~25 - 35 dòng cho 2 phương thức.
  + Chi tiết: Cần inject RestTemplate, viết code khởi tạo URL thủ công, sử dụng try-catch thủ công để bắt từng ngoại lệ (ResourceAccessException, HttpClientErrorException, v.v.) và gọi phương thức fallback trực tiếp.

- FeignClient (ProductClient + ProductClientFallbackFactory):
  + Số dòng code interface ProductClient: ~10 dòng.
  + Chi tiết: Khai báo interface declarative chỉ tốn 10 dòng code cực kỳ ngắn gọn, không chứa logic xử lý HTTP request thủ công hay try-catch trực tiếp trong client code.

2. Lập luận khi nào mỗi cách phù hợp hơn:
--------------------------------------------------------------------------------
a) Khi nào nên dùng FeignClient (Declarative HTTP Client):
   - Phù hợp trong kiến trúc Microservices chuẩn hóa: Khi dự án có nhiều service giao tiếp với nhau và tuân theo chuẩn REST API định sẵn.
   - Giúp tối ưu hóa năng suất phát triển: Giảm thiểu boilerplate code (try-catch, URL building, Object Mapping), tăng tính đọc hiểu và bảo trì code.
   - Khi cần tích hợp sẵn với Spring Cloud (Eureka Client, Resilience4j, Circuit Breaker / Fallback Factory): Tích hợp fallback và load balancing dễ dàng chỉ qua annotation.

b) Khi nào nên dùng RestTemplate (Imperative HTTP Client):
   - Phù hợp với các hệ thống Legacy hoặc dự án Spring cũ không sử dụng Spring Cloud OpenFeign.
   - Khi cần tùy biến HTTP request một cách chi tiết/phức tạp: Cần can thiệp sâu vào HTTP Headers, Low-level Connection Timeout/Read Timeout từng request riêng biệt, interceptor tùy chỉnh phức tạp, hoặc làm việc với các hệ thống API không theo chuẩn REST.
   - Khi giao tiếp với các bên thứ 3 (Third-party APIs) không nằm trong hệ thống Microservices nội bộ.
*/