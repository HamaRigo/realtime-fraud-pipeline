package dev.atb.ocr.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atb.dto.OcrDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Pushes real-time fraud alerts onto a dedicated Kafka topic so downstream
 * consumers (operator alerting, audit, case management) can react without the
 * detection path knowing who is listening.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String FRAUD_ALERT_TOPIC = "fraud-alerts";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final LoggerService loggerService;

    /**
     * Publishes a fraud alert for a document flagged by the pipeline.
     * Failures are logged and swallowed so a notification problem can never
     * abort an otherwise successful analysis.
     */
    public void notifyFraudDetected(OcrDTO dto) {
        if (dto == null) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(dto);
            kafkaTemplate.send(FRAUD_ALERT_TOPIC, payload);
            loggerService.logInfo("🚨 Fraud alert dispatched to topic '" + FRAUD_ALERT_TOPIC
                    + "' (score: " + dto.getConfidenceScore() + ")");
        } catch (JsonProcessingException e) {
            loggerService.logError("Failed to serialize fraud alert: " + e.getMessage(), e);
        }
    }
}
