# 공개 PADS QCV 지원 범위

Java QcvNetlistParser가 NetlistParser를 구현하고 KiCad와 같은 Circuit Model을 반환합니다.
Sparrow 3개 공개 Revision R3 원본으로 검증한 문법입니다. 삼성 전체 QCV 호환을 보장하지 않습니다.

- UTF-8(선두 BOM 허용), 한 줄의 NET/PIN, 작은따옴표 label, 공백 구분 terminal, # comment/빈 줄.
- NET는 동일 전기 연결망입니다. PIN은 별도 핀 선언이며 가짜 Net을 생성하지 않습니다.
- `(by TERM)`은 TERMINATED, 일반 PIN은 UNCONNECTED. one-pin-net section에서 명시 연결 없는 PIN은 UNSPECIFIED입니다.
- Component는 NET/PIN에서 추출합니다. COMP 목록을 요구하지 않습니다. 핀 기능/MPN/전압을 추측하지 않습니다.
- Reference/Pin은 검증된 마지막 suffix 경계로 식별합니다. 계층·하이픈 Reference와 Net 원문을 보존합니다.
- 반복 Net은 합치면서 issue, 같은 연결 반복은 dedup/issue, 여러 Net 배정과 TERM 충돌은 모호 연결을 제외하고 issue.
- Net ID는 원문 label의 SHA-256입니다. 같은 label의 지역 scope가 따로 있는 exporter는 지원하지 않습니다.
- 핀에 행 번호·terminal·section·declaration 출처를 보존합니다. NET 및 부품 경유 탐색 edge에도 출처가 있습니다.
- 10 MiB 및 레코드/token 제한. 잘못된 문법/UTF-8은 전체 import 실패, 부분 모델 저장 없음.

미지원: COMP 및 다른 exporter 레코드, 여러 줄 레코드, label quote escape, 모호한 hyphen pin number,
label 외 별도 scope, implicit hierarchy alias. 이를 지원하려면 생성 도구/버전과 허가된 실제 샘플이 필요합니다.
핀 번호/핀 기능 alias와 논리·물리 번호의 매핑은 별도 데이터가 필요합니다.

[실제 수치·라이선스·재현 절차](qcv-poc-validation.md), [Stage 03 조회 계약](stage03-interface.md).
