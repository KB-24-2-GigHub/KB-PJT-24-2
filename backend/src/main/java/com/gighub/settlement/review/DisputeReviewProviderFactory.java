package com.gighub.settlement.review;

import com.gighub.settlement.config.DisputeReviewProperties;
import org.springframework.stereotype.Component;

/** 설정이 허용한 하나의 Provider만 만들고 비활성 모드에서는 외부 호출 객체를 만들지 않습니다. */
@Component
public class DisputeReviewProviderFactory {

    private final DisputeReviewProperties properties;
    private final DisputeReviewProvider provider;

    public DisputeReviewProviderFactory(DisputeReviewProperties properties) {
        this.properties = properties;
        this.provider = create(properties);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public DisputeReviewProvider requireProvider() {
        if (provider == null) {
            throw new IllegalStateException("분쟁 검토 Provider가 비활성 상태입니다.");
        }
        return provider;
    }

    private static DisputeReviewProvider create(DisputeReviewProperties properties) {
        return switch (properties.getMode()) {
            case DISABLED -> null;
            case FAKE -> new FakeDisputeReviewProvider(
                    properties.getFakeDecision(), properties.getPromptVersion());
            case DEMO_LLM -> new OpenAiDisputeReviewProvider(
                    properties.getApiKey(),
                    properties.getModel(),
                    properties.getPromptVersion(),
                    properties.getTimeout());
        };
    }
}
