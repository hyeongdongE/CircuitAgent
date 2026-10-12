# 실제 Sparrow Stage 03 PoC 검증

2026-10-11, PR #1 HEAD29a9f17 기반 별도 feat/stage03-integration-poc에서 검증했다. [정확한 suite별 결과](integration-test-results.json), [단계 기록](integration-progress.md), [전체 성능 samples](audit-performance.json)를 제공한다.

## 데이터와 제조사 근거

Sparrow FE R3 실제 BOM CSV를 기존 Apache POI 기능으로 XLSX로 변환하고 실제 PADS QCV와 함께 입력했다. [고정 출처/체크섬/재현 절차](qcv-poc-validation.md), scripts/qcv-fixtures.json/fetch-qcv-fixtures.py를 유지했다. 세 보드 및 기존 KiCad 회귀도 전체 테스트에서 재실행했다. 원본·변환 XLSX·브라우저 trace는 커밋하지 않는다.

| FE R3 사실 | 실제 결과 |
| --- | ---: |
| BOM Reference |445|
| QCV 전체 Component |453|
| BOM/QCV 매칭 |445/445|
| Net |335|
| Connected Pin |1500|
| 별도 PIN 선언 |143|
| Terminated Pin |135|
| BOM NoMount |51|
| ImportIssue |80|

QCV의 추가8개 PIN-only PCB 부품은 보존한다. Connected/별도 PIN/TERM 숫자는 합산하지 않는다. 기존 issue80은72 MISSING_VALUE와8 NETLIST_ONLY_COMPONENT이며 UNKNOWN 사유와 구분한다.

