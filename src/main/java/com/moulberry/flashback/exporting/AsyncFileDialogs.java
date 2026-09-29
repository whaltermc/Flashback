package com.moulberry.flashback.exporting;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.utils.MobileCompat;
import com.moulberry.flashback.utils.NamedDaemonThreadFactory;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NFDFilterItem;
import org.lwjgl.util.nfd.NativeFileDialog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async wrappers around the native file dialog (NFD).
 * <p>
 * If the native dialog can't be used (no natives for the platform such as on Android launchers, no portal/GTK on
 * Linux, NFD returning an error, ...) we fall back to {@code <game_dir>/flashback/exports} instead of failing.
 * A dialog that the user cancelled is <b>not</b> treated as a failure and still returns null.
 */
public class AsyncFileDialogs {

    private static final boolean ON_OSX = Util.getPlatform() == Util.OS.OSX;
    private static volatile CompletableFuture<String> currentSaveOrOpenFileDialog = null;
    private static final ExecutorService dialogThread = Executors.newSingleThreadExecutor(new NamedDaemonThreadFactory("FlashbackFileDialogs"));
    private static boolean initializedNfd = false;
    // Once NFD failed to init/load, don't try again - go straight to the fallback
    private static final AtomicBoolean nfdUnavailable = new AtomicBoolean(MobileCompat.isMobile());

    public static boolean hasDialog() {
        return currentSaveOrOpenFileDialog != null;
    }

    /** Result of trying to show a native dialog. */
    private enum Status { OK, CANCELLED, FAILED }

    private record DialogResult(Status status, String path) {
        static DialogResult ok(String path) { return new DialogResult(Status.OK, path); }
        static DialogResult cancelled() { return new DialogResult(Status.CANCELLED, null); }
        static DialogResult failed() { return new DialogResult(Status.FAILED, null); }
    }

    @FunctionalInterface
    private interface NativeDialog {
        DialogResult show(MemoryStack stack, PointerBuffer out) throws Throwable;
    }

    @FunctionalInterface
    private interface Fallback {
        String get();
    }

    public static CompletableFuture<String> saveFileDialog(String defaultPath, String defaultName, String filterDescription, String... filters) {
        return run((stack, out) -> {
            NFDFilterItem.Buffer filtersBuffer = createFilters(stack, filterDescription, filters);
            int result = NativeFileDialog.NFD_SaveDialog(out, filtersBuffer, filter(defaultPath), filter(defaultName));
            return toResult(result, out);
        }, () -> fallbackSavePath(defaultName, filters));
    }

    public static CompletableFuture<String> openFileDialog(String defaultPath, String filterDescription, String... filters) {
        return run((stack, out) -> {
            NFDFilterItem.Buffer filtersBuffer = createFilters(stack, filterDescription, filters);
            int result = NativeFileDialog.NFD_OpenDialog(out, filtersBuffer, filter(defaultPath));
            return toResult(result, out);
        }, () -> {
            // There is no sensible file to pick without a dialog
            Flashback.LOGGER.warn("Native file dialog unavailable, can't open a file (put files into {} manually)", getFallbackDirectory());
            return null;
        });
    }

    public static CompletableFuture<String> openFolderDialog(String defaultPath) {
        return run((stack, out) -> {
            int result = NativeFileDialog.NFD_PickFolder(out, filter(defaultPath));
            return toResult(result, out);
        }, () -> {
            Path directory = getFallbackDirectory();
            Flashback.LOGGER.warn("Native file dialog unavailable, using {}", directory);
            return directory.toString();
        });
    }

    private static NFDFilterItem.Buffer createFilters(MemoryStack stack, String filterDescription, String... filters) {
        StringBuilder filterBuilder = new StringBuilder();
        for (String filter : filters) {
            if (!filterBuilder.isEmpty()) filterBuilder.append(",");
            filterBuilder.append(filter(filter));
        }

        // Allocated on the stack so it gets freed together with the rest of the dialog's memory
        NFDFilterItem.Buffer filtersBuffer = NFDFilterItem.malloc(1, stack);
        filtersBuffer.get(0)
                .name(stack.UTF8(filter(filterDescription)))
                .spec(stack.UTF8(filterBuilder.toString()));
        return filtersBuffer;
    }

