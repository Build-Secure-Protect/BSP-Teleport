package com.mrgregles.bsp_teleport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** B10: every whole text key the code names must be in the generated lang file, or the screen shows the raw key. */
class LangKeysTest {
    private static final Pattern KEY = Pattern.compile("\"((?:screen|message)\\.bsp_teleport\\.[a-z0-9_.]*[a-z0-9])\"");

    @Test
    void every_key_the_code_names_is_in_the_lang_file() throws IOException {
        String lang = Files.readString(Path.of("src/main/resources/assets/bsp_teleport/lang/en_us.json"));
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = KEY.matcher(Files.readString(f));
                while (m.find()) {
                    if (!lang.contains("\"" + m.group(1) + "\":")) {
                        missing.add(m.group(1) + " (" + f.getFileName() + ")");
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "keys missing from en_us.json, add them to tools/gen_lang.py: " + missing);
    }
}
