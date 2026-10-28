package com.blu.teacher.api;

import com.blu.teacher.model.*;
import com.blu.teacher.service.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class LessonController {
    private final LessonPlanner planner; private final VideoExporter exporter;
    public LessonController(LessonPlanner planner, VideoExporter exporter){this.planner=planner;this.exporter=exporter;}
    @PostMapping("/lesson") public Lesson lesson(@Valid @RequestBody LessonRequest request){return planner.create(request);}
    @PostMapping("/narration") public ResponseEntity<?> narration(@RequestBody Lesson lesson){try{return ResponseEntity.ok(exporter.prepareNarration(lesson));}catch(Exception e){return ResponseEntity.status(500).body(Map.of("error","Narration generation failed. Check eSpeak NG and the selected local voice.","detail",e.getMessage()==null?"unknown":e.getMessage()));}}
    @PostMapping("/export") public ResponseEntity<?> export(@RequestBody Lesson lesson){try{return ResponseEntity.ok(exporter.export(lesson));}catch(Exception e){return ResponseEntity.status(500).body(Map.of("error","Export failed. Check that FFmpeg and a compatible local TTS voice are installed.","detail",e.getMessage()==null?"unknown":e.getMessage()));}}
    @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","ok","planner","local Ollama with deterministic fallback","supportedLanguages",List.of("en","hi","ta","te","bn","mr"));}
}
