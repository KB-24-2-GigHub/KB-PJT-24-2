package com.gighub.document.contract;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.util.XRLog;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ContractSnapshot}로 근로계약서 ORIGINAL PDF(Version 1)를 렌더링합니다
 * (DEC-CONTRACT-AUTO-GENERATION, DEC-DOCUMENT-STORAGE).
 *
 * <p>배포물에 포함된 HTML/CSS Template 한 종({@value #TEMPLATE_PATH})에 Snapshot 값을 넣고
 * PDF로 렌더링한다. 표와 서명란 배치는 Template의 CSS가 담당하므로 조항을 더하거나 칸을
 * 바꿀 때 좌표를 다시 계산하지 않는다.</p>
 *
 * <p>Pretendard(SIL OFL 1.1) 한글 Font를 Embed해 서버 환경과 관계없이 같은 글꼴로 렌더링한다.
 * 문서 정보의 생성·수정 시각은 렌더링을 수행한 시각이 아니라 {@link ContractSnapshot#acceptedAt()}로
 * 고정해, 같은 Snapshot을 다시 렌더링해도 문서 Metadata가 매번 달라지지 않게 한다.</p>
 *
 * <p>ORIGINAL과 SIGNED는 같은 계약 조건을 렌더링하고, SIGNED에만 WORKER의 이름 직접 입력
 * 증거와 서명 시각을 덧붙입니다.</p>
 */
@Component
public class ContractPdfRenderer {

    private static final String TEMPLATE_PATH = "/templates/contract/employment-contract.html";
    private static final String FONT_REGULAR = "/fonts/Pretendard-Regular.ttf";
    private static final String FONT_BOLD = "/fonts/Pretendard-Bold.ttf";
    private static final String FONT_FAMILY = "Pretendard";
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA);
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.KOREA);

    /** 서명 전 ORIGINAL의 근로자 서명란에 넣는 안내다. */
    private static final String UNSIGNED_NAME = "(서명 전)";
    private static final String UNSIGNED_DATE = "-";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)}");

    static {
        // 렌더러 기본 설정은 CSS 경고를 java.util.logging으로 흘려보내므로 저장소 Log 경계 밖으로 나간다.
        XRLog.setLoggingEnabled(false);
    }

    /** ORIGINAL Version(서명 전)을 렌더링한다. */
    public byte[] render(ContractSnapshot snapshot) {
        return render(snapshot, null);
    }

    /**
     * SIGNED Version을 렌더링한다.
     *
     * <p>{@code signature}가 있으면 근로자 서명란에 이름 직접 입력 증거와 서명 시각을 넣는다.
     * ORIGINAL과 SIGNED는 이 서명란 내용으로만 갈리고 나머지 계약 내용은 같은
     * {@link ContractSnapshot}에서 온다.</p>
     */
    public byte[] render(ContractSnapshot snapshot, ContractSnapshot.Signature signature) {
        String html = fill(loadTemplate(), placeholders(snapshot, signature));

        try {
            ByteArrayOutputStream rendered = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.usePdfUaAccessbility(false);
            builder.useFont(
                    () -> requireResource(FONT_REGULAR), FONT_FAMILY, 400,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(
                    () -> requireResource(FONT_BOLD), FONT_FAMILY, 700,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.withHtmlContent(html, null);
            builder.toStream(rendered);
            builder.run();

            return applyDeterministicMetadata(rendered.toByteArray(), snapshot);
        } catch (ContractDocumentGenerationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ContractDocumentGenerationException("근로계약서 PDF 생성에 실패했습니다.", e);
        }
    }

    /** Template 자리표시자와 Snapshot 값의 대응을 한곳에 모은다. */
    private Map<String, String> placeholders(
            ContractSnapshot snapshot, ContractSnapshot.Signature signature) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("workCaseId", String.valueOf(snapshot.workCaseId()));
        values.put("title", snapshot.title());
        values.put("workplaceName", snapshot.workplaceName());
        values.put("workplaceAddress", snapshot.workplaceAddress());
        values.put("workDate", snapshot.startsAt().toLocalDate().format(DATE_FORMAT));
        values.put("startsAt", formatDateTime(snapshot.startsAt()));
        values.put("endsAt", formatDateTime(snapshot.endsAt()));
        values.put("workDuration", formatDuration(snapshot.startsAt(), snapshot.endsAt()));
        values.put("breakMinutes", String.valueOf(snapshot.breakMinutes()));
        values.put("breakPaidLabel", snapshot.breakPaid() ? "유급" : "무급");
        values.put("agreedWage", formatWon(snapshot.agreedWage()));
        values.put("employerName", snapshot.employerName());
        values.put("employerContactLine", contactLine(snapshot.employerPhone()));
        values.put("workerName", snapshot.workerName());
        values.put("workerContactLine", contactLine(snapshot.workerPhone()));
        values.put("sourceTermsVersion", String.valueOf(snapshot.sourceTermsVersion()));
        values.put("acceptedAt", formatDateTime(snapshot.acceptedAt()));
        values.put("employerActionAt", formatDateTime(snapshot.employerActionAt()));

        if (signature == null) {
            values.put("workerSignatureName", UNSIGNED_NAME);
            values.put("workerSignatureDate", UNSIGNED_DATE);
        } else {
            values.put("workerSignatureName", signature.typedName());
            values.put("workerSignatureDate", formatDateTime(signature.signedAt()));
        }
        return values;
    }

    private String loadTemplate() {
        try (InputStream template = requireResource(TEMPLATE_PATH)) {
            return new String(template.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ContractDocumentGenerationException("근로계약서 Template을 읽지 못했습니다.", e);
        }
    }

    /**
     * 사용자 입력이 Template의 Markup을 깨지 않도록 Escape한 뒤 자리표시자를 치환한다.
     *
     * <p>Template을 한 번만 훑으며 채워 넣으므로, 치환된 값 안에 {@code ${...}} 모양 문자열이
     * 들어 있어도 그 값이 다시 자리표시자로 해석되지 않는다.</p>
     */
    private String fill(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            String replacement = value == null ? matcher.group(0) : escapeXml(value);
            matcher.appendReplacement(filled, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(filled);
        return filled.toString();
    }

    private String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 렌더링 시각 대신 {@code acceptedAt}을 문서 시각으로 다시 쓴다.
     *
     * <p>렌더러는 실행 시각을 문서 정보에 남기므로, 같은 Snapshot이 매번 다른 Metadata를 갖지
     * 않도록 PDFBox로 다시 열어 덮어쓴다. 실행 시각이 남은 XMP Metadata도 함께 제거한다.</p>
     */
    private byte[] applyDeterministicMetadata(byte[] pdf, ContractSnapshot snapshot)
            throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            Calendar fixed = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"), Locale.KOREA);
            fixed.clear();
            LocalDateTime acceptedAt = snapshot.acceptedAt();
            fixed.set(
                    acceptedAt.getYear(), acceptedAt.getMonthValue() - 1, acceptedAt.getDayOfMonth(),
                    acceptedAt.getHour(), acceptedAt.getMinute(), acceptedAt.getSecond());

            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("근로계약서 - work_case " + snapshot.workCaseId());
            info.setCreationDate(fixed);
            info.setModificationDate(fixed);
            document.getDocumentCatalog().setMetadata(null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private InputStream requireResource(String resourcePath) {
        InputStream resource = getClass().getResourceAsStream(resourcePath);
        if (resource == null) {
            throw new ContractDocumentGenerationException(
                    "계약서 자원을 찾을 수 없습니다: " + resourcePath, null);
        }
        return resource;
    }

    private String formatDateTime(LocalDateTime value) {
        return value.format(DATE_TIME_FORMAT);
    }

    /** 휴게 시간을 빼지 않은 계약상 구속 시간을 사람이 읽는 형태로 적는다. */
    private String formatDuration(LocalDateTime startsAt, LocalDateTime endsAt) {
        Duration duration = Duration.between(startsAt, endsAt);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (minutes == 0) {
            return hours + "시간";
        }
        return hours + "시간 " + minutes + "분";
    }

    private String formatWon(long amount) {
        return String.format(Locale.KOREA, "%,d원", amount);
    }

    /** 연락처는 선택 입력이라 없으면 계약서에서 그 표시를 통째로 뺀다. */
    private String contactLine(String phone) {
        return (phone == null || phone.isBlank()) ? "" : " (연락처: " + phone + ")";
    }
}
