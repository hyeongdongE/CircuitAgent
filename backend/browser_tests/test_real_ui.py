"""실제 Streamlit→FastAPI→MySQL 검증. 원본 파일을 사용하며 API 응답 mock이 없다."""

from pathlib import Path
from time import time

from playwright.sync_api import expect, sync_playwright

expect.set_options(timeout=60000)

ROOT = Path(__file__).resolve().parents[2]


def test_real_upload_graph_audit_history():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1280, "height": 1000})
        page.set_default_timeout(45000)
        page.goto("http://127.0.0.1:8501")
        page.get_by_label("설계 이름", exact=True).fill("Python browser " + str(time()))
        page.get_by_test_id("stFileUploader").nth(0).locator("input[type=file]").set_input_files(
            str(ROOT / ".local/qcv-fixtures/fe.csv")
        )
        page.get_by_test_id("stFileUploader").nth(1).locator("input[type=file]").set_input_files(
            str(ROOT / ".local/qcv-fixtures/fe.qcv")
        )
        page.get_by_role("button", name="회로 업로드", exact=True).click()
        expect(
            page.get_by_text("Component 453 · Net 335 · Connection 1500 · TERM 135 · Issue 80", exact=True)
        ).to_be_visible()
        page.get_by_label("Component 검색", exact=True).fill("U16")
        page.get_by_label("Component 검색", exact=True).press("Enter")
        expect(page.get_by_text("U16 · FITTED · Value: 미확인 · MPN: 미확인", exact=True)).to_be_visible()
        # Streamlit selectbox는 실제 데이터의 Pin 번호를 선택한다. IC 기능 이름을 추정하지 않는다.
        box = page.get_by_test_id("stSelectbox").filter(has_text="연결 Pin 선택")
        expect(page.get_by_test_id("stApp")).to_have_attribute("data-test-script-state", "notRunning")
        box.locator("input").click()
        page.get_by_role("option", name="5", exact=True).click()
        expect(page.get_by_text("Net: U16_EN", exact=True)).to_be_visible()
        expect(page.get_by_text("이웃 부품: R107, R83, U16", exact=True)).to_be_visible()
        page.get_by_role("button", name="Rule 검증 실행", exact=True).click()
        expect(page.get_by_text("PASS · tps54302-c5-nominal · C5", exact=True)).to_be_visible()
        expect(page.get_by_text("UNKNOWN · tps54302-u16-en-network · U16", exact=True)).to_be_visible()
        expect(page.get_by_text("UNKNOWN · tps54302-u16-fb-nominal · U16", exact=True)).to_be_visible()
        design = page.get_by_label("설계 ID", exact=True).input_value()
        page.reload()
        page.get_by_label("설계 ID", exact=True).fill(design)
        page.get_by_label("설계 ID", exact=True).press("Enter")
        expect(page.get_by_text("PASS · tps54302-c5-nominal · C5", exact=True)).to_be_visible()
        browser.close()


def test_mobile_missing_upload():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 390, "height": 844})
        page.goto("http://127.0.0.1:8501")
        page.get_by_role("button", name="회로 업로드", exact=True).click()
        expect(page.get_by_text("BOM CSV와 QCV 파일을 모두 선택하세요.", exact=True)).to_be_visible()
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
        browser.close()
