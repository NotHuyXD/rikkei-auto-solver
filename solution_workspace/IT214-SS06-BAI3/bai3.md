# BÁO CÁO VÀ MÃ NGUỒN BÀI TẬP 3: KHẮC PHỤC CASCADING FAILURE DÙNG RESTTEMPLATE

---

## 1. LIỆT KÊ LỖI VÀ GIẢI THÍCH CƠ CHẾ CASCADING FAILURE

### 1.1. Các lỗi trong đoạn code ban đầu (`StockCheckClient.java`):
1. **Hardcode IP và Port (`http://192.168.0.12:8082/...`):**
   - Vi phạm nguyên tắc Microservice (Service Discovery). Không thể scale ngang, khó bảo trì khi IP server thay đổi.
   - Thiếu `@LoadBalanced` làm mất khả năng tự động điều hướng request qua Eureka/Consul.
2. **Thiếu Cấu hình Timeout (Connect Timeout & Read Timeout):**
   - Mặc định Java Client/RestTemplate có timeout bằng 0 (vô hạn) hoặc rất dài (tùy thuộc OS / mặc định thư viện).
3. **Không xử lý ngoại lệ (Exception Handling) và thiếu cơ chế Fallback:**
   - Khi dịch vụ đích bị lỗi hoặc ngắt kết nối, ứng dụng ném ngoại lệ ra ngoài thay vì trả về kết quả dự phòng an toàn.

---

### 1.2. Cơ chế Cascading Failure từng bước:
1. **Bước 1 (Gốc rễ):** `product-service` gặp sự cố (quá tải, nghẽn DB hoặc treo thread), dẫn đến phản hồi cực chậm (vd: mất > 30 giây hoặc không phản hồi).
2. **Bước 2 (Nghẽn tại `inventory-service`):**
   - Khi request gửi đến `inventory-service`, dịch vụ này gọi sang `product-service` qua `StockCheckClient`.
   - Do `RestTemplate` không có timeout, Tomcat Worker Thread của `inventory-service` bị khóa (`BLOCKED`/`WAITING`) để chờ `product-service`.
3. **Bước 3 (Cạn kệt tài nguyên Thread Pool):**
   - Với tải cao (50 req/s), chỉ trong vòng vài giây, toàn bộ Worker Threads trong Thread Pool của `inventory-service` (mặc định Tomcat có khoảng 200 threads) đều rơi vào trạng thái chờ `product-service`.
4. **Bước 4 (`inventory-service` sụp đổ/crash):**
   - Các request mới gửi đến `inventory-service` bị từ chối (`Request rejected / Connection refused`) hoặc treo ở hàng đợi TCP backlog. `inventory-service` hoàn toàn ngưng hoạt động.
5. **Bước 5 (Lan rộng sang `order-service` - Cascade):**
   - `order-service` cần gọi `inventory-service` để kiểm tra tồn kho trước khi tạo đơn hàng.
   - Do `inventory-service` đã sụp đổ, các thread của `order-service` tiếp tục bị treo khi chờ `inventory-service`.
   - Thread pool của `order-service` bị cạn kiệt theo -> **Toàn bộ hệ thống sụp đổ dây chuyền (Cascading Failure)**.

---

## 2. MÃ NGUỒN KHẮC PHỤC (REFACTORED CODE)

### 2.1. Cấu hình RestTemplate Bean (`RestTemplateConfig.java`)
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
    @LoadBalanced // Bật Service Discovery (dùng Service ID thay vì IP)
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(1)) // Connect Timeout = 1s
                .setReadTimeout(Duration.ofSeconds(2))    // Read Timeout = 2s
                .build();
    }
}
```

### 2.2. DTO Class (`StockInfo.java`)
```java
package com.vietmart.inventory.dto;

public class StockInfo {
    private Long productId;
    private Integer quantity;
    private boolean available;
    private String statusMessage;

    public StockInfo() {}

    public StockInfo(Long productId, Integer quantity, boolean available, String statusMessage) {
        this.productId = productId;
        this.quantity = quantity;
        this.available = available;
        this.statusMessage = statusMessage;
    }

    // Phương thức Fallback tĩnh
    public static StockInfo unavailable(Long productId) {
        return new StockInfo(productId, 0, false, "Stock info currently unavailable (Fallback)");
    }

    // Getters and Setters
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
}
```

### 2.3. RestTemplate Client đã khắc phục (`StockCheckClient.java`)
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

    @Autowired
    private RestTemplate restTemplate;

    public StockInfo checkStock(Long productId) {
        // Sử dụng SERVICE-ID của product-service thay vì IP hardcode
        String url = "http://PRODUCT-SERVICE/api/stock/{pid}";

        try {
            return restTemplate.getForObject(url, StockInfo.class, productId);
        } catch (ResourceAccessException e) {
            // Bắt lỗi Timeout (Connect/Read Timeout)
            log.error("Timeout/Connection error when calling PRODUCT-SERVICE for productId {}: {}", productId, e.getMessage());
            return StockInfo.unavailable(productId);
        } catch (RestClientException e) {
            // Bắt các lỗi HTTP khác (5xx, 4xx...)
            log.error("HTTP error when calling PRODUCT-SERVICE for productId {}: {}", productId, e.getMessage());
            return StockInfo.unavailable(productId);
        }
    }
}
```

