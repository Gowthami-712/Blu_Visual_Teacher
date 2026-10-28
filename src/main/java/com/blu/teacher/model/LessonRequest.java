package com.blu.teacher.model;

import jakarta.validation.constraints.*;

public record LessonRequest(
    @NotBlank @Size(max=180) String topic,
    @NotBlank String level,
    @NotBlank String language,
    @Min(30) @Max(90) int targetSeconds
) {
    public LessonRequest {
        if (level != null && !level.equals("beginner") && !level.equals("intermediate")) throw new IllegalArgumentException("level must be beginner or intermediate");
        if (language != null && !java.util.Set.of("en","hi","ta","te","bn","mr").contains(language)) throw new IllegalArgumentException("language must be en, hi, ta, te, bn or mr");
    }
}
