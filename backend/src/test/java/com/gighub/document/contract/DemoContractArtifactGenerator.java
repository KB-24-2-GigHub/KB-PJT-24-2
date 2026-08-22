package com.gighub.document.contract;

import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.Sha256;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Video 4 과거 계약의 PDF를 운영 Renderer와 같은 방식으로 만드는 로컬 Demo 도구입니다. */
public final class DemoContractArtifactGenerator {

    private static final String DEMO_SCENARIO = "video-04-three-years";
    private static final int EXPECTED_CONTRACTS = 64;

    private static final String SELECT_CONTRACTS = """
            SELECT d.id AS document_id,
                   d.work_case_id,
                   contract.title,
                   contract.starts_at,
                   contract.ends_at,
                   contract.break_minutes,
                   contract.break_paid,
                   contract.workplace_name,
                   contract.workplace_address,
                   contract.agreed_wage,
                   employer.name AS employer_name,
                   employer.phone AS employer_phone,
                   worker.name AS worker_name,
                   worker.phone AS worker_phone,
                   contract.source_terms_version,
                   contract.accepted_at,
                   wc.created_at AS work_case_created_at,
                   original.id AS original_version_id,
                   original.storage_key AS original_storage_key,
                   signed.id AS signed_version_id,
                   signed.storage_key AS signed_storage_key
            FROM documents d
            JOIN work_contracts contract ON contract.work_case_id = d.work_case_id
            JOIN work_cases wc ON wc.id = d.work_case_id
            JOIN users employer ON employer.id = contract.employer_id
            JOIN users worker ON worker.id = contract.worker_id
            JOIN document_versions original
              ON original.document_id = d.id
             AND original.version_no = 1
             AND original.version_type = 'ORIGINAL'
            JOIN document_versions signed
              ON signed.document_id = d.id
             AND signed.version_no = 2
             AND signed.version_type = 'SIGNED'
            WHERE d.document_type = 'EMPLOYMENT_CONTRACT'
              AND d.status = 'ACTIVE'
              AND JSON_UNQUOTE(JSON_EXTRACT(contract.terms_snapshot, '$.demoScenario')) = ?
            ORDER BY d.id
            """;

    private static final String UPDATE_VERSION = """
            UPDATE document_versions
            SET size_bytes = ?, checksum = ?
            WHERE id = ? AND document_id = ? AND version_no = ?
            """;

    private static final String UPDATE_SIGNATURE = """
            UPDATE document_signatures
            SET source_checksum = ?, signed_checksum = ?
            WHERE document_id = ?
              AND source_version_id = ?
              AND signed_version_id = ?
            """;

    private DemoContractArtifactGenerator() {
    }

