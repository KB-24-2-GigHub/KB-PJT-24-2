package com.gighub.work.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 승인된 근무 {@code DRAFT} 등록·조건 수정 입력입니다.
 *
 * <p>API_SPEC 4.0.0이 {@code POST}와 {@code PATCH} Body를 같은 일곱 필드로 고정해 이 DTO를
 * 두 Endpoint가 함께 씁니다. {@code PATCH}도 일곱 필드를 모두 요구하며 생략·명시적
 * {@code null}은 검증 실패입니다.</p>
 *
 * <p>API_SPEC이 등록 Body를 {@code title}, {@code workDate}, {@code startTime},
 * {@code endTime}, {@code breakMinutes}, {@code breakPaid}, {@code dailyWage} 7개로
 * 고정했습니다. {@code employerId}, {@code workerId}, {@code status},
 * {@code termsVersion}, 사업장 Snapshot과 에스크로 금액은 서버가 정하므로 입력 필드로 두지
 * 않고, 보내면 {@link #rejectUnknownField}가 요청 오류로 거절합니다. 조용히 무시하면
 * 클라이언트는 값이 반영된 줄 알고 잘못된 화면을 그립니다.</p>
 *
 * <p>검증 경계는 Flyway Head 제약에서 가져왔습니다. {@code title}은 {@code VARCHAR(150)},
 * {@code breakMinutes}는 {@code SMALLINT UNSIGNED}의 표현 범위, {@code dailyWage}는
 * {@code ck_work_cases_wage}의 양수 조건입니다. 휴게 시간의 제품 상한은 명세에 없어 컬럼이
 * 담지 못하는 값만 막습니다.</p>
 *
 * <p>시작·종료 시각의 선후 관계는 두 값을 근무일과 결합한 뒤에야 판정할 수 있어 여기서
 * 검증하지 않습니다. 결합은 {@link com.gighub.work.domain.WorkCaseTimes}가 담당합니다.</p>
 *
 * <p>검증을 통과한 값이 이후에 바뀌지 않도록 모든 필드를 {@code final}로 두고, 정규화는
 * 생성자에서 한 번만 합니다. Setter를 두면 검증한 값과 저장되는 값이 달라질 수 있습니다.</p>
 */
@Getter
public final class WorkCaseCreateRequest {

    /** {@code DATETIME(6)}이 담을 수 있는 가장 이른 날입니다. */
    private static final LocalDate MIN_WORK_DATE = LocalDate.of(1000, 1, 1);

    /** 종료가 다음 날로 결합돼도 {@code DATETIME(6)} 안에 남는 마지막 날입니다. */
    private static final LocalDate MAX_WORK_DATE = LocalDate.of(9999, 12, 30);

    @NotBlank(message = "근무 제목은 필수입니다.")
    @Size(max = 150, message = "근무 제목은 150자 이하여야 합니다.")
    private final String title;

    @NotNull(message = "근무일은 필수입니다.")
    private final LocalDate workDate;

    @NotNull(message = "시작 시각은 필수입니다.")
    private final LocalTime startTime;

    @NotNull(message = "종료 시각은 필수입니다.")
    private final LocalTime endTime;

    @NotNull(message = "휴게 시간은 필수입니다.")
    @Min(value = 0, message = "휴게 시간은 0분 이상이어야 합니다.")
    @Max(value = 65535, message = "휴게 시간이 허용 범위를 넘었습니다.")
    private final Integer breakMinutes;

    @NotNull(message = "휴게 시간 유급 여부는 필수입니다.")
    private final Boolean breakPaid;

    @NotNull(message = "일급은 필수입니다.")
    @Min(value = 1, message = "일급은 1원 이상이어야 합니다.")
    private final Long dailyWage;

    @JsonCreator
    public WorkCaseCreateRequest(
            @JsonProperty("title") String title,
            @JsonProperty("workDate") LocalDate workDate,
            @JsonProperty("startTime") LocalTime startTime,
            @JsonProperty("endTime") LocalTime endTime,
            @JsonProperty("breakMinutes") Integer breakMinutes,
            @JsonProperty("breakPaid") Boolean breakPaid,
            @JsonProperty("dailyWage") Long dailyWage) {
        this.title = normalizeText(title);
        this.workDate = requireStorableWorkDate(workDate);
        this.startTime = startTime;
        this.endTime = endTime;
        this.breakMinutes = breakMinutes;
        this.breakPaid = breakPaid;
        this.dailyWage = dailyWage;
    }

    /** 명세에 없는 근무 조건 필드는 조용히 무시하지 않고 요청 오류로 처리합니다. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("허용되지 않은 근무 조건 필드입니다: " + fieldName);
    }

    /**
     * {@code starts_at}·{@code ends_at}이 {@code DATETIME(6)}이라 저장할 수 있는 근무일에
     * 한계가 있습니다. {@code LocalDate}는 그보다 훨씬 넓은 값을 받아들이므로 여기서 막습니다.
     *
     * <p>상한을 하루 앞당긴 것은 자정을 넘기는 근무의 종료가 {@code workDate}의 다음 날로
     * 결합되기 때문입니다(SPEC-413-01). {@code LocalDate.MAX} 같은 값이 들어오면 그 덧셈이
     * {@code DateTimeException}으로 터져 검증 실패가 아니라 500이 됩니다.</p>
     */
    private static LocalDate requireStorableWorkDate(LocalDate workDate) {
        if (workDate == null) {
            // @NotNull이 필수 누락으로 보고합니다 — 여기서 겹쳐 던지면 오류 형태가 갈립니다.
            return null;
        }
        if (workDate.isBefore(MIN_WORK_DATE) || workDate.isAfter(MAX_WORK_DATE)) {
            throw new IllegalArgumentException(
                    "근무일은 " + MIN_WORK_DATE + "부터 " + MAX_WORK_DATE + " 사이여야 합니다.");
        }
        return workDate;
    }

    /**
     * 앞뒤 공백만 제거합니다. 공백만 남는 입력은 {@code null}로 바꿔
     * {@code @NotBlank}가 필수 누락과 같은 오류로 처리하게 합니다.
     */
    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
