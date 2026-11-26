package org.lab.gpuqueue.allocation;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "queue.scanner.enabled", havingValue = "true", matchIfMissing = true)
public class AllocationScanner {
    private static final Logger log = LoggerFactory.getLogger(AllocationScanner.class);
    private final AllocationService allocations;

    public AllocationScanner(AllocationService allocations) {
        this.allocations = allocations;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        scanIdleGpus();
    }

    @Scheduled(
            fixedDelayString = "${queue.scanner.fixed-delay-ms:60000}",
            initialDelayString = "${queue.scanner.initial-delay-ms:60000}")
    public void scheduledScan() {
        scanIdleGpus();
    }

    public void scanIdleGpus() {
        List<Integer> candidateGpuIds;
        try {
            candidateGpuIds = allocations.findIdleCandidateGpuIds();
        } catch (RuntimeException exception) {
            log.error("Failed to discover idle GPU candidates for allocation scan", unwrap(exception));
            return;
        }
        for (Integer gpuId : candidateGpuIds) {
            try {
                allocations.allocateIfIdle(gpuId);
            } catch (RuntimeException exception) {
                log.error("Background allocation failed for GPU {}", gpuId, unwrap(exception));
            }
        }
    }

    private static Throwable unwrap(RuntimeException exception) {
        if (exception instanceof UndeclaredThrowableException undeclared
                && undeclared.getUndeclaredThrowable() != null) {
            return undeclared.getUndeclaredThrowable();
        }
        return exception;
    }
}
