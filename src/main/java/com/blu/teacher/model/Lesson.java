package com.blu.teacher.model;

import java.util.List;

public record Lesson(String title, String level, String language, List<Scene> scenes, boolean fallback) {
    public Lesson { scenes = List.copyOf(scenes); }
}
