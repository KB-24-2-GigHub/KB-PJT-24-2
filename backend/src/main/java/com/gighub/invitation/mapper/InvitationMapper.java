package com.gighub.invitation.mapper;

import com.gighub.invitation.mapper.param.InvitationInsertParam;
import com.gighub.invitation.mapper.result.AcceptWorkCaseLockRow;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.mapper.result.InvitationWorkCaseLockRow;
import com.gighub.invitation.mapper.result.InvitationWorkCaseRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 근무 초대 행의 저장과 상태 전이 SQL 진입점입니다. */
@Mapper
public interface InvitationMapper {

    /**
     * 임시 Token Hash로 {@code PENDING} 초대를 만들고 생성된 식별자를 {@code param.id}에
     * 채웁니다.
     *
     * <p>Token 원문은 초대 ID에서 파생하므로 INSERT 시점에는 최종 Hash를 알 수 없습니다.
     * {@code token_hash}는 {@code NOT NULL UNIQUE}라 비워 둘 수도 없어, 충돌하지 않는 임시
     * 값으로 행을 만든 뒤 {@link #updateTokenHash(long, byte[])}로 확정합니다. 두 문장은 반드시
     * 같은 Transaction에 있어야 임시 Hash가 밖에서 보이지 않습니다.</p>
     *
     * <p>같은 Work Case에 활성 {@code PENDING}이 이미 있으면 {@code uk_work_invitations_active}가
     * 중복 예외를 던집니다. 사전 조회로는 동시 발급을 막을 수 없으므로 이 제약을 그대로
     * 활용하고 Service가 승인된 응답으로 바꿉니다.</p>
     */
    int insertPending(InvitationInsertParam param);

    /**
     * 확보한 초대 ID로 파생한 실제 Token Hash를 기록합니다.
     *
     * @param invitationId {@link #insertPending(InvitationInsertParam)}이 채운 식별자
     * @param tokenHash    Token 원문의 SHA-256 Hash 32byte
     * @return 갱신된 행 수
     */
    int updateTokenHash(
            @Param("invitationId") long invitationId,
            @Param("tokenHash") byte[] tokenHash);

    /**
     * Token Hash로 초대 한 건을 잠근 채 조회합니다.
     *
     * <p>조회 흐름이 만료된 {@code PENDING}을 같은 Transaction에서 {@code EXPIRED}로 전이하므로
     * 읽는 시점에 행을 잠급니다. 잠그지 않으면 두 요청이 같은 초대를 동시에 읽고 서로 다른
     * 판정을 내릴 수 있습니다.</p>
     *
     * <p>Token 원문이 아니라 Hash로 찾습니다. 저장소에 원문이 없고, SQL Parameter와 느린 Query
     * 로그에 원문이 남지 않게 하려는 의도도 있습니다.</p>
     *
     * @param tokenHash 요청이 전달한 Token 원문의 Hash
     * @return 일치하는 초대. 없으면 {@code null}
     */
    InvitationRow findByTokenHashForUpdate(@Param("tokenHash") byte[] tokenHash);

    /**
     * Work Case의 활성 {@code PENDING} 초대를 잠근 채 조회합니다.
     *
     * <p>{@code uk_work_invitations_active} 덕분에 결과는 최대 한 건입니다.</p>
     *
     * @param workCaseId 대상 근무 식별자
     * @return 활성 초대. 없으면 {@code null}
     */
    InvitationRow findActivePendingByWorkCaseIdForUpdate(@Param("workCaseId") long workCaseId);

    /**
     * Token Hash로 초대를 잠그지 않고 조회합니다.
     *
     * <p>수락은 멱등 Claim을 선점하기 <b>전에</b> Token의 존재와 대상 근무를 알아야 합니다.
     * 형식 오류·미존재를 Claim 없이 404로 끝내야 하고, 잠금 순서상 근무 행을 초대보다 먼저
     * 잠가야 하는데 그 근무 식별자가 이 조회에서 나옵니다.</p>
     *
     * <p>여기서 읽은 상태는 판정에 쓰지 않습니다. 잠그지 않았으므로 곧바로 낡을 수 있고,
     * 실제 판정은 잠금 뒤 {@link #lockInvitationById(long)}이 다시 읽은 값으로 합니다.</p>
     *
     * @param tokenHash 요청이 전달한 Token 원문의 Hash
     * @return 일치하는 초대. 없으면 {@code null}
     */
    InvitationRow findByTokenHash(@Param("tokenHash") byte[] tokenHash);

    /**
     * 초대 한 건을 식별자로 잠근 채 다시 읽습니다.
     *
     * <p>근무 행을 먼저 잠근 뒤에 부릅니다. Token Hash로 잠그면 조회 계획에 따라 근무보다
     * 초대를 먼저 잡을 수 있어, 조건 수정·발급 흐름과 잠금 순서가 엇갈립니다.</p>
     *
     * @param invitationId 대상 초대 식별자
     * @return 잠근 초대. 없으면 {@code null}
     */
    InvitationRow lockInvitationById(@Param("invitationId") long invitationId);

    /**
     * 수락 대상 근무 행을 잠그고 판정과 계약 Snapshot에 필요한 값을 함께 읽습니다.
     *
     * <p>잠금 순서는 <b>Claim → 근무 → 초대 → 지갑</b>입니다. 조건 수정(#154)과 초대 발급도
     * 근무 행을 먼저 잠그므로 세 흐름이 동시에 실행돼도 교착이 생기지 않습니다.</p>
     *
     * @param workCaseId 수락 대상 근무 식별자
     * @return 해당 근무가 없으면 {@code null}
     */
    AcceptWorkCaseLockRow lockWorkCaseForAccept(@Param("workCaseId") long workCaseId);

