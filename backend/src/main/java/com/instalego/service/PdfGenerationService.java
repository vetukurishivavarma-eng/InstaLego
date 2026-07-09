package com.instalego.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfGenerationService {

    private final ObjectMapper objectMapper;

    private static final PDType1Font FONT_HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font FONT_HELVETICA_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    /**
     * Generate a filled output PDF from extracted data and bank template.
     */
    public Path generateOutputPdf(String templatePdfPath, String extractedJson, Path outputDir) throws IOException {
        String fileName = "output_" + System.currentTimeMillis() + ".pdf";
        Files.createDirectories(outputDir);
        Path outputPath = outputDir.resolve(fileName);

        // Parse the extracted JSON
        Map<String, Object> extractionData = objectMapper.readValue(extractedJson,
                new TypeReference<Map<String, Object>>() {});
        Object fieldsObj = extractionData.getOrDefault("fields", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = fieldsObj instanceof Map ? (Map<String, Object>) fieldsObj : Map.of();

        // Determine the best approach: use the existing template if it's fillable, or create a new document
        if (templatePdfPath != null && Files.exists(Path.of(templatePdfPath))) {
            try (PDDocument document = Loader.loadPDF(new java.io.File(templatePdfPath))) {
                PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm(null);

                if (acroForm != null) {
                    // Template has AcroForm fields — fill them
                    fillAcroForm(acroForm, fields);
                    document.save(outputPath.toFile());
                    log.info("Generated filled PDF using AcroForm: {}", outputPath);
                } else {
                    // Template doesn't have AcroForm — overlay text on top of template
                    overlayTextOnTemplate(document, fields, outputPath);
                    log.info("Generated filled PDF with text overlay: {}", outputPath);
                }
            }
        } else {
            // No template — generate a standalone PDF with the extracted data
            generateStandalonePdf(fields, outputPath);
            log.info("Generated standalone PDF: {}", outputPath);
        }

        return outputPath;
    }

    /**
     * Fill an AcroForm-based PDF with extracted field values.
     */
    private void fillAcroForm(PDAcroForm acroForm, Map<String, Object> fields) throws IOException {
        acroForm.setNeedAppearances(true);

        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();
            if (value == null) continue;

            PDField field = acroForm.getField(fieldName);
            if (field != null) {
                try {
                    field.setValue(value.toString());
                } catch (IOException e) {
                    log.warn("Could not set field '{}': {}", fieldName, e.getMessage());
                }
            }
        }
    }

    /**
     * Overlay text on a non-fillable PDF template at approximate positions.
     */
    private void overlayTextOnTemplate(PDDocument document, Map<String, Object> fields, Path outputPath) throws IOException {
        try (PDPageContentStream contentStream = new PDPageContentStream(document,
                document.getPage(0), PDPageContentStream.AppendMode.APPEND, true, true)) {

            contentStream.setFont(FONT_HELVETICA, 10);
            contentStream.setLeading(14.5f);

            float yStart = 700;
            float xStart = 50;
            float yPosition = yStart;

            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                if (entry.getValue() == null) continue;
                String text = entry.getKey() + ": " + entry.getValue();
                contentStream.beginText();
                contentStream.newLineAtOffset(xStart, yPosition);
                contentStream.showText(text);
                contentStream.endText();
                yPosition -= 15;
            }
        }

        document.save(outputPath.toFile());
    }

    /**
     * Generate a standalone PDF from extracted fields when no template is available.
     */
    private void generateStandalonePdf(Map<String, Object> fields, Path outputPath) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.setFont(FONT_HELVETICA_BOLD, 16);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 750);
                contentStream.showText("Extracted Document Data");
                contentStream.endText();

                contentStream.setFont(FONT_HELVETICA, 11);
                contentStream.setLeading(16f);
                contentStream.beginText();
                contentStream.newLineAtOffset(50, 720);

                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    String text = entry.getKey() + ": " + (entry.getValue() != null ? entry.getValue() : "[Not found]");
                    contentStream.showText(text);
                    contentStream.newLine();
                }

                contentStream.endText();
            }

            document.save(outputPath.toFile());
        }
    }
}
