package com.blu.teacher.service;

import com.blu.teacher.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.List;

@Service
public class VideoExporter {
    @Value("${blu.output-dir:./generated}") private String outputDir;
    @Value("${blu.espeak-command:espeak-ng}") private String espeak;
    @Value("${blu.kokoro-command:python3}") private String kokoroCommand;
    @Value("${blu.kokoro-script:./tts/kokoro_synthesize.py}") private String kokoroScript;
    @Value("${blu.kokoro-model:./tts/models/kokoro-v1.0.onnx}") private String kokoroModel;
    @Value("${blu.kokoro-voices:./tts/models/voices-v1.0.bin}") private String kokoroVoices;
    @Value("${blu.kokoro-voice-en:af_heart}") private String kokoroEnglishVoice;
    @Value("${blu.kokoro-speed:1.0}") private double kokoroSpeed;
    private volatile String lastAudioEngine = "eSpeak NG (local fallback)";
    @Value("${blu.ffmpeg-command:ffmpeg}") private String ffmpeg;
    @Value("${blu.ffprobe-command:ffprobe}") private String ffprobe;
    private final java.util.concurrent.ConcurrentMap<String, Font> bundledFonts = new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String,Object> prepareNarration(Lesson lesson) throws Exception {
        Path root = Paths.get(outputDir).toAbsolutePath();
        Files.createDirectories(root);
        String id = "audio-" + UUID.randomUUID().toString().substring(0, 8);
        Path job = root.resolve(id);
        Files.createDirectories(job);
        List<Map<String,Object>> tracks = new ArrayList<>();
        for (int i = 0; i < lesson.scenes().size(); i++) {
            Scene scene = lesson.scenes().get(i);
            Path wav = job.resolve(String.format(Locale.ROOT, "scene-%02d.wav", i));
            synthesize(scene.narration(), lesson.language(), wav);
            double seconds = duration(wav);
            if (seconds <= 0.1) seconds = Math.max(2.5, scene.narration().length() / 12.0);
            tracks.add(Map.of("sceneIndex", i, "audioUrl", "/generated/" + id + "/" + wav.getFileName(), "durationSeconds", Math.round(seconds * 100.0) / 100.0));
        }
        return Map.of("tracks", tracks, "language", lesson.language(), "audioEngine", lastAudioEngine);
    }

    public Map<String,Object> export(Lesson lesson) throws Exception {
        Path root = Paths.get(outputDir).toAbsolutePath(); Files.createDirectories(root);
        String id = UUID.randomUUID().toString().substring(0, 8);
        Path job = root.resolve(id); Files.createDirectories(job);
        Path audioDir=job.resolve("audio"), framesDir=job.resolve("frames"); Files.createDirectories(audioDir); Files.createDirectories(framesDir);
        List<Path> audioFiles = new ArrayList<>(); List<Double> durations = new ArrayList<>();
        for (int i=0;i<lesson.scenes().size();i++) {
            Scene s=lesson.scenes().get(i); Path wav=audioDir.resolve(String.format(Locale.ROOT,"scene-%02d.wav",i));
            synthesize(s.narration(), lesson.language(), wav);
            double seconds=duration(wav); if(seconds <= 0.1) seconds=Math.max(2.5, s.narration().length()/12.0);
            seconds=Math.max(1.5, seconds); durations.add(seconds); audioFiles.add(wav);
        }
        Path concat=job.resolve("audio-list.txt");
        Path silence=job.resolve("pause.wav");
        LocalProcess.run(List.of(ffmpeg,"-y","-v","error","-f","lavfi","-i","anullsrc=r=44100:cl=stereo","-t","0.35",silence.toString()),job,20);
        StringBuilder list=new StringBuilder();
        for(Path p:audioFiles) {
            list.append("file '").append(concatPath(p)).append("'\n");
            list.append("file '").append(concatPath(silence)).append("'\n");
        }
        Files.writeString(concat,list);
        Path combinedAudio=job.resolve("combined.wav");
        LocalProcess.run(List.of(ffmpeg,"-y","-v","error","-f","concat","-safe","0","-i",concat.toString(),"-ac","2","-ar","44100",combinedAudio.toString()),job,120);
        int frame=0; List<Double> sceneStarts=new ArrayList<>(); double elapsed=0;
        for(int i=0;i<lesson.scenes().size();i++) {
            sceneStarts.add(elapsed); Scene s=lesson.scenes().get(i); double duration=durations.get(i)+0.35; int count=Math.max(15,(int)Math.ceil(duration*12));
            for(int j=0;j<count;j++) { BufferedImage image=draw(lesson,s,i,j,count); ImageIO.write(image,"png",framesDir.resolve(String.format(Locale.ROOT,"frame-%06d.png",frame++)).toFile()); }
            elapsed+=duration;
        }
        Path mp4=root.resolve("lesson-"+id+".mp4");
        LocalProcess.run(List.of(ffmpeg,"-y","-v","error","-framerate","12","-i",framesDir.resolve("frame-%06d.png").toString(),"-i",combinedAudio.toString(),"-vf","scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,format=yuv420p","-c:v","libx264","-preset","ultrafast","-crf","25","-c:a","aac","-b:a","128k","-shortest","-movflags","+faststart",mp4.toString()),job,240);
        Path srt=root.resolve("lesson-"+id+".srt"); writeSrt(lesson,durations,srt);
        return Map.of("videoUrl","/generated/lesson-"+id+".mp4","subtitleUrl","/generated/lesson-"+id+".srt","durationSeconds",Math.round(elapsed),"language",lesson.language(),"audioEngine",lastAudioEngine);
    }
    private String concatPath(Path path) {
        // FFmpeg concat manifests use forward slashes even on Windows.
        return path.toAbsolutePath().toString().replace("\\", "/").replace("'", "\\'");
    }

