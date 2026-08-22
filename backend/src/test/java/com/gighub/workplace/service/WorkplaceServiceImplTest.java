package com.gighub.workplace.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.gighub.attendance.service.WorkplaceQrIssuer;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.UserRole;
import com.gighub.workplace.dto.WorkplaceListItemResponse;
import com.gighub.workplace.exception.WorkplaceCoordinatesAlreadySetException;
import com.gighub.workplace.exception.WorkplaceGeocodingException;
import com.gighub.workplace.geocoding.AddressGeocoder;
import com.gighub.workplace.geocoding.GeocodedCoordinates;
import com.gighub.workplace.mapper.WorkplaceMapper;
import com.gighub.workplace.mapper.param.WorkplaceInsertParam;
import com.gighub.workplace.mapper.param.WorkplaceUpdateParam;
import com.gighub.workplace.mapper.result.WorkplaceListRow;
import com.gighub.workplace.service.command.WorkplaceCoordinateConfirmCommand;
import com.gighub.workplace.service.command.WorkplaceCreateCommand;
import com.gighub.workplace.service.command.WorkplaceUpdateCommand;
import com.gighub.workplace.service.impl.WorkplaceServiceImpl;
import com.gighub.workplace.service.result.WorkplaceLocationSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkplaceServiceImplTest {

    private static final BigDecimal GEOCODED_LATITUDE = new BigDecimal("37.1234567");
    private static final BigDecimal GEOCODED_LONGITUDE = new BigDecimal("127.1234567");
    private static final BigDecimal DEFAULT_RADIUS_METERS = new BigDecimal("100.00");
    private static final GeocodedCoordinates GEOCODED =
            new GeocodedCoordinates(GEOCODED_LATITUDE, GEOCODED_LONGITUDE);

    private final WorkplaceMapper workplaceMapper = mock(WorkplaceMapper.class);
    private final WorkplaceQrIssuer qrIssuer = mock(WorkplaceQrIssuer.class);
    private final AddressGeocoder addressGeocoder = mock(AddressGeocoder.class);
    /** 저장 경계만 검증하므로 Callback을 그대로 실행하는 Template을 씁니다. */
    private final TransactionTemplate transactionTemplate =
            new TransactionTemplate(new PseudoTransactionManager());
    private final WorkplaceServiceImpl service = new WorkplaceServiceImpl(
            workplaceMapper, qrIssuer, addressGeocoder, transactionTemplate);

    WorkplaceServiceImplTest() {
        when(addressGeocoder.geocode(any())).thenReturn(GEOCODED);
    }

    @Test
    void issuesFixedQrForTheNewWorkplaceWithinTheSameCall() {
        doAnswer(invocation -> {
            invocation.getArgument(0, WorkplaceInsertParam.class).setId(42L);
            return 1;
        }).when(workplaceMapper).insert(any(WorkplaceInsertParam.class));

        service.create(owner(7L), validCommand());

        verify(qrIssuer).issueActive(42L, 7L);
    }

    @Test
    void doesNotIssueQrWhenWorkplaceInsertFails() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(workplaceMapper).insert(any(WorkplaceInsertParam.class));

        assertThrows(ConflictException.class, () -> service.create(owner(7L), validCommand()));

        verifyNoInteractions(qrIssuer);
    }

    @Test
    void storesOwnerFromPrincipalAndReturnsGeneratedIdentifier() {
        doAnswer(invocation -> {
            invocation.getArgument(0, WorkplaceInsertParam.class).setId(42L);
            return 1;
        }).when(workplaceMapper).insert(any(WorkplaceInsertParam.class));

        Long workplaceId = service.create(owner(7L), validCommand());

        assertEquals(42L, workplaceId);

        ArgumentCaptor<WorkplaceInsertParam> captor =
                ArgumentCaptor.forClass(WorkplaceInsertParam.class);
        verify(workplaceMapper).insert(captor.capture());

        WorkplaceInsertParam param = captor.getValue();
        assertEquals(7L, param.getOwnerUserId());
        assertEquals("1234567890", param.getBusinessRegistrationNumber());
        assertEquals("강남점", param.getName());
        assertEquals("김사장", param.getRepresentativeName());
        assertEquals("서울 강남구 테헤란로 1", param.getRoadAddress());
        assertEquals("2층", param.getDetailAddress());
        assertEquals("0212345678", param.getPhone());
        assertEquals(0, new BigDecimal("37.1234567").compareTo(param.getLatitude()));
        assertEquals(0, new BigDecimal("127.1234567").compareTo(param.getLongitude()));
    }

    @Test
    void rejectsNonOwnerBeforeTouchingStorage() {
        AuthPrincipal worker = new AuthPrincipal(9L, UserRole.WORKER, "김근로");

        assertThrows(RoleMismatchException.class, () -> service.create(worker, validCommand()));
        verify(workplaceMapper, never()).insert(any(WorkplaceInsertParam.class));
    }

    @Test
    void translatesUniqueViolationIntoApprovedConflict() {
        doThrow(new DuplicateKeyException("uk_workplaces_business_registration_number"))
                .when(workplaceMapper).insert(any(WorkplaceInsertParam.class));

        assertThrows(ConflictException.class, () -> service.create(owner(7L), validCommand()));
    }

    /**
     * 상세주소는 선택값이므로 없는 요청도 그대로 저장 파라미터에 전달돼야 합니다.
     *
     * <p>좌표는 더 이상 선택값이 아닙니다(SPEC-343-01). 요청이 좌표를 담지 않아도 서버가
     * 주소로 확정한 값이 저장됩니다.</p>
     */
    @Test
    void keepsOptionalValuesAbsentInsteadOfSubstituting() {
        doAnswer(invocation -> {
            invocation.getArgument(0, WorkplaceInsertParam.class).setId(43L);
            return 1;
        }).when(workplaceMapper).insert(any(WorkplaceInsertParam.class));

        service.create(owner(7L), WorkplaceCreateCommand.builder()
                .businessRegistrationNumber("1234567890")
                .name("강남점")
                .representativeName("김사장")
                .roadAddress("서울 강남구 테헤란로 1")
                .phone("0212345678")
                .build());

        ArgumentCaptor<WorkplaceInsertParam> captor =
                ArgumentCaptor.forClass(WorkplaceInsertParam.class);
        verify(workplaceMapper).insert(captor.capture());

        WorkplaceInsertParam param = captor.getValue();
        assertEquals(null, param.getDetailAddress());
        assertEquals(0, GEOCODED.latitude().compareTo(param.getLatitude()));
        assertEquals(0, GEOCODED.longitude().compareTo(param.getLongitude()));
    }

    /**
     * 좌표의 출처는 요청이 아니라 도로명주소 변환 결과입니다(SPEC-343-01).
     *
     * <p>요청 필드로는 좌표를 받지 않으므로(DTO가 거절) 저장되는 값이 변환 결과와 같은지만
     * 확인합니다. 변환에 넘긴 주소가 요청 주소와 다르면 엉뚱한 지점이 기준점이 됩니다.</p>
     */
    @Test
    void storesCoordinatesResolvedFromRequestedAddress() {
        doAnswer(invocation -> {
            invocation.getArgument(0, WorkplaceInsertParam.class).setId(44L);
            return 1;
        }).when(workplaceMapper).insert(any(WorkplaceInsertParam.class));

        service.create(owner(7L), WorkplaceCreateCommand.builder()
                .businessRegistrationNumber("1234567890")
                .name("강남점")
                .representativeName("김사장")
                .roadAddress("서울 강남구 테헤란로 1")
                .phone("0212345678")
                .build());

        ArgumentCaptor<WorkplaceInsertParam> captor =
                ArgumentCaptor.forClass(WorkplaceInsertParam.class);
        verify(workplaceMapper).insert(captor.capture());

        WorkplaceInsertParam param = captor.getValue();
        assertEquals(0, GEOCODED.latitude().compareTo(param.getLatitude()));
        assertEquals(0, GEOCODED.longitude().compareTo(param.getLongitude()));
        verify(addressGeocoder).geocode("서울 강남구 테헤란로 1");
    }

    /** 확정할 수 없는 주소는 422이고 사업장 행도 활성 QR도 남지 않아야 합니다. */
    @Test
    void doesNotStoreAnythingWhenAddressCannotBeResolved() {
        when(addressGeocoder.geocode(any()))
                .thenThrow(WorkplaceGeocodingException.addressNotResolvable());

        WorkplaceGeocodingException exception = assertThrows(
                WorkplaceGeocodingException.class, () -> service.create(owner(7L), validCommand()));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatus());
        assertEquals(ApiErrorCode.WORKPLACE_ADDRESS_NOT_RESOLVABLE, exception.getCode());
        verify(workplaceMapper, never()).insert(any(WorkplaceInsertParam.class));
        verifyNoInteractions(qrIssuer);
    }

    /**
     * 외부 서비스 장애는 503이고 저장은 시작되지 않아야 합니다.
     *
     * <p>주소 오류와 상태·Code가 달라야 화면이 재시도 가능 여부를 구분해 안내할 수 있습니다.</p>
     */
    @Test
    void doesNotStoreAnythingWhenGeocodingServiceIsUnavailable() {
        when(addressGeocoder.geocode(any()))
                .thenThrow(WorkplaceGeocodingException.temporarilyUnavailable());

        WorkplaceGeocodingException exception = assertThrows(
                WorkplaceGeocodingException.class, () -> service.create(owner(7L), validCommand()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals(
                ApiErrorCode.WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE, exception.getCode());
        verify(workplaceMapper, never()).insert(any(WorkplaceInsertParam.class));
        verifyNoInteractions(qrIssuer);
    }

    /** 역할 거절이 외부 호출보다 먼저입니다 — 권한 없는 호출자가 외부 Quota를 소모할 수 없습니다. */
    @Test
    void rejectsNonOwnerBeforeCallingGeocoder() {
        AuthPrincipal worker = new AuthPrincipal(9L, UserRole.WORKER, "김근로");

        assertThrows(RoleMismatchException.class, () -> service.create(worker, validCommand()));
        verifyNoInteractions(addressGeocoder);
    }

    /** 요청한 Page 값이 그대로 SQL 경계와 응답 Metadata에 반영돼야 합니다. */
    @Test
    void translatesRequestedPageIntoQueryBoundsAndMetadata() {
        when(workplaceMapper.countByOwnerUserId(7L)).thenReturn(3);
        when(workplaceMapper.findPageByOwnerUserId(7L, 2, 2L))
                .thenReturn(List.of(row(11L, "ACTIVE", true)));

        PageResponse<WorkplaceListItemResponse> response = service.findOwnedWorkplaces(owner(7L), 1, 2);

        verify(workplaceMapper).findPageByOwnerUserId(7L, 2, 2L);
        assertEquals(1, response.getPage().getNumber());
        assertEquals(2, response.getPage().getSize());
        assertEquals(3L, response.getPage().getTotalElements());
        assertEquals(2, response.getPage().getTotalPages());
        assertEquals(1, response.getContent().size());
    }

    /** 저장 정밀도(DECIMAL)와 상태 값이 승인된 응답 형태로 옮겨져야 합니다. */
    @Test
    void mapsRowColumnsIntoApprovedItemFields() {
        when(workplaceMapper.countByOwnerUserId(7L)).thenReturn(1);
        when(workplaceMapper.findPageByOwnerUserId(7L, 20, 0L))
                .thenReturn(List.of(row(11L, "INACTIVE", true)));

        WorkplaceListItemResponse item =
                service.findOwnedWorkplaces(owner(7L), 0, 20).getContent().get(0);

        assertEquals(11L, item.getWorkplaceId());
        assertEquals("1234567890", item.getBusinessRegistrationNumber());
        assertEquals("강남점", item.getName());
        assertEquals("김사장", item.getRepresentativeName());
        assertEquals("서울 강남구 테헤란로 1", item.getRoadAddress());
        assertEquals("2층", item.getDetailAddress());
        assertEquals("0212345678", item.getPhone());
        assertEquals(100, item.getRadiusMeters(), "DECIMAL(8,2)가 아니라 명세의 정수 100이어야 합니다.");
        assertTrue(item.isAttendanceLocationConfirmed());
        assertEquals("INACTIVE", item.getStatus(), "INACTIVE 사업장도 상태를 그대로 노출합니다.");
    }

    /** 좌표가 비어 있는 사업장은 목록에서 미확정으로 노출돼야 합니다. */
    @Test
    void reportsUnconfirmedLocationWhenRowHasNoCoordinates() {
        when(workplaceMapper.countByOwnerUserId(7L)).thenReturn(1);
        when(workplaceMapper.findPageByOwnerUserId(7L, 20, 0L))
                .thenReturn(List.of(row(12L, "ACTIVE", false)));

        WorkplaceListItemResponse item =
                service.findOwnedWorkplaces(owner(7L), 0, 20).getContent().get(0);

        assertFalse(item.isAttendanceLocationConfirmed());
    }

    @Test
    void rejectsNonOwnerBeforeQueryingList() {
        AuthPrincipal worker = new AuthPrincipal(9L, UserRole.WORKER, "김근로");

        assertThrows(RoleMismatchException.class, () -> service.findOwnedWorkplaces(worker, 0, 20));
        verifyNoInteractions(workplaceMapper);
    }

    /**
     * 권한 없는 호출자에게는 Page 규칙보다 역할 거절이 먼저입니다.
     *
     * <p>순서가 뒤집히면 WORKER가 400과 403을 구분해 Endpoint의 Query 규칙을 알아낼 수 있습니다.</p>
     */
    @Test
    void rejectsNonOwnerEvenWhenPageBoundsAreAlsoInvalid() {
        AuthPrincipal worker = new AuthPrincipal(9L, UserRole.WORKER, "김근로");

        assertThrows(RoleMismatchException.class, () -> service.findOwnedWorkplaces(worker, -1, 101));
        verifyNoInteractions(workplaceMapper);
    }

    @Test
    void rejectsPageBoundaryViolationBeforeQueryingList() {
        assertThrows(ValidationException.class, () -> service.findOwnedWorkplaces(owner(7L), 0, 101));
        assertThrows(ValidationException.class, () -> service.findOwnedWorkplaces(owner(7L), 0, 0));
        assertThrows(ValidationException.class, () -> service.findOwnedWorkplaces(owner(7L), -1, 20));

        verify(workplaceMapper, never()).findPageByOwnerUserId(anyLong(), anyInt(), anyLong());
        verify(workplaceMapper, never()).countByOwnerUserId(anyLong());
    }

    /** 첫 등록 전 OWNER는 오류가 아니라 빈 Page입니다. */
    @Test
    void returnsEmptyPageForOwnerWithoutWorkplaces() {
        when(workplaceMapper.countByOwnerUserId(7L)).thenReturn(0);
        when(workplaceMapper.findPageByOwnerUserId(7L, 20, 0L)).thenReturn(List.of());

        PageResponse<WorkplaceListItemResponse> response = service.findOwnedWorkplaces(owner(7L), 0, 20);

        assertTrue(response.getContent().isEmpty());
        assertEquals(0L, response.getPage().getTotalElements());
        assertEquals(0, response.getPage().getTotalPages());
    }

    @Test
    void confirmsLocationOnFirstRequestForOwnedActiveWorkplaceWithoutCoordinates() {
        when(workplaceMapper.findOwnedActiveLocationForUpdate(11L, 7L))
                .thenReturn(new WorkplaceLocationSnapshot(
                        11L, null, null, DEFAULT_RADIUS_METERS));
        when(workplaceMapper.confirmCoordinates(11L, GEOCODED_LATITUDE, GEOCODED_LONGITUDE))
                .thenReturn(1);

        service.confirmLocation(owner(7L), 11L, confirmCommand(GEOCODED_LATITUDE, GEOCODED_LONGITUDE));

        verify(workplaceMapper).confirmCoordinates(11L, GEOCODED_LATITUDE, GEOCODED_LONGITUDE);
    }

    /**
     * 갱신된 행이 없는데 성공으로 끝나면 아무것도 저장하지 않고 204가 나갑니다.
     *
     * <p>행을 잠근 뒤라 실제로는 일어날 수 없지만, 잠금이나 순서가 바뀌었을 때 이 조용한
     * 실패를 그대로 통과시키지 않도록 확인합니다.</p>
     */
    @Test
    void failsLoudlyWhenConfirmUpdatesNoRow() {
        when(workplaceMapper.findOwnedActiveLocationForUpdate(11L, 7L))
                .thenReturn(new WorkplaceLocationSnapshot(
                        11L, null, null, DEFAULT_RADIUS_METERS));
        when(workplaceMapper.confirmCoordinates(anyLong(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> service.confirmLocation(
                        owner(7L), 11L, confirmCommand(GEOCODED_LATITUDE, GEOCODED_LONGITUDE)));
    }

    /**
     * 같은 정규화 좌표의 재요청은 응답 유실 재시도이므로 다시 성공해야 합니다.
     *
     * <p>DB 저장 정밀도와 요청 값의 소수 자릿수가 다를 수 있어 다른 Scale의 같은 값으로
     * 재요청해도 같은 값으로 판정돼야 합니다.</p>
     */
    @Test
    void treatsSameNormalizedCoordinatesAsIdempotentReplay() {
        when(workplaceMapper.findOwnedActiveLocationForUpdate(11L, 7L))
                .thenReturn(new WorkplaceLocationSnapshot(
                        11L, GEOCODED_LATITUDE, GEOCODED_LONGITUDE, DEFAULT_RADIUS_METERS));

        service.confirmLocation(
                owner(7L),
                11L,
                confirmCommand(new BigDecimal("37.12345670"), new BigDecimal("127.12345670")));

        verify(workplaceMapper, never())
                .confirmCoordinates(anyLong(), any(BigDecimal.class), any(BigDecimal.class));
    }

    /** 이미 확정된 좌표를 보호해야 하므로 다른 값의 재요청은 409여야 합니다. */
    @Test
    void rejectsDifferentCoordinatesWhenAlreadyConfirmed() {
        when(workplaceMapper.findOwnedActiveLocationForUpdate(11L, 7L))
                .thenReturn(new WorkplaceLocationSnapshot(
                        11L, GEOCODED_LATITUDE, GEOCODED_LONGITUDE, DEFAULT_RADIUS_METERS));

        WorkplaceCoordinateConfirmCommand differentValue =
                confirmCommand(new BigDecimal("1.0000000"), new BigDecimal("2.0000000"));

        assertThrows(
                WorkplaceCoordinatesAlreadySetException.class,
                () -> service.confirmLocation(owner(7L), 11L, differentValue));
        verify(workplaceMapper, never())
                .confirmCoordinates(anyLong(), any(BigDecimal.class), any(BigDecimal.class));
    }

    /** 없는 사업장과 다른 OWNER의 사업장을 구분하지 않고 404여야 합니다. */
    @Test
    void reportsNotFoundWhenWorkplaceIsNotOwnedOrNotActive() {
        when(workplaceMapper.findOwnedActiveLocationForUpdate(11L, 7L)).thenReturn(null);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.confirmLocation(
                        owner(7L), 11L, confirmCommand(GEOCODED_LATITUDE, GEOCODED_LONGITUDE)));
    }

    /** 오래된 측정값은 사업장 상태와 무관하게 거절해야 하므로 잠금보다 먼저 검사합니다. */
    @Test
    void rejectsStaleCapturedAtBeforeLockingTheWorkplace() {
        WorkplaceCoordinateConfirmCommand stale = WorkplaceCoordinateConfirmCommand.builder()
                .latitude(GEOCODED_LATITUDE)
                .longitude(GEOCODED_LONGITUDE)
                .capturedAt(Instant.now().minus(Duration.ofMinutes(10)))
                .build();

        assertThrows(
                ValidationException.class, () -> service.confirmLocation(owner(7L), 11L, stale));
        verifyNoInteractions(workplaceMapper);
    }

    @Test
    void rejectsNonOwnerBeforeConfirmingLocation() {
        AuthPrincipal worker = new AuthPrincipal(9L, UserRole.WORKER, "김근로");

        assertThrows(
                RoleMismatchException.class,
                () -> service.confirmLocation(
                        worker, 11L, confirmCommand(GEOCODED_LATITUDE, GEOCODED_LONGITUDE)));
        verifyNoInteractions(workplaceMapper);
    }

    /**
     * 주소가 바뀌면 새 주소로 확정한 좌표가 같은 갱신에 함께 실려야 합니다(SPEC-349-01).
     *
     * <p>변환 근거였던 저장 주소가 갱신 조건으로 넘어가는지도 함께 확인합니다. 이 값이 빠지면
     * 변환 중 다른 요청이 바꾼 주소를 이전 주소로 구한 좌표가 덮어씁니다.</p>
     */
    @Test
    void recalculatesCoordinatesWhenRoadAddressChanges() {
        when(workplaceMapper.findOwnedActiveRoadAddress(11L, 7L))
                .thenReturn("서울 강남구 테헤란로 1");
        when(workplaceMapper.updateOwnedActive(any(WorkplaceUpdateParam.class))).thenReturn(1);

        service.update(owner(7L), 11L, WorkplaceUpdateCommand.builder()
                .roadAddressProvided(true)
                .roadAddress("서울 강남구 테헤란로 2")
                .build());

        verify(addressGeocoder).geocode("서울 강남구 테헤란로 2");

        WorkplaceUpdateParam param = capturedUpdateParam();
        assertEquals("서울 강남구 테헤란로 2", param.getRoadAddress());
        assertEquals(0, GEOCODED_LATITUDE.compareTo(param.getLatitude()));
        assertEquals(0, GEOCODED_LONGITUDE.compareTo(param.getLongitude()));
        assertEquals("서울 강남구 테헤란로 1", param.getExpectedRoadAddress());
    }

    /** 같은 주소를 다시 보낸 수정까지 외부 변환을 부르면 상호만 바꾸는 수정도 장애에 묶입니다. */
    @Test
    void doesNotCallGeocoderWhenRoadAddressIsUnchanged() {
        when(workplaceMapper.findOwnedActiveRoadAddress(11L, 7L))
                .thenReturn("서울 강남구 테헤란로 1");
        when(workplaceMapper.updateOwnedActive(any(WorkplaceUpdateParam.class))).thenReturn(1);

        service.update(owner(7L), 11L, WorkplaceUpdateCommand.builder()
                .nameProvided(true)
                .name("강남 2호점")
                .roadAddressProvided(true)
                .roadAddress("서울 강남구 테헤란로 1")
                .build());

        verifyNoInteractions(addressGeocoder);

        WorkplaceUpdateParam param = capturedUpdateParam();
        assertNull(param.getLatitude());
        assertNull(param.getLongitude());
        assertNull(param.getExpectedRoadAddress(), "좌표를 바꾸지 않는 수정은 주소 조건을 걸지 않습니다.");
    }

    /**
     * 같은 주소를 다시 보낸 요청은 {@code road_address}를 아예 쓰지 않아야 합니다.
     *
     * <p>이 요청은 좌표를 다시 확정하지 않으므로 갱신 조건도 없습니다. 그 상태에서 주소를 다시
     * 쓰면 다음 순서로 주소와 좌표가 어긋납니다.</p>
     *
     * <ol>
     *   <li>요청 A가 저장 주소 {@code old}를 읽고 같은 값을 보내 변환을 생략합니다.</li>
     *   <li>요청 B가 주소를 {@code new}로 바꾸고 좌표도 {@code new} 기준으로 갱신합니다.</li>
     *   <li>요청 A가 조건 없이 {@code road_address = old}만 되돌립니다.</li>
     * </ol>
     *
     * <p>결과는 주소 {@code old} + 좌표 {@code new}이며, 좌표가 주소의 파생값이라는
     * SPEC-349-01의 핵심 불변식이 깨집니다.</p>
     */
    @Test
    void doesNotRewriteRoadAddressWhenRequestRepeatsStoredAddress() {
        when(workplaceMapper.findOwnedActiveRoadAddress(11L, 7L))
                .thenReturn("서울 강남구 테헤란로 1");
        when(workplaceMapper.updateOwnedActive(any(WorkplaceUpdateParam.class))).thenReturn(1);

        service.update(owner(7L), 11L, WorkplaceUpdateCommand.builder()
                .nameProvided(true)
                .name("강남 2호점")
                .roadAddressProvided(true)
                .roadAddress("서울 강남구 테헤란로 1")
                .build());

        WorkplaceUpdateParam param = capturedUpdateParam();
        assertFalse(
                param.isRoadAddressProvided(),
                "같은 주소 요청은 road_address 를 SET 대상에서 빼야 다른 요청의 주소를 되돌리지 않습니다.");
        assertNull(param.getExpectedRoadAddress());
        assertTrue(param.isNameProvided(), "함께 보낸 다른 필드는 그대로 반영돼야 합니다.");
    }

    @Test
    void doesNotCallGeocoderWhenRoadAddressIsAbsent() {
        when(workplaceMapper.findOwnedActiveRoadAddress(11L, 7L))
                .thenReturn("서울 강남구 테헤란로 1");
        when(workplaceMapper.updateOwnedActive(any(WorkplaceUpdateParam.class))).thenReturn(1);

        service.update(owner(7L), 11L, WorkplaceUpdateCommand.builder()
                .phoneProvided(true)
                .phone("0212345679")
                .build());

        verifyNoInteractions(addressGeocoder);
        assertFalse(capturedUpdateParam().isRoadAddressProvided());
    }

    /** 상세주소는 명시적 삭제가 정상 입력이라 값이 아니라 존재 여부로 전달돼야 합니다. */
    @Test
    void carriesExplicitDetailAddressRemovalIntoUpdate() {
        when(workplaceMapper.findOwnedActiveRoadAddress(11L, 7L))
                .thenReturn("서울 강남구 테헤란로 1");
        when(workplaceMapper.updateOwnedActive(any(WorkplaceUpdateParam.class))).thenReturn(1);

        service.update(owner(7L), 11L, WorkplaceUpdateCommand.builder()
                .detailAddressProvided(true)
                .detailAddress(null)
                .build());

        WorkplaceUpdateParam param = capturedUpdateParam();
        assertTrue(param.isDetailAddressProvided());
        assertNull(param.getDetailAddress());
    }

    /** 변환할 수 없는 새 주소는 422이고 주소도 좌표도 바뀌지 않아야 합니다. */
    @Test
    void doesNotUpdateAnythingWhenNewAddressCannotBeResolved() {
        when(workplaceMapper.findOwnedActiveRoadAddress(11L, 7L))
                .thenReturn("서울 강남구 테헤란로 1");
        when(addressGeocoder.geocode(any()))
                .thenThrow(WorkplaceGeocodingException.addressNotResolvable());

        WorkplaceGeocodingException exception = assertThrows(
                WorkplaceGeocodingException.class,
                () -> service.update(owner(7L), 11L, addressChangeCommand()));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatus());
        assertEquals(ApiErrorCode.WORKPLACE_ADDRESS_NOT_RESOLVABLE, exception.getCode());
        verify(workplaceMapper, never()).updateOwnedActive(any(WorkplaceUpdateParam.class));
    }

    /** 외부 장애는 503이고, 주소만 바뀌고 좌표가 과거 위치에 남는 상태를 만들면 안 됩니다. */
    @Test
    void doesNotUpdateAnythingWhenGeocodingServiceIsUnavailableDuringUpdate() {
        when(workplaceMapper.findOwnedActiveRoadAddress(11L, 7L))
                .thenReturn("서울 강남구 테헤란로 1");
        when(addressGeocoder.geocode(any()))
                .thenThrow(WorkplaceGeocodingException.temporarilyUnavailable());

        WorkplaceGeocodingException exception = assertThrows(
                WorkplaceGeocodingException.class,
                () -> service.update(owner(7L), 11L, addressChangeCommand()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals(
                ApiErrorCode.WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE, exception.getCode());
        verify(workplaceMapper, never()).updateOwnedActive(any(WorkplaceUpdateParam.class));
    }

    /** 없는 사업장과 다른 OWNER의 사업장을 구분하지 않고 404여야 합니다. */
    @Test
    void reportsNotFoundWhenUpdateTargetIsNotOwnedOrNotActive() {
        when(workplaceMapper.findOwnedActiveRoadAddress(11L, 7L)).thenReturn(null);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.update(owner(7L), 11L, addressChangeCommand()));

        verifyNoInteractions(addressGeocoder);
        verify(workplaceMapper, never()).updateOwnedActive(any(WorkplaceUpdateParam.class));
    }

    /**
     * 변환 중 다른 요청이 주소를 바꾸면 갱신 조건이 0행을 만들고, 그때 성공으로 끝내면 안 됩니다.
     *
     * <p>0행을 흘리면 아무것도 저장하지 않은 204가 나가 호출자는 수정이 반영됐다고 믿습니다.</p>
     */
    @Test
    void reportsConflictWhenStoredAddressChangedWhileGeocoding() {
        when(workplaceMapper.findOwnedActiveRoadAddress(11L, 7L))
                .thenReturn("서울 강남구 테헤란로 1");
        when(workplaceMapper.updateOwnedActive(any(WorkplaceUpdateParam.class))).thenReturn(0);

        assertThrows(
                ConflictException.class,
                () -> service.update(owner(7L), 11L, addressChangeCommand()));
    }

    /** 역할 거절이 조회·외부 호출보다 먼저입니다. */
    @Test
    void rejectsNonOwnerBeforeReadingOrGeocodingOnUpdate() {
        AuthPrincipal worker = new AuthPrincipal(9L, UserRole.WORKER, "김근로");

        assertThrows(
                RoleMismatchException.class,
                () -> service.update(worker, 11L, addressChangeCommand()));

        verifyNoInteractions(workplaceMapper);
        verifyNoInteractions(addressGeocoder);
    }

    private WorkplaceUpdateParam capturedUpdateParam() {
        ArgumentCaptor<WorkplaceUpdateParam> captor =
                ArgumentCaptor.forClass(WorkplaceUpdateParam.class);
        verify(workplaceMapper).updateOwnedActive(captor.capture());
        return captor.getValue();
    }

    private WorkplaceUpdateCommand addressChangeCommand() {
        return WorkplaceUpdateCommand.builder()
                .roadAddressProvided(true)
                .roadAddress("서울 강남구 테헤란로 2")
                .build();
    }

    private WorkplaceCoordinateConfirmCommand confirmCommand(BigDecimal latitude, BigDecimal longitude) {
        return WorkplaceCoordinateConfirmCommand.builder()
                .latitude(latitude)
                .longitude(longitude)
                .capturedAt(Instant.now())
                .build();
    }

    private WorkplaceListRow row(Long workplaceId, String status, boolean attendanceLocationConfirmed) {
        return WorkplaceListRow.builder()
                .workplaceId(workplaceId)
                .businessRegistrationNumber("1234567890")
                .name("강남점")
                .representativeName("김사장")
                .roadAddress("서울 강남구 테헤란로 1")
                .detailAddress("2층")
                .phone("0212345678")
                .radiusMeters(new BigDecimal("100.00"))
                .attendanceLocationConfirmed(attendanceLocationConfirmed)
                .status(status)
                .build();
    }

    private AuthPrincipal owner(Long userId) {
        return new AuthPrincipal(userId, UserRole.OWNER, "김사장");
    }

    private WorkplaceCreateCommand validCommand() {
        return WorkplaceCreateCommand.builder()
                .businessRegistrationNumber("1234567890")
                .name("강남점")
                .representativeName("김사장")
                .roadAddress("서울 강남구 테헤란로 1")
                .detailAddress("2층")
                .phone("0212345678")
                .build();
    }

    /**
     * Callback을 그대로 실행하는 Transaction Manager입니다.
     *
     * <p>이 Test의 대상은 저장 순서와 실패 시 미저장이지 Commit·Rollback 자체가 아닙니다.
     * 실제 트랜잭션 동작은 DB Test가 확인합니다.</p>
     */
    private static final class PseudoTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 실제 자원을 열지 않습니다.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // 검증 대상이 아닙니다.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // 검증 대상이 아닙니다.
        }
    }
}
