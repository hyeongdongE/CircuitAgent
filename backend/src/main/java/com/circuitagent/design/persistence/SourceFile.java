package com.circuitagent.design.persistence;

import java.security.*;
import java.util.HexFormat;

public record SourceFile(String role, String filename, byte[] content) {
    public SourceFile {
        if (!"bom".equals(role) && !"netlist".equals(role)) throw new IllegalArgumentException("Invalid source role");
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("Filename is required");
        content = content.clone();
    }
    @Override public byte[] content() { return content.clone(); }
    public String sha256() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
