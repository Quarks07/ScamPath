package org.scampath.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 10;
    private static final int PAPER = 0xfff7f6f0;
    private static final int INK = 0xff172a29;
    private static final int MUTED = 0xff465e59;
    private static final int TEAL = 0xff12605a;
    private static final int OUTLINE = 0xffbccbc3;
    private static final int AMBER = 0xff71431c;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private EditText conversation;
    private TextView feedback;
    private LinearLayout resultView;
    private ScrollView scroll;
    private Button checkButton;
    private Button imageButton;
    private Detector.Result report;
    private boolean readingImage;
    private int imageRequest;
    private boolean generating;
    private boolean destroyed;

    private record Draft(String text, boolean checked) {}

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        });

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout wrapper = new FrameLayout(this);
        scroll.addView(wrapper, new ScrollView.LayoutParams(-1, -2));
        LinearLayout page = column();
        page.setFocusableInTouchMode(true);
        page.setPadding(dp(24), dp(24), dp(24), dp(32));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        wrapper.addView(page, new FrameLayout.LayoutParams(
                Math.min(screenWidth, dp(620)), -2, Gravity.CENTER_HORIZONTAL));

        heading(page, "ScamPath", 36, INK, 0);
        add(page, label("Check a chat before you respond.", 17, MUTED), 3);
        TextView inputLabel = heading(page, "Conversation", 17, INK, 28);
        conversation = new EditText(this);
        conversation.setId(View.generateViewId());
        inputLabel.setLabelFor(conversation.getId());
        conversation.setSaveEnabled(false);
        conversation.setGravity(Gravity.TOP | Gravity.START);
        conversation.setTextSize(16);
        conversation.setTextColor(INK);
        conversation.setHintTextColor(MUTED);
        conversation.setHint("Paste the chat with one message per line");
        conversation.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        conversation.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        conversation.setMinLines(5);
        conversation.setMaxLines(8);
        conversation.setPadding(dp(14), dp(13), dp(14), dp(13));
        conversation.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Detector.LIMIT)});
        conversation.setBackground(box(Color.WHITE, OUTLINE, 14));
        add(page, conversation, 8);

        checkButton = button("Check conversation", true);
        add(page, checkButton, 12);
        checkButton.setOnClickListener(v -> scan(true));
        imageButton = button("Read a screenshot", false);
        add(page, imageButton, 4);
        imageButton.setOnClickListener(v -> pickImage());
        feedback = label("", 14, MUTED);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        add(page, feedback, 8);
        add(page, label("ScamPath processes this chat on your device.", 14, MUTED), 12);

        resultView = column();
        resultView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(page, resultView, 24);
        setContentView(root);
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(appearance, appearance);
        }
        page.requestFocus();
        conversation.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                report = null;
                resultView.removeAllViews();
                setFeedback("");
                updateButtons();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        if (getLastNonConfigurationInstance() instanceof Draft draft) {
            conversation.setText(draft.text());
            if (draft.checked()) scan(false);
        } else if (saved == null) {
            receiveShare(getIntent());
            setIntent(new Intent());
        }
        updateButtons();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        return new Draft(conversation.getText().toString(), report != null);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        receiveShare(intent);
    }

    private void receiveShare(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction()) || !"text/plain".equals(intent.getType())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null || shared.toString().trim().isEmpty() || shared.length() > Detector.LIMIT) {
            setFeedback("Share a conversation of up to 12,000 characters.");
            return;
        }
        imageRequest++;
        readingImage = false;
        conversation.setText(shared.toString());
        setFeedback("Shared text is ready to check.");
    }

    private void scan(boolean userAction) {
        String chat = conversation.getText().toString();
        if (chat.trim().isEmpty()) {
            setFeedback("Paste or share a conversation, or read a screenshot first.");
            return;
        }
        long started = System.nanoTime();
        report = Detector.analyze(chat);
        double millis = (System.nanoTime() - started) / 1_000_000.0;
        if (BuildConfig.DEBUG) Log.i("ScamPath", "detector_ms=" + millis);
        if (userAction) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(conversation.getWindowToken(), 0);
            conversation.clearFocus();
        }
        setFeedback("");
        showReport(report);
        if (userAction) resultView.post(() -> scroll.smoothScrollTo(0, resultView.getTop()));
    }

    private void showReport(Detector.Result current) {
        resultView.removeAllViews();
        View rule = new View(this);
        rule.setBackgroundColor(OUTLINE);
        resultView.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        int color = current.concern() == Detector.Concern.LOW ? TEAL : AMBER;
        heading(resultView, current.concern().label, 28, color, 18);
        if (current.firstHighLine() > 0) {
            add(resultView, label("Concern rose at line " + current.firstHighLine() + ".", 15, MUTED), 4);
        }
        if (!current.findings().isEmpty()) {
            heading(resultView, "What ScamPath found", 17, INK, 16);
            for (Detector.Finding finding : current.findings()) {
                add(resultView, label("Line " + finding.line() + "  ·  " + finding.signal().label, 15, INK), 7);
            }
        }
        heading(resultView, "What to do next", 17, INK, 16);
        add(resultView, label(current.nextStep(), 16, INK), 5);
        if (current.concern() == Detector.Concern.LOW) return;

        Button explain = button("Get a local explanation", false);
        explain.setEnabled(!generating);
        add(resultView, explain, 12);
        TextView advice = label("", 15, MUTED);
        advice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        advice.setVisibility(View.GONE);
        add(resultView, advice, 6);
        explain.setOnClickListener(v -> explain(current, explain, advice));
    }

    private void explain(Detector.Result current, Button explain, TextView advice) {
        generating = true;
        updateButtons();
        explain.setEnabled(false);
        advice.setVisibility(View.VISIBLE);
        advice.setText("Generating on this device…");
        worker.execute(() -> {
            String message;
            try {
                LocalAdvisor.Output output = LocalAdvisor.explain(getFilesDir(), current);
                message = "Llama 3.2 via ExecuTorch\n" + output.advice();
                if (BuildConfig.DEBUG) Log.i("ScamPath", "model_load_ms=" + output.loadMs()
                        + " first_output_ms=" + output.firstOutputMs() + " generation_ms="
                        + output.generationMs() + " runtime_stats=" + output.runtimeStats());
            } catch (IOException e) {
                message = e.getMessage();
            } catch (RuntimeException | LinkageError e) {
                if (BuildConfig.DEBUG) Log.w("ScamPath", "ExecuTorch failed: " + e.getClass().getSimpleName());
                message = "The local model could not run. Check the model files and use the evidence above.";
            }
            String completed = message;
            runOnUiThread(() -> {
                if (destroyed) return;
                generating = false;
                updateButtons();
                if (report == current) {
                    advice.setText(completed);
                    explain.setEnabled(true);
                    advice.post(() -> scroll.smoothScrollTo(0, resultView.getTop() + advice.getTop()));
                } else if (report != null) {
                    showReport(report);
                }
            });
        });
    }

    private void pickImage() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        startActivityForResult(picker, PICK_IMAGE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) {
            setFeedback("Choose a screenshot from the Android file picker.");
            return;
        }
        int requestId = ++imageRequest;
        readingImage = true;
        updateButtons();
        setFeedback("Reading the screenshot…");
        worker.execute(() -> {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
                Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    double scale = Math.min(1, 2400.0 / Math.max(width, height));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setTargetSize(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)));
                });
                runOnUiThread(() -> readBitmap(bitmap, requestId));
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> finishImage(requestId, "", "The image could not be opened. Try another screenshot or paste the chat."));
            }
        });
    }

    private void readBitmap(Bitmap bitmap, int requestId) {
        if (destroyed || requestId != imageRequest) {
            bitmap.recycle();
            return;
        }
        var reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        reader.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(result -> {
                    String text = result.getTextBlocks().stream()
                            .flatMap(block -> block.getLines().stream())
                            .sorted(Comparator.comparingInt((Text.Line line) ->
                                    line.getBoundingBox() == null ? Integer.MAX_VALUE : line.getBoundingBox().top))
                            .map(Text.Line::getText)
                            .collect(Collectors.joining("\n"));
                    finishImage(requestId, text, "Review the extracted text before checking the chat.");
                })
                .addOnFailureListener(error -> finishImage(requestId, "", "Text reading failed. Try another screenshot or paste the chat."))
                .addOnCompleteListener(task -> {
                    reader.close();
                    bitmap.recycle();
                });
    }

    private void finishImage(int requestId, String text, String message) {
        if (destroyed || requestId != imageRequest) return;
        readingImage = false;
        if (text.length() > Detector.LIMIT) {
            message = "This screenshot has too much text. Crop it or paste a shorter chat.";
        } else if (!text.trim().isEmpty()) {
            conversation.setText(text);
        } else if (message.startsWith("Review")) {
            message = "No readable text was found. Try a sharper screenshot or paste the chat.";
        }
        setFeedback(message);
        updateButtons();
    }

    private void setFeedback(String text) {
        feedback.setText(text);
        feedback.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateButtons() {
        checkButton.setEnabled(!readingImage && !generating);
        imageButton.setEnabled(!readingImage && !generating);
        conversation.setEnabled(!readingImage && !generating);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView heading(LinearLayout parent, String text, int sp, int color, int top) {
        TextView view = label(text, sp, color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setAccessibilityHeading(true);
        add(parent, view, top);
        return view;
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private Button button(String text, boolean primary) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setTextSize(16);
        view.setMinHeight(dp(48));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{MUTED, primary ? Color.WHITE : TEAL}));
        GradientDrawable active = box(primary ? TEAL : PAPER, primary ? TEAL : OUTLINE, 12);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, box(0xffe1e7df, OUTLINE, 12));
        states.addState(new int[0], active);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33ffffff : 0x2412605a),
                states, null));
        view.setStateListAnimator(null);
        return view;
    }

    private GradientDrawable box(int color, int stroke, int radius) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setStroke(dp(1), stroke);
        fill.setCornerRadius(dp(radius));
        return fill;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
