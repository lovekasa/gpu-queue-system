package org.lab.gpuqueue.allocation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.lab.gpuqueue.allocation.AllocationRepository.AllocationView;
import org.lab.gpuqueue.allocation.AllocationRepository.InboxMessageView;
import org.lab.gpuqueue.allocation.RequestRepository.RequestView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AllocationService {
    private final RequestRepository requests;
    private final AllocationRepository allocations;
    private final QueueOrderingPolicy orderingPolicy;
    private final Clock clock;

    public AllocationService(RequestRepository requests,
                             AllocationRepository allocations,
                             QueueOrderingPolicy orderingPolicy,
                             Clock clock) {
        this.requests = requests;
        this.allocations = allocations;
        this.orderingPolicy = orderingPolicy;
        this.clock = clock;
    }

    // All queue and quota writes enter here; returning from the proxy commits or rolls back atomically.
    @Transactional(rollbackFor = Exception.class)
    public RequestView submitOrdinaryRequest(UUID memberId, List<String> rawGpuIds, String rawDurationHours) {
        int durationHours = parseInteger(rawDurationHours, "durationHours", "预计时长须为 1 至 72 的整数小时。");
        if (durationHours < 1 || durationHours > 72) {
            throw new InvalidRequest("durationHours", "预计时长须为 1 至 72 的整数小时。");
        }
        if (rawGpuIds == null || rawGpuIds.isEmpty()) {
            throw new InvalidRequest("gpuIds", "请至少选择一张 GPU。");
        }
        var gpuIds = new ArrayList<Integer>();
        var seen = new HashSet<Integer>();
        for (String rawGpuId : rawGpuIds) {
            int gpuId = parseInteger(rawGpuId, "gpuIds", "请选择有效的 GPU。");
            if (!seen.add(gpuId)) {
                throw new InvalidRequest("gpuIds", "同一次申请中不能重复选择同一张 GPU。");
            }
            gpuIds.add(gpuId);
        }
        gpuIds.sort(Integer::compareTo);
        for (Integer gpuId : gpuIds) {
            if (!allocations.lockGpu(gpuId)) {
                throw new InvalidRequest("gpuIds", "所选 GPU 不存在，请刷新后重新选择。");
            }
        }
        Instant now = clock.instant();
        UUID requestId = requests.create(memberId, now, durationHours, gpuIds);
        for (Integer gpuId : gpuIds) {
            allocateGpuIfIdle(gpuId, now);
        }
        return requests.findForMember(memberId, requestId)
                .orElseThrow(() -> new DataIntegrityViolationException("Missing persisted request " + requestId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Optional<StartAllocationResult> confirmStart(UUID memberId, UUID allocationId) {
        var initial = allocations.findOwnedAllocationState(memberId, allocationId);
        if (initial.isEmpty()) {
            return Optional.empty();
        }
        allocations.lockGpu(initial.get().gpuId());
        var state = allocations.findOwnedAllocationState(memberId, allocationId).orElseThrow();
        if (!state.current()) {
            throw new AllocationEnded("该名额已结束或已失效，未修改当前名额。");
        }
        if (state.startedAt() != null) {
            return Optional.of(new StartAllocationResult(state.toView(), true));
        }
        Instant now = clock.instant();
        if (!allocations.markAllocationStarted(allocationId, now)) {
            var latest = allocations.findOwnedAllocationState(memberId, allocationId).orElseThrow();
            return Optional.of(new StartAllocationResult(latest.toView(), true));
        }
        allocations.insertStartAuditLog(
                now,
                memberId,
                state.requestId(),
                state.queueItemId(),
                allocationId,
                state.gpuId(),
                "allocationStatus=UNSTARTED",
                "allocationStatus=IN_USE;startedAt=" + now);
        var updated = allocations.findAllocationForMember(memberId, allocationId)
                .orElseThrow(() -> new DataIntegrityViolationException("Missing started allocation " + allocationId));
        return Optional.of(new StartAllocationResult(updated, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public Optional<ReturnAllocationResult> confirmReturn(UUID memberId, UUID allocationId) {
        var initial = allocations.findOwnedAllocationState(memberId, allocationId);
        if (initial.isEmpty()) {
            return Optional.empty();
        }
        allocations.lockGpu(initial.get().gpuId());
        var state = allocations.findOwnedAllocationState(memberId, allocationId).orElseThrow();
        if (!state.current() || state.endedAt() != null) {
            return Optional.of(new ReturnAllocationResult(state.toView(), true));
        }
        Instant now = clock.instant();
        String beforeStatus = state.startedAt() != null ? "IN_USE" : "UNSTARTED";
        allocations.markAllocationEnded(allocationId, now, "RETURNED");
        allocations.deleteCurrentAllocation(state.gpuId(), allocationId);
        allocations.insertReturnAuditLog(
                now,
                memberId,
                state.requestId(),
                state.queueItemId(),
                allocationId,
                state.gpuId(),
                "allocationStatus=" + beforeStatus,
                "allocationStatus=ENDED;endReason=RETURNED;endedAt=" + now);
        allocateGpuIfIdle(state.gpuId(), now);
        var endedState = allocations.findOwnedAllocationState(memberId, allocationId)
                .orElseThrow(() -> new DataIntegrityViolationException("Missing ended allocation " + allocationId));
        return Optional.of(new ReturnAllocationResult(endedState.toView(), false));
    }

    @Transactional(rollbackFor = Exception.class)
    public Optional<AllocationView> allocateIfIdle(int gpuId) {
        return allocateGpuIfIdle(gpuId, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<Integer> findIdleCandidateGpuIds() {
        return allocations.findIdleCandidateGpuIds();
    }

    @Transactional(readOnly = true)
    public List<RequestView> listOwnRequests(UUID memberId) {
        return requests.listForMember(memberId);
    }

    @Transactional(readOnly = true)
    public Optional<RequestView> findOwnRequest(UUID memberId, UUID requestId) {
        return requests.findForMember(memberId, requestId);
    }

    @Transactional(readOnly = true)
    public List<AllocationView> listOwnAllocations(UUID memberId) {
        return allocations.listAllocationsForMember(memberId);
    }

    @Transactional(readOnly = true)
    public Optional<AllocationView> findOwnAllocation(UUID memberId, UUID allocationId) {
        return allocations.findAllocationForMember(memberId, allocationId);
    }

    @Transactional(readOnly = true)
    public List<InboxMessageView> listOwnMessages(UUID memberId) {
        return allocations.listMessagesForMember(memberId);
    }

    @Transactional(readOnly = true)
    public Optional<InboxMessageView> findOwnMessage(UUID memberId, UUID messageId) {
        return allocations.findMessageForMember(memberId, messageId);
    }

    private Optional<AllocationView> allocateGpuIfIdle(int gpuId, Instant evaluationTime) {
        if (!allocations.lockGpu(gpuId) || allocations.hasCurrentAllocation(gpuId)) {
            return Optional.empty();
        }
        var candidates = allocations.findWaitingCandidates(gpuId);
        var winnerOptional = orderingPolicy.selectNext(candidates, evaluationTime);
        if (winnerOptional.isEmpty()) {
            return Optional.empty();
        }
        var winner = winnerOptional.get();
        UUID allocationId = UUID.randomUUID();
        Instant expiresAt = evaluationTime.plus(Duration.ofHours(winner.durationHours()));
        allocations.markQueueItemAllocated(winner.queueItemId(), gpuId);
        allocations.insertAllocation(allocationId, winner.queueItemId(), gpuId, evaluationTime, expiresAt);
        allocations.insertCurrentAllocation(gpuId, allocationId);
        String beforeState = "queueItemStatus=WAITING;currentAllocation=NONE";
        String afterState = "queueItemStatus=ALLOCATED;allocationId=" + allocationId
                + ";allocationStatus=UNSTARTED;expiresAt=" + expiresAt;
        long transitionSeq = allocations.insertAuditLog(
                evaluationTime,
                winner.requestId(),
                winner.queueItemId(),
                allocationId,
                gpuId,
                beforeState,
                afterState);
        String title = "GPU " + gpuId + " 使用名额已获配";
        String body = "申请 " + winner.requestId() + " 已获配 GPU " + gpuId
                + " 使用名额（未开始）。名额仅记录使用权，不代表硬件实际空闲或训练任务已启动；使用前请先按约定确认开始。";
        allocations.insertInboxMessage(
                UUID.randomUUID(),
                winner.memberId(),
                transitionSeq,
                allocationId,
                winner.requestId(),
                gpuId,
                title,
                body,
                evaluationTime);
        return Optional.of(allocations.findAllocationForMember(winner.memberId(), allocationId)
                .orElseThrow(() -> new DataIntegrityViolationException("Missing persisted allocation " + allocationId)));
    }

    private int parseInteger(String value, String field, String message) {
        try {
            if (value == null || !value.strip().matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            throw new InvalidRequest(field, message);
        }
    }

    public record StartAllocationResult(AllocationView allocation, boolean alreadyStarted) {}

    public record ReturnAllocationResult(AllocationView allocation, boolean alreadyEnded) {}

    static class AllocationEnded extends RuntimeException {
        AllocationEnded(String message) {
            super(message);
        }
    }

    static class InvalidRequest extends RuntimeException {
        private final String field;

        InvalidRequest(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }
}
