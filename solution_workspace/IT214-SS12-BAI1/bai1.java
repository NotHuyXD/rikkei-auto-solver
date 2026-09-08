/*
 * PHẦN 1 – PHÂN TÍCH
 * 
 * 1. Nguyên nhân sập Order-Service:
 *    - Theo mặc định, RestTemplate không thiết lập timeout (hoặc timeout rất lớn). Khi Recommendation-Service
 *      gặp sự cố và phản hồi mất 60 giây, các luồng (threads) của Order-Service gọi sang Recommendation-Service
 *      sẽ rơi vào trạng thái nghẽn (blocked) để chờ kết quả.
 * 
 * 2. Nút thắt cổ chai (Bottleneck Resource):
 *    - Nút thắt cổ chai chính là Thread Pool của Servlet Container (ví dụ: Tomcat Worker Threads trong Spring Boot, 
 *      thường mặc định tối đa là 200 threads).
 *    - Khi 1000 khách hàng bấm thanh toán cùng lúc, toàn bộ các thread xử lý request của Order-Service nhanh chóng bị 
 *      chiếm dụng hoàn toàn và treo trong 60 giây. Khi Thread Pool bị kiệt quệ (Thread Starvation), các request mới
 *      gửi tới Order-Service (dù là chức năng thanh toán hay các chức năng khác) không còn thread nào tiếp nhận,
 *      dẫn đến việc request bị xếp hàng chờ rồi bị từ chối với lỗi HTTP 503 (Service Unavailable). 
 *      Đây chính là hiện tượng Cascading Failure (Lỗi lan truyền).
 */

// PHẦN 2 – SỬA LỖI

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Service
public class OrderService {

    private final RestTemplate restTemplate;

    // Cấu hình RestTemplate với Timeout là 3 giây
    public OrderService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Lấy danh sách sản phẩm gợi ý mua kèm.
     * Nếu quá 3 giây hoặc xảy ra lỗi, hệ thống sẽ trả về danh sách rỗng []
     */
    public List<Object> getRecommendedProducts(Long userId) {
        String url = "http://recommendation-service/api/recommendations?userId=" + userId;
        
        try {
            // Gọi API lấy danh sách gợi ý
            Object[] response = restTemplate.getForObject(url, Object[].class);
            return response != null ? List.of(response) : Collections.emptyList();
        } catch (Exception e) {
            // FALLBACK MECHANISM:
            // Bắt mọi lỗi (bao gồm SocketTimeoutException/ResourceAccessException khi quá 3s)
            // Trả về danh sách rỗng [] để không ảnh hưởng đến luồng thanh toán chính.
            System.err.println("Recommendation-Service bị lỗi hoặc timeout (>3s). Kích hoạt Fallback: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}