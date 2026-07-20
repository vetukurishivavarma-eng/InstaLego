package com.instalego.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Shared plain-text extraction for uploaded documents (PDFBox for PDFs, Tika for everything
 * else). Used both by the verification pipeline and by bank report-format analysis so the two
 * don't duplicate the same extraction logic.
 */
@Service
@Slf4j
public class TextExtractionService {

    private static final Tika TIKA = new Tika();

    public String extractText(Path filePath, String fileType) {
        try {
            String ext = fileType != null ? fileType.toUpperCase() : "";
            if ("PDF".equals(ext)) {
                return extractTextFromPdf(filePath);
            }
            return extractTextWithTika(filePath);
        } catch (Exception e) {
            log.warn("Text extraction failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private String extractTextFromPdf(Path pdfPath) throws Exception {
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdfPath.toFile())) {
            var stripper = new org.apache.pdfbox.text.PDFTextStripper();
            String text = stripper.getText(document);
            return text != null ? text.trim() : null;
        }
    }

    private String extractTextWithTika(Path filePath) throws Exception {
        try (InputStream is = new FileInputStream(filePath.toFile())) {
            String text = TIKA.parseToString(is);
            return text != null ? text.trim() : null;
        }
    }
}
