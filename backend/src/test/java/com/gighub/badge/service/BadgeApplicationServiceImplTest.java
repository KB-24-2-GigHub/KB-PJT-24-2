package com.gighub.badge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import com.gighub.badge.mapper.BadgeEvidenceSourceMapper;
import com.gighub.badge.mapper.UserBadgeMapper;
import com.gighub.badge.mapper.param.UserBadgeUpsertParam;
import com.gighub.badge.mapper.result.BadgeEvidenceCountsRow;
import com.gighub.badge.service.result.BadgeCalculationResult;
import com.gighub.badge.service.result.BadgeSnapshot;
import com.gighub.member.domain.User;
import com.gighub.member.domain.UserRole;
import com.gighub.member.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BadgeApplicationServiceImplTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final UserMapper userMapper = mock(UserMapper.class);
    private final BadgeEvidenceSourceMapper evidenceMapper = mock(BadgeEvidenceSourceMapper.class);
    private final UserBadgeMapper userBadgeMapper = mock(UserBadgeMapper.class);
    private final BadgeEvidenceCodec codec = mock(BadgeEvidenceCodec.class);

    private final BadgeApplicationServiceImpl service = new BadgeApplicationServiceImpl(
            userMapper, evidenceMapper, userBadgeMapper, codec, FIXED_CLOCK);

    @Test
    void locksUserThenUsesOwnerEvidenceAndUpsertsTrustOwner() {
        User owner = new User();
        owner.setId(7L);
        owner.setRole(UserRole.OWNER);
        when(userMapper.lockById(7L)).thenReturn(owner);
        when(evidenceMapper.countOwnerEvidence(7L)).thenReturn(new BadgeEvidenceCountsRow(10, 8));
        when(codec.writeEvidence(any(), any(), any())).thenReturn("{\"level\":1}");

        BadgeCalculationResult result = service.recalculate(7L);

        assertEquals("TRUST_OWNER", result.getBadgeType());
        assertEquals(1, result.getLevel());
        assertEquals(10, result.getTotalCount());
        assertEquals(8, result.getNormalCount());
        verify(evidenceMapper, never()).countWorkerEvidence(any());
        ArgumentCaptor<UserBadgeUpsertParam> captor =
                ArgumentCaptor.forClass(UserBadgeUpsertParam.class);
        verify(userBadgeMapper).upsert(captor.capture());
        UserBadgeUpsertParam savedParam = captor.getValue();
        assertEquals(7L, savedParam.getUserId());
        assertEquals("TRUST_OWNER", savedParam.getBadgeType());
        assertEquals("{\"level\":1}", savedParam.getEvidence());
        assertEquals(LocalDateTime.now(FIXED_CLOCK), savedParam.getAwardedAt());
    }

    @Test
    void locksUserThenUsesWorkerEvidenceAndUpsertsTrustWorker() {
        User worker = new User();
        worker.setId(9L);
        worker.setRole(UserRole.WORKER);
        when(userMapper.lockById(9L)).thenReturn(worker);
        when(evidenceMapper.countWorkerEvidence(9L)).thenReturn(new BadgeEvidenceCountsRow(0, 0));
        when(codec.writeEvidence(any(), any(), any())).thenReturn("{\"level\":0}");

        BadgeCalculationResult result = service.recalculate(9L);

        assertEquals("TRUST_WORKER", result.getBadgeType());
        assertEquals(0, result.getLevel());
        verify(evidenceMapper, never()).countOwnerEvidence(any());
        verify(userBadgeMapper).upsert(any());
    }

    @Test
    void currentBadgeReadsTheStoredEvidenceWithoutLockingOrRecalculating() {
        User owner = new User();
        owner.setId(7L);
        owner.setRole(UserRole.OWNER);
        when(userMapper.findById(7L)).thenReturn(owner);
        when(userBadgeMapper.findEvidenceByUserIdAndType(7L, "TRUST_OWNER"))
                .thenReturn("{\"level\":2}");
        when(codec.readLevel("{\"level\":2}")).thenReturn(2);

        Optional<BadgeSnapshot> snapshot = service.currentBadge(7L);

        assertTrue(snapshot.isPresent());
        assertEquals("TRUST_OWNER", snapshot.get().getBadgeType());
        assertEquals(2, snapshot.get().getLevel());
        verify(userMapper, never()).lockById(any());
        verify(userBadgeMapper, never()).upsert(any());
    }

    @Test
    void currentBadgeIsEmptyWhenNothingWasEverStored() {
        User owner = new User();
        owner.setId(7L);
        owner.setRole(UserRole.OWNER);
        when(userMapper.findById(7L)).thenReturn(owner);
        when(userBadgeMapper.findEvidenceByUserIdAndType(7L, "TRUST_OWNER")).thenReturn(null);

        assertEquals(Optional.empty(), service.currentBadge(7L));
    }

    @Test
    void currentBadgeIsEmptyWhenTheStoredLevelIsZero() {
        User owner = new User();
        owner.setId(7L);
        owner.setRole(UserRole.OWNER);
        when(userMapper.findById(7L)).thenReturn(owner);
        when(userBadgeMapper.findEvidenceByUserIdAndType(7L, "TRUST_OWNER"))
                .thenReturn("{\"level\":0}");
        when(codec.readLevel("{\"level\":0}")).thenReturn(0);

        assertEquals(Optional.empty(), service.currentBadge(7L));
    }
}
