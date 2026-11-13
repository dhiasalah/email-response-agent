package com.emailagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The packages form a layered design: no package depends on another that depends back on it, directly or through
 * a chain. A cycle means two packages can only be understood, tested and changed together.
 */
class ArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java/com/emailagent");
    private static final Pattern PACKAGE = Pattern.compile("^package com\\.emailagent\\.(\\w+);", Pattern.MULTILINE);
    private static final Pattern REFERENCE = Pattern.compile("com\\.emailagent\\.(\\w+)\\.[A-Z]");

    @Test
    void packagesHaveNoDependencyCycles() throws IOException {
        Map<String, Set<String>> deps = dependencies();

        assertThat(deps).as("packages found").containsKeys("agents", "orchestrator", "mailbox", "store");
        assertThat(cycles(deps)).as("package dependency cycles").isEmpty();
    }

    /** Top to bottom, as the README lists them: a package may only use packages below it. */
    static final List<String> LAYERS = List.of("web", "orchestrator", "agents", "step", "mailbox", "llm", "security",
            "store", "config", "model");

    @Test
    void packagesOnlyUsePackagesBelowThem() throws IOException {
        Map<String, Set<String>> deps = dependencies();

        assertThat(LAYERS).as("every package has a layer").containsAll(deps.keySet());
        List<String> upward = new ArrayList<>();
        deps.forEach((from, targets) -> targets.stream()
                .filter(to -> LAYERS.indexOf(to) <= LAYERS.indexOf(from))
                .forEach(to -> upward.add(from + " -> " + to)));
        assertThat(upward).as("dependencies on a package at the same level or above").isEmpty();
    }

    /** Package → the other com.emailagent packages its classes import or name. */
    static Map<String, Set<String>> dependencies() throws IOException {
        Map<String, Set<String>> deps = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String text = read(p);
                Matcher pkg = PACKAGE.matcher(text);
                if (!pkg.find()) {
                    return;
                }
                Set<String> out = deps.computeIfAbsent(pkg.group(1), k -> new TreeSet<>());
                Matcher ref = REFERENCE.matcher(text);
                while (ref.find()) {
                    if (!ref.group(1).equals(pkg.group(1))) {
                        out.add(ref.group(1));
                    }
                }
            });
        }
        return deps;
    }

    /** Strongly connected components with more than one package (Tarjan), each listed as "a -> b -> a". */
    static List<String> cycles(Map<String, Set<String>> deps) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new TreeSet<>();
        List<String> found = new ArrayList<>();
        int[] counter = {0};
        for (String v : deps.keySet()) {
            if (!index.containsKey(v)) {
                connect(v, deps, index, low, stack, onStack, found, counter);
            }
        }
        return found;
    }

    private static void connect(String v, Map<String, Set<String>> deps, Map<String, Integer> index,
            Map<String, Integer> low, Deque<String> stack, Set<String> onStack, List<String> found, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]++);
        stack.push(v);
        onStack.add(v);
        for (String w : deps.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                connect(w, deps, index, low, stack, onStack, found, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            Set<String> component = new TreeSet<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                component.add(w);
            } while (!w.equals(v));
            if (component.size() > 1) {
                found.add(String.join(" <-> ", component));
            }
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
