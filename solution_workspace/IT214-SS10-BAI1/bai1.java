// ============================================================================
// BÁO CÁO GIẢI THÍCH 3 LỖI TRONG CODE CŨ
// ============================================================================
/*
  1. LỖI 1: Không dùng @LoadBalanced RestTemplate
     - Giải thích: Khi tạo mới RestTemplate bằng `new RestTemplate()`, nó chỉ hoạt động như một HTTP client thông thường, không tích hợp với Spring Cloud Service Discovery (Eureka).
     - Hậu quả: Không thể phân giải (resolve) các tên dịch vụ (service-id) như "http://product-service". Khi deploy, client không biết gọi đến instance nào.

  2. LỖI 2: Hardcode IP:port thay vì dùng service-id ("http://192.168.1.45:8082/api/products/{id}")
     - Giải thích: Chỉ định trực tiếp địa chỉ IP và Port cố định của môi trường local/dev.
     - Hậu quả:
       + Ứng dụng bị lỗi ngay khi triển khai lên môi trường Staging/Production do IP thay đổi.
       + Mất khả năng Load Balancing (cân bằng tải) khi product-service chạy nhiều instance.

  3. LỖI 3: Không cấu hình Timeout (Connect Timeout & Read Timeout)
     - Giải thích: RestTemplate mặc định không thiết lập thời gian chờ (hoặc chờ vô hạn tùy thuộc underlying HTTP library).
     - Hậu quả: Khi product-service bị treo, quá tải hoặc nghẽn mạng, các Thread bên order-service sẽ bị nghẽn (blocked) vô thời hạn. Điều này dẫn đến cạn kiệt tài nguyên (Thread exhaustion) và làm sập toàn bộ order-service (Cascading Failure).
*/

// ============================================================================
// 1. DTO & EXCEPTION CLASSES
// ============================================================================
package com.vietmart.orderservice.dto;

public class ProductInfo {
    private Long id;
    private String name;
    private Double price;

    public ProductInfo() {}

    public ProductInfo(Long id, String name, Double price) {
        this.id = id;
        this.name = name;
        this.price = price;
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
}

package com.vietmart.orderservice.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String message) {
        super(message);
    }
}

// ============================================================================
// 2. CONFIGURATION CLASS (TẠO BEAN RESTTEMPLATE VỚI @LOADBALANCED VÀ TIMEOUT)
// ============================================================================
package com.vietmart.orderservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced // Bắt buộc để giao tiếp qua Eureka Server và Load Balancing
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(2)) // Connect Timeout = 2s
                .setReadTimeout(Duration.ofSeconds(3))    // Read Timeout = 3s
                .build();
    }
}

// ============================================================================
// 3. SERVICE CLIENT (VIẾT LẠI CHUẨN NGHỆP VỤ & XỬ LÝ LỖI)
// ============================================================================
package com.vietmart.orderservice.client;

import com.vietmart.orderservice.dto.ProductInfo;
import com.vietmart.orderservice.exception.ProductNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Component
public class ProductServiceClientRT {

    private final RestTemplate restTemplate;
    private static final String PRODUCT_SERVICE_URL = "http://product-service/api/products/{id}";

    // Inject RestTemplate Bean đã cấu hình @LoadBalanced
    public ProductServiceClientRT(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ProductInfo getById(Long productId) {
        try {
            return restTemplate.getForObject(PRODUCT_SERVICE_URL, ProductInfo.class, productId);
        } catch (HttpClientErrorException.NotFound e) {
            // Lỗi 404: Không tìm thấy sản phẩm -> Ném ngoại lệ nghiệp vụ
            throw new ProductNotFoundException("Sản phẩm không tồn tại với ID: " + productId);
        } catch (ResourceAccessException e) {
            // Lỗi Timeout hoặc Không thể kết nối -> Trả về Fallback
            return getFallbackProductInfo(productId);
        }
    }

    // Phương thức Fallback khi gặp sự cố mạng / Timeout
    private ProductInfo getFallbackProductInfo(Long productId) {
        return new ProductInfo(productId, "Tạm thời không lấy được thông tin sản phẩm", 0.0);
    }
}

// ============================================================================
// 4. UNIT TEST (JUNIT 5 + MOCKITO)
// ============================================================================
package com.vietmart.orderservice.client;

import com.vietmart.orderservice.dto.ProductInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
public class ProductServiceClientRTTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private ProductServiceClientRT productServiceClient;

    private static final String URL = "http://product-service/api/products/{id}";

    @Test
    void testGetById_Success() {
        // Arrange
        Long productId = 1L;
        ProductInfo mockProduct = new ProductInfo(productId, "Laptop Dell XPS", 1500.0);
        
        Mockito.when(restTemplate.getForObject(eq(URL), eq(ProductInfo.class), eq(productId)))
                .thenReturn(mockProduct);

        // Act
        ProductInfo result = productServiceClient.getById(productId);

        // Assert
        Assertions.assertNotNull(result);
        Assertions.assertEquals(productId, result.getId());
        Assertions.assertEquals("Laptop Dell XPS", result.getName());
        Assertions.assertEquals(1500.0, result.getPrice());
    }

    @Test
    void testGetById_Timeout_ReturnsFallback() {
        // Arrange
        Long productId = 1L;
        
        // Giả lập ngoại lệ Timeout (ResourceAccessException)
        Mockito.when(restTemplate.getForObject(eq(URL), eq(ProductInfo.class), eq(productId)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        // Act
        ProductInfo result = productServiceClient.getById(productId);

        // Assert
        Assertions.assertNotNull(result);
        Assertions.assertEquals(productId, result.getId());
        Assertions.assertEquals("Tạm thời không lấy được thông tin sản phẩm", result.getName());
        Assertions.assertEquals(0.0, result.getPrice());
    }
}