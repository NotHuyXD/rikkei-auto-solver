package com.storex.notification.consumer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class OrderNotificationConsumer {

    private final WebClient preferenceWebClient;
    private final WebClient notificationWebClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Lũy đẳng (Idempotency): Lưu danh sách orderId đã xử lý thành công trong RAM
    private final Set<String> processedOrderIds = ConcurrentHashMap.newKeySet();

    public OrderNotificationConsumer(WebClient.Builder webClientBuilder,
                                   KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.preferenceWebClient = webClientBuilder.baseUrl("http://localhost:8081").build();
        this.notificationWebClient = webClientBuilder.baseUrl("http://localhost:8082").build();
    }

    @KafkaListener(topics = "storex-order-events", groupId = "notification-group")
    public void consumeOrderEvent(OrderEvent event) {
        String orderId = event.getOrderId();

        // BUG-06: Kiểm tra tính lũy đẳng. Nếu orderId đã xử lý trước đó -> Bỏ qua
        if (processedOrderIds.contains(orderId)) {
            log.info("Order {} đã được xử lý thành công trước đó. Bỏ qua message trùng lặp.", orderId);
            return;
        }

        // REQ-01: Sử dụng luồng Non-blocking với flatMap và subscribe (CẤM sử dụng .block())
        fetchUserPreference(event.getUserId())
                .flatMap(channel -> sendNotification(channel, event))
                .doOnSuccess(unused -> {
                    // Đánh dấu orderId đã xử lý thành công
                    processedOrderIds.add(orderId);
                    log.info("Xử lý và gửi thông báo thành công cho order: {}", orderId);
                })
                .onErrorResume(ex -> {
                    // BUG-07: Xử lý thất bại cuối cùng sau retry -> Đẩy vào DLQ
                    log.error("Đã đẩy order {} vào DLQ do lỗi gửi thông báo", orderId);
                    kafkaTemplate.send("storex-order-events.DLQ", orderId, event);
                    return Mono.empty();
                })
                .subscribe(); // Thực thi reactive stream bất đồng bộ
    }

    /**
     * Lấy kệnh ưu tiên của User (EMAIL hoặc ZALO)
     */
    private Mono<String> fetchUserPreference(String userId) {
        return preferenceWebClient.get()
                .uri("/api/preferences/{userId}", userId)
                .retrieve()
                .bodyToMono(PreferenceResponse.class)
                .map(PreferenceResponse::getPreferredChannel)
                .timeout(Duration.ofSeconds(3)) // Timeout 3s
                .retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(1)) // Retry tối đa 2 lần, mỗi lần cách 1s
                        .filter(this::isRetryableError))
                .onErrorResume(ex -> {
                    // BUG-05: Fallback về mặc định EMAIL khi API Preference lỗi/timeout/dead
                    log.warn("Lỗi khi lấy preference cho user {}: {}. Chuyển sang Fallback kênh EMAIL.", userId, ex.getMessage());
                    return Mono.just("EMAIL");
                });
    }

    /**
     * Gửi thông báo đến Email API hoặc Zalo API dựa trên kênh ưu tiên
     */
    private Mono<Void> sendNotification(String channel, OrderEvent event) {
        String endpoint = "ZALO".equalsIgnoreCase(channel) 
                ? "/api/notify/zalo" 
                : "/api/notify/email";

        return notificationWebClient.post()
                .uri(endpoint)
                .bodyValue(event)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(3)) // Timeout 3s
                .retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(1)) // Retry tối đa 2 lần, mỗi lần cách 1s
                        .filter(this::isRetryableError));
    }

    /**
     * Điều kiện Retry: Chỉ retry khi gặp lỗi 5xx Server Error hoặc Timeout
     */
    private boolean isRetryableError(Throwable throwable) {
        if (throwable instanceof TimeoutException) {
            return true;
        }
        if (throwable instanceof WebClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError();
        }
        return false;
    }

    // --- DTO Classes ---
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderEvent {
        private String orderId;
        private String userId;
        private Double totalAmount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreferenceResponse {
        private String userId;
        private String preferredChannel; // "EMAIL" hoặc "ZALO"
    }
}