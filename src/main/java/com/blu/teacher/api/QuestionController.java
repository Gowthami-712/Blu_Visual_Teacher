package com.blu.teacher.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/ask")
public class QuestionController {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Value("${blu.ollama-url:http://localhost:11434}") private String ollamaUrl;
    @Value("${blu.ollama-model:qwen2.5:3b}") private String model;
    public record AskRequest(String question, String topic, String language, String lessonContext) {}
    public record AskResponse(String answer, String model) {}

    @PostMapping
    public AskResponse ask(@RequestBody AskRequest req) {
        if (req.question() == null || req.question().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a question.");
        String language = switch (req.language() == null ? "en" : req.language()) {
            case "hi" -> "Hindi"; case "ta" -> "Tamil"; case "te" -> "Telugu"; case "bn" -> "Bengali"; case "mr" -> "Marathi"; default -> "English";
        };
        String prompt = "You are a helpful teacher. Answer in " + language + ". Use the lesson context when relevant, explain simply, and do not invent facts. Topic: " + safe(req.topic(), 180) + "\nLesson context: " + safe(req.lessonContext(), 8000) + "\nStudent question: " + safe(req.question(), 1200) + "\nGive a clear, concise answer.";
        try {
            String body = mapper.writeValueAsString(Map.of("model", model, "stream", false, "prompt", prompt, "options", Map.of("temperature", 0.2, "num_predict", 500)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaUrl + "/api/generate")).timeout(Duration.ofSeconds(180)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Local model returned HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            String answer = root.path("response").asText("").trim();
            if (answer.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Local model returned an empty answer.");
            return new AskResponse(answer, model);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "The local AI assistant did not respond in time. Try a shorter question or check Ollama.", e); }
    }
    private String safe(String s, int max) { if (s == null) return ""; s=s.trim(); return s.substring(0, Math.min(max, s.length())); }
}
