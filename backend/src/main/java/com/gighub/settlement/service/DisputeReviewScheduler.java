package com.gighub.settlement.service;

import com.gighub.settlement.config.DisputeReviewProperties;
import com.gighub.settlement.mapper.DisputeReviewMapper;
import com.gighub.settlement.mapper.result.DisputeReviewCandidate;
import com.gighub.settlement.review.DisputeReviewProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 지급 Scheduler를 막지 않는 전용 Worker Pool로 분쟁 검토 후보를 처리합니다. */
@Component
public class DisputeReviewScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DisputeReviewScheduler.class);
    private static final int WORKER_COUNT = 2;
    private static final int QUEUE_CAPACITY = 100;

    private final DisputeReviewMapper reviewMapper;
    private final DisputeReviewProcessor processor;
    private final DisputeReviewProviderFactory providerFactory;
    private final DisputeReviewProperties properties;
    private final ExecutorService executor;
    private final Set<Long> inFlightReviewIds = ConcurrentHashMap.newKeySet();

    @Autowired
    public DisputeReviewScheduler(
            DisputeReviewMapper reviewMapper,
            DisputeReviewProcessor processor,
            DisputeReviewProviderFactory providerFactory,
            DisputeReviewProperties properties) {
        this(reviewMapper, processor, providerFactory, properties, createExecutor());
    }

    DisputeReviewScheduler(
            DisputeReviewMapper reviewMapper,
            DisputeReviewProcessor processor,
            DisputeReviewProviderFactory providerFactory,
            DisputeReviewProperties properties,
            ExecutorService executor) {
        this.reviewMapper = reviewMapper;
        this.processor = processor;
        this.providerFactory = providerFactory;
        this.properties = properties;
        this.executor = executor;
    }

    public void runOnce() {
        if (!providerFactory.isEnabled()) {
            return;
        }
        List<DisputeReviewCandidate> candidates;
        try {
            LocalDateTime now = reviewMapper.currentDatabaseTime();
            candidates = reviewMapper.findCandidates(now, properties.getBatchSize());
        } catch (RuntimeException lookupFailure) {
            log.warn("분쟁 검토 후보 조회에 실패했습니다.", lookupFailure);
            return;
        }
        for (DisputeReviewCandidate candidate : candidates) {
            if (!inFlightReviewIds.add(candidate.getReviewId())) {
                continue;
            }
            try {
                executor.execute(() -> {
                    try {
                        processOne(candidate);
                    } finally {
                        inFlightReviewIds.remove(candidate.getReviewId());
                    }
                });
            } catch (RejectedExecutionException saturated) {
                inFlightReviewIds.remove(candidate.getReviewId());
                // 후보는 DB에 남아 다음 주기에 다시 조회되므로 Scheduler Thread를 막지 않습니다.
                log.warn("분쟁 검토 Worker Queue가 가득 차 후보를 다음 주기로 미룹니다.");
                return;
            }
        }
    }

    private void processOne(DisputeReviewCandidate candidate) {
        try {
            processor.process(candidate);
        } catch (RuntimeException failure) {
            log.error("분쟁 검토 후보 처리에 실패했습니다. reviewId={}",
                    candidate.getReviewId(), failure);
        }
    }

    @Override
    public void destroy() {
        inFlightReviewIds.clear();
        executor.shutdownNow();
    }

    private static ExecutorService createExecutor() {
        return new ThreadPoolExecutor(
                WORKER_COUNT,
                WORKER_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new NamedDaemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "dispute-review-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
