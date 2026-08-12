---
patch_id: SPEC-178-07
status: draft
issue: 178
base_spec_version: 7.0.0
targets:
  - requirement: DOC-001
  - requirement: DOC-003
  - requirement: DOC-009
  - requirement: DOC-011
  - api: GET /api/documents/{documentId}
  - decision: DEC-DOCUMENT-RESPONSE-SHAPES
  - decision: DEC-DOCUMENT-SHARE-UNIT
  - decision: DEC-DOCUMENT-ACCESS-AUDIT
---

# SPEC-178-07: 공유 보건증 단건 상세 문맥 식별자

## 추가 사항

- `GET /api/documents/{documentId}`의 `workCaseId` Query는 SHARED `HEALTH_CERTIFICATE`의
  문맥을 선택하는 식별자다.
- SHARED 보건증 상세 요청은 목록의 같은 SHARED Item이 반환한 `workCaseId`를 반드시
  전달한다. 서버는 Query가 없다는 이유로 하나뿐인 관계를 자동 선택하거나, 복수 관계를
  정렬해 임의의 첫 행으로 축약하지 않는다.
- `workCaseId`가 전달되면 서버는 현재 사용자, `documentId`, Work Case, 사업장 OWNER,
  문서 소유 WORKER와 ACTIVE 공유 관계가 모두 일치하는지 검증한다. 문서·사업장·공유가
  ACTIVE이고 보건증이 만료되지 않았으며 Work Case가 `ACCEPTED|READY|IN_PROGRESS`이고
  현재 시각이 `endsAt` 전이어야 한다.
- SHARED 보건증 요청에서 Query가 없거나 관계가 틀리거나 보이지 않으면 외부 응답은
  `404 RESOURCE_NOT_FOUND`이며 다른 공유 관계로 fallback하지 않는다. 문서가 식별된
  거부는 `PARTY_ACCESS_DENIED`, 철회·만료·근무 종료로 식별된 관계가 무효가 된 거부는
  `DOCUMENT_UNAVAILABLE`로 `DOCUMENT_DETAIL_VIEW` 감사 행을 정확히 한 번 Commit한다.
  존재하지 않는 문서는 DB 감사 대상이 아니다.
- 성공 응답은 선택된 관계의 `workplaceId`, `workplaceName`, `workCaseId`, Capability와
  안전한 `versions[]`를 반환하고 `DOCUMENT_DETAIL_VIEW` ALLOWED 감사를 정확히 한 번
  Commit한다.
- OWN 보건증과 문서 자체 `work_case_id`가 있는 근로계약 당사자는 Query 없는 기존 상세
  경로를 유지한다. 파일 Bytes는 공유 문맥별로 달라지지 않으므로 파일 Endpoint는 현재
  사용자에게 유효한 공유 관계가 하나 이상이면 허용하는 기존 계약을 유지한다.
- 새 오류 Code, DB Column, Index 또는 Migration은 추가하지 않는다.

## 완료 조건

- [ ] SHARED 목록 Item의 `workCaseId`로 단건 상세를 조회하면 같은 사업장·Work Case
      문맥과 안전한 `versions[]`가 반환된다.
- [ ] SHARED 보건증 요청에서 `workCaseId`를 생략해도 유일·복수 관계를 자동 선택하지 않고
      `404 RESOURCE_NOT_FOUND`를 반환한다.
- [ ] 다른 문서·다른 사용자·다른 사업장의 `workCaseId` 및 철회·만료·종료 관계는 모두
      404이고 허용된 DENIED 감사만 남긴다.
- [ ] OWN 보건증과 계약 당사자의 기존 Query 없는 상세 요청은 유지된다.
- [ ] 상세 성공·거부 감사가 응답 전에 정확히 한 번 Commit되고 감사 실패 시 성공 Metadata를
      반환하지 않는다.
- [ ] API 응답과 일반 로그에 Storage Key, Checksum, 내부 Version·공유 ID가 노출되지 않는다.
