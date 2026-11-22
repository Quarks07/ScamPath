package org.scampath.app;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class DetectorTest {
    private static final Map<String, String> LEGITIMATE = new LinkedHashMap<>();
    private static final Map<String, String> SUSPICIOUS = new LinkedHashMap<>();

    static {
        LEGITIMATE.put("transaction", "INR 750 debited from your account ending 1234.");
        LEGITIMATE.put("otp notification", "Your OTP is 000000. Do not share this OTP with anyone.");
        LEGITIMATE.put("UPI confirmation", "You received INR 500 through UPI.");
        LEGITIMATE.put("delivery", "Your parcel is out for delivery.");
        LEGITIMATE.put("onboarding", "You are shortlisted.\nYour onboarding starts on Monday.");
        LEGITIMATE.put("ordinary link", "View your delivery receipt at https://example.com/receipt.");
        LEGITIMATE.put("Hinglish", "Parcel kal aayega. Tracking app mein dekho.");

        SUSPICIOUS.put("late fee", "We reviewed your profile.\nYou have been shortlisted.\nPay the refundable registration fee.");
        SUSPICIOUS.put("credential request", "Reply with your verification code immediately.");
        SUSPICIOUS.put("utility threat", "Your service will be disconnected.\nPay your fee.");
        SUSPICIOUS.put("numeric host", "Open https://192.0.2.8/ then share your OTP.");
        SUSPICIOUS.put("Hinglish request", "OTP bhej do abhi.");
        SUSPICIOUS.put("single fee", "Please pay the processing fee.");
    }

    @Test public void flagsTheEscalationLineAndPreservesEarlierEvidence() {
        String chat = SUSPICIOUS.get("late fee");
        assertEquals(Detector.Concern.LOW, Detector.analyze("We reviewed your profile.\nYou have been shortlisted.").concern());
        Detector.Result result = Detector.analyze(chat);
        assertEquals(Detector.Concern.HIGH, result.concern());
        assertEquals(3, result.firstHighLine());
        assertEquals(Arrays.asList(
                new Detector.Finding(2, Detector.Signal.RECRUITMENT),
                new Detector.Finding(3, Detector.Signal.PAYMENT)), result.findings());
        assertEquals(3, Detector.analyze(chat.replace("\n", "\r\n")).firstHighLine());
    }

    @Test public void urlObservationRequiresEvidenceAndNeverOpensTheAddress() {
        assertEquals(Detector.Concern.LOW, Detector.analyze("https://example.com/a").concern());
        Detector.Result flagged = Detector.analyze("https://192.0.2.8/a.");
        assertEquals(Detector.Concern.REVIEW, flagged.concern());
        assertEquals(Detector.Signal.SUSPICIOUS_LINK, flagged.findings().get(0).signal());
    }

    @Test public void advisoryOnlyReceivesDerivedEvidence() {
        Detector.Result result = Detector.analyze("Secret chat XYZ\nShare your OTP immediately.");
        String prompt = AdvisoryPrompt.build(result);
        assertTrue(prompt.contains("Line 2 - Request to share a code or credential"));
        assertTrue(prompt.contains(result.concern().label));
        assertFalse(prompt.contains("Secret chat XYZ"));
        assertFalse(prompt.contains("Share your OTP immediately."));
    }

    @Test public void rejectsEmptyAndOversizedInput() {
        assertThrows(IllegalArgumentException.class, () -> Detector.analyze(null));
        assertThrows(IllegalArgumentException.class, () -> Detector.analyze("\r\n \n"));
        assertThrows(IllegalArgumentException.class, () -> Detector.analyze("a".repeat(Detector.LIMIT + 1)));
        assertEquals(Detector.Concern.LOW, Detector.analyze("a".repeat(Detector.LIMIT)).concern());
    }

    @Test public void checksSyntheticFixturesAndReportsHostLatency() {
        LEGITIMATE.forEach((name, text) ->
                assertEquals(name, Detector.Concern.LOW, Detector.analyze(text).concern()));
        SUSPICIOUS.forEach((name, text) ->
                assertNotEquals(name, Detector.Concern.LOW, Detector.analyze(text).concern()));

        String[] inputs = java.util.stream.Stream.concat(LEGITIMATE.values().stream(), SUSPICIOUS.values().stream())
                .toArray(String[]::new);
        long[] nanos = new long[1300];
        for (int i = 0; i < 2600; i++) {
            long start = System.nanoTime();
            Detector.analyze(inputs[i % inputs.length]);
            if (i >= 1300) nanos[i - 1300] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        System.out.printf(java.util.Locale.ROOT,
                "Synthetic smoke: %d legitimate, %d suspicious, 0 observed errors%n"
                        + "Host scanner (%,d warm calls), p50=%.4f ms, p95=%.4f ms, p99=%.4f ms%n",
                LEGITIMATE.size(), SUSPICIOUS.size(), nanos.length,
                percentile(nanos, 0.50), percentile(nanos, 0.95), percentile(nanos, 0.99));
    }

    private static double percentile(long[] sorted, double fraction) {
        return sorted[(int) Math.ceil(sorted.length * fraction) - 1] / 1_000_000.0;
    }
}
