package com.circuitagent.design.parse;

/** Conservative PoC limits; parse failure never produces a truncated model. */
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
