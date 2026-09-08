package com.vietmart.dashboard.controller;

import com.vietmart.dashboard.client.OrderClient;
import com.vietmart.dashboard.client.ProductClient;
import com.vietmart.dashboard.client.UserClient;
import com.vietmart.dashboard.dto.DashboardResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final OrderClient orderClient;
    private final ProductClient productClient;
    private final UserClient userClient;

    /**
     * API Aggregator cho màn hình Dashboard
     * Thực hiện gọi 4 API song song bằng CompletableFuture với Timeout và Fallback mặc định.
     */
    @GetMapping
    public DashboardResponse getDashboard() {
        // 1. Tạo các Async Task với xử lý Fallback (exceptionally) khi service lỗi/down
        CompletableFuture<Long> totalOrdersFuture = CompletableFuture
                .supplyAsync(orderClient::getTotalCount)
                .exceptionally(ex -> {
                    log.error("Lỗi khi gọi orderClient.getTotalCount(): {}", ex.getMessage());
                    return 0L; // Giá trị mặc định khi gặp lỗi
                });

        CompletableFuture<Long> weekRevenueFuture = CompletableFuture
                .supplyAsync(orderClient::getWeekRevenue)
                .exceptionally(ex -> {
                    log.error("Lỗi khi gọi orderClient.getWeekRevenue(): {}", ex.getMessage());
                    return 0L; // Giá trị mặc định khi gặp lỗi
                });

        CompletableFuture<Long> activeProdsFuture = CompletableFuture
                .supplyAsync(productClient::getActiveCount)
                .exceptionally(ex -> {
                    log.error("Lỗi khi gọi productClient.getActiveCount(): {}", ex.getMessage());
                    return 0L; // Giá trị mặc định khi gặp lỗi
                });

        CompletableFuture<Long> newUsersFuture = CompletableFuture
                .supplyAsync(userClient::getNewUsersThisMonth)
                .exceptionally(ex -> {
                    log.error("Lỗi khi gọi userClient.getNewUsersThisMonth(): {}", ex.getMessage());
                    return 0L; // Giá trị mặc định khi gặp lỗi
                });

        // 2. Gom tất cả các Future lại và thiết lập Timeout tổng là 3 giây
        CompletableFuture<Void> allOfFutures = CompletableFuture.allOf(
                totalOrdersFuture,
                weekRevenueFuture,
                activeProdsFuture,
                newUsersFuture
        );

        try {
            // Chờ tối đa 3 giây cho toàn bộ các service hoàn tất
            allOfFutures.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Tổng thời gian gọi API Dashboard vượt quá 3s timeout!");
        } catch (Exception e) {
            log.error("Lỗi không xác định khi tổng hợp dữ liệu Dashboard: {}", e.getMessage());
        }

        // 3. Lấy kết quả từ từng Future (sử dụng join() vì các task đã chạy xong hoặc đã catch exception/timeout)
        Long totalOrders = totalOrdersFuture.getNow(0L);
        Long weekRevenue = weekRevenueFuture.getNow(0L);
        Long activeProds = activeProdsFuture.getNow(0L);
        Long newUsers = newUsersFuture.getNow(0L);

        // 4. Trả về kết quả tổng hợp
        return new DashboardResponse(totalOrders, weekRevenue, activeProds, newUsers);
    }
}

/* 
================================================================================
BAO CÁO PHÂN TÍCH VÀ GIẢI THÍCH (TÀI LIỆU KÈM THEO)
================================================================================

1. GIẢI THÍCH VỀ THỜI GIAN PHẢN HỒI (800ms vs 200ms)
--------------------------------------------------------------------------------
- Lời gọi đồng bộ tuần tự (Sequential):
  Thread xử lý chính phải chờ lượt từ call 1 -> call 2 -> call 3 -> call 4.
  Tổng thời gian = T1 + T2 + T3 + T4 = 200ms + 200ms + 200ms + 200ms = ~800ms.
  Thread bị block hoàn toàn trong lúc đợi I/O của từng downstream service.

- Lời gọi song song (Parallel với CompletableFuture):
  4 lời gọi I/O được đẩy ra chạy đồng thời trên 4 thread khác nhau (thuộc ThreadPool).
  Thời gian phản hồi tổng = max(T1, T2, T3, T4) ≈ 200ms (cộng thêm vài ms chi phí chuyển context thread).

2. PHÂN TÍCH TRADEOFF: COMPLEATABLEFUTURE PARALLEL vs SEQUENTIAL
--------------------------------------------------------------------------------
a) Ưu điểm của Parallel Execution:
   - Giảm độ trễ (Latency): Thời gian phản hồi giảm từ 800ms xuống ~200ms, đáp ứng yêu cầu < 250ms của Trưởng nhóm.
   - Tăng độ tin cậy (Resilience): Nhờ có `.exceptionally()`, nếu 1 downstream service sập, dashboard vẫn hiển thị phần còn lại thay vì sập toàn bộ (Fail-Soft instead of Fail-Hard).
   - Tối ưu trải nghiệm người dùng (UX): Người dùng không cần đợi lâu khi load màn hình quản trị.

b) Nhược điểm và Thách thức (Tradeoffs):
   - Đô phức tạp của Code (Code Complexity): Chuyển từ lập trình imperative đơn giản sang async/reactive code, cần quản lý exception, timeout phức tạp hơn.
   - Debugging & Tracing khó khăn hơn: 
     + Stack trace bị chia nhỏ qua các thread khác nhau.
     + ThreadLocal context (như Spring Security Context, MDC Log Trace ID) không tự động truyền sang thread mới nếu không cấu hình TaskDecorator/Context Propagator.
   - Tiêu tốn tài nguyên (Resource Consumption):
     + Sử dụng mặc định `ForkJoinPool.commonPool()` hoặc Custom Thread Pool gây ngốn RAM/CPU khi lượng truy cập cao.
     + Nếu không cấu hình thread pool hợp lý, hệ thống dễ rơi vào tình trạng Thread Starvation khi dashboard bị quá tải.
*/