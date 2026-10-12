package com.circuitagent.design.api;

import com.circuitagent.design.model.ImportIssue;
import java.util.List;

final class ImportRejectedException extends RuntimeException {
    private final List<ImportIssue> issues;
    ImportRejectedException(String source, String message, Throwable cause) {
        super(message, cause);
        issues = List.of(new ImportIssue(ImportIssue.Severity.ERROR, ImportIssue.Code.PARSE_FAILURE, null, source, message));
    }
    List<ImportIssue> issues() { return issues; }
}
