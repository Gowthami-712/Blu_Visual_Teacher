package com.blu.teacher.service;

import com.blu.teacher.model.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class LessonPlanner {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Value("${blu.ollama-url:http://localhost:11434}") private String ollamaUrl;
    @Value("${blu.ollama-model:qwen2.5:3b}") private String model;

    public Lesson create(LessonRequest request) {
        // Small local models occasionally ignore the requested language. Retry with an explicit
        // native-script correction instead of silently accepting an English lesson.
        String previousError = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            try { return planLocally(request, attempt, previousError); }
            catch (Exception ex) {
                previousError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                System.err.println("Lesson generation attempt " + (attempt + 1) + " failed: " + previousError);
                ex.printStackTrace();
                if (attempt == 1) return fallback(request);
            }
        }
        return fallback(request);
    }

    private Lesson planLocally(LessonRequest r, int attempt, String previousError) throws Exception {
        int approximateWords = Math.max(90, (int)Math.round(r.targetSeconds() * 2.6));
        String languageName = languageName(r.language());
        String scriptInstruction = switch (r.language()) {
            case "hi" -> "Write all lesson prose in natural Hindi using Devanagari script (हिन्दी).";
            case "mr" -> "Write all lesson prose in natural Marathi using Devanagari script (मराठी), not Hindi or English.";
            case "ta" -> "Write all lesson prose in natural Tamil using Tamil script (தமிழ்).";
            case "te" -> "Write all lesson prose in natural Telugu using Telugu script (తెలుగు).";
            case "bn" -> "Write all lesson prose in natural Bengali using Bengali script (বাংলা).";
            default -> "Write all lesson prose in English.";
        };
        String correction = attempt == 0 ? "" : " IMPORTANT CORRECTION: The previous response failed validation with this error: " + previousError + ". Fix that exact problem. Ensure the scenes array has 3 to 6 scene objects and at least one diagram scene. The diagram scene's items field MUST be a JSON array containing 4 to 6 non-empty strings in the correct teaching order; never omit items, use an object, or return fewer than 4 items. Also write all title, headings, narration, diagram items, and labels in " + languageName + " using its native script. Keep programming keywords/code only when technically necessary. This is attempt " + (attempt + 1) + ".";
        String prompt = "You are a multilingual teacher creating a complete visual lesson. TARGET LANGUAGE: " + languageName + " (code " + r.language() + "). " + scriptInstruction +
                " Every user-facing text field must be in the target language, including title, headings, narration, diagram items and labels. Do not write English explanations. Translate the topic itself; retain English only for proper names and unavoidable technical tokens. If code is useful, write explanatory comments in the selected language while keeping programming syntax/keywords valid. " + correction +
                " Return valid JSON only, no markdown, with exactly this shape: {\"title\":string,\"level\":\"beginner|intermediate\",\"language\":string,\"scenes\":[{\"type\":\"title_card|diagram|code_walkthrough\",\"heading\":string,\"narration\":string,\"code\":string,\"highlightLines\":[1],\"items\":[string],\"labels\":[string]}]}. Use 3 to 6 scenes and include at least one diagram scene. In the diagram scene, items MUST be an array of 4 to 6 short, non-empty strings describing the actual topic in logical order. Never leave items empty. Labels are separate from items. Keep narration concise. Aim for about " + approximateWords + " spoken words total for a " + r.targetSeconds() + " second lesson. Topic: " + r.topic() + ". Difficulty: " + r.level() + ". Do not invent executable code for non-code topics.";
        Map<String,Object> body = Map.of("model", model, "stream", false, "format", "json", "prompt", prompt,
                "options", Map.of("temperature", 0.1, "num_predict", 2000, "repeat_penalty", 1.08));
        HttpRequest req = HttpRequest.newBuilder(URI.create(ollamaUrl + "/api/generate")).timeout(Duration.ofSeconds(300))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Ollama status " + response.statusCode());
        JsonNode root = mapper.readTree(response.body());
        JsonNode result = mapper.readTree(root.path("response").asText());
        String title = requiredText(result, "title");
        String level = r.level(); // Keep request metadata stable; never trust model-translated metadata.
        JsonNode scenesNode = result.path("scenes");
        if (!scenesNode.isArray() || scenesNode.size() < 3 || scenesNode.size() > 6) throw new IllegalArgumentException("Expected 3-6 scenes");
        List<Scene> parsedScenes = mapper.convertValue(scenesNode, new TypeReference<List<Scene>>(){});
        List<Scene> scenes = new ArrayList<>(parsedScenes.size());
        Set<String> allowed = Set.of("title_card", "diagram", "code_walkthrough");
        boolean hasDiagram = false;
        for (Scene s : parsedScenes) {
            if (!allowed.contains(s.type()) || blank(s.heading()) || blank(s.narration())) throw new IllegalArgumentException("Invalid scene");
            if ("diagram".equals(s.type())) {
                hasDiagram = true;
                List<String> repairedItems = repairDiagramItems(s.items(), s.labels(), r);
                s = new Scene(s.type(), s.heading(), s.narration(), s.code(), s.highlightLines(), repairedItems, s.labels());
            }
            if (s.heading().length() > 180 || s.narration().length() > 1200 || (s.code() != null && s.code().length() > 1800)) throw new IllegalArgumentException("Scene too long");
            scenes.add(s);
        }
        if (!hasDiagram) throw new IllegalArgumentException("Missing diagram scene");
        Lesson lesson = new Lesson(title, level, r.language(), scenes, false);
        if (!hasRequiredScript(lesson, r.language())) throw new IllegalArgumentException("Generated prose is not in requested language " + languageName);
        return lesson;
    }


    /**
     * Small local models sometimes return an empty or undersized diagram list even when
     * prompted otherwise. Recover from labels when possible; otherwise supplement with
     * localized, ordered fallback steps so one malformed list does not discard the lesson.
     */
    private List<String> repairDiagramItems(List<String> items, List<String> labels, LessonRequest request) {
        List<String> cleanItems = cleanList(items);
        if (cleanItems.size() >= 3) return cleanItems.size() > 8 ? List.copyOf(cleanItems.subList(0, 8)) : List.copyOf(cleanItems);

        List<String> cleanLabels = cleanList(labels);
        if (cleanLabels.size() >= 3) {
            return List.copyOf(cleanLabels.subList(0, Math.min(8, cleanLabels.size())));
        }

        List<String> repaired = new ArrayList<>(cleanItems);
        for (Scene fallbackScene : fallback(request).scenes()) {
            if (!"diagram".equals(fallbackScene.type())) continue;
            for (String fallbackItem : fallbackScene.items()) {
                if (repaired.size() >= 3) break;
                if (!repaired.contains(fallbackItem)) repaired.add(fallbackItem);
            }
        }
        if (repaired.size() < 3) throw new IllegalArgumentException("Unable to repair diagram items");
        System.err.println("Repaired incomplete diagram items for topic: " + request.topic());
        return List.copyOf(repaired.subList(0, Math.min(8, repaired.size())));
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) return new ArrayList<>();
        List<String> clean = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) clean.add(value.trim());
        }
        return clean;
    }

    private String languageName(String code) {
        return switch (code) { case "hi" -> "Hindi"; case "ta" -> "Tamil"; case "te" -> "Telugu"; case "bn" -> "Bengali"; case "mr" -> "Marathi"; default -> "English"; };
    }

    private boolean hasRequiredScript(Lesson lesson, String language) {
        if ("en".equals(language)) return true;
        Character.UnicodeScript expected = switch (language) {
            case "hi", "mr" -> Character.UnicodeScript.DEVANAGARI;
            case "ta" -> Character.UnicodeScript.TAMIL;
            case "te" -> Character.UnicodeScript.TELUGU;
            case "bn" -> Character.UnicodeScript.BENGALI;
            default -> null;
        };
        if (expected == null) return false;
        StringBuilder prose = new StringBuilder().append(lesson.title()).append(' ');
        for (Scene scene : lesson.scenes()) {
            prose.append(scene.heading()).append(' ').append(scene.narration()).append(' ');
            scene.items().forEach(v -> prose.append(v).append(' '));
            scene.labels().forEach(v -> prose.append(v).append(' '));
        }
        int letters = 0, nativeLetters = 0;
        String text = prose.toString();
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            if (Character.isLetter(cp)) {
                letters++;
                if (Character.UnicodeScript.of(cp) == expected) nativeLetters++;
            }
            offset += Character.charCount(cp);
        }
        return nativeLetters >= 12 && letters > 0 && (double) nativeLetters / letters >= 0.15;
    }
    private String requiredText(JsonNode n, String key) { String s=n.path(key).asText("").trim(); if(s.isEmpty()) throw new IllegalArgumentException("Missing " + key); return s; }
    private boolean blank(String s) { return s == null || s.isBlank(); }

    public Lesson fallback(LessonRequest r) {
        String title;
        String firstHeading;
        String firstNarration;
        String diagramHeading;
        String diagramNarration;
        String diagramItems;
        String diagramLabels;
        String finalHeading;
        String finalNarration;
        switch (r.language()) {
            case "hi" -> {
                title = "आइए सीखें"; firstHeading = "आइए समझें";
                firstNarration = "आइए इस विषय को चरण-दर-चरण समझते हैं।";
                diagramHeading = "मुख्य विचार"; diagramNarration = "हर प्रक्रिया में शुरुआत, कुछ चरण और एक परिणाम होता है।";
                diagramItems = "शुरुआत|प्रक्रिया|परिणाम"; diagramLabels = "यहाँ से शुरू करें|हर चरण का पालन करें|परिणाम देखें";
                finalHeading = "याद रखने योग्य बातें"; finalNarration = "विचार को छोटे चरणों में बाँटें और हर चरण के बाद परिणाम जाँचें।";
            }
            case "ta" -> {
                title = "படிப்படியாகக் கற்போம்"; firstHeading = "புரிந்துகொள்வோம்";
                firstNarration = "இந்தக் கருத்தை படிப்படியாகப் புரிந்துகொள்வோம்.";
                diagramHeading = "முக்கிய கருத்து"; diagramNarration = "ஒவ்வொரு செயல்முறையிலும் தொடக்கம், படிகள், முடிவு இருக்கும்.";
                diagramItems = "தொடக்கம்|செயல்முறை|முடிவு"; diagramLabels = "இங்கே தொடங்கு|ஒவ்வொரு படியையும் பின்பற்று|முடிவைப் பார்";
                finalHeading = "நினைவில் கொள்ளுங்கள்"; finalNarration = "கருத்தைச் சிறிய படிகளாகப் பிரித்து, ஒவ்வொரு படிக்குப் பிறகும் முடிவைச் சரிபார்க்கவும்.";
            }
            case "te" -> {
                title = "దశలవారీగా నేర్చుకుందాం"; firstHeading = "అర్థం చేసుకుందాం";
                firstNarration = "ఈ విషయాన్ని దశలవారీగా అర్థం చేసుకుందాం.";
                diagramHeading = "ముఖ్యమైన విషయం"; diagramNarration = "ప్రతి ప్రక్రియలో ప్రారంభం, దశలు, ఫలితం ఉంటాయి.";
                diagramItems = "ప్రారంభం|ప్రక్రియ|ఫలితం"; diagramLabels = "ఇక్కడ ప్రారంభించండి|దశలను అనుసరించండి|ఫలితాన్ని చూడండి";
                finalHeading = "గుర్తుంచుకోండి"; finalNarration = "విషయాన్ని చిన్న దశలుగా విభజించి ప్రతి దశ తర్వాత ఫలితాన్ని తనిఖీ చేయండి.";
            }
            case "bn" -> {
                title = "ধাপে ধাপে শিখি"; firstHeading = "বুঝে নিই";
                firstNarration = "এই বিষয়টি ধাপে ধাপে বুঝে নিই।";
                diagramHeading = "মূল ধারণা"; diagramNarration = "প্রতিটি প্রক্রিয়ায় শুরু, কয়েকটি ধাপ এবং একটি ফলাফল থাকে।";
                diagramItems = "শুরু|প্রক্রিয়া|ফলাফল"; diagramLabels = "এখান থেকে শুরু|প্রতিটি ধাপ অনুসরণ করুন|ফলাফল দেখুন";
                finalHeading = "মনে রাখুন"; finalNarration = "ধারণাটিকে ছোট ধাপে ভাগ করুন এবং প্রতিটি ধাপের পরে ফলাফল যাচাই করুন।";
            }
            case "mr" -> {
                title = "चला शिकूया"; firstHeading = "समजून घेऊया";
                firstNarration = "हा विषय टप्प्याटप्प्याने समजून घेऊया.";
                diagramHeading = "मुख्य कल्पना"; diagramNarration = "प्रत्येक प्रक्रियेत सुरुवात, काही टप्पे आणि एक परिणाम असतो.";
                diagramItems = "सुरुवात|प्रक्रिया|परिणाम"; diagramLabels = "इथून सुरू करा|प्रत्येक टप्पा पाळा|परिणाम पाहा";
                finalHeading = "लक्षात ठेवा"; finalNarration = "कल्पना लहान टप्प्यांत विभागा आणि प्रत्येक टप्प्यानंतर परिणाम तपासा.";
            }
            default -> {
                title = "Learn: " + r.topic(); firstHeading = "Understand the concept";
                firstNarration = "Let's understand " + r.topic() + " step by step.";
                diagramHeading = "The main idea"; diagramNarration = "A process has a starting point, steps, and a result.";
                diagramItems = "Start|Process|Result"; diagramLabels = "Begin here|Follow each step|See the outcome";
                finalHeading = "Remember these steps"; finalNarration = "Break the idea into small steps and check the result after each step.";
            }
        }
        List<String> items = List.of(diagramItems.split("\\|"));
        List<String> labels = List.of(diagramLabels.split("\\|"));
        String genericCode = switch (r.language()) {
            case "hi" -> "// चरण 1: जानकारी समझें\n// चरण 2: प्रक्रिया का पालन करें\n// चरण 3: परिणाम जाँचें";
            case "ta" -> "// படி 1: உள்ளீட்டைப் புரிந்துகொள்\n// படி 2: செயல்முறையைப் பின்பற்று\n// படி 3: முடிவைச் சரிபார்";
            case "te" -> "// దశ 1: ఇన్‌పుట్‌ను అర్థం చేసుకోండి\n// దశ 2: ప్రక్రియను అనుసరించండి\n// దశ 3: ఫలితాన్ని తనిఖీ చేయండి";
            case "bn" -> "// ধাপ ১: ইনপুট বুঝুন\n// ধাপ ২: প্রক্রিয়া অনুসরণ করুন\n// ধাপ ৩: ফলাফল যাচাই করুন";
            case "mr" -> "// टप्पा १: इनपुट समजून घ्या\n// टप्पा २: प्रक्रिया पाळा\n// टप्पा ३: परिणाम तपासा";
            default -> "// Step 1: Understand the input\n// Step 2: Follow the process\n// Step 3: Check the result";
        };
        List<Scene> scenes = List.of(
            new Scene("title_card", firstHeading, firstNarration, "", List.of(), List.of(), List.of()),
            new Scene("diagram", diagramHeading, diagramNarration, "", List.of(), items, labels),
            new Scene("code_walkthrough", finalHeading, finalNarration, genericCode, List.of(1), List.of(), List.of())
        );
        return new Lesson(title, r.level(), r.language(), scenes, true);
    }
}
