package com.gighub.document.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 근로계약서 보존 만료 파기(DOC-012, {@code DEC-CONTRACT-RETENTION})의 실행 모드를
 * 외부 설정에서 읽는다.
 *
 * <p>실행 시각(매일 02:00)과 배치 크기(100건)는 결정문이 고정한 값이라 여기서 다루지
 * 않고 {@link com.gighub.document.service.ContractRetentionPurgeScheduler}의 상수로 둔다.
 * 이 클래스는 "기본 운영 경로는 Dry-run이며 실제 파기는 명시적 실행 옵션이 있어야 한다"는
 * 결정만 담당한다. 값이 없으면 Dry-run(false)이 기본이라 이 Key가 없어도 안전하게
 * 기동한다.</p>
 */
@Component
public class ContractRetentionProperties {

    public static final String PURGE_ENABLED_KEY = "contract.retention.purge-enabled";

    private final boolean purgeEnabled;

    @Autowired
    public ContractRetentionProperties(Environment environment) {
        this(environment.getProperty(PURGE_ENABLED_KEY, Boolean.class, Boolean.FALSE));
    }

    ContractRetentionProperties(boolean purgeEnabled) {
        this.purgeEnabled = purgeEnabled;
    }

    /** {@code true}면 저장소 Object를 실제로 지운다. {@code false}(기본)면 Dry-run이다. */
    public boolean isPurgeEnabled() {
        return purgeEnabled;
    }
}
