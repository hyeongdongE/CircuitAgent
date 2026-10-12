package com.circuitagent.design.api;

import com.circuitagent.design.model.ImportIssue;
import java.util.List;

/** 원본 파싱 실패를 API 입력 오류로 전달하는 예외. 내부 cause는 보존하되 외부에는 파일 역할/안전한 메시지와 ImportIssue만 제공한다. */
final class ImportRejectedException extends RuntimeException {
  private final List<ImportIssue> issues;

  ImportRejectedException(String source, String message, Throwable cause) {
    super(message, cause);
    issues =
        List.of(
            new ImportIssue(
                ImportIssue.Severity.ERROR, ImportIssue.Code.PARSE_FAILURE, null, source, message));
  }

  List<ImportIssue> issues() {
    return issues;
  }
}
