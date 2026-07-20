package com.instalego.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;

/**
 * Typesets the JSON report produced by {@link VerificationService} into a formatted, multi-page
 * "Legal Opinion" PDF — a header, verdict banner, per-document findings, cross-reference table,
 * missing documents (if any), recommendations, and a closing disclaimer/signature block. Works
 * the same whether the bank supplied a custom report structure or the default one: it typesets
 * whatever sections are present in the JSON rather than assuming a fixed field set.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LegalOpinionPdfService {

    private final ObjectMapper objectMapper;

    private static final PDType1Font SERIF = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
    private static final PDType1Font SERIF_BOLD = new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
    private static final PDType1Font SERIF_ITALIC = new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC);

    private static final float MARGIN = 56f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;

    private static final Color INK = new Color(0x2b, 0x24, 0x1c);
    private static final Color MUTED = new Color(0x6b, 0x60, 0x54);
    private static final Color RULE = new Color(0xd8, 0xcf, 0xc0);
    private static final Color PASS_COLOR = new Color(0x1e, 0x5e, 0x3a);
    private static final Color FAIL_COLOR = new Color(0x7a, 0x1f, 0x1f);
    private static final Color WARN_COLOR = new Color(0x8a, 0x5a, 0x12);

    @SuppressWarnings("unchecked")
    public byte[] generateOpinionPdf(String reportJson, String bankName, Long sessionId) throws IOException {
        Map<String, Object> report;
        try {
            report = objectMapper.readValue(reportJson, Map.class);
        } catch (Exception e) {
            report = Map.of("title", "Legal Opinion", "overallVerdict", "Report could not be parsed.");
        }

        try (PDDocument document = new PDDocument()) {
            Cursor cursor = new Cursor(document);
            cursor.newPage();

            renderHeader(cursor, bankName, sessionId);
            renderTitleAndVerdict(cursor, report);
            renderSection(cursor, "Overall Assessment", str(report.get("overallVerdict")));
            renderDocumentsAnalyzed(cursor, report);
            renderCrossReferenceCheck(cursor, report);
            renderMissingDocuments(cursor, report);
            renderRecommendations(cursor, report);
            renderClosing(cursor);

            cursor.close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }

    private void renderHeader(Cursor c, String bankName, Long sessionId) throws IOException {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy"));
        c.drawText(SERIF_BOLD, 11, INK, MARGIN, "INSTALEGO — LEGAL OPINION SERVICES");
        c.moveDown(15);
        String subtitle = (bankName != null && !bankName.isBlank())
                ? "Prepared for: " + bankName + "  •  Reference: OPINION-" + sessionId + "  •  " + dateStr
                : "Reference: OPINION-" + sessionId + "  •  " + dateStr;
        c.drawText(SERIF, 9, MUTED, MARGIN, subtitle);
        c.moveDown(10);
        c.rule(RULE);
        c.moveDown(18);
    }

    private void renderTitleAndVerdict(Cursor c, Map<String, Object> report) throws IOException {
        String title = str(report.getOrDefault("title", "Legal Opinion"));
        c.ensureSpace(40);
        c.drawText(SERIF_BOLD, 20, INK, MARGIN, title.toUpperCase());
        c.moveDown(26);

        String verdict = str(report.get("verdict"));
        if (!verdict.isBlank()) {
            Color color = switch (verdict) {
                case "PASS" -> PASS_COLOR;
                case "FAIL", "ERROR" -> FAIL_COLOR;
                default -> WARN_COLOR;
            };
            String label = switch (verdict) {
                case "PASS" -> "VERDICT: LEGALLY VALID (PASS)";
                case "FAIL" -> "VERDICT: FAIL — DEFECTS FOUND";
                case "INCOMPLETE" -> "VERDICT: INCOMPLETE — DOCUMENTS PENDING";
                case "ERROR" -> "VERDICT: ANALYSIS ERROR";
                default -> "VERDICT: " + verdict;
            };
            c.ensureSpace(28);
            c.drawBanner(label, color);
            c.moveDown(14);
        }
    }

    private void renderSection(Cursor c, String heading, String body) throws IOException {
        if (body == null || body.isBlank()) return;
        c.ensureSpace(30);
        c.drawText(SERIF_BOLD, 12, INK, MARGIN, heading);
        c.moveDown(16);
        c.drawWrapped(SERIF, 10.5f, INK, 0, body, 14);
        c.moveDown(16);
    }

    @SuppressWarnings("unchecked")
    private void renderDocumentsAnalyzed(Cursor c, Map<String, Object> report) throws IOException {
        Object raw = report.get("documentsAnalyzed");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return;

        c.ensureSpace(30);
        c.drawText(SERIF_BOLD, 12, INK, MARGIN, "Document-by-Document Analysis");
        c.moveDown(18);

        int idx = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> docRaw)) continue;
            Map<String, Object> doc = (Map<String, Object>) docRaw;
            idx++;

            c.ensureSpace(20);
            String name = str(doc.getOrDefault("name", "Document " + idx));
            String type = str(doc.get("type"));
            String status = str(doc.get("status"));
            String heading = name + (type.isBlank() ? "" : " (" + type + ")") + (status.isBlank() ? "" : " — " + status);
            c.drawText(SERIF_BOLD, 10.5f, INK, MARGIN, heading);
            c.moveDown(15);

            Object keyDetailsRaw = doc.get("keyDetails");
            if (keyDetailsRaw instanceof Map<?, ?> kdRaw) {
                Map<String, Object> kd = (Map<String, Object>) kdRaw;
                String line = joinKeyDetails(kd);
                if (!line.isBlank()) {
                    c.drawWrapped(SERIF_ITALIC, 9.5f, MUTED, 10, line, 13);
                    c.moveDown(6);
                }
            }

            for (String finding : listOfStrings(doc.get("findings"))) {
                c.drawWrapped(SERIF, 10, INK, 10, "- " + finding, 13);
            }
            for (String issue : listOfStrings(doc.get("issues"))) {
                c.drawWrapped(SERIF, 10, FAIL_COLOR, 10, "! " + issue, 13);
            }
            c.moveDown(12);
        }
    }

    @SuppressWarnings("unchecked")
    private String joinKeyDetails(Map<String, Object> kd) {
        List<String> parts = new ArrayList<>();
        if (kd.get("date") != null && !str(kd.get("date")).isBlank()) parts.add("Date: " + str(kd.get("date")));
        addListDetail(parts, "Parties", kd.get("parties"));
        addListDetail(parts, "Ref #", kd.get("referenceNumbers"));
        addListDetail(parts, "Amounts", kd.get("amounts"));
        return String.join("  |  ", parts);
    }

    private void addListDetail(List<String> parts, String label, Object raw) {
        List<String> values = listOfStrings(raw);
        if (!values.isEmpty()) parts.add(label + ": " + String.join(", ", values));
    }

    @SuppressWarnings("unchecked")
    private void renderCrossReferenceCheck(Cursor c, Map<String, Object> report) throws IOException {
        Object raw = report.get("crossReferenceCheck");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return;

        c.ensureSpace(30);
        c.drawText(SERIF_BOLD, 12, INK, MARGIN, "Cross-Reference Check");
        c.moveDown(16);

        for (Object o : list) {
            if (!(o instanceof Map<?, ?> entryRaw)) continue;
            Map<String, Object> entry = (Map<String, Object>) entryRaw;
            String docs = String.join(" <-> ", listOfStrings(entry.get("documents")));
            String status = str(entry.get("status"));
            String field = str(entry.get("field"));
            String detail = str(entry.get("detail"));

            Color statusColor = "MATCH".equals(status) ? PASS_COLOR : "MISMATCH".equals(status) ? FAIL_COLOR : MUTED;
            String line = (docs.isBlank() ? "" : docs + " — ") + field + (status.isBlank() ? "" : " [" + status + "]");
            c.drawWrapped(SERIF_BOLD, 10, statusColor, 10, line, 13);
            if (!detail.isBlank()) {
                c.drawWrapped(SERIF, 9.5f, INK, 16, detail, 13);
            }
            c.moveDown(6);
        }
        c.moveDown(10);
    }

    @SuppressWarnings("unchecked")
    private void renderMissingDocuments(Cursor c, Map<String, Object> report) throws IOException {
        Object raw = report.get("missingDocuments");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return;

        c.ensureSpace(30);
        c.drawText(SERIF_BOLD, 12, WARN_COLOR, MARGIN, "Documents Still Required");
        c.moveDown(16);

        for (Object o : list) {
            if (!(o instanceof Map<?, ?> entryRaw)) continue;
            Map<String, Object> entry = (Map<String, Object>) entryRaw;
            String description = str(entry.get("description"));
            String reason = str(entry.get("reason"));
            String referencedIn = str(entry.get("referencedIn"));

            c.drawWrapped(SERIF_BOLD, 10, INK, 10, "- " + description, 13);
            if (!reason.isBlank()) c.drawWrapped(SERIF, 9.5f, INK, 16, reason, 13);
            if (!referencedIn.isBlank()) c.drawWrapped(SERIF_ITALIC, 9, MUTED, 16, "Referenced in: " + referencedIn, 13);
            c.moveDown(6);
        }
        c.moveDown(10);
    }

    private void renderRecommendations(Cursor c, Map<String, Object> report) throws IOException {
        List<String> recs = listOfStrings(report.get("recommendations"));
        if (recs.isEmpty()) return;

        c.ensureSpace(30);
        c.drawText(SERIF_BOLD, 12, INK, MARGIN, "Recommendations");
        c.moveDown(16);
        for (String rec : recs) {
            c.drawWrapped(SERIF, 10, INK, 10, "- " + rec, 13);
        }
        c.moveDown(10);
    }

    private void renderClosing(Cursor c) throws IOException {
        c.ensureSpace(70);
        c.moveDown(6);
        c.rule(RULE);
        c.moveDown(16);
        c.drawWrapped(SERIF_ITALIC, 8.5f, MUTED, 0,
                "This opinion was produced with the assistance of an AI document-analysis system based solely on " +
                "the documents submitted. It does not constitute independent legal advice and should be reviewed " +
                "by a qualified legal professional before being relied upon for any transaction or decision.", 12);
        c.moveDown(28);
        c.drawText(SERIF, 10, INK, MARGIN, "_____________________________");
        c.moveDown(14);
        c.drawText(SERIF_ITALIC, 9, MUTED, MARGIN, "Authorized signatory");
    }

    // --- helpers ---

    private String str(Object o) {
        return o == null ? "" : sanitizeForPdf(o.toString());
    }

    private List<String> listOfStrings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object o : list) {
            if (o != null) result.add(sanitizeForPdf(o.toString()));
        }
        return result;
    }

    /**
     * The Standard 14 PDF fonts only reliably support the basic ASCII range. LLM-generated text
     * commonly contains smart quotes/dashes/ellipses that would otherwise throw at render time —
     * normalize those, and drop anything else non-ASCII rather than crash PDF generation.
     */
    private static String sanitizeForPdf(String s) {
        if (s == null) return "";
        String normalized = s
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-')
                .replace("…", "...");
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            sb.append(ch >= 32 && ch < 127 || ch == '\n' ? ch : ' ');
        }
        return sb.toString();
    }

    /**
     * Manages the current page/content-stream and vertical cursor, starting new pages
     * automatically when content would overflow the bottom margin.
     */
    private static class Cursor {
        private final PDDocument document;
        private PDPageContentStream stream;
        private float y;

        Cursor(PDDocument document) {
            this.document = document;
        }

        void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN;
        }

        void ensureSpace(float needed) throws IOException {
            if (y - needed < MARGIN) newPage();
        }

        void moveDown(float amount) {
            y -= amount;
        }

        void rule(Color color) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(0.75f);
            stream.moveTo(MARGIN, y);
            stream.lineTo(PAGE_WIDTH - MARGIN, y);
            stream.stroke();
        }

        void drawText(PDFont font, float size, Color color, float x, String text) throws IOException {
            ensureSpace(size + 4);
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, y);
            stream.showText(sanitizeForPdf(text));
            stream.endText();
        }

        void drawBanner(String label, Color color) throws IOException {
            float bannerHeight = 22f;
            stream.setNonStrokingColor(color);
            stream.addRect(MARGIN, y - bannerHeight + 6, CONTENT_WIDTH, bannerHeight);
            stream.fill();

            stream.beginText();
            stream.setFont(SERIF_BOLD, 11);
            stream.setNonStrokingColor(Color.WHITE);
            stream.newLineAtOffset(MARGIN + 10, y - bannerHeight + 12);
            stream.showText(sanitizeForPdf(label));
            stream.endText();
            y -= bannerHeight;
        }

        /**
         * Word-wraps text to the content width (minus indent) and draws each line, paging as
         * needed. Sanitizes defensively here (in addition to the call-site sanitization on LLM
         * data) so any hardcoded literal or future unicode slip-through still can't crash
         * rendering — the Standard 14 fonts only reliably cover the basic ASCII range.
         */
        void drawWrapped(PDFont font, float size, Color color, float indent, String text, float leading) throws IOException {
            if (text == null || text.isBlank()) return;
            text = sanitizeForPdf(text);
            float maxWidth = CONTENT_WIDTH - indent;

            for (String paragraph : text.split("\n")) {
                List<String> lines = wrap(paragraph, font, size, maxWidth);
                for (String line : lines) {
                    ensureSpace(leading);
                    stream.beginText();
                    stream.setFont(font, size);
                    stream.setNonStrokingColor(color);
                    stream.newLineAtOffset(MARGIN + indent, y);
                    stream.showText(line);
                    stream.endText();
                    y -= leading;
                }
            }
        }

        private List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                float width = font.getStringWidth(candidate) / 1000f * size;
                if (width > maxWidth && !current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
            if (lines.isEmpty()) lines.add("");
            return lines;
        }

        void close() throws IOException {
            if (stream != null) stream.close();
        }
    }
}
