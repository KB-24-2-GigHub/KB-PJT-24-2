package com.gighub.contract.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 계약 확정 당시의 근무 조건을 그대로 굳힌 불변 Snapshot입니다.
 *
 * <p>승인 명세가 확정한 닫힌 JSON Shape 그대로이며 {@code work_contracts.terms_snapshot}에
 * 저장합니다. 근무 조건은 이후 수정될 수 있지만 계약서는 확정 순간을 증명해야 하므로, 현재
 * 근무 행을 다시 읽지 않고 이 Snapshot만으로 계약 내용을 복원할 수 있어야 합니다.</p>
 *
 * <p>{@code schemaVersion}은 이 Shape 자체의 판이고 {@code termsVersion}은 근무 조건의 판입니다.
 * 둘은 함께 올라가지 않으므로 이름이 비슷해도 다른 값입니다.</p>
 */
@Getter
public final class ContractTermsSnapshot {

    /** 이 JSON Shape의 판입니다. Shape가 바뀌면 올리고 이전 판도 읽을 수 있어야 합니다. */
    private final int schemaVersion;

    private final int termsVersion;
    private final String title;
    private final Instant startsAt;
    private final Instant endsAt;
    private final int breakMinutes;
    private final boolean breakPaid;
    private final String workplaceName;
    private final String workplaceAddress;
    private final BigDecimal workplaceLatitude;
    private final BigDecimal workplaceLongitude;
    private final BigDecimal allowedRadiusMeters;
    private final long dailyWage;
    private final Party owner;
    private final Party worker;

    private ContractTermsSnapshot(Builder builder) {
        this.schemaVersion = builder.schemaVersion;
        this.termsVersion = builder.termsVersion;
        this.title = builder.title;
        this.startsAt = builder.startsAt;
        this.endsAt = builder.endsAt;
        this.breakMinutes = builder.breakMinutes;
        this.breakPaid = builder.breakPaid;
        this.workplaceName = builder.workplaceName;
        this.workplaceAddress = builder.workplaceAddress;
        this.workplaceLatitude = trim(builder.workplaceLatitude);
        this.workplaceLongitude = trim(builder.workplaceLongitude);
        this.allowedRadiusMeters = trim(builder.allowedRadiusMeters);
        this.dailyWage = builder.dailyWage;
        this.owner = builder.owner;
        this.worker = builder.worker;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * DECIMAL Column이 채우는 뒤쪽 0을 없앱니다.
     *
     * <p>DB의 {@code DECIMAL(8,2)}는 반경 100을 {@code 100.00}으로 돌려주지만 승인 Shape는
     * {@code 100}입니다. 좌표도 마찬가지라 저장 표현이 아니라 계약 Shape에 맞춥니다.</p>
     *
     * <p>{@code stripTrailingZeros()}만 쓰면 {@code 100.00}이 지수 표기 {@code 1E+2}가 되어
     * JSON에 {@code 100.0}으로 실립니다. 음수 Scale을 0으로 되돌려 항상 평범한 정수·소수
     * 표기가 나오게 합니다.</p>
     */
    private static BigDecimal trim(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** 계약 당사자입니다. 이름은 수락 당시 값을 굳힙니다. */
    @Getter
    public static final class Party {

        private final long userId;
        private final String name;

        public Party(long userId, String name) {
            this.userId = userId;
            this.name = name;
        }
    }

    /** 필드가 많아 Builder로만 만들고, 만든 뒤에는 바꿀 수 없습니다. */
    public static final class Builder {

        private int schemaVersion = 1;
        private int termsVersion;
        private String title;
        private Instant startsAt;
        private Instant endsAt;
        private int breakMinutes;
        private boolean breakPaid;
        private String workplaceName;
        private String workplaceAddress;
        private BigDecimal workplaceLatitude;
        private BigDecimal workplaceLongitude;
        private BigDecimal allowedRadiusMeters;
        private long dailyWage;
        private Party owner;
        private Party worker;

        private Builder() {
        }

        public Builder schemaVersion(int value) {
            this.schemaVersion = value;
            return this;
        }

        public Builder termsVersion(int value) {
            this.termsVersion = value;
            return this;
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder startsAt(Instant value) {
            this.startsAt = value;
            return this;
        }

        public Builder endsAt(Instant value) {
            this.endsAt = value;
            return this;
        }

        public Builder breakMinutes(int value) {
            this.breakMinutes = value;
            return this;
        }

        public Builder breakPaid(boolean value) {
            this.breakPaid = value;
            return this;
        }

        public Builder workplaceName(String value) {
            this.workplaceName = value;
            return this;
        }

        public Builder workplaceAddress(String value) {
            this.workplaceAddress = value;
            return this;
        }

        public Builder workplaceLatitude(BigDecimal value) {
            this.workplaceLatitude = value;
            return this;
        }

        public Builder workplaceLongitude(BigDecimal value) {
            this.workplaceLongitude = value;
            return this;
        }

        public Builder allowedRadiusMeters(BigDecimal value) {
            this.allowedRadiusMeters = value;
            return this;
        }

        public Builder dailyWage(long value) {
            this.dailyWage = value;
            return this;
        }

        public Builder owner(long userId, String name) {
            this.owner = new Party(userId, name);
            return this;
        }

        public Builder worker(long userId, String name) {
            this.worker = new Party(userId, name);
            return this;
        }

        public ContractTermsSnapshot build() {
            return new ContractTermsSnapshot(this);
        }
    }
}
