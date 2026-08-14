package com.gighub.document.contract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Calendar;

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
