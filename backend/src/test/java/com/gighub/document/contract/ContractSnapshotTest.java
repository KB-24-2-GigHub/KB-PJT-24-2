package com.gighub.document.contract;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ContractSnapshotTest {

    @Test
    void rejectsBreakMinutesLongerThanTheShiftItself() {
        assertThrows(IllegalArgumentException.class, () -> new ContractSnapshot(
                106L,
                "주말 홀 서빙",
                LocalDateTime.of(2026, 7, 22, 10, 0),
                LocalDateTime.of(2026, 7, 22, 18, 0),
                481,
                false,
                "기가 허브",
                "서울시 강남구 테스트로 1",
                90_000L,
                "김사장",
                null,
                "이알바",
                null,
                3,
                LocalDateTime.of(2026, 7, 22, 13, 0),
                LocalDateTime.of(2026, 7, 1, 9, 0)));
    }
}
