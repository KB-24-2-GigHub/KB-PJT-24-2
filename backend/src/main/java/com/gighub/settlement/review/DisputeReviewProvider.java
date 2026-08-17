package com.gighub.settlement.review;

public interface DisputeReviewProvider {

    String providerName();

    String modelName();

    String promptVersion();

    DisputeReviewProviderResult review(String requestId, DisputeReviewInput input);
}
