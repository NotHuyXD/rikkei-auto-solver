# GIẢI PHÁP XỬ LÝ LỖI CHO KAFKA CONSUMER VỚI RETRY VÀ DEAD LETTER QUEUE (DLQ)

---

## PHẦN 1: PHÂN TÍCH NGUYÊN NHÂN VÀ CƠ CHẾ KAFKA OFFSET

### 1. Cơ chế đọc Offset của Kafka
- **Kafka Offset** là một con số nguyên tăng dần đại diện cho vị trí (định danh) của từng tin nhắn (message) trong một Partition.
- Khi Consumer đọc tin nhắn từ Partition, nó theo dõi vị trí hiện tại thông qua **Current Offset**.
- Khi một tin nhắn được xử lý xong, Consumer sẽ thực hiện **Commit Offset** (lưu lại offset mới nhất đã xử lý thành công lên Kafka broker / topic `__consumer_offsets`). Nhờ đó, nếu Consumer bị crash hoặc restart, nó sẽ biết đọc tiếp từ offset đã commit gần nhất.

### 2. Lý do Consumer bị kẹt khi gặp Exception
- **Mặc định trong Spring Kafka**, khi một phương thức `@KafkaListener` quăng ra Exception (do JSON sai định dạng, null pointer,...):
  1. Offset của tin nhắn lỗi đó **chưa được commit**.
  2. Spring Kafka sẽ thực hiện cơ chế phục hồi mặc định (Seek to Current), tức là rollback vị trí đọc về đúng offset của tin nhắn vừa bị lỗi.
  3. Ở đợt `poll()` tiếp theo, Consumer lại tiếp tục nhận đúng tin nhắn lỗi đó và lại tiếp tục văng Exception.
- **Hậu quả**: Tạo thành một vòng lặp vô tận (Infinite Loop). Consumer bị "kẹt" tại tin nhắn lỗi này, dẫn đến các tin nhắn hợp lệ xếp hàng phía sau không bao giờ được xử lý.

---

## PHẦN 2: MÃ NGUỒN VÀ CẤU HÌNH GIAO DIỆN

### 1. Cấu hình `application.yml`

```yaml
spring:
  application:
    name: inventory-service
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: inventory-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.deserializer.key.delegate.class: org.apache.kafka.common.serialization.StringDeserializer
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.trusted.packages: "*"
        spring.json.value.default.type: com.storex.inventory.dto.OrderEvent
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

logging:
  level:
    com.storex.inventory: DEBUG
    org.springframework.kafka: INFO
```

---

### 2. File cấu hình Kafka Error Handler: `KafkaConfig.java`

```java
package com.storex.inventory.config;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        // Cấu hình DeadLetterPublishingRecoverer để đẩy message lỗi vào DLQ topic
        // Mặc định sẽ đẩy vào topic: <topic_gốc>.DLT (ví dụ: order-events.DLT)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> {
                log.error("Chuyển message sang DLQ. Topic: {}, Offset: {}, Lỗi: {}", 
                        record.topic(), record.offset(), ex.getMessage());
                // Gửi sang topic DLQ tùy chỉnh "order-events.DLQ"
                return new TopicPartition("order-events.DLQ", record.partition());
            }
        );

        // Cấu hình Retry 3 lần, khoảng thời gian giữa các lần thử lại là 1000ms (1 giây)
        // FixedBackOff(interval, maxAttempts): maxAttempts = 3 nghĩa là 1 lần chạy ban đầu + 2 lần retry
        FixedBackOff backOff = new FixedBackOff(1000L, 3L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // In log mỗi lần retry thất bại
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> {
            log.warn("Thử lại lần {} cho message tại offset {} thất bại. Lỗi: {}", 
                    deliveryAttempt, record.offset(), ex.getMessage());
        });

        return errorHandler;
    }
}
```

---

### 3. File Consumer xử lý sự kiện: `InventoryConsumer.java`

```java
package com.storex.inventory.consumer;

import com.storex.inventory.dto.OrderEvent;
import com.storex.inventory.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
public class InventoryConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryConsumer.class);

    @Autowired
    private InventoryService inventoryService;

    // Consumer lắng nghe tin nhắn chính từ topic order-events
    @KafkaListener(topics = "order-events", groupId = "inventory-group")
    public void consume(OrderEvent event) {
        log.info("Nhận sự kiện trừ kho cho sản phẩm: {}, Số lượng: {}", 
                event.getProductId(), event.getQuantity());
        
        // Thực hiện trừ kho
        inventoryService.deductStock(event.getProductId(), event.getQuantity());
        
        log.info("Xử lý trừ kho thành công cho sản phẩm: {}", event.getProductId());
    }

    // Consumer lắng nghe tin nhắn lỗi từ Dead Letter Queue (DLQ) để ghi log / lưu DB xử lý sau
    @KafkaListener(topics = "order-events.DLQ", groupId = "inventory-dlq-group")
    public void consumeDlq(Object failedEvent,
                           @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                           @Header(KafkaHeaders.OFFSET) long offset) {
        log.error("[DLQ ALERT] Nhận message lỗi từ Topic: {}, Offset: {}. Nội dung: {}", 
                topic, offset, failedEvent);
        // Có thể lưu vào Database tin nhắn hỏng để Admin kiểm tra và khắc phục thủ công
    }
}
```