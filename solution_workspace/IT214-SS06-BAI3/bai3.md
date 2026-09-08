# BÁO CÁO BÀI TẬP 3: KHẮC PHỤC CASCADING FAILURE DO THIẾU TIMEOUT TRONG RESTTEMPLATE

---

## PHẦN 1: PHÂN TÍCH LỖI VÀ CƠ CHẾ CASCADING FAILURE

### 1. Các lỗi trong đoạn code ban đầu (`StockCheckClient.java`)
- **Hardcode URL IP (`http://192.168.0.12:8082/...`)**: Không sử dụng Service Discovery (Eureka/Consul), làm cứng vị trí triển khai, gây rủi ro khi IP thay đổi hoặc scaling.
- **Thiếu annotation `@LoadBalanced`**: Không thể tự động phân giải Service ID (ví dụ: `PRODUCT-SERVICE`) và không hỗ trợ cân bằng tải phía client.
- **Không cấu hình Timeout (Connect Timeout & Read Timeout)**: Mặc định `RestTemplate` ngâm kết nối vô hạn hoặc chờ theo timeout mặc định rất lớn của OS/JVM.
- **Thiếu cơ chế xử lý lỗi (Fallback)**: Khi dịch vụ `product-service` phản hồi chậm hoặc treo, ngoại lệ sẽ bắn thẳng ra ngoài khiến request bị thất bại trực tiếp thay vì trả về dữ liệu an toàn dự phòng.

### 2. Cơ chế Cascading Failure theo từng bước
1. **Giai đoạn khởi phát**: `product-service` gặp sự cố (sự cố mạng, high CPU, Full GC, đĩa I/O bị khóa), dẫn đến response time bị delay kéo dài (ví dụ: > 30 giây hoặc treo).
2. **Tích tụ thread bị block**: Khi `inventory-service` nhận request và gọi sang `product-service` qua `StockCheckClient`, do không có timeout, mỗi thread gọi API sẽ bị block vô thời hạn để chờ phản hồi từ `product-service`.
3. **Cạn kiệt Thread Pool (Thread Exhaustion)**: Với lưu lượng cao (50 req/s), chỉ trong vòng vài giây, toàn bộ thread trong Thread Pool của Tomcat (mặc định 200 threads) trên `inventory-service` sẽ bị chiếm giữ hoàn toàn.
4. **`inventory-service` bị crash/unresponsive**: `inventory-service` không còn thread trống nào để xử lý các request mới, kể cả những API độc lập không gọi sang `product-service`.
5. **Lan rộng sang `order-service`**: Các service tuyến trước (như `order-service`) khi gọi sang `inventory-service` cũng bị ngâm thread theo. Chuỗi phản ứng dây chuyền này kéo đổ toàn bộ hệ thống Microservices (Cascading Failure).

---

## PHẦN 2: MÃ NGUỒN KHẮC PHỤC

### 1. Configuration Bean RestTemplate (`RestTemplateConfig.java`)

```java
package com.vietmart.inventory.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(1)) // Connect Timeout = 1s
                .setReadTimeout(Duration.ofSeconds(2))    // Read Timeout = 2s
                .build();
    }
}
```

### 2. DTO Model (`StockInfo.java`)

```java
package com.vietmart.inventory.dto;

public class StockInfo {
    private Long productId;
    private Integer quantity;
    private String status;

    public StockInfo() {}

    public StockInfo(Long productId, Integer quantity, String status) {
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
    }

    // Phương thức Fallback tĩnh khi dịch vụ bị sự cố
    public static StockInfo unavailable(Long productId) {
        return new StockInfo(productId, 0, "UNAVAILABLE");
    }

    // Getters and Setters
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
```

### 3. Client đã sửa đổi (`StockCheckClient.java`)

```java
package com.vietmart.inventory.client;

import com.vietmart.inventory.dto.StockInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class StockCheckClient {

    private static final Logger log = LoggerFactory.getLogger(StockCheckClient.class);
    private static final String PRODUCT_SERVICE_URL = "http://PRODUCT-SERVICE/api/stock/{pid}";

    @Autowired
    private RestTemplate restTemplate;

    public StockInfo checkStock(Long productId) {
        try {
            // Sử dụng Service ID thay vì IP hardcode
            return restTemplate.getForObject(PRODUCT_SERVICE_URL, StockInfo.class, productId);
        } catch (ResourceAccessException e) {
            // Xử lý sự cố Timeout (Connect Timeout hoặc Read Timeout)
            log.error("Timeout khi gọi PRODUCT-SERVICE cho productId: {}. Error: {}", productId, e.getMessage());
            return StockInfo.unavailable(productId);
        } catch (RestClientException e) {
            // Xử lý các lỗi Rest Client khác
            log.error("Lỗi giao tiếp với PRODUCT-SERVICE cho productId: {}. Error: {}", productId, e.getMessage());
            return StockInfo.unavailable(productId);
        }
    }
}
```

---

