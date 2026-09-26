package com.moulberry.flashback.exporting;

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

public class AsyncFileDialogs {

    private static final boolean ON_OSX = Util.getPlatform() == Util.OS.OSX;
    private static CompletableFuture<String> currentSaveOrOpenFileDialog = null;
    private static final ExecutorService dialogThread = Executors.newSingleThreadExecutor(new NamedDaemonThreadFactory("FlashbackFileDialogs"));
    private static boolean initializedNfd = false;

    public static boolean hasDialog() {
        return currentSaveOrOpenFileDialog != null;
    }

    /**
     * Android/launcher fallback for save dialogs.
     *
     * If native-file-dialog (NFD) is unavailable or throws an exception,
     * save directly to:
     *
     * <game directory>/flashback/exports/<defaultName>
     */
    private static String getFallbackExportPath(String defaultName) {
        try {
            Path exportDirectory = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("flashback")
                    .resolve("exports");

            Files.createDirectories(exportDirectory);

            String safeName = defaultName == null || defaultName.isBlank()
                    ? "flashback_export"
                    : defaultName;

            // Prevent a caller from escaping the exports directory.
            safeName = Path.of(safeName).getFileName().toString();

            return exportDirectory.resolve(safeName).toString();
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    public static CompletableFuture<String> saveFileDialog(String defaultPath, String defaultName, String filterDescription, String... filters) {
        if (hasDialog()) return CompletableFuture.completedFuture(null);

        currentSaveOrOpenFileDialog = new CompletableFuture<>();
        CompletableFuture<String> future = currentSaveOrOpenFileDialog;

        boolean initializedNfd = AsyncFileDialogs.initializedNfd;
        AsyncFileDialogs.initializedNfd = true;

        Runnable runnable = () -> {
            try {
                if (!initializedNfd) {
                    int initResult = NativeFileDialog.NFD_Init();

                    // NFD failed to initialize. Use the Android-safe fallback.
                    if (initResult != NativeFileDialog.NFD_OKAY) {
                        future.complete(getFallbackExportPath(defaultName));
                        currentSaveOrOpenFileDialog = null;
                        return;
                    }
                }

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer out = stack.callocPointer(1);

                    StringBuilder filterBuilder = new StringBuilder();

                    for (String filter : filters) {
                        if (!filterBuilder.isEmpty()) filterBuilder.append(",");
                        filterBuilder.append(filter(filter));
                    }

                    NFDFilterItem.Buffer filtersBuffer = NFDFilterItem.malloc(1);
                    filtersBuffer.get(0)
                            .name(stack.UTF8(filter(filterDescription)))
                            .spec(stack.UTF8(filterBuilder.toString()));

                    int result = NativeFileDialog.NFD_SaveDialog(
                            out,
                            filtersBuffer,
                            filter(defaultPath),
                            filter(defaultName)
                    );

                    if (result != NativeFileDialog.NFD_OKAY) {
                        // A normal cancel is still a cancel. Do not silently save somewhere else.
                        future.complete(null);
                        currentSaveOrOpenFileDialog = null;
                    } else {
                        String selectedPath = out.getStringUTF8(0);
                        future.complete(selectedPath);
                        currentSaveOrOpenFileDialog = null;

                        if (out.get(0) != 0) {
                            NativeFileDialog.NFD_FreePath(out.get(0));
                        }
                    }
                }
            } catch (Throwable t) {
                // Android launchers may not provide NFD/native dialogs.
                // Fall back to the game's flashback/exports directory.
                t.printStackTrace();
                future.complete(getFallbackExportPath(defaultName));
                currentSaveOrOpenFileDialog = null;
            } finally {
                if (currentSaveOrOpenFileDialog != null) {
                    currentSaveOrOpenFileDialog = null;
                }
            }
        };

        if (ON_OSX) {
            // MacOS needs dialogs to be run from the main thread
            Minecraft.getInstance().submit(runnable);
        } else {
            dialogThread.submit(runnable);
        }

        return future;
    }

    public static CompletableFuture<String> openFileDialog(String defaultPath, String filterDescription, String... filters) {
        if (hasDialog()) return CompletableFuture.completedFuture(null);

        currentSaveOrOpenFileDialog = new CompletableFuture<>();
        CompletableFuture<String> future = currentSaveOrOpenFileDialog;

        boolean initializedNfd = AsyncFileDialogs.initializedNfd;
        AsyncFileDialogs.initializedNfd = true;

        Runnable runnable = () -> {
            try {
                if (!initializedNfd) {
                    int initResult = NativeFileDialog.NFD_Init();

                    if (initResult != NativeFileDialog.NFD_OKAY) {
                        future.complete(null);
                        currentSaveOrOpenFileDialog = null;
                        return;
                    }
                }

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer out = stack.callocPointer(1);

                    StringBuilder filterBuilder = new StringBuilder();

                    for (String filter : filters) {
                        if (!filterBuilder.isEmpty()) filterBuilder.append(",");
                        filterBuilder.append(filter(filter));
                    }

                    NFDFilterItem.Buffer filtersBuffer = NFDFilterItem.malloc(1);
                    filtersBuffer.get(0)
                                 .name(stack.UTF8(filter(filterDescription)))
                                 .spec(stack.UTF8(filterBuilder.toString()));

                    int result = NativeFileDialog.NFD_OpenDialog(
                            out,
                            filtersBuffer,
                            filter(defaultPath)
                    );

                    if (result != NativeFileDialog.NFD_OKAY) {
                        future.complete(null);
                        currentSaveOrOpenFileDialog = null;
                    } else {
                        future.complete(out.getStringUTF8(0));
                        currentSaveOrOpenFileDialog = null;

                        if (out.get(0) != 0) {
                            NativeFileDialog.NFD_FreePath(out.get(0));
                        }
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
                future.complete(null);
                currentSaveOrOpenFileDialog = null;
            } finally {
                if (currentSaveOrOpenFileDialog != null) {
                    currentSaveOrOpenFileDialog = null;
                }
            }
        };

        if (ON_OSX) {
            // MacOS needs dialogs to be run from the main thread
            Minecraft.getInstance().submit(runnable);
        } else {
            dialogThread.submit(runnable);
        }

        return future;
    }

    public static CompletableFuture<String> openFolderDialog(String defaultPath) {
        if (hasDialog()) return CompletableFuture.completedFuture(null);

        currentSaveOrOpenFileDialog = new CompletableFuture<>();
        CompletableFuture<String> future = currentSaveOrOpenFileDialog;

        boolean initializedNfd = AsyncFileDialogs.initializedNfd;
        AsyncFileDialogs.initializedNfd = true;

        Runnable runnable = () -> {
            try {
                if (!initializedNfd) {
                    int initResult = NativeFileDialog.NFD_Init();

                    if (initResult != NativeFileDialog.NFD_OKAY) {
                        future.complete(null);
                        currentSaveOrOpenFileDialog = null;
                        return;
                    }
                }

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer out = stack.callocPointer(1);

                    int result = NativeFileDialog.NFD_PickFolder(out, filter(defaultPath));

                    if (result != NativeFileDialog.NFD_OKAY) {
                        future.complete(null);
                        currentSaveOrOpenFileDialog = null;
                    } else {
                        future.complete(out.getStringUTF8(0));
                        currentSaveOrOpenFileDialog = null;

                        if (out.get(0) != 0) {
                            NativeFileDialog.NFD_FreePath(out.get(0));
                        }
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
                future.complete(null);
                currentSaveOrOpenFileDialog = null;
            } finally {
                if (currentSaveOrOpenFileDialog != null) {
                    currentSaveOrOpenFileDialog = null;
                }
            }
        };

        if (ON_OSX) {
            // MacOS needs dialogs to be run from the main thread
            Minecraft.getInstance().submit(runnable);
        } else {
            dialogThread.submit(runnable);
        }

        return future;
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
