package org.scampath.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Detector {
    static final int LIMIT = 12_000;

    enum Concern {
        LOW("Few warning signs"),
        REVIEW("Pause and verify"),
        HIGH("High concern");

        final String label;
        Concern(String label) { this.label = label; }
    }

    enum Signal {
        RECRUITMENT("Recruitment or onboarding claim", "\\b(shortlisted|recruiter|offer letter|job offer|onboarding|recruitment)\\b"),
        PAYMENT("Request to pay a fee or send money", "\\b(?:(?:pay|send|transfer|deposit|submit)\\b.{0,45}\\b(?:fee|deposit|payment|rupees|rs|upi)|(?:pay|send|transfer|deposit)\\s+₹|(?:refundable|registration|processing|verification)\\s+(?:fee|deposit))"),
        SECRET("Request to share a code or credential", "\\b(?:share|send|reply with|tell me|forward|provide|bhej do|batao)\\b.{0,36}\\b(?:otp|pin|password|cvv|verification code)\\b|\\b(?:otp|pin|password|cvv)\\s+(?:(?:bhej|send)\\s+do|batao)\\b"),
        PRESSURE("Time pressure or service threat", "\\b(?:act now|pay now|within \\d+ (?:minutes|hours)|last chance|immediately|will be (?:blocked|disconnected)|aaj hi|abhi)\\b"),
        SUSPICIOUS_LINK("Link address has a warning sign", null);

        final String label;
        final Pattern pattern;
        Signal(String label, String regex) {
            this.label = label;
            this.pattern = regex == null ? null : Pattern.compile(regex);
        }
    }

    record Finding(int line, Signal signal) {}
    record Result(Concern concern, List<Finding> findings, int firstHighLine) {
        String nextStep() {
            return switch (concern) {
                case LOW -> "If a later message asks for money or a code, check again.";
                case REVIEW, HIGH -> "Pause before paying or sharing a code. Verify the request using an official app, site or number you find independently.";
            };
        }
    }

    private static final Pattern LINKS = Pattern.compile("https?://[^\\s<>\"']+");

    static Result analyze(String chat) {
        if (chat == null || chat.trim().isEmpty() || chat.length() > LIMIT) {
            throw new IllegalArgumentException("Enter a chat of 1 to 12,000 characters.");
        }
        Map<Signal, Finding> seen = new EnumMap<>(Signal.class);
        List<Finding> timeline = new ArrayList<>();
        int firstHighLine = 0;
        String[] lines = chat.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = Normalizer.normalize(lines[i], Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            String requests = line.replaceAll("\\b(?:never|do not|don't)\\s+(?:share|send|forward|provide)\\b.{0,36}\\b(?:otp|pin|password|cvv|verification code)\\b", "");
            for (Signal signal : Signal.values()) {
                if (seen.containsKey(signal)) continue;
                boolean matched = switch (signal) {
                    case SUSPICIOUS_LINK -> hasSuspiciousLink(line);
                    case SECRET -> signal.pattern.matcher(requests).find();
                    default -> signal.pattern.matcher(line).find();
                };
                if (matched) {
                    Finding evidence = new Finding(i + 1, signal);
                    seen.put(signal, evidence);
                    timeline.add(evidence);
                }
            }
            if (firstHighLine == 0 && assess(seen) == Concern.HIGH) firstHighLine = i + 1;
        }
        return new Result(assess(seen), java.util.Collections.unmodifiableList(timeline), firstHighLine);
    }

    private static Concern assess(Map<Signal, Finding> seen) {
        boolean payment = seen.containsKey(Signal.PAYMENT);
        boolean secret = seen.containsKey(Signal.SECRET);
        boolean pressure = seen.containsKey(Signal.PRESSURE);
        boolean link = seen.containsKey(Signal.SUSPICIOUS_LINK);
        if ((payment && (pressure || link || seen.containsKey(Signal.RECRUITMENT)))
                || (secret && (pressure || link || payment))) return Concern.HIGH;
        if (payment || secret || link || pressure) return Concern.REVIEW;
        return Concern.LOW;
    }

    private static boolean hasSuspiciousLink(String line) {
        Matcher links = LINKS.matcher(line);
        while (links.find()) {
            String address = links.group().replaceFirst("[),.;!?\\]}]+$", "");
            try {
                URI uri = new URI(address);
                String host = uri.getHost();
                if (uri.getRawUserInfo() != null || host == null || host.contains(":")
                        || host.matches("\\d+(\\.\\d+){3}") || host.startsWith("xn--")
                        || host.contains(".xn--")) return true;
            } catch (URISyntaxException ignored) {
                return true;
            }
        }
        return false;
    }
}