    private static DialogResult toResult(int result, PointerBuffer out) {
        if (result == NativeFileDialog.NFD_OKAY) {
            String path = out.getStringUTF8(0);
            NativeFileDialog.NFD_FreePath(out.get(0));
            return DialogResult.ok(path);
        } else if (result == NativeFileDialog.NFD_CANCEL) {
            return DialogResult.cancelled();
        } else {
            String error = NativeFileDialog.NFD_GetError();
            Flashback.LOGGER.error("Native file dialog failed: {}", error);
            return DialogResult.failed();
        }
    }

    private static CompletableFuture<String> run(NativeDialog dialog, Fallback fallback) {
        if (hasDialog()) return CompletableFuture.completedFuture(null);

        CompletableFuture<String> future = new CompletableFuture<>();
        currentSaveOrOpenFileDialog = future;

        boolean shouldInitNfd = !AsyncFileDialogs.initializedNfd;
        AsyncFileDialogs.initializedNfd = true;

        Runnable runnable = () -> {
            String resultPath = null;
            try {
                DialogResult result = DialogResult.failed();

                if (!nfdUnavailable.get()) {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        boolean initialized = true;
                        if (shouldInitNfd) {
                            initialized = NativeFileDialog.NFD_Init() == NativeFileDialog.NFD_OKAY;
                            if (!initialized) {
                                Flashback.LOGGER.error("Failed to initialize native file dialogs: {}", NativeFileDialog.NFD_GetError());
                            }
                        }

                        if (initialized) {
                            PointerBuffer out = stack.callocPointer(1);
                            result = dialog.show(stack, out);
                        }
                    } catch (Throwable t) {
                        // UnsatisfiedLinkError / NoClassDefFoundError when the natives don't exist for this platform, etc.
                        Flashback.LOGGER.error("Native file dialog threw an exception", t);
                        result = DialogResult.failed();
                    }

                    if (result.status() == Status.FAILED) {
                        // Don't keep retrying something that is broken
                        nfdUnavailable.set(true);
                    }
                }

                if (result.status() == Status.OK) {
                    resultPath = result.path();
                } else if (result.status() == Status.FAILED) {
                    try {
                        resultPath = fallback.get();
                    } catch (Throwable t) {
                        Flashback.LOGGER.error("File dialog fallback failed", t);
                        resultPath = null;
                    }
                }
            } finally {
                currentSaveOrOpenFileDialog = null;
                future.complete(resultPath);
            }
        };

        if (ON_OSX && !nfdUnavailable.get()) {
            // MacOS needs dialogs to be run from the main thread
            Minecraft.getInstance().submit(runnable);
        } else {
            dialogThread.submit(runnable);
        }

        return future;
    }

    /** {@code <game_dir>/flashback/exports}, created if it doesn't exist */
    public static Path getFallbackDirectory() {
        Path directory = Flashback.getDataDirectory().resolve("exports");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            Flashback.LOGGER.error("Unable to create fallback export directory {}", directory, e);
        }
        return directory;
    }

    private static String fallbackSavePath(String defaultName, String... filters) {
        String name = sanitizeFileName(defaultName);
        if (name.isEmpty()) {
            name = "export";
        }

        // Add an extension if the name doesn't have one
        if (filters != null && filters.length > 0 && name.lastIndexOf('.') <= 0) {
            String extension = filter(filters[0]);
            int comma = extension.indexOf(',');
            if (comma >= 0) extension = extension.substring(0, comma);
            extension = sanitizeFileName(extension);
            if (!extension.isEmpty()) {
                name += "." + extension;
            }
        }

        Path directory = getFallbackDirectory();
        Path path = directory.resolve(name);

        // Never silently overwrite something
        if (Files.exists(path)) {
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String extension = dot > 0 ? name.substring(dot) : "";
            for (int i = 1; i < 10000 && Files.exists(path); i++) {
                path = directory.resolve(base + " (" + i + ")" + extension);
            }
        }

        Flashback.LOGGER.warn("Native file dialog unavailable, saving to {}", path);
        return path.toString();
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "";
        String cleaned = filter(name);
        // Only keep the file name, drop any directories
        int slash = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'));
        if (slash >= 0) cleaned = cleaned.substring(slash + 1);
        cleaned = cleaned.replaceAll("[<>:\"|?*\\x00-\\x1f]", "_").trim();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        return cleaned;
    }

    public static String filter(CharSequence in) {
        return filterLT20(in.toString()
                .replace("'", "")
                .replace("\"", "")
                .replace("$", "")
                .replace("`", ""));
    }

    public static String filterLT20(CharSequence in) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c >= 32 || c == '\n') builder.append(c);
        }
        return builder.toString();
    }

}
