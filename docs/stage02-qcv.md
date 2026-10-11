# QCV 지원 준비 — Parser 미구현

실제 `.qcv` 원본이나 삼성 프로젝트의 형식 명세는 현재 제공되지 않았습니다.
저장소 및 첨부/작업 폴더 검색에서도 원본 샘플이 발견되지 않았습니다.
QcvNetlistParser는 NetlistParser의 구현 경계만 제공하며 parse를 호출하면
명시적 UnsupportedOperationException을 발생시킵니다. REST는 422로 응답합니다.
이 동작의 테스트는 미지원 상태 검증이며 QCV 문법/파싱 성공 검증이 아닙니다.

| 검토 항목 | 공통 모델/추가 확인사항 |
| --- | --- |
| 연결 정보 | PinKey(부품 Reference, Pin Number)와 Net ID 사이의 PinConnection으로 표현 가능. 실제 QCV 레코드 구조는 미확인 |
| 계층형 Net | Net ID와 원본 name/sourcePath를 분리해 보존 가능. 실제 계층 범위, 전역/지역 이름 및 ID 규칙 필요 |
| 계층형 부품 | 현재 Reference는 설계 전체에서 유일해야 함. QCV가 계층마다 같은 Ref를 사용하면 실제 샘플에 근거한 경로를 포함하는 키 규칙/모델 확장 필요 |
| Pin Number/Name | 번호는 식별 키, 이름은 선택 속성. QCV의 번호/이름/패키지 핀/논리 핀 매핑 확인 필요 |
| 전원 Net | 이름만으로 전원 Net을 추정하거나 같은 이름의 Net을 합치지 않음. QCV 전역 전원 심볼/연결/스코프 규칙 필요 |
| 미연결 Pin | ComponentPin을 보존하고 PinConnection 없이 표현. QCV가 미연결 핀을 어떻게 표기/생략하는지 확인 필요 |
| 모호한 연결 | 하나의 핀에 복수 Net이 있으면 ImportIssue 기록 후 연결 제외. QCV 멀티유닛/alias 규칙 확인 필요 |
| Source | 설계 원본·sheet 경로·파일명과 원본 업로드 파일 보존 가능. 실제 QCV 위치/라인/버전 정보 필요 |

다음 입력을 확보해야 QCV 파서를 시작할 수 있습니다.

1. 배포/사용 허가된 실제 QCV 파일과 해당 회로의 BOM.
2. 파일 생성 EDA 도구/버전, 인코딩 및 형식 명세(가능한 경우).
3. 정답 확인 가능한 Component/Pin/Net 수량과 연결 예시.
4. 계층, 전원, 미연결 Pin이 포함된 샘플 또는 규칙.

KiCad 공개 회로의 성공은 공통 모델의 PoC 검증이며 삼성 QCV 지원 완료를 의미하지 않습니다.
