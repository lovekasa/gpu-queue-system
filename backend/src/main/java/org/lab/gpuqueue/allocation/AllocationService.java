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
import org.lab.gpuqueue.allocation.AllocationRepository.PendingVerificationView;
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
        var updated = allocations.findAllocationForMember(memberId, allocationId, now)
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
    public List<PendingVerificationView> listPendingVerificationAllocations() {
        return allocations.listPendingVerificationAllocations(clock.instant());
    }

    @Transactional(rollbackFor = Exception.class)
    public Optional<VerifiedReleaseResult> adminVerifiedRelease(
            UUID adminId, UUID allocationId, String seenStatus, boolean verified, String rawReason) {
        if (!"ANOMALY".equals(seenStatus) && !"OVERDUE".equals(seenStatus)) {
            throw new InvalidRequest("seenStatus", "所见状态须为 ANOMALY 或 OVERDUE。");
        }
        if (!verified) {
            throw new InvalidRequest("verified", "请先勾选确认已完成核实。");
        }
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new InvalidRequest("reason", "请填写 1 至 500 字的核实释放理由。");
        }
        var initial = allocations.findAllocationLifecycleState(allocationId);
        if (initial.isEmpty()) {
            return Optional.empty();
        }
        allocations.lockGpu(initial.get().gpuId());
        var state = allocations.findAllocationLifecycleState(allocationId).orElseThrow();
        if (!state.current() || state.endedAt() != null) {
            return Optional.of(new VerifiedReleaseResult(allocationId.toString(), Integer.toString(state.gpuId()),
                    state.endedAt(), state.endReason(), true));
        }
        Instant now = clock.instant();
        String actualStatus = AllocationRepository.deriveActiveStatus(
                state.startedAt(), state.notStartedAnomalyAt(), state.expiresAt(), now);
        if (!actualStatus.equals(seenStatus)) {
            throw new StatusConflict("目标名额当前状态已变更为 " + actualStatus + "，请刷新列表后重新核实。");
        }
        allocations.markAllocationEnded(allocationId, now, "VERIFIED_RELEASED");
        allocations.deleteCurrentAllocation(state.gpuId(), allocationId);
        long seq = allocations.insertAdminAuditLog(
                now,
                adminId,
                "VERIFIED_RELEASED",
                state.requestId(),
                state.queueItemId(),
                allocationId,
                state.gpuId(),
                reason,
                "allocationStatus=" + actualStatus,
                "allocationStatus=ENDED;endReason=VERIFIED_RELEASED;endedAt=" + now);
        allocations.insertInboxMessage(
                UUID.randomUUID(),
                state.memberId(),
                seq,
                allocationId,
                state.requestId(),
                state.gpuId(),
                "VERIFIED_RELEASED",
                "GPU " + state.gpuId() + " 名额已由管理员核实释放",
                "名额 " + allocationId + "（GPU " + state.gpuId() + "，原状态 " + actualStatus
                        + "）已由管理员核实并释放。理由：" + reason,
                now);
        allocateGpuIfIdle(state.gpuId(), now);
        return Optional.of(new VerifiedReleaseResult(allocationId.toString(), Integer.toString(state.gpuId()),
                now, "VERIFIED_RELEASED", false));
    }

    @Transactional(readOnly = true)
    public List<UUID> findExpiryCandidateAllocationIds() {
        return allocations.findExpiryCandidateAllocationIds(clock.instant());
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean processExpiryForAllocation(UUID allocationId) {
        var initial = allocations.findAllocationLifecycleState(allocationId);
        if (initial.isEmpty()) {
            return false;
        }
        allocations.lockGpu(initial.get().gpuId());
        var state = allocations.findAllocationLifecycleState(allocationId).orElseThrow();
        if (!state.current() || state.endedAt() != null) {
            return false;
        }
        Instant now = clock.instant();
        // Branch 3: started and reached/passed expiresAt -> mark OVERDUE once per expiresAt, keep current mapping.
        if (state.startedAt() != null) {
            if (now.isBefore(state.expiresAt())) {
                return false;
            }
            if (state.overdueNotifiedExpiresAt() != null
                    && !state.overdueNotifiedExpiresAt().isBefore(state.expiresAt())) {
                return false;
            }
            allocations.markOverdueNotified(allocationId, state.expiresAt());
            long seq = allocations.insertSystemAuditLog(
                    now,
                    "MARKED_OVERDUE",
                    state.requestId(),
                    state.queueItemId(),
                    allocationId,
                    state.gpuId(),
                    "allocationStatus=IN_USE",
                    "allocationStatus=OVERDUE;expiresAt=" + state.expiresAt());
            allocations.insertInboxMessage(
                    UUID.randomUUID(),
                    state.memberId(),
                    seq,
                    allocationId,
                    state.requestId(),
                    state.gpuId(),
                    "OVERDUE",
                    "GPU " + state.gpuId() + " 使用名额已逾期",
                    "名额 " + allocationId + "（GPU " + state.gpuId() + "）已到期未归还，当前标记为逾期并保留名额，等待核实处理。",
                    now);
            return true;
        }
        // Unstarted branches: compare approved span (expiresAt - allocatedAt) against 24 hours.
        Duration approvedSpan = Duration.between(state.allocatedAt(), state.expiresAt());
        if (approvedSpan.compareTo(Duration.ofHours(24)) < 0) {
            // Branch 1: short-lived (< 24h) unstarted reaches expiresAt -> auto release & allocate successor.
            if (now.isBefore(state.expiresAt())) {
                return false;
            }
            allocations.markAllocationEnded(allocationId, now, "UNSTARTED_EXPIRED");
            allocations.deleteCurrentAllocation(state.gpuId(), allocationId);
            long seq = allocations.insertSystemAuditLog(
                    now,
                    "UNSTARTED_EXPIRED",
                    state.requestId(),
                    state.queueItemId(),
                    allocationId,
                    state.gpuId(),
                    "allocationStatus=UNSTARTED",
                    "allocationStatus=ENDED;endReason=UNSTARTED_EXPIRED;endedAt=" + now);
            allocations.insertInboxMessage(
                    UUID.randomUUID(),
                    state.memberId(),
                    seq,
                    allocationId,
                    state.requestId(),
                    state.gpuId(),
                    "UNSTARTED_EXPIRED",
                    "GPU " + state.gpuId() + " 未开始名额已到期自动释放",
                    "名额 " + allocationId + "（GPU " + state.gpuId() + "，批准跨度不足 24 小时）到期未确认开始，已自动释放。",
                    now);
            allocateGpuIfIdle(state.gpuId(), now);
            return true;
        }
        // Branch 2: approved span >= 24h, 24h elapsed since allocatedAt without start -> mark anomaly & retain mapping.
        Instant anomalyThreshold = state.allocatedAt().plus(Duration.ofHours(24));
        if (now.isBefore(anomalyThreshold) || state.notStartedAnomalyAt() != null) {
            return false;
        }
        allocations.markNotStartedAnomaly(allocationId, now);
        long seq = allocations.insertSystemAuditLog(
                now,
                "MARKED_ANOMALY",
                state.requestId(),
                state.queueItemId(),
                allocationId,
                state.gpuId(),
                "allocationStatus=UNSTARTED",
                "allocationStatus=ANOMALY;notStartedAnomalyAt=" + now);
        allocations.insertInboxMessage(
                UUID.randomUUID(),
                state.memberId(),
                seq,
                allocationId,
                state.requestId(),
                state.gpuId(),
                "ANOMALY",
                "GPU " + state.gpuId() + " 24 小时未开始已标记异常",
                "名额 " + allocationId + "（GPU " + state.gpuId() + "）获配满 24 小时仍未确认开始，已标记异常并保留名额等待管理员核实。",
                now);
        return true;
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
        return allocations.listAllocationsForMember(memberId, clock.instant());
    }

    @Transactional(readOnly = true)
    public Optional<AllocationView> findOwnAllocation(UUID memberId, UUID allocationId) {
        return allocations.findAllocationForMember(memberId, allocationId, clock.instant());
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
        return Optional.of(allocations.findAllocationForMember(winner.memberId(), allocationId, evaluationTime)
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

    public record VerifiedReleaseResult(String allocationId, String gpuId, Instant endedAt,
                                        String endReason, boolean alreadyEnded) {}

    static class StatusConflict extends RuntimeException {
        StatusConflict(String message) {
            super(message);
        }
    }

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
