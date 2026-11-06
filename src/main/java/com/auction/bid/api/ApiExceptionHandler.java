package com.auction.bid.api;

import com.auction.bid.pipeline.BidPublishException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BidPublishException.class)
    public ResponseEntity<Map<String, String>> pipelineIndisponivel(BidPublishException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "pipeline_indisponivel"));
    }
}
