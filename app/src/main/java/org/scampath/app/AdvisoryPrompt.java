package org.scampath.app;

final class AdvisoryPrompt {
    static String build(Detector.Result result) {
        StringBuilder evidence = new StringBuilder();
        for (Detector.Finding finding : result.findings()) {
            evidence.append("\nLine ").append(finding.line()).append(" - ").append(finding.signal().label);
        }
        return "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n"
                + "You explain a scam detector's result in plain English. Use only the given evidence. "
                + "In two short sentences, explain the relevant warning signs and give one safe step. "
                + "Do not invent identities, facts, links or phone numbers. Never ask for payments, codes or credentials. "
                + "Do not change the verdict or assert the sender is a criminal."
                + "<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n"
                + "Detector result: " + result.concern().label + "\nEvidence:" + evidence
                + "\nRecommended action: " + result.nextStep()
                + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n";
    }
}
