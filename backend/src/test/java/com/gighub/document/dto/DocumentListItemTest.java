package com.gighub.document.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentListItemTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 12, 0);

    @Test
    void healthCertificateFileNameOmitsIssuedDateSegment() {
        DocumentListItem item = DocumentListItem.of(
                1L,
                "HEALTH_CERTIFICATE",
                "ACTIVE",
                "image/jpeg",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 8, 1),
                1,
                "OWN",
                "김근로",
                null,
                null,
                null,
                null,
                null,
                false,
                NOW);

        assertEquals("보건증_김근로.jpg", item.getFileName());
    }

    @Test
    void employmentContractFileNameOmitsIssuedDateSegment() {
        DocumentListItem item = DocumentListItem.of(
                2L,
                "EMPLOYMENT_CONTRACT",
                "ACTIVE",
                "application/pdf",
                LocalDate.of(2026, 8, 1),
                null,
                1,
                "SYSTEM",
                null,
                null,
                100L,
                "강남점",
                200L,
                "김근로",
                false,
                NOW);

        assertEquals("근로계약서_강남점_김근로.pdf", item.getFileName());
    }
}
