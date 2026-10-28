package dev.atb.ocr.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin, injectable wrapper around SLF4J so pipeline components can log
 * consistently without each holding their own {@link Logger} instance.
 */
@Service
public class LoggerService {

    private static final Logger log = LoggerFactory.getLogger(LoggerService.class);

    public void logInfo(String message) {
        log.info(message);
    }

    public void logWarn(String message) {
        log.warn(message);
    }

    public void logError(String message) {
        log.error(message);
    }

    public void logError(String message, Throwable cause) {
        log.error(message, cause);
    }

    public void logDebug(String message) {
        log.debug(message);
    }
}
