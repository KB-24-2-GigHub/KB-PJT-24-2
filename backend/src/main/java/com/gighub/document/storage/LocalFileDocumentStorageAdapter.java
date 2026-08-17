package com.gighub.document.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 로컬 파일시스템 기반 계약 문서 저장소입니다.
 *
 * <p>배포 환경을 클라우드 Object Storage로 바꿀 때는 이 구현만 교체하면 된다
 * ({@link DocumentStorageAdapter}는 로컬 파일시스템을 전제하지 않는다).</p>
 */
@Component
@RequiredArgsConstructor
public class LocalFileDocumentStorageAdapter implements DocumentStorageAdapter {

    private final DocumentStorageProperties properties;

    @Override
    public void writePending(String pendingKey, byte[] content) {
        Path path = resolve(pendingKey);
        try {
            Files.createDirectories(path.getParent());
            Files.write(
                    path, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new DocumentStorageIntegrityException("임시 계약 파일을 쓰지 못했습니다.", e);
        }
    }

    @Override
    public void promote(String pendingKey, String finalKey, byte[] expectedSha256) {
        Path pendingPath = resolve(pendingKey);
        Path finalPath = resolve(finalKey);

        byte[] pendingContent = readBytes(pendingPath, "임시 계약 파일을 찾을 수 없습니다.");
        if (!Arrays.equals(Sha256.digest(pendingContent), expectedSha256)) {
            throw new DocumentStorageIntegrityException(
                    "임시 계약 파일의 Checksum이 기대값과 다릅니다.");
        }

        if (Files.exists(finalPath)) {
            byte[] existingContent = readBytes(finalPath, "최종 계약 파일을 읽지 못했습니다.");
            if (!Arrays.equals(Sha256.digest(existingContent), expectedSha256)) {
                throw new DocumentStorageIntegrityException(
                        "이미 존재하는 최종 계약 파일의 Checksum이 일치하지 않습니다.");
            }
            // 같은 내용으로 이미 승격되어 있다 - Replay이므로 임시 파일만 정리하고 성공 처리한다.
            deletePending(pendingKey);
            return;
        }

        try {
            Files.createDirectories(finalPath.getParent());
            Files.move(pendingPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new DocumentStorageIntegrityException("계약 파일 승격에 실패했습니다.", e);
        }
    }

    @Override
    public byte[] read(String finalKey) {
        return readBytes(resolve(finalKey), "계약 파일을 찾을 수 없습니다.");
    }

    @Override
    public boolean exists(String finalKey) {
        return Files.exists(resolve(finalKey));
    }

    @Override
    public void deletePending(String pendingKey) {
        try {
            Files.deleteIfExists(resolve(pendingKey));
        } catch (IOException e) {
            throw new DocumentStorageIntegrityException("임시 계약 파일을 정리하지 못했습니다.", e);
        }
    }

    @Override
    public void deleteFinal(String finalKey) {
        try {
            Files.deleteIfExists(resolve(finalKey));
        } catch (IOException e) {
            throw new DocumentStorageIntegrityException("최종 계약 파일을 정리하지 못했습니다.", e);
        }
    }

    @Override
    public void deletePendingByWorkCaseId(long workCaseId, Set<Long> retainedDocumentIds) {
        Path workCasePath = resolve("contracts/" + workCaseId);
        if (!Files.exists(workCasePath)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(workCasePath)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isPendingObject)
                    .filter(path -> !belongsToRetainedDocument(path, retainedDocumentIds))
                    .forEach(this::deletePendingPath);
        } catch (IOException e) {
            throw new DocumentStorageIntegrityException(
                    "Rollback된 계약 임시 파일을 정리하지 못했습니다.", e);
        }

        // 파일을 지운 뒤 비어 있는 .pending·문서·근무 디렉터리만 아래에서부터 정리한다.
        try (Stream<Path> paths = Files.walk(workCasePath)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(properties.getBasePath()))
                    .forEach(this::deleteDirectoryIfEmpty);
        } catch (IOException e) {
            throw new DocumentStorageIntegrityException(
                    "Rollback된 계약 임시 디렉터리를 정리하지 못했습니다.", e);
        }
    }

    // Key가 상위 디렉터리 이동(..)으로 저장 경계를 벗어나지 못하게 막는다.
    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new DocumentStorageIntegrityException("Storage Key가 올바르지 않습니다.");
        }
        Path basePath = properties.getBasePath();
        Path resolved = basePath.resolve(key).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new DocumentStorageIntegrityException("Storage Key가 저장 경계를 벗어났습니다.");
        }
        return resolved;
    }

    private byte[] readBytes(Path path, String notFoundMessage) {
        if (!Files.exists(path)) {
            throw new DocumentStorageIntegrityException(notFoundMessage);
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new DocumentStorageIntegrityException(notFoundMessage, e);
        }
    }

    private boolean isPendingObject(Path path) {
        Path parent = path.getParent();
        return parent != null && ".pending".equals(parent.getFileName().toString());
    }

    private boolean belongsToRetainedDocument(Path path, Set<Long> retainedDocumentIds) {
        Path pendingDirectory = path.getParent();
        Path documentDirectory = pendingDirectory == null ? null : pendingDirectory.getParent();
        if (documentDirectory == null) {
            return false;
        }
        try {
            return retainedDocumentIds.contains(
                    Long.parseLong(documentDirectory.getFileName().toString()));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void deletePendingPath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new DocumentStorageIntegrityException(
                    "Rollback된 계약 임시 파일을 정리하지 못했습니다.", e);
        }
    }

    private void deleteDirectoryIfEmpty(Path path) {
        if (!Files.isDirectory(path)) {
            return;
        }
        try (Stream<Path> children = Files.list(path)) {
            if (children.findAny().isEmpty()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new DocumentStorageIntegrityException(
                    "Rollback된 계약 임시 디렉터리를 정리하지 못했습니다.", e);
        }
    }
}
