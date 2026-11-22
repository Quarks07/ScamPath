package org.scampath.app;

import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import org.pytorch.executorch.extension.llm.LlmCallback;
import org.pytorch.executorch.extension.llm.LlmGenerationConfig;
import org.pytorch.executorch.extension.llm.LlmModule;

final class LocalAdvisor {
    record Output(String advice, long loadMs, long firstOutputMs, long generationMs, String runtimeStats) {}

    static Output explain(File privateFiles, Detector.Result result) throws IOException {
        File model = new File(privateFiles, "model.pte");
        File tokenizer = new File(privateFiles, "tokenizer.json");
        if (!model.isFile() || !tokenizer.isFile()) {
            throw new IOException("Copy the model and tokenizer to the app using the setup steps in the README.");
        }
        long loadingStarted = SystemClock.elapsedRealtime();
        try (LlmModule module = new LlmModule(model.getPath(), tokenizer.getPath(), 0.0f)) {
            module.load();
            long started = SystemClock.elapsedRealtime();
            class Generation implements LlmCallback {
                final StringBuilder text = new StringBuilder();
                long first = -1;
                String stats = "";
                Integer errorCode;

                @Override public void onResult(String token) {
                    if (first == -1 && !token.trim().isEmpty()) first = SystemClock.elapsedRealtime() - started;
                    text.append(token);
                }

                @Override public void onStats(String value) { stats = value; }

                @Override public void onError(int code, String message) { errorCode = code; }
            }
            Generation generation = new Generation();
            LlmGenerationConfig config = LlmGenerationConfig.create()
                    .maxNewTokens(96)
                    .echo(false)
                    .temperature(0.0f)
                    .build();
            module.generate(AdvisoryPrompt.build(result), config, generation);
            String text = generation.text.toString().replaceAll("<\\|[^|]*\\|>", "").trim();
            if (generation.errorCode != null || text.trim().isEmpty()) {
                throw new IOException("The local model did not finish an explanation. The detector result is still available.");
            }
            return new Output(text, started - loadingStarted, generation.first,
                    SystemClock.elapsedRealtime() - started, generation.stats);
        }
    }
}
