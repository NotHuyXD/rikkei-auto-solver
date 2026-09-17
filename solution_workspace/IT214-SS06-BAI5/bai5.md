# BÁO CÁO VÀ TRIỂN KHAI: API AGGREGATOR PATTERN CHO DASHBOARD VIETMART

---

## 1. Giải thích Thời gian Phản hồi (Sequential vs Parallel)

* **Gọi đồng bộ tuần tự (Sequential):**
  * **Cơ chế:** Thread chính xử lý request phải chờ `orderClient.getTotalCount()` hoàn thành (200ms), rồi mới bắt đầu gọi `orderClient.getWeekRevenue()` (200ms), tiếp theo là `productClient.getActiveCount()` (200ms) và cuối cùng là `userClient.getNewUsersThisMonth()` (200ms).
  * **Tổng thời gian:** $T_{total} = T_1 + T_2 + T_3 + T_4 = 200ms + 200ms + 200ms + 200ms = 800ms$.

* **Gọi đồng bộ song song (Parallel):**
  * **Cơ chế:** Thread chính kích hoạt cả 4 lời gọi API cùng một lúc trên các worker threads riêng biệt. Cả 4 request được thực thi đồng thời.
  * **Tổng thời gian:** $T_{total} = \max(T_1, T_2, T_3, T_4) \approx 200ms$ (thời gian của lời gọi hoàn thành muộn nhất).

---

## 2. Mã nguồn triển khai (DashboardController.java)

```java
package com.vietmart.dashboard.controller;

import com.vietmart.dashboard.client.OrderClient;
import com.vietmart.dashboard.client.ProductClient;
import com.vietmart.dashboard.client.UserClient;
import com.vietmart.dashboard.dto.DashboardResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.*;

@RestController
@RequestMapping("/api/v1")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final OrderClient orderClient;
    private final ProductClient productClient;
    private final UserClient userClient;

    // Khai báo ThreadPool riêng để tránh làm cạn kệt ForkJoinPool.commonPool()
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    @Autowired
    public DashboardController(OrderClient orderClient, ProductClient productClient, UserClient userClient) {
        this.orderClient = orderClient;
        this.productClient = productClient;
        this.userClient = userClient;
    }

    @GetMapping("/dashboard")
    public DashboardResponse getDashboard() {
        long startTime = System.currentTimeMillis();

        // 1. Tạo các CompletableFuture chạy song song với xử lý ngoại lệ (Fallback = 0L)
        CompletableFuture<Long> totalOrdersFuture = CompletableFuture
                .supplyAsync(orderClient::getTotalCount, executorService)
                .exceptionally(ex -> {
                    log.error("Lỗi khi lấy tổng số đơn hàng: {}", ex.getMessage());
                    return 0L; // Giá trị mặc định khi lỗi
                });

        CompletableFuture<Long> weekRevenueFuture = CompletableFuture
                .supplyAsync(orderClient::getWeekRevenue, executorService)
                .exceptionally(ex -> {
                    log.error("Lỗi khi lấy doanh thu tuần: {}", ex.getMessage());
                    return 0L; // Giá trị mặc định khi lỗi
                });

        CompletableFuture<Long> activeProdsFuture = CompletableFuture
                .supplyAsync(productClient::getActiveCount, executorService)
                .exceptionally(ex -> {
                    log.error("Lỗi khi lấy số sản phẩm đang bán: {}", ex.getMessage());
                    return 0L; // Giá trị mặc định khi lỗi
                });

        CompletableFuture<Long> newUsersFuture = CompletableFuture
                .supplyAsync(userClient::getNewUsersThisMonth, executorService)
                .exceptionally(ex -> {
                    log.error("Lỗi khi lấy số user mới trong tháng: {}", ex.getMessage());
                    return 0L; // Giá trị mặc định khi lỗi
                });

        // 2. Gom tất cả các Future lại
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                totalOrdersFuture,
                weekRevenueFuture,
                activeProdsFuture,
                newUsersFuture
        );

        // 3. Chờ tất cả hoàn thành với Timeout tổng là 3 giây
        try {
            allFutures.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Dashboard API vượt quá thời gian chờ (Timeout 3s)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread bị gián đoạn: {}", e.getMessage());
        } catch (ExecutionException e) {
            log.error("Lỗi khi thực thi các tác vụ song song: {}", e.getMessage());
        }

        // 4. Lấy kết quả (Sử dụng join/getNow để đảm bảo không bị block lâu vì đã handle timeout ở trên)
        Long totalOrders = totalOrdersFuture.getNow(0L);
        Long weekRevenue = weekRevenueFuture.getNow(0L);
        Long activeProds = activeProdsFuture.getNow(0L);
        Long newUsers = newUsersFuture.getNow(0L);

        long executionTime = System.currentTimeMillis() - startTime;
        log.info("Dashboard API response time: {} ms", executionTime);

        return new DashboardResponse(totalOrders, weekRevenue, activeProds, newUsers);
    }
}
```

---

## 3. Phân tích Tradeoff (Sử dụng CompletableFuture vs Gọi tuần tự)

### Ưu điểm
1. **Tối ưu thời gian phản hồi (Latency):** Giảm thời gian chờ từ $\sum T_i$ (~800ms) xuống $\max(T_i)$ (~200ms), giúp tăng trải nghiệm người dùng trên màn hình Dashboard.
2. **Khả năng chịu lỗi cục bộ (Fault Tolerance / Resilience):** Nhờ cơ chế `.exceptionally()`, nếu 1 downstream service gặp sự cố (ví dụ: `user-service` bị die hoặc timeout), các service khác vẫn trả về dữ liệu bình thường. Màn hình Dashboard hiển thị giá trị mặc định (`0`) thay vì trả về lỗi HTTP 500 cho người dùng.
3. **Kiểm soát thời gian chờ tổng thể (Global Timeout):** Giới hạn tối đa thời gian chờ của endpoint bằng `allOf().get(3, TimeUnit.SECONDS)`.

### Nhược điểm / Đánh đổi
1. **Độ phức tạp của mã nguồn (Code Complexity):** Code bất đồng bộ/song song khó đọc, khó bảo trì hơn logic tuần tự đơn giản.
2. **Khó khăn trong Debugging & Tracing:**
   * Stack trace khó theo dõi hơn khi ngoại lệ bắn ra từ các worker thread khác nhau.
   * Cần cấu hình truyền ngữ cảnh (Tracing Context như Spring Cloud Sleuth / Micrometer Tracing / ThreadLocal) để log giữ được `traceId` nhất quán qua các thread.
3. **Tiêu tốn tài nguyên hệ thống (Resource Consumption):**
   * Sử dụng nhiều thread hơn đồng nghĩa với tăng chi phí Context Switching của CPU và ngốn nhiều bộ nhớ RAM hơn.
   * Nếu không cấu hình Thread Pool riêng (như dùng mặc định `ForkJoinPool.commonPool()`), một request tải cao có thể chiếm dụng toàn bộ thread pool chung của ứng dụng, gây tắc nghẽn toàn bộ hệ thống (Thread Pool Exhaustion).