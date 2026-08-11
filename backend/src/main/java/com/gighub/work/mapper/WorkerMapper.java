package com.gighub.work.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.gighub.work.mapper.param.WorkerWorkCaseListQuery;
import com.gighub.work.mapper.result.WorkerHomeCandidateRow;
import com.gighub.work.mapper.result.WorkerWorkCaseRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * WORKER 홈 요약과 본인 근무 이력의 SQL 진입점입니다.
 *
 * <p>{@code work_cases} DML은 {@link com.gighub.work.mapper.WorkCaseMapper}가 소유합니다. 이
 * Mapper는 WORKER 시점 읽기 전용 조합만 담당합니다.</p>
 */
@Mapper
public interface WorkerMapper {

    /**
     * 오늘 근무 후보 한 건을 우선순위대로 골라 읽습니다.
     *
     * <p>후보는 시작일이 오늘인 배정 근무와, 전날부터 남은
     * {@code IN_PROGRESS}·{@code CHECK_OUT_MISSING}입니다. 복수이면
     * {@code IN_PROGRESS, CHECK_OUT_MISSING, READY, ACCEPTED, COMPLETED, NO_SHOW} 순서, 같은
     * 상태에서는 {@code startsAt ASC, workCaseId ASC}로 정렬을 XML에 고정합니다.</p>
     *
     * <p>날짜가 아니라 {@code Asia/Seoul} 자정 경계 셋을 받습니다. {@code starts_at}에 함수를
     * 씌우지 않아야 Index Range Scan이 가능하고, 경계 계산을 Service 한 곳에 모아 SQL이
     * 시간대 규약을 다시 해석하지 않게 합니다.</p>
     *
     * @param carryOverStart 이월 후보를 허용하는 가장 이른 시각(어제 자정)
     * @param todayStart     오늘 자정. 이 시각 이후는 상태 제한 없이 후보가 됩니다
     * @param tomorrowStart  내일 자정. 후보 구간의 열린 상한입니다
     * @return 오늘 후보가 없으면 {@code null}
     */
    WorkerHomeCandidateRow findTodayCandidate(
            @Param("workerId") Long workerId,
            @Param("carryOverStart") LocalDateTime carryOverStart,
            @Param("todayStart") LocalDateTime todayStart,
            @Param("tomorrowStart") LocalDateTime tomorrowStart);

    /**
     * 인증 WORKER의 확정 이후 근무 목록 한 Page를 정렬이 고정된 순서로 조회합니다.
     *
     * <p>정렬은 {@code starts_at DESC, id DESC}로 고정됩니다. 범위는 배정이 확정된 이후 상태
     * 허용 목록으로 제한합니다. {@code worker_id}만으로는 부족합니다. 배정 뒤 취소된 근무는
     * {@code worker_id}가 남은 채 {@code CANCELED}가 되므로, 상태를 걸지 않으면 성립하지 않은
     * 근무가 본인 이력에 섞입니다.</p>
     */
    List<WorkerWorkCaseRow> findPage(WorkerWorkCaseListQuery query);

    /**
     * 같은 조건으로 전체 건수를 셉니다. Page Metadata의 {@code totalElements}에 씁니다.
     *
     * <p>{@link #findPage}와 같은 조건 조각을 공유해야 {@code totalElements}와 실제 반환된
     * {@code content}가 어긋나지 않습니다.</p>
     */
    long countByWorker(WorkerWorkCaseListQuery query);
}
