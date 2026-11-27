package org.lab.gpuqueue.allocation;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class QueueOrderingPolicy {
    private static final Duration BOOST_THRESHOLD = Duration.ofHours(72);

    public boolean isBoostedOver72Hours(Instant submittedAt, Instant evaluationTime) {
        return submittedAt.plus(BOOST_THRESHOLD).isBefore(evaluationTime);
    }

    public List<WaitingCandidate> order(List<WaitingCandidate> candidates, Instant evaluationTime) {
        Comparator<WaitingCandidate> comparator = Comparator
                .<WaitingCandidate>comparingInt(candidate -> priorityGroup(candidate, evaluationTime))
                .thenComparing(WaitingCandidate::submittedAt)
                .thenComparingLong(WaitingCandidate::receiveSeq);
        return candidates.stream().sorted(comparator).toList();
    }

    private int priorityGroup(WaitingCandidate candidate, Instant evaluationTime) {
        if (isBoostedOver72Hours(candidate.submittedAt(), evaluationTime)) {
            return 0;
        }
        return candidate.approvedEmergency() ? 1 : 2;
    }

    public Optional<WaitingCandidate> selectNext(List<WaitingCandidate> candidates, Instant evaluationTime) {
        return order(candidates, evaluationTime).stream().findFirst();
    }

    public record WaitingCandidate(
            UUID queueItemId,
            UUID requestId,
            UUID memberId,
            int gpuId,
            int durationHours,
            Instant submittedAt,
            long receiveSeq,
            boolean approvedEmergency
    ) {
        public WaitingCandidate(
                UUID queueItemId,
                UUID requestId,
                UUID memberId,
                int gpuId,
                int durationHours,
                Instant submittedAt,
                long receiveSeq) {
            this(queueItemId, requestId, memberId, gpuId, durationHours, submittedAt, receiveSeq, false);
        }
    }
}
