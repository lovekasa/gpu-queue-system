package org.lab.gpuqueue.allocation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

    public List<WaitingCandidate> order(List<WaitingCandidate> candidates, List<ReorderRule> rules, Instant evaluationTime) {
        List<WaitingCandidate> list = new ArrayList<>(order(candidates, evaluationTime));
        if (rules == null || rules.isEmpty() || list.size() <= 1) {
            return List.copyOf(list);
        }
        for (ReorderRule rule : rules) {
            int targetIdx = indexOf(list, rule.targetQueueItemId());
            if (targetIdx < 0) {
                continue;
            }
            WaitingCandidate target = list.remove(targetIdx);
            int insertedAt = -1;
            if (rule.beforeQueueItemId() != null) {
                int succIdx = indexOf(list, rule.beforeQueueItemId());
                if (succIdx >= 0) {
                    list.add(succIdx, target);
                    insertedAt = succIdx;
                }
            }
            if (insertedAt < 0 && rule.afterQueueItemId() != null) {
                int predIdx = indexOf(list, rule.afterQueueItemId());
                if (predIdx >= 0) {
                    list.add(predIdx + 1, target);
                    insertedAt = predIdx + 1;
                }
            }
            if (insertedAt < 0) {
                list.add(targetIdx, target);
            }
        }
        return List.copyOf(list);
    }

    private int indexOf(List<WaitingCandidate> list, UUID queueItemId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).queueItemId().equals(queueItemId)) {
                return i;
            }
        }
        return -1;
    }

    public Optional<WaitingCandidate> selectNext(List<WaitingCandidate> candidates, Instant evaluationTime) {
        return order(candidates, evaluationTime).stream().findFirst();
    }

    public Optional<WaitingCandidate> selectNext(List<WaitingCandidate> candidates, List<ReorderRule> rules, Instant evaluationTime) {
        return order(candidates, rules, evaluationTime).stream().findFirst();
    }

    public record ReorderRule(
            long id,
            UUID targetQueueItemId,
            UUID beforeQueueItemId,
            UUID afterQueueItemId
    ) {}

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
