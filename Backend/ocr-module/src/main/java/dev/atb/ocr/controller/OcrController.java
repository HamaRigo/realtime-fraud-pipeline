package dev.atb.ocr.controller;

import dev.atb.dto.OcrDTO;
import dev.atb.ocr.Service.OcrService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/ocrs")
@Validated
@Configuration
public class OcrController {

    private static final Logger logger = LoggerFactory.getLogger(OcrController.class);
    private final OcrService ocrService;

    public OcrController(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    private File convertMultipartFileToFile(MultipartFile file) throws IOException {
        File tempFile = File.createTempFile("fraud_check_", "_tmp");
        file.transferTo(tempFile);
        tempFile.deleteOnExit();
        return tempFile;
    }

    /**
     * ✅ Uploads an image, performs OCR & Fraud Detection, then saves the result.
     */
    @PostMapping("/upload")
    public CompletableFuture<ResponseEntity<OcrDTO>> uploadImageAsync(
            @RequestPart("file") @Valid @NotNull MultipartFile file,
            @RequestParam("typeDocument") @Valid @NotEmpty String typeDocument,
            @RequestParam("id") @Valid @NotNull Long id) {

        logger.info("📤 Received file: {}", file.getOriginalFilename());
        return ocrService.uploadAndAnalyzeAsync(file, typeDocument, id)
                .thenApply(ResponseEntity::ok)
                .exceptionally(e -> ResponseEntity.internalServerError().body(
                        new OcrDTO(null, typeDocument, null, false, null, "default",
                                "❌ OCR processing failed: " + e.getMessage(), 0.0)));
    }

    /**
     * ✅ Perform OCR and return a preview (Image + OCR text).
     */
    @PostMapping("/perform-and-preview")
    public ResponseEntity<OcrDTO> performOcrAndPreview(
            @RequestPart("file") MultipartFile file,
            @RequestParam("typeDocument") String typeDocument) {

        logger.info("📑 Performing OCR & Preview on file: file", file.getOriginalFilename());
        logger.info("📑 Performing OCR & Preview on file: typeDocument", typeDocument);

        return null;
        /*try {
            OcrDTO ocrDTO = ocrService.performOcrAndPreview(file, typeDocument);
            return ResponseEntity.ok(ocrDTO);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    new OcrDTO(null, typeDocument, null, false, null, "default",
                            "❌ OCR & Preview processing failed: " + e.getMessage(), 0.0));
        }*/
    }

    /**
     * ✅ Perform fraud detection on an uploaded file.
     */
    @PostMapping("/fraud-detection")
    public ResponseEntity<String> detectFraud(
            @RequestPart("file") @Valid @NotNull MultipartFile file,
            @RequestParam("id") @Valid @NotNull Long id) {

        logger.info("🛡️ Performing fraud detection on file: {}", file.getOriginalFilename());

        try {
            // ✅ Convert MultipartFile to File before passing to the service
            File tempFile = convertMultipartFileToFile(file);

            double fraudScore = ocrService.detectFraudScore(tempFile, id);
            // Cleanup temp file
            tempFile.delete();

            return ResponseEntity.ok(fraudScore > 0.7 ? "⚠️ Fraud Detected! (Score: " + fraudScore + ")" :
                    "✅ No Fraud Detected. (Score: " + fraudScore + ")");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("❌ Fraud detection failed: " + e.getMessage());
        }
    }
        /**
         * ✅ Retrieves a single OCR record by ID.
         */
        @GetMapping("/{id}")
        public ResponseEntity<OcrDTO> getOcrById (@PathVariable String id){
            return ResponseEntity.ok(ocrService.getResult(id));
        }

        /**
         * ✅ Retrieves all OCR records.
         */
        @GetMapping
        public ResponseEntity<List<OcrDTO>> getAllOcrEntities () {
            return ResponseEntity.ok(ocrService.getAllResults());
        }

        /**
         * ✅ Deletes a specific OCR record by ID.
         */
        @DeleteMapping("/{id}")
        public ResponseEntity<Void> deleteOcr (@PathVariable String id){
            try {
                ocrService.deleteResult(id);
                return ResponseEntity.noContent().build();
            } catch (Exception e) {
                return ResponseEntity.notFound().build();
            }
        }

        /**
         * ✅ Handles exceptions with a standardized error message.
         */
        private ResponseEntity<String> handleException (String message, Throwable e){
            logger.error(message, e);
            return ResponseEntity.internalServerError().body(message + ": " + e.getMessage());
        }

        /**
         * ❗ Handles file upload errors (e.g., incorrect file type).
         */
        @ExceptionHandler(MultipartException.class)
        public ResponseEntity<String> handleMultipartException (MultipartException ex){
            return ResponseEntity.badRequest().body("❌ Invalid file type (only images & PDFs allowed): " + ex.getMessage());
        }
    }
