package lol.candyriya.continuum.paper.integrations.papi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaceholderAnswersTest {

    private static PlaceholderAnswers answers(List<String> modulesEnabled) {
        return new PlaceholderAnswers("1.0.0", "backend", modulesEnabled, "default");
    }

    @Test
    void versionPassesTheDescriptorVersionThrough() {
        assertEquals("1.0.0", answers(List.of()).answer("version"));
    }

    @Test
    void roleIsTheConfiguredNetworkRole() {
        var coordinator = new PlaceholderAnswers("1.0.0", "coordinator", List.of(), "custom");
        assertEquals("coordinator", coordinator.answer("role"));
        assertEquals("backend", answers(List.of()).answer("role"));
    }

    @Test
    void modulesEnabledIsACommaSeparatedList() {
        assertEquals("inventory,pdc", answers(List.of("inventory", "pdc")).answer("modules_enabled"));
    }

    @Test
    void modulesEnabledOfNothingIsAnEmptyStringNotNull() {
        assertEquals("", answers(List.of()).answer("modules_enabled"));
    }

    @Test
    void restoreOrderReportsDefaultAndCustom() {
        assertEquals("default", new PlaceholderAnswers("1.0.0", "backend", List.of(), "default").answer("restore_order"));
        assertEquals("custom", new PlaceholderAnswers("1.0.0", "backend", List.of(), "custom").answer("restore_order"));
    }

    @Test
    void unknownKeysReturnNullSoTheHostRendersNothing() {
        assertNull(answers(List.of()).answer("restore_status"));
        assertNull(answers(List.of()).answer("uptime"));
        assertNull(answers(List.of()).answer(""));
        assertNull(answers(List.of()).answer("nonsense"));
    }

    @Test
    void keysAreCaseInsensitiveAndTolerateWhitespace() {
        assertEquals("1.0.0", answers(List.of()).answer("VERSION"));
        assertEquals("1.0.0", answers(List.of()).answer("  version  "));
        assertEquals("backend", answers(List.of()).answer("Role"));
    }

    @Test
    void nullKeyReturnsNullInsteadOfThrowing() {
        assertNull(answers(List.of()).answer(null));
    }

    @Test
    void everyDocumentedKeyIsAnswered() {
        var subject = answers(List.of("inventory"));
        assertEquals("1.0.0", subject.answer("version"));
        assertEquals("inventory", subject.answer("modules_enabled"));
        assertEquals("backend", subject.answer("role"));
        assertEquals("default", subject.answer("restore_order"));
    }
}