## PHẦN 3: TEST TÍCH HỢP XÁC MINH TIMEOUT & FALLBACK

```java
package com.vietmart.inventory.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.vietmart.inventory.dto.StockInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class StockCheckClientTest {

    private WireMockServer wireMockServer;

    @Autowired
    private StockCheckClient stockCheckClient;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public RestTemplate testRestTemplate(RestTemplateBuilder builder) {
            // RestTemplate cấu hình timeout tương tự sản phẩm (Connect 1s, Read 2s)
            return builder
                    .setConnectTimeout(Duration.ofSeconds(1))
                    .setReadTimeout(Duration.ofSeconds(2))
                    .build();
        }
    }

    @BeforeEach
    void startWireMock() {
        // Khởi chạy WireMock Server trên port 8082 giả lập product-service
        wireMockServer = new WireMockServer(8082);
        wireMockServer.start();
    }

    @AfterEach
    void stopWireMock() {
        wireMockServer.stop();
    }

    @Test
    void testCheckStock_WhenProductServiceDelayed5s_ShouldReturnFallbackUnder3s() {
        Long productId = 100L;

        // Giả lập Server bị trễ 5 giây (5000ms)
        wireMockServer.stubFor(get(urlEqualTo("/api/stock/" + productId))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": 100, \"quantity\": 50, \"status\": \"AVAILABLE\"}")
                        .withFixedDelay(5000)));

        long startTime = System.currentTimeMillis();

        // Thực hiện lệnh gọi
        StockInfo result = stockCheckClient.checkStock(productId);

        long executionTime = System.currentTimeMillis() - startTime;

        // KẾT QUẢ KỲ VỌNG:
        // 1. Phải phản hồi trong thời gian < 3000ms (do readTimeout = 2s)
        assertTrue(executionTime < 3000, 
                "Thời gian thực thi phải nhỏ hơn 3s nhưng thực tế là: " + executionTime + "ms");

        // 2. Phải trả về Fallback
        assertNotNull(result);
        assertEquals("UNAVAILABLE", result.getStatus());
        assertEquals(0, result.getQuantity());
    }
}
```

---

## PHẦN 4: PHÂN TÍCH NÂNG CAO VÀ ĐỀ XUẤT BIỆN PHÁP BỔ SUNG

### 1. Phân tích tác động sau khi đã cấu hình Timeout
Sau khi đã bổ sung Timeout (2 giây), `order-service` **vẫn có thể bị ảnh hưởng** nếu `inventory-service` bị tràn ngập lượng request cực lớn (traffic spike).

**Lý do:**
- Mặc dù mỗi thread bị hủy sau 2 giây (thay vì 30 giây), nhưng nếu tải đầu vào quá lớn (ví dụ: 1.000 req/s), Tomcat vẫn cần cấp phát 1.000 thread. Trong khoảng thời gian 2 giây chờ timeout, các thread này vẫn bị giải phóng quá chậm so với tốc độ request đổ vào -> Thread pool của `inventory-service` vẫn cạn kiệt.
- `inventory-service` vẫn tốn tài nguyên vô ích để tạo kết nối HTTP kết nối sang `product-service` dù đã biết `product-service` đang sập.

### 2. Đề xuất biện pháp bổ sung kỹ thuật (Circuit Breaker Pattern)

Để khắc phục triệt để, hệ thống cần áp dụng **Circuit Breaker Pattern** (ví dụ dùng thư viện **Resilience4j** hoặc **Spring Cloud CircuitBreaker**):

*   **Nguyên lý hoạt động:**
    1. **Trạng thái Closed (Bình thường):** Mọi request được chuyển tiếp tới `product-service`.
    2. **Chuyển sang Open (Ngắt mạch):** Khi tỷ lệ thất bại/timeout vượt quá ngưỡng thiết lập (ví dụ: 50% request trong 10s bị timeout), Circuit Breaker chuyển sang trạng thái **OPEN**.
    3. **Fail-Fast (Phản hồi ngay tức thì):** Ở trạng thái Open, mọi request từ `inventory-service` gọi sang `product-service` sẽ bị ngắt lập tức mà **KHÔNG tốn thời gian chờ kết nối HTTP (0ms execution time)** và lập tức trả về Fallback `StockInfo.unavailable()`.
    4. **Trạng thái Half-Open (Thử nghiệm):** Sau một khoảng thời gian (wait duration, ví dụ: 10s), Circuit Breaker cho phép một vài request test thử nghiệm. Nếu thành công, mạch đóng lại (Closed); nếu vẫn thất bại, mạch giữ nguyên (Open).

*   **Các biện pháp hỗ trợ khác:**
    - **Bulkhead Pattern:** Phân chia thread pool riêng biệt cho các tác vụ gọi external service khác nhau để tránh rủi ro lây nhiễm chéo giữa các API.
    - **Rate Limiting (API Gateway):** Giới hạn số lượng request tối đa trên giây (RPS) đổ vào `inventory-service`.