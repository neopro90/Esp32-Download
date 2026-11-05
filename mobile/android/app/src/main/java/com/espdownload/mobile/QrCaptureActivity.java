package com.espdownload.mobile;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.common.InputImage;

import android.util.Size;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class QrCaptureActivity extends AppCompatActivity {
    private static final String TAG = "QrCaptureActivity";
    private final AtomicBoolean handled = new AtomicBoolean(false);
    private ExecutorService cameraExecutor;
    private BarcodeScanner scanner;
    private TextView prompt;
    private final AtomicBoolean scanErrorShown = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff17392e);
        cameraExecutor = Executors.newSingleThreadExecutor();
        BarcodeScannerOptions scannerOptions = new BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build();
        scanner = BarcodeScanning.getClient(scannerOptions);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xff101816);
        PreviewView preview = new PreviewView(this);
        preview.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(preview);
        root.addView(new QrGuideView(this), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        prompt = new TextView(this);
        prompt.setText("Đưa trọn mã QR vào khung vuông và giữ yên");
        prompt.setTextColor(0xffffffff);
        prompt.setTextSize(16f);
        prompt.setGravity(Gravity.CENTER);
        prompt.setPadding(32, 48, 32, 48);
        FrameLayout.LayoutParams promptParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        promptParams.gravity = Gravity.TOP;
        root.addView(prompt, promptParams);
        setContentView(root);

        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                Preview cameraPreview = new Preview.Builder().build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1920, 1080))
                    .setTargetRotation(preview.getDisplay().getRotation())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis);
            } catch (Exception error) {
                Log.e(TAG, "Unable to start QR camera", error);
                Intent result = new Intent();
                result.putExtra("SCAN_ERROR", error.getMessage());
                setResult(RESULT_CANCELED, result);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(ImageProxy imageProxy) {
        if (handled.get() || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        try {
            InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
            scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String value = barcode.getRawValue();
                        if (value != null && !value.isEmpty() && handled.compareAndSet(false, true)) {
                            runOnUiThread(() -> {
                                Intent result = new Intent();
                                result.putExtra("SCAN_RESULT", value);
                                setResult(RESULT_OK, result);
                                finish();
                            });
                            break;
                        }
                    }
                })
                .addOnFailureListener(error -> {
                    Log.e(TAG, "QR frame analysis failed", error);
                    if (scanErrorShown.compareAndSet(false, true)) {
                        runOnUiThread(() -> {
                            if (!isFinishing() && prompt != null) {
                                prompt.setText("Lỗi nhận dạng QR. Hãy đóng camera và thử lại.");
                            }
                        });
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
        } catch (Exception error) {
            imageProxy.close();
            Log.e(TAG, "Unable to process QR camera frame", error);
        }
    }

    @Override
    protected void onDestroy() {
        if (scanner != null) scanner.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        super.onDestroy();
    }

    private static final class QrGuideView extends View {
        private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        QrGuideView(android.content.Context context) {
            super(context);
            setWillNotDraw(false);
            scrimPaint.setColor(0x99000000);
            framePaint.setColor(0xff54e5a0);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(5f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth() * 0.78f, getHeight() * 0.42f);
            float left = (getWidth() - size) / 2f;
            float top = (getHeight() - size) / 2f;
            float right = left + size;
            float bottom = top + size;
            canvas.drawRect(0, 0, getWidth(), top, scrimPaint);
            canvas.drawRect(0, bottom, getWidth(), getHeight(), scrimPaint);
            canvas.drawRect(0, top, left, bottom, scrimPaint);
            canvas.drawRect(right, top, getWidth(), bottom, scrimPaint);
            canvas.drawRoundRect(left, top, right, bottom, 22f, 22f, framePaint);
        }
    }
}
