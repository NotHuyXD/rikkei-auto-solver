/* 
 ============================================================================
 PHẦN 1 – PHÂN TÍCH QUY TẮC CHỮ KÝ (SIGNATURE) CỦA PHƯƠNG THỨC FALLBACK
 ============================================================================
 Để phương thức Fallback hoạt động hợp lệ trong Resilience4j (Spring Boot), 
 chữ ký (signature) của nó phải tuân thủ các quy tắc bắt buộc sau:
 1. Kiểu trả về (Return Type): BẮT BUỘC phải giống hệt kiểu trả về của phương thức gốc.
 2. Tham số đầu vào (Parameters): BẮT BUỘC phải chứa toàn bộ các tham số của phương thức gốc (theo đúng thứ tự), VÀ BẮT BUỘC phải bổ sung thêm tham số cuối cùng có kiểu là Throwable (hoặc một Exception cụ thể).
 3. Tên phương thức: Phải trùng khớp hoàn toàn với giá trị được khai báo trong thuộc tính `fallbackMethod` của annotation `@CircuitBreaker`.
 4. Vị trí: Phải nằm trong cùng một Class với phương thức gốc (hoặc thông qua class Fallback riêng nếu sử dụng Feign Client/Fallback Factory).
*/

// ============================================================================
// PHẦN 2 – THỰC THI MÃ NGUỒN
// ============================================================================
package com.storex.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class BannerService {

    private static final Logger log = LoggerFactory.getLogger(BannerService.class);

    /**
     * Bổ sung thuộc tính fallbackMethod vào annotation @CircuitBreaker
     */
    @CircuitBreaker(name = "bannerService", fallbackMethod = "getBannersFallback")
    public List<String> getBanners() throws IOException {
        // Giả định logic gọi HTTP Client sang Banner-Service
        return callExternalBannerService();
    }

    private List<String> callExternalBannerService() throws IOException {
        // Logic thực tế kết nối microservice...
        return List.of("Banner Khuyến Mãi Hè", "Banner Giảm Giá 50%");
    }

    /**
     * Phương thức Fallback hoàn chỉnh
     * Hứng Throwable làm tham số cuối cùng để xử lý "bẫy dữ liệu" và ghi log chi tiết
     */
    public List<String> getBannersFallback(Throwable throwable) {
        // Phân tích và ghi log nguyên nhân gây ra lỗi
        if (throwable instanceof CallNotPermittedException) {
            log.warn("[Circuit Breaker OPEN] Cầu dao đang MỞ MẠCH. Tự động chuyển hướng sang Banner mặc định. Chi tiết lỗi: {}", throwable.getMessage());
        } else if (throwable instanceof IOException) {
            log.error("[Lỗi Mạng] Không thể kết nối tới Banner-Service (IOException). Chi tiết lỗi: {}", throwable.getMessage());
        } else {
            log.error("[Lỗi Không Xác Định] Xảy ra sự cố khi gọi Banner-Service. Chi tiết: {}", throwable.getMessage(), throwable);
        }

        // Trả về Banner mặc định theo đúng nghiệp vụ đề bài
        return List.of("Freeship mọi đơn hàng");
    }
}