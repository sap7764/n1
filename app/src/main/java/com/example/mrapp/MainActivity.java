package com.example.mrapp;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Renderer;
import com.google.android.filament.Scene;
import com.google.android.filament.SwapChain;
import com.google.android.filament.TransformManager;
import com.google.android.filament.View;
import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.MaterialProvider;
import com.google.android.filament.utils.Utils;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, Choreographer.FrameCallback {

    private static final String TAG = "MainActivity";
    private static final String SCENE_FILE_NAME = "scene.json";

    static { Utils.init(); }

    private SurfaceView surfaceView;
    private Choreographer choreographer;
    private SwapChain swapChain;
    private Engine engine;
    private Renderer renderer;
    private Scene scene;
    private View view;
    private Camera camera;
    private TextView currentModelTextView;

    private GestureDetector gestureDetector;
    private final ArrayDeque<MotionEvent> queuedSingleTaps = new ArrayDeque<>();

    private AssetLoader assetLoader;
    private final List<FilamentAsset> loadedAssets = new ArrayList<>();
    private final List<String> loadedAssetNames = new ArrayList<>();
    private int currentAssetIndex = -1;

    private static class PlacedObject {
        final FilamentAsset.Instance instance;
        final int modelIndex;
        PlacedObject(FilamentAsset.Instance instance, int modelIndex) {
            this.instance = instance;
            this.modelIndex = modelIndex;
        }
    }
    private final List<PlacedObject> placedObjects = new ArrayList<>();

    private Session arSession;
    private boolean isVrModeEnabled = false;
    private final float ipd = 0.064f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    copyModelToInternalStorage(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);
        currentModelTextView = findViewById(R.id.current_model_text);

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) { queuedSingleTaps.add(e); return true; }
        });
        surfaceView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

        choreographer = Choreographer.getInstance();
        engine = Engine.create();
        renderer = engine.createRenderer();
        scene = engine.createScene();
        view = engine.createView();
        camera = engine.createCamera(engine.getEntityManager().create());
        view.setScene(scene);
        view.setCamera(camera);
        assetLoader = new AssetLoader(engine, MaterialProvider.createDefault(engine), EntityManager.get());

        findViewById(R.id.load_model_button).setOnClickListener(v -> openFilePicker());
        findViewById(R.id.next_model_button).setOnClickListener(v -> cycleNextModel());
        findViewById(R.id.vr_mode_button).setOnClickListener(v -> isVrModeEnabled = !isVrModeEnabled);
        findViewById(R.id.save_scene_button).setOnClickListener(v -> saveScene());
        findViewById(R.id.clear_scene_button).setOnClickListener(v -> clearScene());

        loadScene();
        updateUi();
    }

    private void saveScene() {
        executor.execute(() -> {
            try {
                JSONObject sceneJson = new JSONObject();
                sceneJson.put("models", new JSONArray(loadedAssetNames));

                JSONArray objectsArray = new JSONArray();
                TransformManager tm = engine.getTransformManager();
                for (PlacedObject placedObject : placedObjects) {
                    JSONObject objectJson = new JSONObject();
                    objectJson.put("modelIndex", placedObject.modelIndex);
                    float[] transform = new float[16];
                    tm.getTransform(tm.getInstance(placedObject.instance.getRoot()), transform);
                    objectJson.put("transform", new JSONArray(transform));
                    objectsArray.put(objectJson);
                }
                sceneJson.put("placedObjects", objectsArray);

                try (FileOutputStream fos = openFileOutput(SCENE_FILE_NAME, Context.MODE_PRIVATE)) {
                    fos.write(sceneJson.toString(4).getBytes());
                    runOnUiThread(() -> Toast.makeText(this, "Scene saved", Toast.LENGTH_SHORT).show());
                }

            } catch (JSONException | IOException e) {
                Log.e(TAG, "Failed to save scene", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed to save scene", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void loadScene() {
        executor.execute(() -> {
            File file = new File(getFilesDir(), SCENE_FILE_NAME);
            if (!file.exists()) return;

            try (FileInputStream fis = openFileInput(SCENE_FILE_NAME)) {
                String jsonString = new String(fis.readAllBytes());
                JSONObject sceneJson = new JSONObject(jsonString);

                JSONArray modelsArray = sceneJson.getJSONArray("models");
                for (int i = 0; i < modelsArray.length(); i++) {
                    String modelName = modelsArray.getString(i);
                    File modelFile = new File(new File(getFilesDir(), "models"), modelName);
                    if (modelFile.exists()) {
                        loadModelFromFile(modelFile, false);
                    }
                }

                JSONArray objectsArray = sceneJson.getJSONArray("placedObjects");
                TransformManager tm = engine.getTransformManager();
                runOnUiThread(() -> {
                    try {
                        for (int i = 0; i < objectsArray.length(); i++) {
                            JSONObject objectJson = objectsArray.getJSONObject(i);
                            int modelIndex = objectJson.getInt("modelIndex");
                            if (modelIndex >= 0 && modelIndex < loadedAssets.size()) {
                                FilamentAsset asset = loadedAssets.get(modelIndex);
                                FilamentAsset.Instance instance = asset.createInstance();
                                JSONArray transformArray = objectJson.getJSONArray("transform");
                                float[] transform = new float[16];
                                for (int j = 0; j < transformArray.length(); j++) {
                                    transform[j] = (float) transformArray.getDouble(j);
                                }
                                placedObjects.add(new PlacedObject(instance, modelIndex));
                                scene.addEntities(instance.getEntities());
                                tm.setTransform(tm.getInstance(instance.getRoot()), transform);
                            }
                        }
                        Toast.makeText(this, "Scene loaded", Toast.LENGTH_SHORT).show();
                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to parse placed objects", e);
                    }
                });
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Failed to load scene", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed to load scene", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void clearScene() {
        for (PlacedObject placedObject : placedObjects) {
            scene.removeEntities(placedObject.instance.getEntities());
        }
        placedObjects.clear();
        Toast.makeText(this, "Scene cleared", Toast.LENGTH_SHORT).show();
    }

    private void cycleNextModel() {
        if (!loadedAssets.isEmpty()) {
            currentAssetIndex = (currentAssetIndex + 1) % loadedAssets.size();
            updateUi();
        }
    }

    private void updateUi() {
        if (currentAssetIndex != -1) {
            currentModelTextView.setText("Current Model: " + loadedAssetNames.get(currentAssetIndex));
        } else {
            currentModelTextView.setText("No model loaded");
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"model/gltf-binary", "model/gltf+json"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        filePickerLauncher.launch(intent);
    }

    private void copyModelToInternalStorage(Uri uri) {
        executor.execute(() -> {
            String fileName = getFileName(uri);
            File modelsDir = new File(getFilesDir(), "models");
            if (!modelsDir.exists()) modelsDir.mkdirs();
            File destinationFile = new File(modelsDir, fileName);

            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(destinationFile)) {
                final ReadableByteChannel inputChannel = Channels.newChannel(in);
                final WritableByteChannel outputChannel = Channels.newChannel(out);
                final ByteBuffer buffer = ByteBuffer.allocateDirect(16 * 1024);
                while (inputChannel.read(buffer) != -1) {
                    buffer.flip();
                    outputChannel.write(buffer);
                    buffer.compact();
                }
                buffer.flip();
                while (buffer.hasRemaining()) outputChannel.write(buffer);
                loadModelFromFile(destinationFile, true);
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy model", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed to copy model", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void loadModelFromFile(File modelFile, boolean isNew) {
        String assetName = modelFile.getName();
        if (!isNew && loadedAssetNames.contains(assetName)) return;

        try (InputStream inputStream = new FileInputStream(modelFile)) {
            byte[] buffer = inputStream.readAllBytes();
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            FilamentAsset asset = assetLoader.createAsset(byteBuffer);
            asset.releaseSourceData();

            if (!loadedAssetNames.contains(assetName)) {
                loadedAssets.add(asset);
                loadedAssetNames.add(assetName);
            }
            if (isNew) {
                currentAssetIndex = loadedAssets.size() - 1;
                runOnUiThread(() -> {
                    updateUi();
                    Toast.makeText(this, "Loaded: " + assetName, Toast.LENGTH_SHORT).show();
                });
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to load model from file", e);
            runOnUiThread(() -> Toast.makeText(this, "Failed to load model: " + assetName, Toast.LENGTH_SHORT).show());
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) result = cursor.getString(index);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (arSession == null) {
            try {
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }
                arSession = new Session(this);
                Config config = new Config(arSession);
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
                arSession.configure(config);
            } catch (Exception e) {
                Log.e(TAG, "ARCore session creation failed", e);
                finish();
                return;
            }
        }
        try {
            arSession.resume();
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available on resume", e);
        }
        choreographer.postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (arSession != null) arSession.pause();
        choreographer.removeFrameCallback(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (arSession != null) { arSession.close(); arSession = null; }
        for (FilamentAsset asset : loadedAssets) assetLoader.destroyAsset(asset);
        if (assetLoader != null) assetLoader.destroy();
        choreographer.removeFrameCallback(this);
        engine.destroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (swapChain == null) swapChain = engine.createSwapChain(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (arSession != null) arSession.setDisplayGeometry(holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        engine.destroySwapChain(swapChain);
        swapChain = null;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        choreographer.postFrameCallback(this);
        if (arSession == null) return;
        try {
            Frame frame = arSession.update();
            com.google.ar.core.Camera arCamera = frame.getCamera();
            if (arCamera.getTrackingState() == TrackingState.TRACKING) {
                handleTap(frame, arCamera);
                if (renderer.beginFrame(swapChain, frameTimeNanos)) {
                    if (isVrModeEnabled) renderStereo(arCamera); else renderMonocular(arCamera);
                    renderer.endFrame();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Exception on doFrame", t);
        }
    }

    private void renderMonocular(com.google.ar.core.Camera arCamera) {
        view.setViewport(0, 0, surfaceView.getWidth(), surfaceView.getHeight());
        float[] projMatrix = new float[16];
        arCamera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f);
        float[] viewMatrix = new float[16];
        arCamera.getViewMatrix(viewMatrix, 0);
        camera.setCustomProjection(projMatrix, 0.1, 100.0);
        camera.setModelMatrix(viewMatrix);
        renderer.render(view);
    }

    private void renderStereo(com.google.ar.core.Camera arCamera) {
        float[] projMatrix = new float[16];
        arCamera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f);
        float[] viewMatrix = new float[16];
        arCamera.getViewMatrix(viewMatrix, 0);

        float[] leftEyeViewMatrix = new float[16];
        Matrix.setIdentityM(leftEyeViewMatrix, 0);
        Matrix.translateM(leftEyeViewMatrix, 0, -ipd / 2.0f, 0.0f, 0.0f);
        Matrix.multiplyMM(leftEyeViewMatrix, 0, viewMatrix, 0, leftEyeViewMatrix, 0);
        view.setViewport(0, 0, surfaceView.getWidth() / 2, surfaceView.getHeight());
        camera.setCustomProjection(projMatrix, 0.1, 100.0);
        camera.setModelMatrix(leftEyeViewMatrix);
        renderer.render(view);

        float[] rightEyeViewMatrix = new float[16];
        Matrix.setIdentityM(rightEyeViewMatrix, 0);
        Matrix.translateM(rightEyeViewMatrix, 0, ipd / 2.0f, 0.0f, 0.0f);
        Matrix.multiplyMM(rightEyeViewMatrix, 0, viewMatrix, 0, rightEyeViewMatrix, 0);
        view.setViewport(surfaceView.getWidth() / 2, 0, surfaceView.getWidth() / 2, surfaceView.getHeight());
        camera.setCustomProjection(projMatrix, 0.1, 100.0);
        camera.setModelMatrix(rightEyeViewMatrix);
        renderer.render(view);
    }

    private void handleTap(Frame frame, com.google.ar.core.Camera camera) {
        MotionEvent tap = queuedSingleTaps.poll();
        if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
            if (currentAssetIndex == -1) {
                runOnUiThread(() -> Toast.makeText(this, "Load a model first", Toast.LENGTH_SHORT).show());
                return;
            }
            for (HitResult hit : frame.hitTest(tap)) {
                Trackable trackable = hit.getTrackable();
                if ((trackable instanceof Plane && ((Plane) trackable).isPoseInPolygon(hit.getHitPose()))
                        || (trackable instanceof Point
                        && ((Point) trackable).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
                    FilamentAsset assetToPlace = loadedAssets.get(currentAssetIndex);
                    FilamentAsset.Instance instance = assetToPlace.createInstance();
                    placedObjects.add(new PlacedObject(instance, currentAssetIndex));
                    scene.addEntities(instance.getEntities());
                    float[] modelMatrix = new float[16];
                    hit.getHitPose().toMatrix(modelMatrix, 0);
                    int rootTransform = engine.getTransformManager().getInstance(instance.getRoot());
                    engine.getTransformManager().setTransform(rootTransform, modelMatrix);
                    break;
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }
}
