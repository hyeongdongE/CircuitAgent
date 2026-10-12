package com.circuitagent.design.model;

/** 부품 배치의 실장 상태: FITTED=실장, DNP=명시적 미실장, UNSPECIFIED=확인 불가. 사양을 공유하는 다른 부품의 실장 상태와 섞지 않는다. */
public enum AssemblyStatus {
  FITTED,
  DNP,
  UNSPECIFIED
}
