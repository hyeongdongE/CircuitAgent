package com.circuitagent.design.parse;

import java.io.IOException;

/**
 * EDA 형식을 공통 회로 모델로 연결하는 어댑터 계약. format은 선택 키이고 parse는 원본을 NetlistResult로 변환한다. Rule 판정은 수행하지 않는다.
 */
public interface NetlistParser {
  String format();

  NetlistResult parse(byte[] bytes, String filename) throws IOException;
}
