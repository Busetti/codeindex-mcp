package com.example.orders.legacy;

import java.sql.Statement;

/**
 * QUALITY SMELLS (intentional, for the extended-detection demo). This class deliberately triggers the
 * maintainability & security rules: God class (>20 methods), long/complex/deeply-nested method,
 * empty catch, string concat in a loop, too many params, hardcoded secret, and SQL built by concatenation.
 */
public class LegacyReportService {

    // HARDCODED_SECRET (field-level heuristic)
    private static final String API_KEY = "sk_live_1234567890abcdef";

    // LONG_METHOD + HIGH_COMPLEXITY + DEEP_NESTING + SILENT_FAILURE + STRING_CONCAT_IN_LOOP + TOO_MANY_PARAMS
    public String generateReport(String a, int b, int c, boolean d, long e, String f) {
        String report = "";
        for (int i = 0; i < b; i++) {
            if (d && c > 0) {
                while (i < c) {
                    if (a != null && f != null) {
                        try {
                            report = report + a + i;
                        } catch (Exception ex) {
                            // swallowed on purpose -> SILENT_FAILURE
                        }
                    }
                    i++;
                }
            } else if (b > 10) {
                report = report + "x";
            }
        }
        if (b > 1) { report += "a"; }
        if (c > 2) { report += "b"; }
        if (e > 3) { report += "c"; }

        // Padding to make this a genuinely LONG_METHOD (> 80 lines) for the demo.
        int total = 0;
        total += b;
        total += c;
        total += (int) e;
        report = report + "total=" + total;
        report = report + "\n";
        report = report + "a=" + a;
        report = report + "\n";
        report = report + "b=" + b;
        report = report + "\n";
        report = report + "c=" + c;
        report = report + "\n";
        report = report + "d=" + d;
        report = report + "\n";
        report = report + "e=" + e;
        report = report + "\n";
        report = report + "f=" + f;
        report = report + "\n";
        String line1 = "section-1";
        String line2 = "section-2";
        String line3 = "section-3";
        String line4 = "section-4";
        String line5 = "section-5";
        report = report + line1;
        report = report + "\n";
        report = report + line2;
        report = report + "\n";
        report = report + line3;
        report = report + "\n";
        report = report + line4;
        report = report + "\n";
        report = report + line5;
        report = report + "\n";
        int checksum = 0;
        checksum = checksum + line1.length();
        checksum = checksum + line2.length();
        checksum = checksum + line3.length();
        checksum = checksum + line4.length();
        checksum = checksum + line5.length();
        report = report + "checksum=" + checksum;
        report = report + "\n";
        report = report + "footer-start";
        report = report + "\n";
        report = report + "generated-by=LegacyReportService";
        report = report + "\n";
        report = report + "version=1";
        report = report + "\n";
        report = report + "footer-end";
        report = report + "\n";
        String extra1 = "extra-1";
        String extra2 = "extra-2";
        String extra3 = "extra-3";
        String extra4 = "extra-4";
        String extra5 = "extra-5";
        report = report + extra1;
        report = report + "\n";
        report = report + extra2;
        report = report + "\n";
        report = report + extra3;
        report = report + "\n";
        report = report + extra4;
        report = report + "\n";
        report = report + extra5;
        report = report + "\n";
        return report;
    }

    // SQL_INJECTION_RISK (heuristic): query built by string concatenation
    public void runQuery(Statement statement, String userId) throws Exception {
        statement.executeQuery("SELECT * FROM users WHERE id = " + userId);
    }

    // HARDCODED_SECRET (method-level heuristic)
    public void connect() {
        String password = "hunter2";
        System.out.println(password.length());
    }

    // --- filler methods to push the class over the God-class method threshold (>20) ---
    public void m01() { }
    public void m02() { }
    public void m03() { }
    public void m04() { }
    public void m05() { }
    public void m06() { }
    public void m07() { }
    public void m08() { }
    public void m09() { }
    public void m10() { }
    public void m11() { }
    public void m12() { }
    public void m13() { }
    public void m14() { }
    public void m15() { }
    public void m16() { }
    public void m17() { }
    public void m18() { }
}
