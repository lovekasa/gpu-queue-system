package org.lab.gpuqueue.allocation;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.lab.gpuqueue.allocation.RequestRepository.RequestView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AllocationService {
    private final RequestRepository requests;
    private final Clock clock;

    public AllocationService(RequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    // All queue writes enter here; returning from the proxy also commits the whole request.
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
        if (!requests.allGpusExist(gpuIds)) {
            throw new InvalidRequest("gpuIds", "所选 GPU 不存在，请刷新后重新选择。");
        }
        return requests.create(memberId, clock.instant(), durationHours, gpuIds);
    }

    @Transactional(readOnly = true)
    public List<RequestView> listOwnRequests(UUID memberId) {
        return requests.listForMember(memberId);
    }

    @Transactional(readOnly = true)
    public Optional<RequestView> findOwnRequest(UUID memberId, UUID requestId) {
        return requests.findForMember(memberId, requestId);
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
