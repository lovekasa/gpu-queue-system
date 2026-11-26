package org.lab.gpuqueue.allocation;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.lab.gpuqueue.allocation.AllocationRepository.AllocationView;
import org.lab.gpuqueue.allocation.AllocationRepository.InboxMessageView;
import org.lab.gpuqueue.allocation.AllocationRepository.PublicGpuQueueView;
import org.lab.gpuqueue.allocation.AllocationService.MarkMessageReadResult;
import org.lab.gpuqueue.allocation.AllocationService.ReturnAllocationResult;
import org.lab.gpuqueue.allocation.AllocationService.StartAllocationResult;
import org.lab.gpuqueue.security.AccountPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AllocationController {
    private static final Logger log = LoggerFactory.getLogger(AllocationController.class);
    private final AllocationService allocations;

    AllocationController(AllocationService allocations) {
        this.allocations = allocations;
    }

    @GetMapping("/api/queues")
    ResponseEntity<List<PublicGpuQueueView>> listPublicQueues() {
        return ResponseEntity.ok(allocations.listPublicQueues());
    }

    @PostMapping("/api/messages/{id}/read")
    ResponseEntity<MarkMessageReadResult> markMessageRead(@AuthenticationPrincipal AccountPrincipal principal,
                                                          @PathVariable String id) {
        UUID messageId;
        try {
            messageId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
        return allocations.markMessageRead(UUID.fromString(principal.id()), messageId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/allocations")
    ResponseEntity<List<AllocationView>> listAllocations(@AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok(allocations.listOwnAllocations(UUID.fromString(principal.id())));
    }

    @GetMapping("/api/allocations/{id}")
    ResponseEntity<AllocationView> allocationDetail(@AuthenticationPrincipal AccountPrincipal principal,
                                                    @PathVariable String id) {
        UUID allocationId;
        try {
            allocationId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
        return allocations.findOwnAllocation(UUID.fromString(principal.id()), allocationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/messages")
    ResponseEntity<List<InboxMessageView>> listMessages(@AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok(allocations.listOwnMessages(UUID.fromString(principal.id())));
    }

    @GetMapping("/api/messages/{id}")
    ResponseEntity<InboxMessageView> messageDetail(@AuthenticationPrincipal AccountPrincipal principal,
                                                   @PathVariable String id) {
        UUID messageId;
        try {
            messageId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
        return allocations.findOwnMessage(UUID.fromString(principal.id()), messageId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/allocations/{id}/start")
    ResponseEntity<StartAllocationResult> confirmStart(@AuthenticationPrincipal AccountPrincipal principal,
                                                       @PathVariable String id) {
        UUID allocationId;
        try {
            allocationId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
        return allocations.confirmStart(UUID.fromString(principal.id()), allocationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/allocations/{id}/return")
    ResponseEntity<ReturnAllocationResult> confirmReturn(@AuthenticationPrincipal AccountPrincipal principal,
                                                         @PathVariable String id) {
        UUID allocationId;
        try {
            allocationId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
        return allocations.confirmReturn(UUID.fromString(principal.id()), allocationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(AllocationService.AllocationEnded.class)
    ResponseEntity<ConflictError> allocationEnded(AllocationService.AllocationEnded exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ConflictError("ALLOCATION_ENDED", exception.getMessage()));
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
        log.error("Allocation database operation failed: {} {}", request.getMethod(), request.getRequestURI(), cause);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    record ConflictError(String code, String message) {}
}
