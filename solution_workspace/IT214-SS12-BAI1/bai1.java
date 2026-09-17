===================================================================
PHẦN 1: PHÂN TÍCH LỖ HỔNG CASCADING FAILURE
===================================================================

1. Nút thắt cổ chai tài nguyên:
   - Tài nguyên bị nghẽn trực tiếp là: THREAD POOL (Tập hợp luồng xử lý của Tomcat/Order-Service) và kết nối HTTP (HTTP Connections).

2. Giải thích cơ chế gây sập hệ thống (Cascading Failure):
   - Mặc định, RestTemplate trong Spring không cấu hình Read Timeout (hoặc timeout mặc định cực kỳ lớn).
   - Khi Recommendation-Service phản hồi chậm (mất 60 giây/request), mỗi request từ khách hàng gọi đến Order-Service sẽ tạo/sử dụng một Thread trong Thread Pool của Tomcat và bị treo giữ trong trạng thái chờ (Blocked/Waiting) suốt 60 giây đó.
   - Tomcat mặc định chỉ có số lượng Thread hạn chế (thường là 200 max-threads).
   - Khi có 1000 khách hàng cùng bấm thanh toán, 200 Thread đầu tiên lập tức bị chiếm dụng trọn vẹn và cạn kiệt (Thread Starvation). 800 request còn lại phải chờ trong hàng đợi (Queue) hoặc bị từ chối thẳng.
   - Kết quả: Order-Service hết sạch Thread để phục vụ bất kỳ yêu cầu nào khác (kể cả những chức năng chính như thanh toán), dẫn đến lỗi 503 (Service Unavailable) và làm sập toàn bộ Order-Service. Một sự cố ở dịch vụ phụ (Recommendation) đã lan truyền làm sập dịch vụ chính (Order).

===================================================================
PHẦN 2: SỬA LỖI (CODE JAVA REFACTOR)
===================================================================

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Service
public class OrderRecommendationService {

    private final RestTemplate restTemplate;

    public OrderRecommendationService() {
        // Thiết lập Timeout 3 giây cho RestTemplate
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000); // 3 giây kết nối
        requestFactory.setReadTimeout(3000);    // 3 giây chờ đọc dữ liệu

        this.restTemplate = new RestTemplate(requestFactory);
    }

    /**
     * Lấy danh sách sản phẩm gợi ý mua kèm.
     * Nếu quá 3 giây không lấy được, fallback trả về danh sách rỗng []
     * để không ảnh hưởng đến luồng thanh toán chính.
     */
    public List<Object> getRecommendedProducts(Long userId) {
        String url = "http://recommendation-service/api/recommendations?userId=" + userId;

        try {
            // Gọi API gợi ý
            Object[] response = restTemplate.getForObject(url, Object[].class);
            if (response != null) {
                return List.of(response);
            }
            return Collections.emptyList();
        } catch (RestClientException e) {
            // Log lỗi/timeout để theo dõi (không throw ngoại lệ ra ngoài)
            System.err.println("[FALLBACK TRIGGERED] Không thể lấy gợi ý từ Recommendation-Service (Lỗi/Timeout 3s): " + e.getMessage());
            
            // Fallback: Trả về danh sách rỗng [] để cho phép khách hàng thanh toán thành công
            return Collections.emptyList();
        }
    }
}