package com.blu.teacher;

import com.blu.teacher.model.Lesson;
import com.blu.teacher.model.LessonRequest;
import com.blu.teacher.service.LessonPlanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LessonPlannerTest {
    @Test void fallbackContainsThreeAllowedScenes() {
        Lesson lesson = new LessonPlanner().fallback(new LessonRequest("Python loops", "beginner", "en", 45));
        assertTrue(lesson.fallback());
        assertEquals(3, lesson.scenes().size());
        assertTrue(lesson.scenes().stream().allMatch(s -> s.heading() != null && !s.heading().isBlank()));
    }

    @Test void fallbackUsesTamilScriptForTamilRequest() {
        Lesson lesson = new LessonPlanner().fallback(new LessonRequest("Python loop", "beginner", "ta", 45));
        assertEquals("ta", lesson.language());
        assertTrue(lesson.scenes().get(0).narration().matches(".*[\\u0B80-\\u0BFF].*"));
    }
}
