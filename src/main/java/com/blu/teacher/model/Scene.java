package com.blu.teacher.model;

import java.util.List;

public record Scene(String type, String heading, String narration, String code,
                    List<Integer> highlightLines, List<String> items, List<String> labels) {
    public Scene {
        highlightLines = highlightLines == null ? List.of() : List.copyOf(highlightLines);
        items = items == null ? List.of() : List.copyOf(items);
        labels = labels == null ? List.of() : List.copyOf(labels);
    }
}
