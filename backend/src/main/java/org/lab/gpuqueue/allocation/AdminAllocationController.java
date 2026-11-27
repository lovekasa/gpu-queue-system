package org.lab.gpuqueue.allocation;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.lab.gpuqueue.allocation.AllocationRepository.PendingVerificationView;
import org.lab.gpuqueue.allocation.AllocationService.EmergencyReviewResult;
import org.lab.gpuqueue.allocation.AllocationService.VerifiedReleaseResult;
import org.lab.gpuqueue.allocation.RequestRepository.AdminEmergencyRequestView;
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
class AdminAllocationController {
    private static final Logger log = LoggerFactory.getLogger(AdminAllocationController.class);
    private final AllocationService allocations;

    AdminAllocationController(AllocationService allocations) {
        this.allocations = allocations;
    }

    @GetMapping("/api/admin/allocations/pending-verification")
    ResponseEntity<List<PendingVerificationView>> listPendingVerification() {
        return ResponseEntity.ok(allocations.listPendingVerificationAllocations());
    }

    @PostMapping(path = "/api/admin/allocations/{id}/verified-release",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<VerifiedReleaseResult> verifiedRelease(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String id,
            @RequestBody MultiValueMap<String, String> form) {
        UUID allocationId;
        try {
            allocationId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
        String seenStatus = form.getFirst("seenStatus");
        boolean verified = "true".equalsIgnoreCase(form.getFirst("verified"));
        String reason = form.getFirst("reason");
        return allocations.adminVerifiedRelease(
                        UUID.fromString(principal.id()), allocationId, seenStatus, verified, reason)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/admin/requests/emergency-pending")
    ResponseEntity<List<AdminEmergencyRequestView>> listPendingEmergencyRequests() {
        return ResponseEntity.ok(allocations.listPendingEmergencyRequests());
    }

    @PostMapping(path = "/api/admin/requests/{id}/emergency-review",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<EmergencyReviewResult> reviewEmergencyRequest(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String id,
            @RequestBody MultiValueMap<String, String> form) {
        UUID requestId;
        try {
            requestId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
        String decision = form.getFirst("decision");
        String reason = form.getFirst("reason");
        return allocations.reviewEmergencyRequest(
                        UUID.fromString(principal.id()), requestId, decision, reason)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(AllocationService.InvalidRequest.class)
    ResponseEntity<FieldError> invalidInput(AllocationService.InvalidRequest exception) {
        return ResponseEntity.badRequest().body(new FieldError(exception.field(), exception.getMessage()));
    }

    @ExceptionHandler(AllocationService.StatusConflict.class)
    ResponseEntity<ConflictError> statusConflict(AllocationService.StatusConflict exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ConflictError("STATUS_CHANGED", exception.getMessage()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class, UndeclaredThrowableException.class})
    ResponseEntity<Void> databaseUnavailable(RuntimeException exception, HttpServletRequest request) {
        Throwable cause = exception;
        if (exception instanceof UndeclaredThrowableException undeclared
                && undeclared.getUndeclaredThrowable() != null) {
            cause = undeclared.getUndeclaredThrowable();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
        }
        log.error("Admin allocation database operation failed: {} {}", request.getMethod(), request.getRequestURI(), cause);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    record FieldError(String field, String message) {}

    record ConflictError(String code, String message) {}
}
