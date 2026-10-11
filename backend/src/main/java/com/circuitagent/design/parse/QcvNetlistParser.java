package com.circuitagent.design.parse;

import org.springframework.stereotype.Component;

/** Explicit unsupported boundary until a real sample and format specification are available. */
@Component
public class QcvNetlistParser implements NetlistParser {
    @Override public String format() { return "qcv"; }
    @Override public NetlistResult parse(byte[] bytes, String filename) {
        throw new UnsupportedOperationException("QCV import is not implemented: a real QCV sample and format specification are required");
    }
}
