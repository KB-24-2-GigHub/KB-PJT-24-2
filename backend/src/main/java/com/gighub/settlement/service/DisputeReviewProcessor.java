package com.gighub.settlement.service;

import com.gighub.settlement.mapper.result.DisputeReviewCandidate;
import com.gighub.settlement.review.DisputeReviewExecution;
import com.gighub.settlement.review.DisputeReviewProvider;
import com.gighub.settlement.review.DisputeReviewProviderException;
import com.gighub.settlement.review.DisputeReviewProviderFactory;
import com.gighub.settlement.review.DisputeReviewProviderResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 외부 호출을 Spring Transaction 밖에서 실행하고 결과 저장 Service를 다시 호출합니다. */
@Service
@RequiredArgsConstructor
public class DisputeReviewProcessor {

    private static final Logger log = LoggerFactory.getLogger(DisputeReviewProcessor.class);

    private final DisputeReviewQueueService queueService;
    private final DisputeReviewProviderFactory providerFactory;

    public void process(DisputeReviewCandidate candidate) {
        DisputeReviewExecution execution = queueService.claim(candidate);
        if (execution == null) {
            return;
        }
        DisputeReviewProvider provider = providerFactory.requireProvider();
        DisputeReviewProviderResult response;
        try {
            response = provider.review(
                    execution.getRequestKey(), execution.getInput());
        } catch (DisputeReviewProviderException providerFailure) {
            queueService.fail(execution, providerFailure.getFailureCode());
            return;
        } catch (RuntimeException unexpectedFailure) {
            log.error("예상하지 못한 분쟁 검토 오류가 발생했습니다. reviewId={}",
                    execution.getReviewId(), unexpectedFailure);
            queueService.fail(execution, "UNEXPECTED_PROVIDER_ERROR");
            return;
        }
        queueService.complete(execution, response);
    }
}
