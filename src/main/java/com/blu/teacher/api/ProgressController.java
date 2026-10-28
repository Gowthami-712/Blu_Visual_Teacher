package com.blu.teacher.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Lightweight local-first progress store for a single trusted computer/network.
 * Add authentication and a database before exposing this API to the public internet. */
@RestController
@RequestMapping("/api/progress")
public class ProgressController {
    private final ObjectMapper mapper = new ObjectMapper();
    @Value("${blu.output-dir:./generated}") private String outputDir;

    public record ProgressEntry(String studentId, String topic, String language,
                                int completedSections, int totalSections,
                                int quizCorrect, int quizTotal, long watchedSeconds,
                                String updatedAt) {}

    private Path file() throws Exception {
        Path dir = Paths.get(outputDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return dir.resolve("progress.json");
    }

    private synchronized List<ProgressEntry> readAll() throws Exception {
        Path f = file();
        if (!Files.exists(f)) return new ArrayList<>();
        return mapper.readValue(Files.readString(f), new TypeReference<List<ProgressEntry>>() {});
    }

    private synchronized void writeAll(List<ProgressEntry> entries) throws Exception {
        Path f = file();
        Path tmp = f.resolveSibling("progress.json.tmp");
        Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries));
        try { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING); }
    }

    @GetMapping
    public List<ProgressEntry> list(@RequestParam(defaultValue = "demo-student") String studentId) {
        try { return readAll().stream().filter(p -> p.studentId().equals(studentId)).sorted(Comparator.comparing(ProgressEntry::updatedAt).reversed()).toList(); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read progress", ex); }
    }

    @PostMapping
    public synchronized ProgressEntry save(@RequestBody Map<String, Object> body) {
        String studentId = str(body, "studentId", "demo-student");
        String topic = str(body, "topic", "Untitled lesson");
        String language = str(body, "language", "en");
        int total = Math.max(1, Math.min(1000, num(body, "totalSections", 1)));
        int completed = Math.max(0, Math.min(total, num(body, "completedSections", 0)));
        int quizTotal = Math.max(0, Math.min(1000, num(body, "quizTotal", 0)));
        int quizCorrect = Math.max(0, Math.min(quizTotal, num(body, "quizCorrect", 0)));
        long watched = Math.max(0, Math.min(7L * 24 * 3600, longNum(body, "watchedSeconds", 0)));
        ProgressEntry entry = new ProgressEntry(studentId, topic, language, completed, total, quizCorrect, quizTotal, watched, Instant.now().toString());
        try {
            List<ProgressEntry> all = readAll();
            all.removeIf(p -> p.studentId().equals(studentId) && p.topic().equals(topic) && p.language().equals(language));
            all.add(entry);
            writeAll(all);
            return entry;
        } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save progress", ex); }
    }

    private String str(Map<String,Object> body, String key, String fallback) { Object v=body.get(key); String s=v==null?fallback:String.valueOf(v).trim(); return s.isEmpty()?fallback:s.substring(0, Math.min(180,s.length())); }
    private int num(Map<String,Object> body, String key, int fallback) { try { return Integer.parseInt(String.valueOf(body.get(key))); } catch (Exception e) { return fallback; } }
    private long longNum(Map<String,Object> body, String key, long fallback) { try { return Long.parseLong(String.valueOf(body.get(key))); } catch (Exception e) { return fallback; } }
}
