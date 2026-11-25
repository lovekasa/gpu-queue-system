package org.lab.gpuqueue.allocation;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.lab.gpuqueue.security.AccountPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RequestController {
    private static final Logger log = LoggerFactory.getLogger(RequestController.class);
    private static final Set<String> INPUT_FIELDS = Set.of("gpuIds", "durationHours");
    private final AllocationService allocations;

    RequestController(AllocationService allocations) {
        this.allocations = allocations;
    }

    @PostMapping(path = "/api/requests", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<?> submit(@AuthenticationPrincipal AccountPrincipal principal,
                             @RequestBody MultiValueMap<String, String> form) {
        for (String field : form.keySet()) {
            if (!INPUT_FIELDS.contains(field)) {
                return ResponseEntity.badRequest().body(new FieldError(field, "此字段不允许提交；申请人由登录身份确定。"));
            }
        }
        if (form.get("durationHours") == null || form.get("durationHours").size() != 1) {
            return ResponseEntity.badRequest().body(new FieldError("durationHours", "请填写一个 1 至 72 的整数小时。"));
        }
        var created = allocations.submitOrdinaryRequest(UUID.fromString(principal.id()),
                form.get("gpuIds"), form.getFirst("durationHours"));
        return ResponseEntity.created(URI.create("/api/requests/" + created.id())).body(created);
    }

    @GetMapping("/api/requests")
    ResponseEntity<?> list(@AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok(allocations.listOwnRequests(UUID.fromString(principal.id())));
    }

    @GetMapping("/api/requests/{id}")
    ResponseEntity<?> detail(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable String id) {
        UUID requestId;
        try {
            requestId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
        return allocations.findOwnRequest(UUID.fromString(principal.id()), requestId)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(AllocationService.InvalidRequest.class)
    ResponseEntity<FieldError> invalidInput(AllocationService.InvalidRequest exception) {
        return ResponseEntity.badRequest().body(new FieldError(exception.field(), exception.getMessage()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> databaseUnavailable(RuntimeException exception, HttpServletRequest request) {
        log.error("Request database operation failed: {} {}", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    record FieldError(String field, String message) {}
}