    private void synthesize(String text,String language,Path out) throws Exception {
        // Kokoro is used for English when its local model files and Python package are installed.
        // All other languages, and any Kokoro failure, keep the existing eSpeak NG offline path.
        if ("en".equalsIgnoreCase(language) && kokoroAssetsAvailable()) {
            try {
                synthesizeKokoro(text, out);
                if (Files.isRegularFile(out) && Files.size(out) > 44) {
                    lastAudioEngine = "Kokoro neural TTS (offline)";
                    return;
                }
                throw new IOException("Kokoro did not create a valid WAV file");
            } catch (Exception ex) {
                System.err.println("Kokoro narration failed; falling back to eSpeak NG: " + ex.getMessage());
                Files.deleteIfExists(out);
            }
        }
        String voice=System.getenv().getOrDefault("BLU_VOICE_"+language.toUpperCase(Locale.ROOT),defaultVoice(language));
        LocalProcess.run(List.of(espeak,"-v",voice,"-s","145","-w",out.toString(),text),out.getParent(),45);
        lastAudioEngine = "eSpeak NG (local fallback)";
    }

    private boolean kokoroAssetsAvailable() {
        return Files.isRegularFile(Paths.get(kokoroScript))
                && Files.isRegularFile(Paths.get(kokoroModel))
                && Files.isRegularFile(Paths.get(kokoroVoices));
    }

