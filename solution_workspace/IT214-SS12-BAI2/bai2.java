/*
 * ====================================================================
 * PHẦN 1 – PHÂN TÍCH QUY TẮC CHỮ KÝ (SIGNATURE) CỦA PHƯƠNG THỨC FALLBACK
 * ====================================================================
 * Quy tắc chữ ký của phương thức Fallback trong Resilience4j:
 * 1. Kiểu trả về (Return Type): Phải GIỐNG HỆT (hoặc tương thích) với kiểu trả về của phương thức gốc.
 * 2. Danh sách tham số (Parameters): Phải chứa TOÀN BỘ các tham số của phương thức gốc (theo đúng thứ tự)
 *    và BẮT BUỘC phải thêm 1 tham số CUỐI CÙNG có kiểu dữ liệu là Throwable (hoặc Exception cụ thể).
 * 3. Phạm vi (Location): Phải nằm trong cùng một Class với phương thức gốc (nếu dùng Resilience4j Annotation).
 */

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

    // PHẦN 2 – THỰC THI: Bổ sung thuộc tính fallbackMethod
    @CircuitBreaker(name = "bannerService", fallbackMethod = "getFallbackBanners")
    public List<String> getBanners() throws IOException {
        // Giả định logic gọi điện sang Banner-Service thực tế ở đây
        return List.of("Khuyến mãi mùa hè", "Giảm giá 50%");
    }

    /**
     * Phương thức Fallback xử lý khi có lỗi hoặc Circuit Breaker mở mạch
     * Bẫy dữ liệu Throwable để kiểm tra và ghi log nguyên nhân chính xác.
     */
    public List<String> getFallbackBanners(Throwable throwable) {
        if (throwable instanceof IOException) {
            log.error("[FALLBACK] Lỗi mạng khi kết nối tới Banner-Service: {}", throwable.getMessage());
        } else if (throwable instanceof CallNotPermittedException) {
            log.error("[FALLBACK] Cầu dao đang Mở mạch (Circuit Breaker OPEN), ngắt kết nối an toàn: {}", throwable.getMessage());
        } else {
            log.error("[FALLBACK] Nguyên nhân lỗi khác: {}", throwable.getMessage(), throwable);
        }

        // Trả về Banner mặc định để bảo vệ giao diện khách hàng
        return List.of("Freeship mọi đơn hàng");
    }
}