    /** 설정 파일과 저장소 Root를 받아 64개 계약의 두 PDF Version을 생성합니다. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "database-local.properties 경로와 저장소 Root 경로가 필요합니다.");
        }

        Path configPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path repositoryRoot = Path.of(args[1]).toAbsolutePath().normalize();
        Properties properties = loadProperties(configPath);
        Path storageRoot = resolveStorageRoot(properties, repositoryRoot);

        String driver = required(properties, "database.driver-class-name");
        Class.forName(driver);
        try (Connection connection = DriverManager.getConnection(
                required(properties, "database.jdbc-url"),
                required(properties, "database.username"),
                required(properties, "database.password"))) {
            connection.setAutoCommit(false);
            try {
                List<DemoContractRow> contracts = findContracts(connection);
                if (contracts.size() != EXPECTED_CONTRACTS) {
                    throw new IllegalStateException(
                            "Video 4 과거 계약은 " + EXPECTED_CONTRACTS
                                    + "건이어야 하지만 " + contracts.size() + "건입니다.");
                }

                ContractPdfRenderer renderer = new ContractPdfRenderer();
                for (DemoContractRow contract : contracts) {
                    materialize(connection, storageRoot, renderer, contract);
                }
                connection.commit();
                System.out.println(
                        "[demo-contracts] Rendered 64 historical contracts and 128 PDF files.");
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static Properties loadProperties(Path configPath) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 설정이 없습니다.");
        }
        return value.trim();
    }

    private static Path resolveStorageRoot(Properties properties, Path repositoryRoot) {
        Path configured = Path.of(required(properties, "document.storage.base-path"));
        return (configured.isAbsolute() ? configured : repositoryRoot.resolve(configured))
                .toAbsolutePath()
                .normalize();
    }

    private static List<DemoContractRow> findContracts(Connection connection) throws SQLException {
        List<DemoContractRow> contracts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_CONTRACTS)) {
            statement.setString(1, DEMO_SCENARIO);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    contracts.add(toRow(rows));
                }
            }
        }
        return contracts;
    }

    private static DemoContractRow toRow(ResultSet row) throws SQLException {
        return new DemoContractRow(
                row.getLong("document_id"),
                row.getLong("work_case_id"),
                row.getString("title"),
                timestamp(row, "starts_at"),
                timestamp(row, "ends_at"),
                row.getInt("break_minutes"),
                row.getBoolean("break_paid"),
                row.getString("workplace_name"),
                row.getString("workplace_address"),
                row.getLong("agreed_wage"),
                row.getString("employer_name"),
                row.getString("employer_phone"),
                row.getString("worker_name"),
                row.getString("worker_phone"),
                row.getInt("source_terms_version"),
                timestamp(row, "accepted_at"),
                timestamp(row, "work_case_created_at"),
                row.getLong("original_version_id"),
                row.getString("original_storage_key"),
                row.getLong("signed_version_id"),
                row.getString("signed_storage_key"));
    }

    private static LocalDateTime timestamp(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toLocalDateTime();
    }

    private static void materialize(
            Connection connection,
            Path storageRoot,
            ContractPdfRenderer renderer,
            DemoContractRow contract) throws IOException, SQLException {
        requireCanonicalStorageKeys(contract);
        ContractSnapshot snapshot = contract.toSnapshot();
        byte[] original = renderer.render(snapshot);
        byte[] signed = renderer.render(
                snapshot,
                new ContractSnapshot.Signature(contract.workerName(), contract.acceptedAt()));
        byte[] originalChecksum = Sha256.digest(original);
        byte[] signedChecksum = Sha256.digest(signed);

        writeFinal(storageRoot, contract.originalStorageKey(), original);
        writeFinal(storageRoot, contract.signedStorageKey(), signed);
        updateVersion(
                connection,
                contract.documentId(),
                contract.originalVersionId(),
                1,
                original,
                originalChecksum);
        updateVersion(
                connection,
                contract.documentId(),
                contract.signedVersionId(),
                2,
                signed,
                signedChecksum);
        updateSignature(connection, contract, originalChecksum, signedChecksum);
    }

    private static void requireCanonicalStorageKeys(DemoContractRow contract) {
        String expectedOriginal = ContractStorageKeys.finalKey(
                contract.workCaseId(), contract.documentId(), 1);
        String expectedSigned = ContractStorageKeys.finalKey(
                contract.workCaseId(), contract.documentId(), 2);
        if (!expectedOriginal.equals(contract.originalStorageKey())
                || !expectedSigned.equals(contract.signedStorageKey())) {
            throw new IllegalStateException("계약서 Storage Key가 표준 경로와 다릅니다.");
        }
    }

    private static void writeFinal(Path storageRoot, String storageKey, byte[] content)
            throws IOException {
        Path target = storageRoot.resolve(storageKey).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalStateException("계약서 Storage Key가 저장 경계를 벗어났습니다.");
        }
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(
                temporary,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        if (!java.util.Arrays.equals(Sha256.digest(Files.readAllBytes(target)), Sha256.digest(content))) {
            throw new IllegalStateException("생성한 계약서 PDF의 Checksum이 일치하지 않습니다.");
        }
    }

    private static void updateVersion(
            Connection connection,
            long documentId,
            long versionId,
            int versionNo,
            byte[] content,
            byte[] checksum) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_VERSION)) {
            statement.setLong(1, content.length);
            statement.setBytes(2, checksum);
            statement.setLong(3, versionId);
            statement.setLong(4, documentId);
            statement.setInt(5, versionNo);
            requireOneUpdated(statement.executeUpdate(), "계약서 Version");
        }
    }

    private static void updateSignature(
            Connection connection,
            DemoContractRow contract,
            byte[] originalChecksum,
            byte[] signedChecksum) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_SIGNATURE)) {
            statement.setBytes(1, originalChecksum);
            statement.setBytes(2, signedChecksum);
            statement.setLong(3, contract.documentId());
            statement.setLong(4, contract.originalVersionId());
            statement.setLong(5, contract.signedVersionId());
            requireOneUpdated(statement.executeUpdate(), "계약서 서명");
        }
    }

    private static void requireOneUpdated(int updatedRows, String target) {
        if (updatedRows != 1) {
            throw new IllegalStateException(target + " 갱신 행이 " + updatedRows + "건입니다.");
        }
    }

    private record DemoContractRow(
            long documentId,
            long workCaseId,
            String title,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            int breakMinutes,
            boolean breakPaid,
            String workplaceName,
            String workplaceAddress,
            long agreedWage,
            String employerName,
            String employerPhone,
            String workerName,
            String workerPhone,
            int sourceTermsVersion,
            LocalDateTime acceptedAt,
            LocalDateTime workCaseCreatedAt,
            long originalVersionId,
            String originalStorageKey,
            long signedVersionId,
            String signedStorageKey) {

        private ContractSnapshot toSnapshot() {
            return new ContractSnapshot(
                    workCaseId,
                    title,
                    startsAt,
                    endsAt,
                    breakMinutes,
                    breakPaid,
                    workplaceName,
                    workplaceAddress,
                    agreedWage,
                    employerName,
                    employerPhone,
                    workerName,
                    workerPhone,
                    sourceTermsVersion,
                    acceptedAt,
                    workCaseCreatedAt);
        }
    }
}