    private void synthesizeKokoro(String text, Path out) throws Exception {
        List<String> command = List.of(kokoroCommand, kokoroScript,
                "--model", kokoroModel,
                "--voices", kokoroVoices,
                "--voice", kokoroEnglishVoice,
                "--speed", Double.toString(kokoroSpeed),
                "--output", out.toAbsolutePath().toString());
        Process process = new ProcessBuilder(command).directory(Paths.get(".").toAbsolutePath().toFile()).redirectErrorStream(true).start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) { in.transferTo(output); }
            catch (IOException ignored) { }
        });
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Kokoro synthesis timed out after 180 seconds");
        }
        reader.join(1000);
        if (process.exitValue() != 0) {
            throw new IOException("Kokoro command failed: " + output.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private String defaultVoice(String lang) { return switch(lang) { case "hi" -> "hi"; case "ta" -> "ta"; case "te" -> "te"; case "bn" -> "bn"; case "mr" -> "mr"; default -> "en-us"; }; }
    private String titleCardLabel(String language, String level) {
        boolean intermediate = "intermediate".equalsIgnoreCase(level);
        return switch (language) {
            case "hi" -> "दृश्य पाठ • " + (intermediate ? "मध्यम स्तर" : "शुरुआती स्तर");
            case "mr" -> "दृश्य धडा • " + (intermediate ? "मध्यम स्तर" : "प्रारंभिक स्तर");
            case "ta" -> "காட்சிப் பாடம் • " + (intermediate ? "இடைநிலை" : "தொடக்கநிலை");
            case "te" -> "దృశ్య పాఠం • " + (intermediate ? "మధ్యస్థ స్థాయి" : "ప్రారంభ స్థాయి");
            case "bn" -> "দৃশ্য পাঠ • " + (intermediate ? "মধ্যম স্তর" : "প্রাথমিক স্তর");
            default -> "A visual lesson • " + (intermediate ? "Intermediate" : "Beginner");
        };
    }
    private double duration(Path wav) { try { Process p=new ProcessBuilder(ffprobe,"-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",wav.toString()).start(); String s=new String(p.getInputStream().readAllBytes()).trim(); p.waitFor(); return Double.parseDouble(s); } catch(Exception e) { return 0; } }
    /** Loads bundled Noto fonts once so Indic captions render offline without OS font installation. */
    private Font fontFor(String language, int style, float size) {
        String file = switch (language == null ? "en" : language.toLowerCase(Locale.ROOT)) {
            case "hi", "mr" -> "NotoSansDevanagari-Regular.ttf";
            case "ta" -> "NotoSansTamil-Regular.ttf";
            case "te" -> "NotoSansTelugu-Regular.ttf";
            case "bn" -> "NotoSansBengali-Regular.ttf";
            default -> null;
        };
        if (file == null) return new Font("SansSerif", style, Math.round(size));
        Font base = bundledFonts.computeIfAbsent(file, key -> {
            try (InputStream in = VideoExporter.class.getResourceAsStream("/static/fonts/" + key)) {
                if (in == null) throw new IOException("Bundled font resource missing: " + key);
                return Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (Exception ex) {
                return new Font("SansSerif", Font.PLAIN, 12);
            }
        });
        return base.deriveFont(style, size);
    }

    private BufferedImage draw(Lesson lesson,Scene s,int sceneIndex,int tick,int total) {
        int w=1280,h=720; BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB); Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(new Color(13,24,48)); g.fillRect(0,0,w,h);
        g.setColor(new Color(34,52,85)); g.fillRoundRect(55,45,1170,600,30,30);
        g.setColor(new Color(93,180,255)); g.fillRoundRect(80,78,12,48,8,8);
        g.setFont(fontFor(lesson.language(),Font.BOLD,34)); g.setColor(Color.WHITE); drawWrapped(g,s.heading(),115,110,1050,44,2,lesson.language());
        g.setFont(fontFor(lesson.language(),Font.PLAIN,22)); g.setColor(new Color(202,218,242));
        switch(s.type()) {
            case "title_card" -> { g.setFont(fontFor(lesson.language(),Font.BOLD,52)); g.setColor(new Color(117,208,255)); drawWrapped(g,lesson.title(),120,245,1030,62,3,lesson.language()); g.setFont(fontFor(lesson.language(),Font.PLAIN,27)); g.setColor(Color.WHITE); drawWrapped(g,titleCardLabel(lesson.language(),lesson.level()),120,440,1030,34,2,lesson.language()); }
            case "code_walkthrough" -> { g.setColor(new Color(9,15,30)); g.fillRoundRect(115,180,1050,300,18,18); g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,27)); String code=s.code()==null?"":s.code(); String[] lines=code.split("\\n"); for(int i=0;i<Math.min(lines.length,8);i++){ if(s.highlightLines().contains(i+1)){g.setColor(new Color(35,93,116));g.fillRoundRect(140,215+i*43,980,37,8,8);} g.setColor(s.highlightLines().contains(i+1)?Color.WHITE:new Color(170,194,224)); drawMultilingualString(g,(i+1)+"   "+lines[i],155,242+i*43,lesson.language()); } }
            default -> drawFlowchart(g, s, lesson.language(), tick, total);
        }
        g.setFont(fontFor(lesson.language(),Font.PLAIN,18));g.setColor(Color.WHITE);drawWrapped(g,s.narration(),105,505,1070,22,5,lesson.language());
        g.setColor(new Color(65,80,110));g.fillRoundRect(100,625,1080,10,8,8);g.setColor(new Color(73,194,255));g.fillRoundRect(100,625,(int)(1080*((sceneIndex+(tick/(double)Math.max(1,total)))/lesson.scenes().size())),10,8,8);
        g.setFont(new Font("SansSerif",Font.PLAIN,16));g.setColor(new Color(177,194,221));g.drawString("BLU VISUAL TEACHER   •   "+lesson.language().toUpperCase(Locale.ROOT),100,675);g.dispose();return image;
    }

    /** Render a readable, ordered flowchart with arrows and optional per-step labels. */
    private void drawFlowchart(Graphics2D g, Scene scene, String language, int tick, int total) {
        List<String> items = scene.items().isEmpty() ? List.of("Start", "Process", "Result") : scene.items();
        int n = Math.min(items.size(), 6);
        int nodeX = 215, nodeW = 850, nodeH = n >= 6 ? 38 : (n == 5 ? 43 : 48);
        int gap = n >= 6 ? 8 : 10;
        int totalHeight = n * nodeH + (n - 1) * gap;
        int startY = 180 + Math.max(0, (280 - totalHeight) / 2);
        List<String> labels = scene.labels() == null ? List.of() : scene.labels();
        for (int i = 0; i < n; i++) {
            int y = startY + i * (nodeH + gap);
            double pulse = Math.sin((tick / (double)Math.max(1, total)) * Math.PI * 2 + i) * 2.0;
            int offset = (int)Math.round(pulse);
            Color fill = (i == 0 || i == n - 1) ? new Color(35, 105, 88) : new Color(42, 91, 139);
            g.setColor(fill);
            g.fillRoundRect(nodeX, y + offset, nodeW, nodeH, 14, 14);
            g.setColor(new Color(115, 206, 255));
            g.setStroke(new BasicStroke(2));
            g.drawRoundRect(nodeX, y + offset, nodeW, nodeH, 14, 14);
            String item = items.get(i);
            String label = i < labels.size() ? labels.get(i) : "";
            if (!label.isBlank() && nodeH >= 38) {
                g.setFont(fontFor(language, Font.BOLD, 16)); g.setColor(Color.WHITE);
                drawCentered(g, item, nodeX + 18, y + offset + 17, nodeW - 36, language);
                g.setFont(fontFor(language, Font.PLAIN, 11)); g.setColor(new Color(218, 234, 250));
                drawCentered(g, label, nodeX + 18, y + offset + nodeH - 7, nodeW - 36, language);
            } else {
                g.setFont(fontFor(language, Font.BOLD, n >= 6 ? 16 : 19)); g.setColor(Color.WHITE);
                drawCentered(g, item, nodeX + 18, y + offset + nodeH / 2 + 6, nodeW - 36, language);
            }
            if (i < n - 1) {
                int cx = nodeX + nodeW / 2;
                int y1 = y + offset + nodeH + 1;
                int y2 = y + nodeH + gap - 1;
                g.setColor(new Color(114, 206, 255)); g.setStroke(new BasicStroke(2.5f));
                g.drawLine(cx, y1, cx, y2 - 3);
                g.fillPolygon(new int[]{cx, cx - 5, cx + 5}, new int[]{y2, y2 - 7, y2 - 7}, 3);
            }
        }
    }

    private void drawCentered(Graphics2D g, String text, int x, int baseline, int maxWidth, String language) {
        if (text == null || text.isBlank()) return;
        int width = multilingualWidth(g, text, language);
        if (width <= maxWidth) {
            drawMultilingualString(g, text, x + (maxWidth - width) / 2, baseline, language);
        } else {
            drawWrapped(g, text, x, baseline - 12, maxWidth, 18, 1, language);
        }
    }

    private record FontRun(String text, Font font) {}

    private List<FontRun> fontRuns(Graphics2D g, String text, String language) {
        List<FontRun> runs = new ArrayList<>();
        if (text == null || text.isEmpty()) return runs;
        Font current = g.getFont();
        StringBuilder part = new StringBuilder();
        Font partFont = null;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            int kind = Character.getType(cp);
            boolean mark = kind == Character.NON_SPACING_MARK || kind == Character.COMBINING_SPACING_MARK || kind == Character.ENCLOSING_MARK;
            Font nextFont;
            if ((script == Character.UnicodeScript.DEVANAGARI && ("hi".equals(language) || "mr".equals(language)))) nextFont = fontFor(language, current.getStyle(), current.getSize2D());
            else if (script == Character.UnicodeScript.TAMIL && "ta".equals(language)) nextFont = fontFor(language, current.getStyle(), current.getSize2D());
            else if (script == Character.UnicodeScript.TELUGU && "te".equals(language)) nextFont = fontFor(language, current.getStyle(), current.getSize2D());
            else if (script == Character.UnicodeScript.BENGALI && "bn".equals(language)) nextFont = fontFor(language, current.getStyle(), current.getSize2D());
            else if (mark && partFont != null) nextFont = partFont;
            else nextFont = new Font("SansSerif", current.getStyle(), Math.round(current.getSize2D()));
            if (partFont != null && !partFont.equals(nextFont)) {
                runs.add(new FontRun(part.toString(), partFont)); part.setLength(0);
            }
            part.appendCodePoint(cp); partFont = nextFont; offset += Character.charCount(cp);
        }
        if (part.length() > 0) runs.add(new FontRun(part.toString(), partFont));
        return runs;
    }

    private int multilingualWidth(Graphics2D g, String text, String language) {
        int width = 0;
        for (FontRun run : fontRuns(g, text, language)) width += g.getFontMetrics(run.font()).stringWidth(run.text());
        return width;
    }

    private void drawMultilingualString(Graphics2D g, String text, int x, int baseline, String language) {
        int cursor = x;
        for (FontRun run : fontRuns(g, text, language)) {
            g.setFont(run.font()); g.drawString(run.text(), cursor, baseline);
            cursor += g.getFontMetrics(run.font()).stringWidth(run.text());
        }
    }

    private void drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight, int maxLines, String language) {
        if (text == null || text.isBlank()) return;
        List<String> output = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.replace('\n', ' ').split("\\s+")) {
            if (word.isEmpty()) continue;
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (multilingualWidth(g, candidate, language) <= maxWidth) { line.setLength(0); line.append(candidate); continue; }
            if (line.length() > 0) { output.add(line.toString()); line.setLength(0); }
            if (multilingualWidth(g, word, language) <= maxWidth) line.append(word);
            else {
                StringBuilder chunk = new StringBuilder();
                for (int offset = 0; offset < word.length();) {
                    int cp = word.codePointAt(offset); String next = chunk.toString() + new String(Character.toChars(cp));
                    if (multilingualWidth(g, next, language) > maxWidth && chunk.length() > 0) { output.add(chunk.toString()); chunk.setLength(0); }
                    chunk.appendCodePoint(cp); offset += Character.charCount(cp);
                }
                line.append(chunk);
            }
        }
        if (line.length() > 0) output.add(line.toString());
        int count = Math.min(maxLines, output.size());
        for (int i = 0; i < count; i++) {
            String lineText = output.get(i);
            if (i == maxLines - 1 && output.size() > maxLines) {
                while (!lineText.isEmpty() && multilingualWidth(g, lineText + "…", language) > maxWidth) lineText = lineText.substring(0, lineText.length() - 1);
                lineText += "…";
            }
            drawMultilingualString(g, lineText, x, y + i * lineHeight, language);
        }
    }
    private void writeSrt(Lesson lesson,List<Double> durations,Path path)throws IOException{StringBuilder b=new StringBuilder();double t=0;for(int i=0;i<lesson.scenes().size();i++){double end=t+durations.get(i)+.35;b.append(i+1).append('\n').append(timestamp(t)).append(" --> ").append(timestamp(end)).append('\n').append(lesson.scenes().get(i).narration()).append("\n\n");t=end;}Files.writeString(path,b.toString());}
    private String timestamp(double sec){long ms=(long)(sec*1000);return String.format(Locale.ROOT,"%02d:%02d:%02d,%03d",ms/3600000,(ms/60000)%60,(ms/1000)%60,ms%1000);}
}