    /**
     * 초대를 {@code ACCEPTED}로 전이하고 수락 당사자·조건 Version·시각을 기록합니다.
     *
     * <p>{@code PENDING}일 때만 바뀝니다. 같은 Token을 여러 WORKER가 동시에 수락하면 이
     * 갱신이 1행을 바꾼 쪽만 당사자가 되고 나머지는 0행으로 패배를 확인합니다. 상태 조건을
     * Java의 if로 옮기면 확인과 갱신 사이가 벌어져 두 요청이 모두 통과할 수 있습니다.</p>
     *
     * @return 전이된 행 수. 이미 종료된 초대면 0
     */
    int markAccepted(
            @Param("invitationId") long invitationId,
            @Param("acceptedByUserId") long acceptedByUserId,
            @Param("acceptedTermsVersion") int acceptedTermsVersion,
            @Param("acceptedAt") LocalDateTime acceptedAt);

    /**
     * 근무에 WORKER를 연결하고 {@code DRAFT}에서 {@code ACCEPTED}로 전이합니다.
     *
     * <p>{@code DRAFT}이고 아직 미매칭일 때만 바뀝니다. 스키마의
     * {@code ck_work_cases_matched_worker}가 {@code ACCEPTED}에 WORKER를 요구하므로 두 변경을
     * 한 문장에서 함께 적용합니다.</p>
     *
     * @return 전이된 행 수. 상태나 매칭이 어긋나면 0
     */
    /**
     * 발급 대상 근무 행을 잠그고 발급 가능 여부의 판단 근거를 읽습니다.
     *
     * <p>반드시 Transaction 안에서, 그리고 초대 행을 잠그기 <b>전에</b> 부릅니다. 조건 수정
     * 흐름(#154)도 근무 행을 먼저 잠그므로 두 흐름의 잠금 순서가 같아 교착이 생기지
     * 않습니다.</p>
     *
     * <p>{@code work_cases}를 근무 도메인 Mapper 대신 여기서 읽는 이유는 조회 흐름과 같습니다.
     * 초대가 필요한 열만 읽는 자기 SQL을 두면 두 도메인이 같은 파일을 함께 고치지 않습니다.</p>
     *
     * @param workCaseId 발급 대상 근무 식별자
     * @return 해당 근무가 없으면 {@code null}
     */
    InvitationWorkCaseLockRow lockWorkCaseForIssue(@Param("workCaseId") long workCaseId);

    /**
     * 초대가 가리키는 근무의 현재 조건을 조회합니다.
     *
     * <p>초대 행을 이미 잠근 뒤에 호출하므로 여기서는 잠그지 않습니다. 근무 조건을 바꾸는
     * Transaction은 같은 Transaction에서 활성 초대를 철회해야 하고, 그 철회가 우리가 잡은 초대
     * 잠금에서 대기하므로 조건과 초대 상태가 어긋난 채로 읽히지 않습니다.</p>
     *
     * @param workCaseId 초대가 가리키는 근무 식별자
     * @return 근무의 현재 조건. 없으면 {@code null}
     */
    InvitationWorkCaseRow findWorkCaseForInvitation(@Param("workCaseId") long workCaseId);

    /**
     * 초대 한 건을 {@code EXPIRED}로 전이합니다.
     *
     * <p>{@code PENDING}일 때만 바뀝니다. 이미 수락·철회된 초대를 만료로 덮으면 종료 사유가
     * 사라지고, 동시에 들어온 다른 전이를 조용히 지웁니다.</p>
     *
     * @param invitationId 대상 초대 식별자
     * @return 전이된 행 수. 이미 다른 상태면 0
     */
    int markExpired(@Param("invitationId") long invitationId);

    /**
     * Work Case에서 만료 시각이 지난 {@code PENDING}을 {@code EXPIRED}로 전이합니다.
     *
     * <p>발급 흐름이 활성 초대를 찾기 전에 실행합니다. 만료된 행이 {@code PENDING}으로 남아
     * 있으면 활성 초대 Unique 제약이 새 발급을 막습니다.</p>
     *
     * @param workCaseId 대상 근무 식별자
     * @param now        Asia/Seoul 기준 현재 시각
     * @return 전이된 행 수
     */
    int expireOverduePending(
            @Param("workCaseId") long workCaseId,
            @Param("now") LocalDateTime now);

    /**
     * Work Case의 활성 {@code PENDING} 초대를 {@code REVOKED}로 전이합니다.
     *
     * <p>근무 조건 수정, 취소, 명시적 재발급이 이전 Link를 무효화할 때 사용합니다. 행을
     * 지우지 않고 상태로 남기므로 이전 Link는 미존재가 아니라 철회로 응답할 수 있고 초대
     * 이력도 보존됩니다.</p>
     *
     * <p>조건 변경 Transaction에서 함께 호출해야 합니다. 따로 실행하면 새 조건과 이전 Link가
     * 잠시 동시에 유효해집니다.</p>
     *
     * @param workCaseId 대상 근무 식별자
     * @param revokedAt  Asia/Seoul 기준 철회 시각
     * @return 전이된 행 수
     */
    int revokePendingByWorkCaseId(
            @Param("workCaseId") long workCaseId,
            @Param("revokedAt") LocalDateTime revokedAt);

    /** Work 조건 수정·취소가 현재 DB 시각으로 활성 초대를 철회할 때 사용합니다. */
    int revokePendingByWorkCaseIdNow(@Param("workCaseId") long workCaseId);
}
