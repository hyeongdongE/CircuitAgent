package com.circuitagent.design.parse;

/** 외부 입력으로 인한 메모리·CPU 고갈을 줄이는 공통 파일/행/셀/토큰 한도. 파서는 큰 자료를 끝까지 객체화하기 전에 해당 한도를 검사한다. */
public final class ParserLimits {
  public static final int MAX_BYTES = 10 * 1024 * 1024;
  public static final int MAX_EXPANDED_BYTES = 32 * 1024 * 1024;
  public static final int MAX_ROWS = 50_000;
  public static final int MAX_COLUMNS = 256;
  public static final int MAX_CELLS = 250_000;
  public static final int MAX_VALUES = 500_000;

  private ParserLimits() {}

  public static void file(byte[] bytes) {
    if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Parser input exceeds 10 MiB");
  }
}
