package com.blu.teacher.api;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/import")
public class ImportController {
    public record ImportResult(String fileName, String text, int characters) {}

    @PostMapping(consumes = "multipart/form-data")
    public ImportResult extract(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty file.");
        if (file.getSize() > 15L * 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Maximum upload size is 15 MB.");
        String name = Objects.toString(file.getOriginalFilename(), "upload.txt");
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            String text;
            if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv")) {
                text = new String(file.getBytes(), StandardCharsets.UTF_8);
            } else if (lower.endsWith(".pdf")) {
                try (PDDocument pdf = Loader.loadPDF(file.getBytes())) { text = new PDFTextStripper().getText(pdf); }
            } else if (lower.endsWith(".pptx")) {
                StringBuilder out = new StringBuilder();
                try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(file.getBytes()))) {
                    int n = 1;
                    for (XSLFSlide slide : ppt.getSlides()) {
                        out.append("Slide ").append(n++).append("\n");
                        for (XSLFShape shape : slide.getShapes()) if (shape instanceof XSLFTextShape t) out.append(t.getText()).append('\n');
                        out.append('\n');
                    }
                }
                text = out.toString();
            } else if (lower.endsWith(".docx")) {
                StringBuilder out = new StringBuilder();
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(file.getBytes()))) {
                    for (XWPFParagraph para : doc.getParagraphs()) out.append(para.getText()).append('\n');
                }
                text = out.toString();
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supported formats: TXT, MD, CSV, PDF, PPTX, DOCX. Legacy PPT is not supported in this build.");
            }
            text = text.replace("\u0000", "").trim();
            if (text.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No selectable text was found. Scanned PDFs need OCR, which is not included yet.");
            if (text.length() > 60000) text = text.substring(0, 60000);
            return new ImportResult(name, text, text.length());
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not extract text from this file: " + e.getMessage(), e); }
    }
}