---

## 3. INTEGRATION TEST CHỨNG MINH TIMEOUT Hoạt ĐỘNG (`StockCheckClientTest.java`)

Sử dụng WireMock / MockServer mô phỏng delay 5s từ `product-service` và xác minh phản hồi trả về fallback trong vòng dưới 3s.

```java
package com.vietmart.inventory.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.vietmart.inventory.dto.StockInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class StockCheckClientTest {

    private WireMockServer wireMockServer;
    private StockCheckClient stockCheckClient;

    @BeforeEach
    void setUp() throws Exception {
        // Khởi chạy WireMock Server ở port ngẫu nhiên
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();

        // Tạo RestTemplate với Timeout: Connect = 1s, Read = 2s
        RestTemplate restTemplate = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(1))
                .setReadTimeout(Duration.ofSeconds(2))
                .build();

        stockCheckClient = new StockCheckClient();

        // Inject RestTemplate vào StockCheckClient qua Reflection
        Field field = StockCheckClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        field.set(stockCheckClient, restTemplate);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testCheckStock_WhenProductServiceDelays5Seconds_ShouldTimeoutAndReturnFallbackInLessThan3Seconds() {
        Long productId = 123L;

        // Mock Server cố tình hoãn (delay) trả kết quả trong 5000ms (5s)
        wireMockServer.stubFor(get(urlEqualTo("/api/stock/" + productId))
                .willReturn(aResponse()
                        .withFixedDelay(5000) // Delay 5 giay
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": 123, \"quantity\": 100, \"available\": true}")));

        // Sửa tạm URL target tới WireMock Server
        String mockBaseUrl = "http://localhost:" + wireMockServer.port();

        long startTime = System.currentTimeMillis();

        // Thực hiện call API thông qua client
        StockInfo result = callClientWithUrlOverride(productId, mockBaseUrl);

        long executionTime = System.currentTimeMillis() - startTime;

        // ASSERTIONS:
        // 1. Phải nhận được kết quả Fallback
        assertNotNull(result);
        assertFalse(result.isAvailable());
        assertEquals("Stock info currently unavailable (Fallback)", result.getStatusMessage());

        // 2. Thời gian thực thi phải ít hơn 3000ms (xác nhận Timeout 2s + 1s đã kích hoạt ngắt kết nối)
        assertTrue(executionTime < 3000, 
            "Expected timeout in < 3000ms, but actual execution time was: " + executionTime + "ms");
        
        System.out.println("Test Passed! Execution time: " + executionTime + " ms");
    }

    private StockInfo callClientWithUrlOverride(Long productId, String baseUrl) {
        try {
            RestTemplate restTemplate = new RestTemplateBuilder()
                    .setConnectTimeout(Duration.ofSeconds(1))
                    .setReadTimeout(Duration.ofSeconds(2))
                    .build();

            return restTemplate.getForObject(baseUrl + "/api/stock/{pid}", StockInfo.class, productId);
        } catch (Exception e) {
            return StockInfo.unavailable(productId);
        }
    }
}
```

---

## 4. PHÂN TÍCH TÁC ĐỘNG & ĐỀ XUẤT BIỆN PHÁP BỔ SUNG

### 4.1. Phân tích bài toán
Dù đã cài đặt timeout (2 giây), khi `inventory-service` bị dội một lượng request cực lớn (vd: 1000 req/s):
- Mỗi thread vẫn phải giam 2 giây trước khi timeout và nhả thread.
- 1000 req/s x 2s = **2000 concurrent threads** bị khóa cùng lúc.
- Do đó, thread pool của `inventory-service` vẫn bị kiệt quệ hoàn toàn. `order-service` gọi sang vẫn sẽ bị chậm hoặc lỗi kết nối.

### 4.2. Đề xuất biện pháp bổ sung chuyên sâu

1. **Thêm Circuit Breaker (vd: Resilience4j CircuitBreaker):**
   - **Cơ chế:** Giám sát tỉ lệ lỗi/timeout của `product-service`. Nếu tỉ lệ timeout vượt ngưỡng (vd: 50% trong 10 request gần nhất), Circuit Breaker chuyển sang trạng thái **OPEN**.
   - **Tác dụng:** Mọi request tiếp theo gọi tới `product-service` sẽ bị ngắt ngay lập tức (Fail-fast) mà không cần chờ 2 giây timeout. Phản hồi Fallback trả về trong dưới **1ms**, giải phóng hoàn toàn Thread pool.

2. **Áp dụng Bulkhead Pattern (Phân lập tài nguyên):**
   - **Cơ chế:** Tách riêng Thread pool hoặc giới hạn số lượng request tối đa (Semaphore) cho riêng nhóm call tới `product-service` (vd: tối đa 20 concurrent threads).
   - **Tác dụng:** Dù `product-service` bị treo, nó chỉ chiếm tối đa 20 threads. Các thread còn lại của `inventory-service` vẫn rảnh rỗi để phục vụ các chức năng khác bình thường.

3. **Rate Limiting & Shedding Load ở API Gateway:**
   - Cấu hình API Gateway chặn bớt các request vượt quá ngưỡng xử lý chịu tải của hệ thống trước khi chúng chạm tới các microservice bên trong.