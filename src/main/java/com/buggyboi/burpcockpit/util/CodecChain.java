package com.buggyboi.burpcockpit.util;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class CodecChain {
    public static final int DEFAULT_MAX_DEPTH = 8;

    private static final Pattern PERCENT_ESCAPE = Pattern.compile("(?i)%[0-9a-f]{2}");
    private static final Pattern STANDARD_BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");
    private static final Pattern URL_BASE64 = Pattern.compile("[A-Za-z0-9_-]+={0,2}");

    private CodecChain() {
    }

    public static Result decode(String input) {
        return decode(input, DEFAULT_MAX_DEPTH);
    }

    public static Result decode(String input, int maxDepth) {
        String current = Objects.toString(input, "");
        List<Step> steps = new ArrayList<>();
        List<String> decodeOutputs = new ArrayList<>();
        Step previous = null;

        for (int depth = 0; depth < Math.max(0, maxDepth); depth++) {
            Optional<Candidate> url = tryUrlDecode(current, previous);
            if (url.isPresent()) {
                Candidate candidate = url.get();
                current = candidate.decoded();
                steps.add(candidate.step());
                decodeOutputs.add(current);
                previous = candidate.step();
                continue;
            }

            Optional<Candidate> base64 = tryBase64Decode(current);
            if (base64.isPresent()) {
                Candidate candidate = base64.get();
                current = candidate.decoded();
                steps.add(candidate.step());
                decodeOutputs.add(current);
                previous = candidate.step();
                continue;
            }

            break;
        }

        return new Result(Objects.toString(input, ""), current, List.copyOf(steps), List.copyOf(decodeOutputs));
    }

    public static String reencode(String decoded, List<Step> steps) {
        List<Layer> layers = encodeLayers(decoded, steps);
        return layers.isEmpty() ? Objects.toString(decoded, "") : layers.get(layers.size() - 1).output();
    }

    public static List<Layer> encodeLayers(String decoded, List<Step> steps) {
        String current = Objects.toString(decoded, "");
        List<Step> chain = steps == null ? List.of() : steps;
        List<Layer> layers = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            Step step = chain.get(i);
            String input = current;
            current = switch (step.kind()) {
                case URL -> encodeUrl(current, step.urlFormSpaces());
                case BASE64 -> encodeBase64(current, step.padded());
                case BASE64URL -> encodeBase64Url(current, step.padded());
            };
            layers.add(new Layer(step, input, current));
        }
        return List.copyOf(layers);
    }

    private static Optional<Candidate> tryUrlDecode(String input, Step previous) {
        boolean hasPercentEscape = PERCENT_ESCAPE.matcher(input).find();
        boolean hasPlus = input.contains("+");
        if (!hasPercentEscape && !hasPlus) return Optional.empty();
        if (!hasPercentEscape && !looksLikeFormSpaces(input, previous)) return Optional.empty();

        try {
            String decoded = URLDecoder.decode(input, StandardCharsets.UTF_8);
            if (decoded.equals(input)) return Optional.empty();
            return Optional.of(new Candidate(decoded, new Step(Kind.URL, hasPlus, false)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static boolean looksLikeFormSpaces(String input, Step previous) {
        if (previous != null && previous.kind() == Kind.URL) return false;
        if (!input.contains("+") || input.contains(" ")) return false;
        return tryBase64Decode(input).isEmpty();
    }

    private static Optional<Candidate> tryBase64Decode(String input) {
        String value = Objects.toString(input, "").trim();
        if (value.length() < 4 || !value.equals(input) || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return Optional.empty();
        }
        if (value.length() % 4 == 1) return Optional.empty();

        boolean urlShape = looksLikeUrlBase64(value);
        boolean standardShape = looksLikeStandardBase64(value);

        if (urlShape && !standardShape) {
            return decodeBase64Candidate(value, Kind.BASE64URL);
        }
        if (standardShape && !urlShape) {
            return decodeBase64Candidate(value, Kind.BASE64);
        }
        if (standardShape) {
            return decodeBase64Candidate(value, Kind.BASE64);
        }
        return Optional.empty();
    }

    private static Optional<Candidate> decodeBase64Candidate(String input, Kind kind) {
        try {
            String paddedInput = withPadding(input);
            byte[] decodedBytes = kind == Kind.BASE64URL
                    ? Base64.getUrlDecoder().decode(paddedInput)
                    : Base64.getDecoder().decode(paddedInput);
            if (decodedBytes.length == 0) return Optional.empty();

            String decoded = utf8(decodedBytes);
            if (decoded.equals(input) || !isPrintableUtf8ish(decoded)) return Optional.empty();
            return Optional.of(new Candidate(decoded, new Step(kind, false, input.endsWith("="))));
        } catch (IllegalArgumentException | CharacterCodingException ignored) {
            return Optional.empty();
        }
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static boolean isPrintableUtf8ish(String decoded) {
        if (decoded.isBlank()) return false;
        int printable = 0;
        int total = 0;
        for (int i = 0; i < decoded.length(); i++) {
            char ch = decoded.charAt(i);
            total++;
            if (ch == '\n' || ch == '\r' || ch == '\t' || (ch >= 0x20 && ch != 0x7F)) {
                printable++;
            }
        }
        return total > 0 && printable / (double) total >= 0.90D;
    }

    private static boolean looksLikeStandardBase64(String input) {
        return plausibleBase64Length(input) && STANDARD_BASE64.matcher(input).matches();
    }

    private static boolean looksLikeUrlBase64(String input) {
        return plausibleBase64Length(input) && URL_BASE64.matcher(input).matches();
    }

    private static boolean plausibleBase64Length(String input) {
        return input.length() >= 4 && input.length() % 4 != 1;
    }

    private static String withPadding(String input) {
        int remainder = input.length() % 4;
        if (remainder == 0) return input;
        return input + "=".repeat(4 - remainder);
    }

    private static String encodeUrl(String input, boolean formSpaces) {
        String encoded = URLEncoder.encode(input, StandardCharsets.UTF_8);
        return formSpaces ? encoded : encoded.replace("+", "%20");
    }

    private static String encodeBase64(String input, boolean padded) {
        Base64.Encoder encoder = padded ? Base64.getEncoder() : Base64.getEncoder().withoutPadding();
        return encoder.encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeBase64Url(String input, boolean padded) {
        Base64.Encoder encoder = padded ? Base64.getUrlEncoder() : Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    public enum Kind {
        URL("URL"),
        BASE64("Base64"),
        BASE64URL("Base64URL");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record Step(Kind kind, boolean urlFormSpaces, boolean padded) {
        public String displayName() {
            return kind.displayName();
        }

        public String decodeDisplayName() {
            return displayName() + " decode";
        }

        public String encodeDisplayName() {
            return displayName() + " encode";
        }
    }

    public record Layer(Step step, String input, String output) {
    }

    public record Result(String original, String decoded, List<Step> steps, List<String> decodeOutputs) {
        public boolean decodedAnything() {
            return !steps.isEmpty();
        }

        public String chainDisplay() {
            if (steps.isEmpty()) return "None";
            List<String> names = new ArrayList<>();
            for (Step step : steps) names.add(step.displayName());
            return String.join(" -> ", names);
        }

        public String sandwichDisplay() {
            if (steps.isEmpty()) return "plain";
            List<String> names = new ArrayList<>();
            for (Step step : steps) names.add(step.decodeDisplayName());
            names.add("plain");
            for (int i = steps.size() - 1; i >= 0; i--) names.add(steps.get(i).encodeDisplayName());
            return String.join(" -> ", names);
        }

        public List<Layer> decodeLayers() {
            List<Layer> layers = new ArrayList<>();
            String current = original;
            for (int i = 0; i < steps.size() && i < decodeOutputs.size(); i++) {
                String output = decodeOutputs.get(i);
                layers.add(new Layer(steps.get(i), current, output));
                current = output;
            }
            return List.copyOf(layers);
        }

        public List<Layer> encodeLayers(String editedDecoded) {
            return CodecChain.encodeLayers(editedDecoded, steps);
        }

        public String reencode(String editedDecoded) {
            return CodecChain.reencode(editedDecoded, steps);
        }
    }

    private record Candidate(String decoded, Step step) {
    }
}
