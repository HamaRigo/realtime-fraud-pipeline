package dev.atb.ocr.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atb.dto.OcrDTO;
import dev.atb.models.Client;
import dev.atb.models.Compte;
import dev.atb.models.Ocr;
import dev.atb.ocr.exceptions.OcrProcessingException;
import dev.atb.ocr.notification.LoggerService;
import dev.atb.ocr.notification.NotificationService;
import dev.atb.repo.CompteRepository;
import dev.atb.repo.OcrRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class OcrService {

    private final OcrRepository ocrRepository;
    private final CompteRepository compteRepository;
    private final RestTemplate restTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LoggerService loggerService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final SignatureService signatureService;

    @Value("${ocr.api.url}")
    private String ocrApiUrl;

    @Value("${fraud.api.url}")
    private String fraudApiUrl;

    @Value("${signature.api.url}")
    private String signatureApiUrl;

    // ========================================================================
    // 🔹 ASYNC OCR PROCESSING
    // ========================================================================

    @Async
    public CompletableFuture<OcrDTO> uploadAndAnalyzeAsync(MultipartFile file, String documentType, Long accountId) {
        return CompletableFuture.supplyAsync(() -> uploadAndAnalyze(file, documentType, accountId));
    }

    // ========================================================================
    // 🔹 OCR PROCESSING
    // ========================================================================

    public OcrDTO performOcrAndPreview(MultipartFile file, String documentType) {
        validateInput(file, documentType);
        File tempFile = saveToTemporaryFile(file);
        try {
            String ocrResult = performOcr(tempFile);
            String base64Image = convertToBase64(file);
            return buildOcrDTO(documentType, ocrResult, base64Image, false, 0.0);
        } finally {
            cleanupFile(tempFile);
        }
    }

    public OcrDTO uploadAndAnalyze(MultipartFile file, String documentType, Long accountId) {
        validateInput(file, documentType);
        File tempFile = saveToTemporaryFile(file);
        try {
            String ocrResult = performOcr(tempFile);
            String base64Image = convertToBase64(file);
            Compte account = getAccount(accountId);
            double fraudScore = detectFraudScore(tempFile, accountId);

            OcrDTO result = buildOcrDTO(documentType, ocrResult, base64Image, fraudScore > 0.7, fraudScore);
            persistResult(result, account);
            notifyKafka(result);
            if (result.isFraud()) {
                notificationService.notifyFraudDetected(result);
            }

            return result;
        } finally {
            cleanupFile(tempFile);
        }
    }

    private String performOcr(File file) {
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    ocrApiUrl, createOcrRequest(file), String.class);
            return processOcrResponse(response);
        } catch (Exception e) {
            throw new OcrProcessingException("OCR processing failed", e);
        }
    }

    // ========================================================================
    // 🔹 FRAUD DETECTION
    // ========================================================================

    public double detectFraudScore(File file, Long accountId) {
        Compte account = getAccount(accountId);

        if (account.getClient() == null) {
            loggerService.logError("❌ Fraud detection failed: No client associated with account ID " + accountId);
            return 0.0;
        }

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    fraudApiUrl, createOcrRequest(file), String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode jsonNode = objectMapper.readTree(response.getBody());
                return jsonNode.has("fraud_score") ? jsonNode.get("fraud_score").asDouble() : 0.0;
            } else {
                loggerService.logError("❌ Fraud API returned unexpected status: " + response.getStatusCode());
                return 0.0;
            }
        } catch (Exception e) {
            loggerService.logError("❌ Fraud detection failed: " + e.getMessage());
            return 0.0;
        }
    }

    // ========================================================================
    // 🔹 FILE HANDLING
    // ========================================================================
    private void validateInput(MultipartFile file, String documentType) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("❌ File is required and cannot be empty.");
        }
        if (!List.of("image/png", "image/jpeg", "application/pdf").contains(file.getContentType())) {
            throw new IllegalArgumentException("❌ Invalid file type. Only PNG, JPEG, and PDF are allowed.");
        }
        if (documentType == null || documentType.trim().isEmpty()) {
            throw new IllegalArgumentException("❌ Document type is required and cannot be empty.");
        }
    }
    private File saveToTemporaryFile(MultipartFile file) {
        try {
            Path tempPath = Files.createTempFile("ocr_", "_tmp");
            Files.write(tempPath, file.getBytes());
            File tempFile = tempPath.toFile();
            tempFile.deleteOnExit();
            return tempFile;
        } catch (IOException e) {
            throw new OcrProcessingException("File handling error", e);
        }
    }

    private String convertToBase64(MultipartFile file) {
        try {
            return Base64.getEncoder().encodeToString(file.getBytes());
        } catch (IOException e) {
            throw new OcrProcessingException("Base64 conversion error", e);
        }
    }

    private void cleanupFile(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    // ========================================================================
    // 🔹 DATA PERSISTENCE
    // ========================================================================

    private void persistResult(OcrDTO dto, Compte account) {
        Ocr entity = new Ocr();
        entity.setTypeDocument(dto.getTypeDocument());
        entity.setResultatsReconnaissance(dto.getResultatsReconnaissance());
        entity.setFraud(dto.isFraud());
        entity.setNumeroCompte(account.getNumeroCompte());
        ocrRepository.save(entity);
    }

    private void notifyKafka(OcrDTO dto) {
        try {
            kafkaTemplate.send("ocr-results", objectMapper.writeValueAsString(dto));
        } catch (JsonProcessingException e) {
            loggerService.logError("Kafka message failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // 🔹 UTILITIES
    // ========================================================================

    public OcrDTO getResult(String id) {
        return ocrRepository.findById(id)
                .map(this::convertToDTO)
                .orElseThrow(() -> new IllegalArgumentException("❌ OCR record not found for ID: " + id));
    }

    public List<OcrDTO> getAllResults() {
        return ocrRepository.findAll().stream()
                .map(this::convertToDTO)
                .toList();
    }

    public void deleteResult(String id) {
        if (!ocrRepository.existsById(id)) {
            throw new IllegalArgumentException("❌ OCR record not found for ID: " + id);
        }
        ocrRepository.deleteById(id);
    }
    private Compte getAccount(Long accountId) {
        return compteRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("❌ Account not found for ID: " + accountId));
    }

    private OcrDTO buildOcrDTO(String type, String result, String image, boolean isFraud, double fraudScore) {
        return new OcrDTO(null, type, result, isFraud, image, "default", null, fraudScore);
    }
    private OcrDTO convertToDTO(Ocr entity) {
        return new OcrDTO(
                entity.getId(),
                entity.getTypeDocument(),
                entity.getResultatsReconnaissance(),
                entity.isFraud(),
                entity.getImage(),
                "default",
                null,
                0.0
        );
    }
    private HttpEntity<MultiValueMap<String, Object>> createOcrRequest(File file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));
        return new HttpEntity<>(body, headers);
    }

    private String processOcrResponse(ResponseEntity<String> response) {
        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            return response.getBody();
        }
        throw new OcrProcessingException("OCR API error: " + response.getStatusCode());
    }
}