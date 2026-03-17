#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Running all tests with coverage..."
echo ""

./gradlew clean test jacocoTestReport jacocoTestCoverageVerification

echo ""
echo "========================================"
echo "  All tests passed. Coverage verified."
echo "========================================"
echo ""
echo "Reports:"
echo "  Test report:     build/reports/tests/test/index.html"
echo "  Coverage report: build/reports/jacoco/test/html/index.html"
echo "  Coverage XML:    build/reports/jacoco/test/jacocoTestReport.xml"
echo ""

if command -v open &>/dev/null; then
    open build/reports/jacoco/test/html/index.html
elif command -v xdg-open &>/dev/null; then
    xdg-open build/reports/jacoco/test/html/index.html
fi
