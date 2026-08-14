package com.gighub.document.contract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractPdfRendererTest {

    private final ContractPdfRenderer renderer = new ContractPdfRenderer();

    @Test
    void rendersAllSnapshotFieldsAsExtractableTextOnASinglePage() throws IOException {
        byte[] pdf = renderer.render(snapshot());

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());

            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("근로계약서"));
            assertTrue(text.contains("주말 홀 서빙"));
            assertTrue(text.contains("기가 허브"));
            assertTrue(text.contains("서울시 강남구 테스트로 1"));
            assertTrue(text.contains("2026-07-22 10:00"));
            assertTrue(text.contains("2026-07-22 18:00"));
            assertTrue(text.contains("60분"));
            assertTrue(text.contains("무급"));
            assertTrue(text.contains("90,000원"));
            assertTrue(text.contains("김사장"));
            assertTrue(text.contains("이알바"));
            assertTrue(text.contains("v3"));
            assertTrue(text.contains("2026-07-22 (1일간)"));
        }
    }

    @Test
    void rendersStatutoryNoticesRequiredByLaborStandardsActArticle17() throws IOException {
        byte[] pdf = renderer.render(snapshot());

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("연차유급휴가와 주휴수당 발생 요건을"));
            assertTrue(text.contains("최저임금법에 따른 시간급"));
            assertTrue(text.contains("산업재해보상보험과 고용보험"));
            assertTrue(text.contains("근로기준법 제17조"));
        }
    }

    @Test
    void rendersContactLineOnlyWhenPhoneIsPresent() throws IOException {
        byte[] withPhone = renderer.render(snapshotWithPhones("010-1111-2222", "010-3333-4444"));
        byte[] withoutPhone = renderer.render(snapshot());

        try (PDDocument document = Loader.loadPDF(withPhone)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("010-1111-2222"));
            assertTrue(text.contains("010-3333-4444"));
        }
        try (PDDocument document = Loader.loadPDF(withoutPhone)) {
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains("연락처"));
        }
    }

    @Test
    void rendersWorkerSignatureDateOnlyInTheSignedVersion() throws IOException {
        byte[] original = renderer.render(snapshot());
        byte[] signed = renderer.render(snapshot(), new ContractSnapshot.Signature(
                "이알바", LocalDateTime.of(2026, 7, 22, 13, 1)));

        try (PDDocument document = Loader.loadPDF(signed)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("서명 일시: 2026-07-22 13:01"));
        }
        try (PDDocument document = Loader.loadPDF(original)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("(서명 전)"));
            assertFalse(text.contains("2026-07-22 13:01"));
        }
    }

    @Test
    void rendersEmployerActionDateFromWorkCaseCreation() throws IOException {
        byte[] pdf = renderer.render(snapshot());

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("근무등록 일시: 2026-07-01 09:00"));
        }
    }

    @Test
    void escapesMarkupCharactersInSnapshotValuesInsteadOfBreakingTheTemplate()
            throws IOException {
        ContractSnapshot markupInValue = new ContractSnapshot(
                1L,
                "<b>주말</b> 홀 & 서빙",
                LocalDateTime.of(2026, 7, 22, 10, 0),
                LocalDateTime.of(2026, 7, 22, 18, 0),
                60,
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
                LocalDateTime.of(2026, 7, 1, 9, 0));

        byte[] pdf = renderer.render(markupInValue);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("<b>주말</b> 홀 & 서빙"));
        }
    }

    @Test
    void doesNotReinterpretAPlaceholderLookingStringInsideASubstitutedValue()
            throws IOException {
        ContractSnapshot placeholderLookingTitle = new ContractSnapshot(
                1L,
                "업무 ${agreedWage} 확인",
                LocalDateTime.of(2026, 7, 22, 10, 0),
                LocalDateTime.of(2026, 7, 22, 18, 0),
                60,
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
                LocalDateTime.of(2026, 7, 1, 9, 0));

        byte[] pdf = renderer.render(placeholderLookingTitle);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("업무 ${agreedWage} 확인"));
        }
    }

    @Test
    void fixesDocumentTimestampsToAcceptedAtInsteadOfRenderTime() throws IOException {
        byte[] pdf = renderer.render(snapshot());

        try (PDDocument document = Loader.loadPDF(pdf)) {
            Calendar creationDate = document.getDocumentInformation().getCreationDate();
            assertEquals(2026, creationDate.get(Calendar.YEAR));
            assertEquals(Calendar.JULY, creationDate.get(Calendar.MONTH));
            assertEquals(22, creationDate.get(Calendar.DAY_OF_MONTH));
            assertEquals(13, creationDate.get(Calendar.HOUR_OF_DAY));
            assertEquals(0, creationDate.get(Calendar.MINUTE));
        }
    }

    @Test
    void wrapsALongWorkplaceAddressWithoutThrowing() throws IOException {
        ContractSnapshot longAddress = new ContractSnapshot(
                1L,
                "주말 홀 서빙",
                LocalDateTime.of(2026, 7, 22, 10, 0),
                LocalDateTime.of(2026, 7, 22, 18, 0),
                60,
                false,
                "기가 허브",
                "서울특별시 강남구 아주 아주 아주 아주 아주 아주 아주 아주 아주 긴 테스트 주소 12345번지 3층 401호",
                90_000L,
                "김사장",
                null,
                "이알바",
                null,
                3,
                LocalDateTime.of(2026, 7, 22, 13, 0),
                LocalDateTime.of(2026, 7, 1, 9, 0));

        byte[] pdf = renderer.render(longAddress);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("401호"));
        }
    }

    @Test
    void keepsASpaceFreeLongValueWithinThePageWidthInsteadOfOverflowingOffscreen()
            throws IOException {
        // 한글은 단어 단위 언어가 아니라 CJK 줄바꿈 규칙상 word-wrap 없이도 글자 사이에서
        // 자연히 줄이 바뀐다. 이 회귀는 라틴 문자·숫자처럼 공백 없이 이어지는 값에서만
        // 재현되므로 알파벳으로 Token을 만든다.
        String spaceFreeToken = "AB12".repeat(150);
        ContractSnapshot longToken = new ContractSnapshot(
                1L,
                "주말 홀 서빙",
                LocalDateTime.of(2026, 7, 22, 10, 0),
                LocalDateTime.of(2026, 7, 22, 18, 0),
                60,
                false,
                "기가 허브",
                spaceFreeToken,
                90_000L,
                "김사장",
                null,
                "이알바",
                null,
                3,
                LocalDateTime.of(2026, 7, 22, 13, 0),
                LocalDateTime.of(2026, 7, 1, 9, 0));

        byte[] pdf = renderer.render(longToken);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            float pageWidth = document.getPage(0).getMediaBox().getWidth();
            MaxXTextStripper stripper = new MaxXTextStripper();
            stripper.getText(document);
            assertTrue(
                    stripper.maxX <= pageWidth,
                    "텍스트가 페이지 폭(" + pageWidth + ") 밖으로 벗어났습니다: maxX=" + stripper.maxX);
        }
    }

    @Test
    void stripsXmlIllegalControlCharactersInsteadOfFailingToRender() throws IOException {
        String titleWithNulCharacter = "주말" + (char) 0 + "홍 서빙";
        ContractSnapshot controlCharacterInValue = new ContractSnapshot(
                1L,
                titleWithNulCharacter,
                LocalDateTime.of(2026, 7, 22, 10, 0),
                LocalDateTime.of(2026, 7, 22, 18, 0),
                60,
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
                LocalDateTime.of(2026, 7, 1, 9, 0));

        byte[] pdf = renderer.render(controlCharacterInValue);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("주말"));
            assertTrue(text.contains("홍 서빙"));
        }
    }

    /** 페이지 폭을 넘는 위치에 글자를 그리는 회귀를 잡기 위해 각 글자의 오른쪽 끝 좌표를 추적한다. */
    private static final class MaxXTextStripper extends PDFTextStripper {

        private float maxX;

        private MaxXTextStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            for (TextPosition position : textPositions) {
                maxX = Math.max(maxX, position.getXDirAdj() + position.getWidthDirAdj());
            }
        }
    }

    private ContractSnapshot snapshot() {
        return snapshotWithPhones(null, null);
    }

    private ContractSnapshot snapshotWithPhones(String employerPhone, String workerPhone) {
        return new ContractSnapshot(
                106L,
                "주말 홀 서빙",
                LocalDateTime.of(2026, 7, 22, 10, 0),
                LocalDateTime.of(2026, 7, 22, 18, 0),
                60,
                false,
                "기가 허브",
                "서울시 강남구 테스트로 1",
                90_000L,
                "김사장",
                employerPhone,
                "이알바",
                workerPhone,
                3,
                LocalDateTime.of(2026, 7, 22, 13, 0),
                LocalDateTime.of(2026, 7, 1, 9, 0));
    }
}
