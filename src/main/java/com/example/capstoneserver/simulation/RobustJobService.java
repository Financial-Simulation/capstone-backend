package com.example.capstoneserver.simulation;

import com.example.capstoneserver.global.exception.GeneralException;
import com.example.capstoneserver.global.response.ErrorStatus;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.simulation.SimulationService.RobustTask;
import com.example.capstoneserver.simulation.dto.PlanSpec;
import com.example.capstoneserver.simulation.dto.RobustResponse;
import com.example.capstoneserver.simulation.dto.RobustResponse.Status;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * /robust 비동기 작업. 결과(숫자)만 완료 후 10분간 메모리에 둔다.
 * 입력은 작업이 끝나면 함께 사라지고 로그에도 남기지 않는다 (NFR-06).
 * 메모리 보관이라 서버 단일 인스턴스가 전제다.
 */
@Slf4j
@Service
public class RobustJobService {

    private static final int MAX_RUNNING = 2;
    private static final Duration RETENTION = Duration.ofMinutes(10);

    private final SimulationService simulationService;
    private final int trials;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_RUNNING);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger running = new AtomicInteger();

    public RobustJobService(SimulationService simulationService, ParamsRepository paramsRepository) {
        this.simulationService = simulationService;
        this.trials = paramsRepository.getRobust().trials();
    }

    public RobustResponse start(SimulationInput input, List<PlanSpec> plans) {
        RobustTask task = simulationService.prepareRobust(input, plans);

        removeExpired();
        if (running.incrementAndGet() > MAX_RUNNING) {
            running.decrementAndGet();
            throw new GeneralException(ErrorStatus.JOB_LIMIT_EXCEEDED);
        }

        String jobId = "rb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Job job = new Job();
        jobs.put(jobId, job);
        executor.submit(() -> run(jobId, job, task));
        return toResponse(jobId, job);
    }

    public RobustResponse get(String jobId) {
        removeExpired();
        Job job = jobs.get(jobId);
        if (job == null) {
            throw new GeneralException(ErrorStatus.JOB_NOT_FOUND);
        }
        return toResponse(jobId, job);
    }

    private void run(String jobId, Job job, RobustTask task) {
        try {
            job.result = simulationService.runRobust(task, done -> job.done = done);
            job.finish(Status.DONE);
        } catch (RuntimeException e) {
            log.error("가능성 범위 계산 실패: {}", jobId, e);
            job.finish(Status.FAILED);
        } finally {
            running.decrementAndGet();
        }
    }

    private RobustResponse toResponse(String jobId, Job job) {
        Status status = job.status;
        if (status != Status.DONE) {
            return new RobustResponse(jobId, status, job.done * 100 / trials, trials, null, null, null, null);
        }
        RobustResponse.Result r = job.result;
        return new RobustResponse(jobId, status, 100, r.trials(), r.pathsPerTrial(), r.probability(), r.plans(), r.message());
    }

    private void removeExpired() {
        Instant now = Instant.now();
        jobs.values().removeIf(job -> job.finishedAt != null && job.finishedAt.plus(RETENTION).isBefore(now));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 작업 스레드가 쓰고 요청 스레드가 읽는다. status를 마지막에 써서 결과가 먼저 보이게 한다 */
    private static final class Job {
        volatile int done;
        volatile RobustResponse.Result result;
        volatile Instant finishedAt;
        volatile Status status = Status.RUNNING;

        void finish(Status finalStatus) {
            finishedAt = Instant.now();
            status = finalStatus;
        }
    }
}
