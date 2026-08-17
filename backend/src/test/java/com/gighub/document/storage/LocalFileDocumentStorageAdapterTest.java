package com.gighub.document.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileDocumentStorageAdapterTest {

    private static final byte[] CONTENT = "PDF-BYTES".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHECKSUM = Sha256.digest(CONTENT);

    @TempDir
    Path basePath;

    private LocalFileDocumentStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LocalFileDocumentStorageAdapter(
                new DocumentStorageProperties(basePath.toString()));
    }

    @Test
    void promotesPendingContentToFinalKeyAndRemovesThePendingFile() {
        adapter.writePending("contracts/1/2/.pending/v1.pdf", CONTENT);

        adapter.promote(
                "contracts/1/2/.pending/v1.pdf", "contracts/1/2/v1.pdf", CHECKSUM);

        assertArrayEquals(CONTENT, adapter.read("contracts/1/2/v1.pdf"));
        assertFalse(Files.exists(basePath.resolve("contracts/1/2/.pending/v1.pdf")));
    }

    @Test
    void replayingPromoteWithTheSameChecksumSucceedsWithoutRewriting() throws IOException {
        adapter.writePending("contracts/1/2/.pending/v1.pdf", CONTENT);
        adapter.promote("contracts/1/2/.pending/v1.pdf", "contracts/1/2/v1.pdf", CHECKSUM);
        long finalModifiedBefore =
                Files.getLastModifiedTime(basePath.resolve("contracts/1/2/v1.pdf")).toMillis();

        // 재시도가 새 임시 파일을 다시 쓴 뒤 승격을 반복해도 기존 최종 파일과 충돌하지 않는다.
        adapter.writePending("contracts/1/2/.pending/v1.pdf", CONTENT);
        adapter.promote("contracts/1/2/.pending/v1.pdf", "contracts/1/2/v1.pdf", CHECKSUM);

        assertArrayEquals(CONTENT, adapter.read("contracts/1/2/v1.pdf"));
        assertTrue(finalModifiedBefore <= Files.getLastModifiedTime(
                basePath.resolve("contracts/1/2/v1.pdf")).toMillis());
    }

    @Test
    void rejectsPromotionWhenExistingFinalObjectChecksumDiffers() throws IOException {
        Path finalPath = basePath.resolve("contracts/1/2/v1.pdf");
        Files.createDirectories(finalPath.getParent());
        Files.write(finalPath, "different content".getBytes(StandardCharsets.UTF_8));
        adapter.writePending("contracts/1/2/.pending/v1.pdf", CONTENT);

        assertThrows(DocumentStorageIntegrityException.class, () -> adapter.promote(
                "contracts/1/2/.pending/v1.pdf", "contracts/1/2/v1.pdf", CHECKSUM));
    }

    @Test
    void rejectsPromotionWhenPendingContentDoesNotMatchExpectedChecksum() {
        adapter.writePending("contracts/1/2/.pending/v1.pdf", "tampered".getBytes());

        assertThrows(DocumentStorageIntegrityException.class, () -> adapter.promote(
                "contracts/1/2/.pending/v1.pdf", "contracts/1/2/v1.pdf", CHECKSUM));
    }

    @Test
    void rejectsPromotionWhenPendingFileIsMissing() {
        assertThrows(DocumentStorageIntegrityException.class, () -> adapter.promote(
                "contracts/1/2/.pending/missing.pdf", "contracts/1/2/v1.pdf", CHECKSUM));
    }

    @Test
    void rejectsKeysThatEscapeTheStorageBoundary() {
        assertThrows(DocumentStorageIntegrityException.class,
                () -> adapter.writePending("../outside.pdf", CONTENT));
        assertThrows(DocumentStorageIntegrityException.class,
                () -> adapter.read("contracts/../../outside.pdf"));
    }

    @Test
    void readThrowsWhenFinalKeyDoesNotExist() {
        assertThrows(DocumentStorageIntegrityException.class,
                () -> adapter.read("contracts/1/2/v1.pdf"));
    }

    @Test
    void existsReflectsWhetherTheFinalKeyIsPresent() {
        assertFalse(adapter.exists("contracts/1/2/v1.pdf"));

        adapter.writePending("contracts/1/2/.pending/v1.pdf", CONTENT);
        adapter.promote("contracts/1/2/.pending/v1.pdf", "contracts/1/2/v1.pdf", CHECKSUM);

        assertTrue(adapter.exists("contracts/1/2/v1.pdf"));
    }

    @Test
    void deletePendingIsSafeWhenNothingExists() {
        adapter.deletePending("contracts/1/2/.pending/never-written.pdf");
    }

    @Test
    void deleteFinalRemovesAPromotedObject() {
        adapter.writePending("contracts/1/2/.pending/v1.pdf", CONTENT);
        adapter.promote("contracts/1/2/.pending/v1.pdf", "contracts/1/2/v1.pdf", CHECKSUM);

        adapter.deleteFinal("contracts/1/2/v1.pdf");

        assertFalse(adapter.exists("contracts/1/2/v1.pdf"));
    }

    @Test
    void deleteFinalIsIdempotentWhenNothingExists() {
        adapter.deleteFinal("contracts/1/2/v1.pdf");

        assertFalse(adapter.exists("contracts/1/2/v1.pdf"));
    }

    @Test
    void rollbackCleanupDeletesOnlyPendingObjectsUnderTheWorkCase() {
        adapter.writePending("contracts/1/2/.pending/v1.pdf", CONTENT);
        adapter.writePending("contracts/1/3/.pending/v2.pdf", CONTENT);
        adapter.writePending("contracts/1/6/.pending/v2.pdf", CONTENT);
        adapter.writePending("contracts/2/4/.pending/v1.pdf", CONTENT);
        adapter.writePending("contracts/1/5/.pending/v1.pdf", CONTENT);
        adapter.promote("contracts/1/5/.pending/v1.pdf", "contracts/1/5/v1.pdf", CHECKSUM);

        adapter.deletePendingByWorkCaseId(1L, Set.of(6L));

        assertFalse(Files.exists(basePath.resolve("contracts/1/2/.pending/v1.pdf")));
        assertFalse(Files.exists(basePath.resolve("contracts/1/3/.pending/v2.pdf")));
        assertTrue(Files.exists(basePath.resolve("contracts/1/5/v1.pdf")));
        assertTrue(Files.exists(basePath.resolve("contracts/1/6/.pending/v2.pdf")));
        assertTrue(Files.exists(basePath.resolve("contracts/2/4/.pending/v1.pdf")));
    }

    @Test
    void storageKeysFollowTheApprovedPattern() {
        assertEquals("contracts/1/2/v1.pdf",
                ContractStorageKeys.finalKey(1L, 2L, 1));
        assertEquals("contracts/1/2/.pending/v1.pdf",
                ContractStorageKeys.pendingKey(1L, 2L, 1));
    }
}
