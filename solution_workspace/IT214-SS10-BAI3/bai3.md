# BÁO CÁO VÀ MÃ NGUỒN BÀI TẬP 3: CHUYỂN ĐỔI RESTTEMPLATE SANG WEBCLIENT TRONG SPRING WEBFLUX

---

## PHẦN 1: PHÂN TÍCH LỖI VÀ BÁO CÁO GIẢI THÍCH

### 1. Cơ chế hoạt động của RestTemplate và lý do gây cạn kiệt Thread
- **Cơ chế Synchronous & Blocking:** `RestTemplate` được thiết kế theo mô hình **Thread-per-request** (I/O đồng bộ). Mỗi khi một HTTP request được gửi đi bằng `RestTemplate`, Thread đang xử lý request đó sẽ bị **khóa (block)** và rơi vào trạng thái chờ cho đến khi dịch vụ phản hồi nhận được dữ liệu hoàn toàn.
- **Hậu quả cạn kiệt Thread:** 
  - Trong ứng dụng Spring WebFlux (chạy trên WebServer bất đồng bộ như Netty), số lượng **Event Loop Threads** rất hạn chế (thường bằng số nhân CPU, ví dụ: 8 hoặc 16 threads) để xử lý tối ưu hàng ngàn kết nối cùng lúc.
  - Khi dùng `RestTemplate`, các Event Loop Thread này bị chiếm giữ và block liên tục khi chờ `promotion-service` phản hồi.
  - Khi có lượng lớn request đồng thời (như 10.000 truy cập Flash Sale), toàn bộ Event Loop Threads nhanh chóng bị cạn kiệt, hệ thống không còn thread nào để nhận request mới -> dẫn đến nghẽn mạng, treo hệ thống và trả về **HTTP 500 Internal Server Error**.

### 2. Tại sao WebFlux không thể xử lý Non-blocking khi dùng RestTemplate?
- **Kiến trúc Reactive bị phá vỡ:** WebFlux yêu cầu toàn bộ luồng xử lý từ Ingress (đầu vào) đến Egress (đầu ra - gọi DB, API bên ngoài) phải tuân theo chuẩn Reactive (Non-blocking I/O).
- **Điểm nghẽn đồng bộ (Blocking Bottleneck):** Dù ứng dụng sử dụng Spring WebFlux, nhưng việc gọi `restTemplate.getForObject(...)` đã vô hiệu hóa hoàn toàn cơ chế Non-blocking Event Loop của Netty. Một thao tác blocking duy nhất ở giữa luồng sẽ làm nghẽn toàn bộ chuỗi reactive.

---

## PHẦN 2: MÃ NGUỒN CHUẨN REAGTIVE

### 1. File cấu hình WebClient (`WebClientConfig.java`)

```java
package com.storex.promotionservice.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        // Cấu hình HttpClient với Timeout 2 giây ở cấp độ kết nối mạng
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(Duration.ofSeconds(2))
                .doOnConnected(conn -> 
                    conn.addHandlerLast(new ReadTimeoutHandler(2, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(2, TimeUnit.SECONDS)));

        return builder
                .baseUrl("http://promotion-service")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
```

### 2. Service xử lý đã tái cấu trúc (`PromotionService.java`)

```java
package com.storex.promotionservice.service;

import com.storex.promotionservice.model.Banner;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
public class PromotionService {

    private final WebClient webClient;

    public PromotionService(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Lấy banner khuyến mãi hiện hành bất đồng bộ (Non-blocking).
     *
     * @return Mono<Banner> chứa thông tin Banner hoặc Banner mặc định nếu lỗi/timeout
     */
    public Mono<Banner> getActiveBanner() {
        String url = "/api/banners/active";

        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(Banner.class)
                .timeout(Duration.ofSeconds(2)) // Xử lý Timeout 2 giây ở cấp độ Reactive Stream
                .onErrorResume(throwable -> {
                    // Fallback xử lý khi promotion-service bị timeout, gián đoạn hoặc gặp lỗi
                    Banner defaultBanner = new Banner();
                    defaultBanner.setTitle("Mặc định");
                    defaultBanner.setMessage("Khuyến mãi đang được cập nhật");
                    
                    return Mono.just(defaultBanner);
                });
    }
}
```