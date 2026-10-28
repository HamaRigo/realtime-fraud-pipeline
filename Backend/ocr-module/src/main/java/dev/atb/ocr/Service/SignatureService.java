package dev.atb.ocr.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atb.ocr.notification.LoggerService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;

/**
 * Delegates signature verification to the Python signature-matching
 * micro-service (OpenCV ORB keypoints + homography alignment) and returns a
 * 0–1 similarity score. Like the fraud scorer, any downstream outage degrades
 * gracefully to {@code 0.0} rather than failing the whole pipeline.
 */
@Service
@RequiredArgsConstructor
public class SignatureService {

    private static final double DEFAULT_MATCH_THRESHOLD = 0.5;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LoggerService loggerService;

    @Value("${signature.api.url}")
    private String signatureApiUrl;

    /**
     * Compares the signature extracted from {@code document} against the
     * client's {@code referenceSignature}.
     *
     * @return similarity score in the range 0.0 (no match) – 1.0 (identical)
     */
    public double verifySignature(File document, File referenceSignature) {
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    signatureApiUrl + "/verify", buildRequest(document, referenceSignature), String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                if (json.has("similarity")) {
                    return json.get("similarity").asDouble();
                }
            }
            loggerService.logWarn("⚠️ Signature service returned no similarity score");
            return 0.0;
        } catch (Exception e) {
            loggerService.logError("❌ Signature verification failed: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Convenience boolean check using the default match threshold.
     */
    public boolean isSignatureValid(File document, File referenceSignature) {
        return verifySignature(document, referenceSignature) >= DEFAULT_MATCH_THRESHOLD;
    }

    private HttpEntity<MultiValueMap<String, Object>> buildRequest(File document, File referenceSignature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("document", new FileSystemResource(document));
        body.add("reference", new FileSystemResource(referenceSignature));
        return new HttpEntity<>(body, headers);
    }
}
