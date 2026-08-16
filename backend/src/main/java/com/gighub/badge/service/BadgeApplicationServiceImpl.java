package com.gighub.badge.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.gighub.badge.domain.TrustBadgeCriteria;
import com.gighub.badge.domain.TrustBadgeResult;
import com.gighub.badge.domain.TrustBadgeType;
import com.gighub.badge.mapper.BadgeEvidenceSourceMapper;
import com.gighub.badge.mapper.UserBadgeMapper;
import com.gighub.badge.mapper.param.UserBadgeUpsertParam;
import com.gighub.badge.mapper.result.BadgeEvidenceCountsRow;
import com.gighub.badge.service.result.BadgeCalculationResult;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.member.domain.User;
import com.gighub.member.domain.UserRole;
import com.gighub.member.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SPEC-178-06의 잠금→재계산→Upsert 순서를 그대로 구현합니다.
 *
 * <p>DB의 {@code DATETIME}이 Asia/Seoul 벽시계 값이므로 {@code calculatedAt}·{@code awardedAt}도
 * 같은 지역 기준 {@link Clock}으로 만듭니다.</p>
 */
@Service
public class BadgeApplicationServiceImpl implements BadgeApplicationService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");

    private final UserMapper userMapper;
    private final BadgeEvidenceSourceMapper badgeEvidenceSourceMapper;
    private final UserBadgeMapper userBadgeMapper;
    private final BadgeEvidenceCodec badgeEvidenceCodec;
    private final Clock clock;

    @Autowired
    public BadgeApplicationServiceImpl(
            UserMapper userMapper,
            BadgeEvidenceSourceMapper badgeEvidenceSourceMapper,
            UserBadgeMapper userBadgeMapper,
            BadgeEvidenceCodec badgeEvidenceCodec) {
        this(
                userMapper,
                badgeEvidenceSourceMapper,
                userBadgeMapper,
                badgeEvidenceCodec,
                Clock.system(DATABASE_ZONE));
    }

    /** 계산 시각 경계를 검증할 때만 고정 Clock을 주입합니다. */
    BadgeApplicationServiceImpl(
            UserMapper userMapper,
            BadgeEvidenceSourceMapper badgeEvidenceSourceMapper,
            UserBadgeMapper userBadgeMapper,
            BadgeEvidenceCodec badgeEvidenceCodec,
            Clock clock) {
        this.userMapper = userMapper;
        this.badgeEvidenceSourceMapper = badgeEvidenceSourceMapper;
        this.userBadgeMapper = userBadgeMapper;
        this.badgeEvidenceCodec = badgeEvidenceCodec;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BadgeCalculationResult recalculate(long userId) {
        User user = userMapper.lockById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
        }
        UserRole role = user.getRole();
        TrustBadgeType badgeType = toBadgeType(role);

        BadgeEvidenceCountsRow counts = role == UserRole.OWNER
                ? badgeEvidenceSourceMapper.countOwnerEvidence(userId)
                : badgeEvidenceSourceMapper.countWorkerEvidence(userId);

        TrustBadgeResult result =
                TrustBadgeCriteria.calculate(counts.getTotalCount(), counts.getNormalCount());
        LocalDateTime calculatedAt = LocalDateTime.now(clock);
        String evidence = badgeEvidenceCodec.writeEvidence(badgeType, result, calculatedAt);

        userBadgeMapper.upsert(
                UserBadgeUpsertParam.of(userId, badgeType.name(), evidence, calculatedAt));

        return BadgeCalculationResult.of(
                badgeType.name(),
                result.getLevel(),
                result.getTotalCount(),
                result.getNormalCount(),
                result.getThresholdCount(),
                result.getThresholdPercent(),
                result.getRemainingToNextLevel(),
                result.getNextThresholdPercent());
    }

    private TrustBadgeType toBadgeType(UserRole role) {
        return role == UserRole.OWNER ? TrustBadgeType.TRUST_OWNER : TrustBadgeType.TRUST_WORKER;
    }
}