공식 [TI product](https://www.ti.com/product/TPS54302), [TI PDF](https://www.ti.com/lit/ds/symlink/tps54302.pdf)를 직접 확인했다. Revision SLVSDG6C, revised March2026,2316621bytes, SHA256 `1632b388d1ba3a46c8e8f090ddfec2114c0f538cfb8364ddcda583fee3fdbdc5`. metadata는 scripts/tps54302-datasheet.json, PDF/HTML/text는 ignored .local/datasheets이다. 링크는 향후 바뀔 수 있으므로 재다운로드 후 SHA와 Revision을 확인하고 다른 문서라면 Rule 근거를 재검토한다. 재배포 권한을 추정하지 않는다.

```sh
mkdir -p .local/datasheets
curl -fL https://www.ti.com/lit/ds/symlink/tps54302.pdf -o .local/datasheets/tps54302.pdf
sha256sum .local/datasheets/tps54302.pdf
```

DDC6pin SOT-23-THIN의 table4-1 p3는 GND1/SW2/VIN3/FB4/EN5/BOOT6이다. p4 §5.3 VIN4.5~28V, p5 §5.5 Vref0.581/0.596/0.611V(명시 VIN/온도 조건), p11 divider1% 이하 권장, p15 §7.2.3.2 bootstrap0.1uF/§7.2.3.3 Eq7을 확인했다. 최신 문서가 R3 당시 실제 silicon에 적용됨은 확인되지 않았다.

U15/U16/U17의 Part Name TPS54302DDC는 가족/패키지 후보다. 주문용 DDCR/DDCT와 실물이 확인되지 않아 제조사 MPN을 생성하지 않는다. QCV에는 pin name이 없어 source model을 수정하지 않고 공식 pin mapping을 Rule fixture에 별도로 둔다.

## 실제 회로와 결과

U16-5→U16_EN→R107-2/R83-2. R107=140kΩ/R83=56kΩ 모두NoMount. 이를 실제 실장된 EN 분압으로 판정하지 않는다. TI는 EN floating도 허용하므로 이 연결은 기능 실패가 아니라 확인 불가다.

U16-4→U16_FB→R82-1/R87-2, R82=82kΩ/R87=4.3kΩ 모두실장. R82 반대 핀은 source label12V, R87 반대 핀은U16-1과 같은GND다. nominal `0.596×(1+82000/4300)=11.96158139534883720930232558139535V`. 목표 출력/공차/확인된 MPN·적용 Revision이 없어 UNKNOWN이며12V label을 측정·목표 전압으로 사용하지 않는다.

C5=100nF 실장, C5-1↔U16-6 BOOT/C5-2↔U16-2 SW를 실제 source와 대조했다.

| PoC Rule | 결과 | 범위/부족 정보 |
| --- | --- | --- |
| U16 EN populated network |UNKNOWN|R107/R83 DNP, IC 신원 적용성|
| U16 operating VIN4.5~28V |UNKNOWN|실제 동작 VIN, IC 신원 적용성|
| U16 FB nominal equation |UNKNOWN|계산값11.961581395…V; 공차·목표·MPN 부족|
| C5 nominal100nF |PASS|BOM nominal 값 확인만; 전체 전원 적합성 아님|
| C5 explicit voltage rating |UNKNOWN|catalogue Voltage를 정격으로 생성하지 않음|
| U16 GND literal net binding |UNKNOWN|GND 연결은 관측되나 확인된 IC 신원 부족|

합계 PASS1/UNKNOWN5/FAIL0. DNP divider는 자체 테스트에서 계산값 없이 UNKNOWN이다. imported MPN 문자열만 있는 자체 시험도 독립 attestation이 없어 UNKNOWN이다. 실제 공개 회로에서 PASS를 만들기 위해 값·Rule·조건을 변경하지 않았다.

## 전체 테스트

`cd backend && ./gradlew clean test bootJar --no-daemon`:103 PASS/FAIL0/ERROR0/SKIP0, 기존80+신규23, BUILD SUCCESSFUL6m18s. 실제 Docker/MySQL8.0.43로 Stage02와 Audit 저장/복원/FK/격리/SQL unique 실패 롤백, V1→V4 migration을 실행했다. 실제 upload/store/snapshot/evaluate/REST/evidence roundtrip과 자체 fixtures의 다섯 상태/단위/DNP/zero/모호 연결을 구분했다.

`npm ci && npm test && npm run build`: unit6 PASS/build PASS. Playwright 실제 Chromium2 PASS34.3s, HTTP/DB mocks 없음. 실제 파일 업로드→MySQL→U16/DNP 탐색→6 Rule→Audit 저장→API→React→history reload와 모바일 invalid upload를 검증했다. Production dependency audit 현시점 vulnerability0이며 라이선스 목록은 별도 제공한다. 사내 환경/다른 브라우저/SSO 테스트는 실행하지 않았다.

## 성능과 한계

Linux/Java21.0.12.1/4 processors/test heap512MiB/MySQL8.0.43, JVM/JPA warm-up 후 각3 sample이다. Stage03 final median(ms): snapshot328.7, validation0.64, traversal preflight1.93, evaluation8.17, hash97.81, Audit DB62.73, MockMvc API56.01. 평가 시간에는 추가 graph lookup이 포함되므로 preflight와 순수 수치 계산을 혼동하지 않는다. DB persistence 시간은 응답 timestamp/timing에 사후 덧붙여 저장하지 않고 외부 benchmark로 측정한다.

동일 세션의 Stage02 median(ms): BOM77.97/QCV11.46/integration2.53/graph2.23/designDB2322.90/API410.29. 과거 측정과 동일 머신/부하를 보장하지 않으므로 단순 개선 비율을 주장하지 않는다.60 Rule은 같은 nominal check를 다른 ID로 반복한 자체 부하로 실제6개와 Rule mix가 다르다. 추가100/1000/5000 component 부하는 실제 connectivity를 추가하거나 DB에 저장한 측정이 아니다. median/mean/전체 samples를 audit-performance.json에 남겼으며 SLA는 없다.

삼성 내부 QCV 변형, 실제 Stage01 cp-v1 Adapter, 독립 MPN·silicon/datasheet attestation, 실제 VIN/목표 출력/공차/DC Bias/온도·전원 기능, 사내 SSO·설계 권한은 BLOCKED 또는 미검증이다. 이번 완료 범위는 공개 PADS QCV의 사실과 정형 Rule로 판단 및 근거를 제공하는 로컬 PoC다. 공개 배포/전체 전원 인증/모든 QCV 호환성을 주장하지 않는다.
