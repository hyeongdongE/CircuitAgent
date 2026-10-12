package com.circuitagent.design.parse;

import java.io.IOException;

public interface NetlistParser {
    String format();
    NetlistResult parse(byte[] bytes, String filename) throws IOException;
}
