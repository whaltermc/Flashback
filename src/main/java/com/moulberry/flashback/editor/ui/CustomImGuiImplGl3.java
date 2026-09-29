package com.moulberry.flashback.editor.ui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.utils.MobileCompat;
import imgui.moulberry92.ImDrawData;
import imgui.moulberry92.ImFontAtlas;
import imgui.moulberry92.ImGui;
import imgui.moulberry92.ImGuiIO;
import imgui.moulberry92.ImGuiViewport;
import imgui.moulberry92.ImVec4;
import imgui.moulberry92.callback.ImPlatformFuncViewport;
import imgui.moulberry92.flag.ImGuiBackendFlags;
import imgui.moulberry92.flag.ImGuiConfigFlags;
import imgui.moulberry92.flag.ImGuiViewportFlags;
import imgui.moulberry92.type.ImInt;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL33.*;

/**
 * Dear ImGui OpenGL renderer that is safe to use on top of OpenGL translation layers
 * (MobileGlues, gl4es, ANGLE, Zink, ...) as used by Android/iOS launchers, while still behaving like the
 * regular {@code imgui_impl_opengl3} on desktop.
 * <p>
 * Differences compared to the stock backend:
 * <ul>
 *     <li>Never touches desktop-only state on translation layers: {@code glPolygonMode}/{@code GL_POLYGON_MODE},
 *     {@code GL_PRIMITIVE_RESTART}, {@code GL_CLIP_ORIGIN}, {@code GL_CONTEXT_PROFILE_MASK}.</li>
 *     <li>{@code glDrawElementsBaseVertex} is emulated by offsetting the vertex attribute pointers, so no
 *     GL 3.2 / GLES 3.2 entry point is required for large meshes.</li>
 *     <li>Attribute locations are bound explicitly ({@code glBindAttribLocation}) instead of relying on
 *     {@code layout(location)} or on the translator keeping names intact.</li>
 *     <li>Robust GL version parsing (handles {@code "OpenGL ES 3.2 MobileGlues ..."}), GLES detection and
 *     a chain of GLSL versions that is tried until one compiles and links.</li>
 *     <li>Persistent VAO instead of creating/deleting one every frame.</li>
 *     <li>Scissor rectangles are clamped to the framebuffer, GL error state is cleaned after probing.</li>
 * </ul>
 * The public API is the same as before ({@link #init(String)}, {@link #newFrame()}, {@link #renderDrawData(ImDrawData)}, ...).
 */
public class CustomImGuiImplGl3 {
    protected static final String OS = System.getProperty("os.name", "generic").toLowerCase();
    protected static final boolean IS_APPLE = OS.contains("mac") || OS.contains("darwin");

    // Constants that are only defined in newer GL versions. Kept as literals so that no newer GL class gets loaded.
    private static final int GL_CLIP_ORIGIN = 0x935C;
    private static final int GL_UPPER_LEFT = 0x8CA2;

    // Fixed attribute locations that are bound before linking
    private static final int LOC_POSITION = 0;
    private static final int LOC_UV = 1;
    private static final int LOC_COLOR = 2;

    protected static class Data {
        protected int glVersion = 0; // e.g. 320 for GL 3.2
        protected boolean isEs = false;
        protected boolean glProfileIsCompat = false;
        protected GLCapabilities glCapabilities = null;
        protected String glslVersion = "";
        protected int fontTexture = -1;
        protected int shaderHandle = -1;
        protected int attribLocationTex = 0;
        protected int attribLocationProjMtx = 0;
        protected int attribLocationVtxPos = LOC_POSITION;
        protected int attribLocationVtxUV = LOC_UV;
        protected int attribLocationVtxColor = LOC_COLOR;
        protected int vboHandle = 0;
        protected int elementsHandle = 0;
        protected int vaoHandle = 0;

        // Feature flags, decided once in init()
        protected boolean useTranslationLayerPath = false; // true on mobile/translation layers: only use the safest subset of GL
        protected boolean hasVaos = true;
        protected boolean hasSamplers = false;
        protected boolean hasPolygonMode = false;
        protected boolean hasPrimitiveRestart = false;
        protected boolean hasClipOrigin = false;
        protected boolean hasBaseVertex = false;
    }

    private static final class Properties {
        private final ImVec4 clipRect = new ImVec4();
        private final float[] orthoProjMatrix = new float[4 * 4];
        private final int[] lastFramebuffer = new int[1];
        private final int[] lastActiveTexture = new int[1];
        private final int[] lastProgram = new int[1];
        private final int[] lastTexture = new int[1];
        private final int[] lastSampler = new int[1];
        private final int[] lastArrayBuffer = new int[1];
        private final int[] lastVertexArrayObject = new int[1];
        private final int[] lastPolygonMode = new int[2];
        private final int[] lastViewport = new int[4];
        private final int[] lastScissorBox = new int[4];
        private final int[] lastBlendSrcRgb = new int[1];
        private final int[] lastBlendDstRgb = new int[1];
        private final int[] lastBlendSrcAlpha = new int[1];
        private final int[] lastBlendDstAlpha = new int[1];
        private final int[] lastBlendEquationRgb = new int[1];
        private final int[] lastBlendEquationAlpha = new int[1];
        private final int[] tmp = new int[1];
        private final java.nio.ByteBuffer lastColorMaskBytes = org.lwjgl.BufferUtils.createByteBuffer(4);
        private boolean lastEnableBlend = false;
        private boolean lastEnableCullFace = false;
        private boolean lastEnableDepthTest = false;
        private boolean lastEnableStencilTest = false;
        private boolean lastEnableScissorTest = false;
        private boolean lastEnablePrimitiveRestart = false;
    }

    protected Data data = null;
    private final Properties props = new Properties();

    // Vertex offset currently applied through the attribute pointers (base vertex emulation)
    private int currentVtxOffset = 0;

    // Diagnostics: logs GL errors per stage for the first frames (or always with -Dflashback.glDebug=true)
    private static final boolean GL_DEBUG_FORCED = Boolean.getBoolean("flashback.glDebug");
    private int debugFrames = 0;
    private final java.util.Set<String> reportedErrors = new java.util.HashSet<>();

    private boolean debugActive() {
        return GL_DEBUG_FORCED || debugFrames < 90;
    }

    private void checkGl(String stage) {
        if (!debugActive()) return;
        int error = glGetError();
        int guard = 0;
        while (error != GL_NO_ERROR && guard++ < 8) {
            String key = stage + "/" + error;
            if (reportedErrors.add(key)) {
                Flashback.LOGGER.warn("ImGui GL error 0x{} after '{}' (frame {})", Integer.toHexString(error), stage, debugFrames);
            }
            error = glGetError();
        }
    }

    protected Data newData() {
        return new Data();
    }

    public boolean init() {
        return init(null);
    }

    /**
     * @param glslVersion GLSL version directive to try first (e.g. {@code "#version 150"}), or null for a sensible default.
     *                    If it fails to compile/link, other versions are tried automatically.
     */
    public boolean init(final String glslVersion) {
        data = newData();
        data.useTranslationLayerPath = MobileCompat.isMobile();

        final ImGuiIO io = ReplayUI.getIO();
        io.setBackendRendererName("flashback_impl_opengl3_compat");

        // Probe the context
        clearGlErrors();
        final String glVersionString = glGetString(GL_VERSION);
        data.isEs = glVersionString != null && glVersionString.contains("OpenGL ES");

        int major = 0;
        int minor = 0;
        if (!data.isEs) {
            major = glGetInteger(GL_MAJOR_VERSION);
            minor = glGetInteger(GL_MINOR_VERSION);
            clearGlErrors(); // GL_MAJOR_VERSION is invalid on GL < 3.0
        }
        if (major == 0 && minor == 0 && glVersionString != null) {
            // "4.6 (Compatibility Profile) Mesa ...", "OpenGL ES 3.2 MobileGlues ...", etc.
            final Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)").matcher(glVersionString);
            if (matcher.find()) {
                major = Integer.parseInt(matcher.group(1));
                minor = Integer.parseInt(matcher.group(2));
            }
        }
        data.glVersion = major * 100 + minor * 10;

        if (!data.useTranslationLayerPath && !data.isEs && data.glVersion >= 320) {
            final int profileMask = glGetInteger(GL_CONTEXT_PROFILE_MASK);
            clearGlErrors();
            data.glProfileIsCompat = (profileMask & GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0;
        }

        try {
            data.glCapabilities = GL.getCapabilities();
        } catch (IllegalStateException ignored) {
            // No capabilities in this thread, only affects optional extension checks
        }

        final boolean arbSamplers = data.glCapabilities != null && data.glCapabilities.GL_ARB_sampler_objects;
        data.hasVaos = data.glVersion >= 300 || (data.glCapabilities != null && data.glCapabilities.GL_ARB_vertex_array_object);
        final boolean samplerFunctions = data.glCapabilities != null && data.glCapabilities.glBindSampler != 0L;
        // Sampler objects override the texture's own filter state. Minecraft binds its own samplers (often with mipmap
        // filters), which makes our mipmap-less font texture "incomplete" => sampled as black. So we always unbind it.
        data.hasSamplers = data.glVersion >= 330 || (data.isEs && data.glVersion >= 300) || arbSamplers || samplerFunctions;

        // Desktop-only state. Translation layers either don't implement it or raise GL errors for it
        data.hasPolygonMode = !data.useTranslationLayerPath && !data.isEs && data.glVersion >= 200;
        data.hasPrimitiveRestart = !data.useTranslationLayerPath && !data.isEs && data.glVersion >= 310;
        data.hasClipOrigin = !data.useTranslationLayerPath && !data.isEs && data.glVersion >= 450;
        data.hasBaseVertex = !data.useTranslationLayerPath && !data.isEs && data.glVersion >= 320;

        // Vertex offsets are always supported: either natively (base vertex) or emulated through attribute pointers
        io.addBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);

        // Multi-viewports need real desktop windows, never advertise them on mobile
        if (!data.useTranslationLayerPath) {
            io.addBackendFlags(ImGuiBackendFlags.RendererHasViewports);
        }

        if (glslVersion == null) {
            if (data.isEs) {
                data.glslVersion = "#version 300 es";
            } else if (IS_APPLE) {
                data.glslVersion = "#version 150";
            } else {
                data.glslVersion = "#version 130";
            }
        } else {
            data.glslVersion = glslVersion;
        }

        // Make an arbitrary GL call, if this crashes the GL function loader didn't do its job
        glGetIntegerv(GL_TEXTURE_BINDING_2D, props.tmp);

        if (!data.useTranslationLayerPath && ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            initPlatformInterface();
        }

        Flashback.LOGGER.info("ImGui GL backend: version={} ({}), es={}, translationLayerPath={}, samplers={}, baseVertex={}",
                glVersionString, data.glVersion, data.isEs, data.useTranslationLayerPath, data.hasSamplers, data.hasBaseVertex);

        return true;
    }

    public void shutdown() {
        final ImGuiIO io = ReplayUI.getIO();

        if (!data.useTranslationLayerPath) {
            shutdownPlatformInterface();
        }
        destroyDeviceObjects();

        io.setBackendRendererName(null);
        io.removeBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset | ImGuiBackendFlags.RendererHasViewports);
        data = null;
    }

    public void newFrame() {
        if (data.shaderHandle == -1) {
            createDeviceObjects();
        }
        if (data.fontTexture == -1) {
            updateFontsTexture();
        }
    }

    private static void clearGlErrors() {
        for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; i++) {
            // drain
        }
    }

    private void setVertexAttribPointers(final long baseOffset) {
        final int stride = ImDrawData.sizeOfImDrawVert();
        glVertexAttribPointer(data.attribLocationVtxPos, 2, GL_FLOAT, false, stride, baseOffset);
        glVertexAttribPointer(data.attribLocationVtxUV, 2, GL_FLOAT, false, stride, baseOffset + 8);
        glVertexAttribPointer(data.attribLocationVtxColor, 4, GL_UNSIGNED_BYTE, true, stride, baseOffset + 16);
    }

    protected void setupRenderState(final ImDrawData drawData, final int fbWidth, final int fbHeight, final int vertexArrayObject) {
        // Alpha-blending enabled, no face culling, no depth testing, scissor enabled
        glEnable(GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_STENCIL_TEST);
        glEnable(GL_SCISSOR_TEST);
        glColorMask(true, true, true, true);

        if (data.hasPrimitiveRestart) {
            glDisable(GL_PRIMITIVE_RESTART);
        }
        if (data.hasPolygonMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }

        // Support for GL 4.5 rarely used glClipControl(GL_UPPER_LEFT)
        boolean clipOriginLowerLeft = true;
        if (data.hasClipOrigin) {
            glGetIntegerv(GL_CLIP_ORIGIN, props.tmp);
            if (props.tmp[0] == GL_UPPER_LEFT) {
                clipOriginLowerLeft = false;
            }
            clearGlErrors();
        }

        glViewport(0, 0, fbWidth, fbHeight);
        float L = drawData.getDisplayPosX();
        float R = drawData.getDisplayPosX() + drawData.getDisplaySizeX();
        float T = drawData.getDisplayPosY();
        float B = drawData.getDisplayPosY() + drawData.getDisplaySizeY();

        if (!clipOriginLowerLeft) {
            float tmp = T;
            T = B;
            B = tmp;
        }

        props.orthoProjMatrix[0] = 2.0f / (R - L);
        props.orthoProjMatrix[5] = 2.0f / (T - B);
        props.orthoProjMatrix[10] = -1.0f;
        props.orthoProjMatrix[12] = (R + L) / (L - R);
        props.orthoProjMatrix[13] = (T + B) / (B - T);
        props.orthoProjMatrix[15] = 1.0f;

        glUseProgram(data.shaderHandle);
        glUniform1i(data.attribLocationTex, 0);
        glUniformMatrix4fv(data.attribLocationProjMtx, false, props.orthoProjMatrix);

        if (data.hasSamplers) {
            glBindSampler(0, 0);
        }

        if (data.hasVaos) {
            glBindVertexArray(vertexArrayObject);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        glBindBuffer(GL_ARRAY_BUFFER, data.vboHandle);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, data.elementsHandle);
        glEnableVertexAttribArray(data.attribLocationVtxPos);
        glEnableVertexAttribArray(data.attribLocationVtxUV);
        glEnableVertexAttribArray(data.attribLocationVtxColor);
        setVertexAttribPointers(0);
        this.currentVtxOffset = 0;
    }

    /**
     * Renders the draw data. Saves and restores every piece of GL state that it modifies so that it can run inside
     * an engine that doesn't track state for us.
     */
    public void renderDrawData(final ImDrawData drawData) {
        final int fbWidth = (int) (drawData.getDisplaySizeX() * drawData.getFramebufferScaleX());
        final int fbHeight = (int) (drawData.getDisplaySizeY() * drawData.getFramebufferScaleY());
        if (fbWidth <= 0 || fbHeight <= 0) {
            return;
        }

        if (drawData.getCmdListsCount() <= 0) {
            return;
        }

        // Backup GL state
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, props.lastFramebuffer);
        glGetIntegerv(GL_ACTIVE_TEXTURE, props.lastActiveTexture);
        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_CURRENT_PROGRAM, props.lastProgram);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, props.lastTexture);
        if (data.hasSamplers) {
            glGetIntegerv(GL_SAMPLER_BINDING, props.lastSampler);
        }
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, props.lastArrayBuffer);
        if (data.hasVaos) {
            glGetIntegerv(GL_VERTEX_ARRAY_BINDING, props.lastVertexArrayObject);
        }
        if (data.hasPolygonMode) {
            glGetIntegerv(GL_POLYGON_MODE, props.lastPolygonMode);
        }
        glGetBooleanv(GL_COLOR_WRITEMASK, props.lastColorMaskBytes.rewind());
        glGetIntegerv(GL_VIEWPORT, props.lastViewport);
        glGetIntegerv(GL_SCISSOR_BOX, props.lastScissorBox);
        glGetIntegerv(GL_BLEND_SRC_RGB, props.lastBlendSrcRgb);
        glGetIntegerv(GL_BLEND_DST_RGB, props.lastBlendDstRgb);
        glGetIntegerv(GL_BLEND_SRC_ALPHA, props.lastBlendSrcAlpha);
        glGetIntegerv(GL_BLEND_DST_ALPHA, props.lastBlendDstAlpha);
        glGetIntegerv(GL_BLEND_EQUATION_RGB, props.lastBlendEquationRgb);
        glGetIntegerv(GL_BLEND_EQUATION_ALPHA, props.lastBlendEquationAlpha);
        props.lastEnableBlend = glIsEnabled(GL_BLEND);
        props.lastEnableCullFace = glIsEnabled(GL_CULL_FACE);
        props.lastEnableDepthTest = glIsEnabled(GL_DEPTH_TEST);
        props.lastEnableStencilTest = glIsEnabled(GL_STENCIL_TEST);
        props.lastEnableScissorTest = glIsEnabled(GL_SCISSOR_TEST);
        if (data.hasPrimitiveRestart) {
            props.lastEnablePrimitiveRestart = glIsEnabled(GL_PRIMITIVE_RESTART);
        }

        // Persistent VAO (single GL context). Falls back to the default VAO/state if VAOs are unavailable.
        if (data.hasVaos && data.vaoHandle == 0) {
            data.vaoHandle = glGenVertexArrays();
        }
        checkGl("backup state");
        setupRenderState(drawData, fbWidth, fbHeight, data.vaoHandle);
        checkGl("setupRenderState");

        final float clipOffX = drawData.getDisplayPosX();
        final float clipOffY = drawData.getDisplayPosY();
        final float clipScaleX = drawData.getFramebufferScaleX();
        final float clipScaleY = drawData.getFramebufferScaleY();

        final int vertexSize = ImDrawData.sizeOfImDrawVert();
        final int indexSize = ImDrawData.sizeOfImDrawIdx();
        final int indexType = indexSize == 2 ? GL_UNSIGNED_SHORT : GL_UNSIGNED_INT;

        for (int n = 0; n < drawData.getCmdListsCount(); n++) {
            glBufferData(GL_ARRAY_BUFFER, drawData.getCmdListVtxBufferData(n), GL_STREAM_DRAW);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, drawData.getCmdListIdxBufferData(n), GL_STREAM_DRAW);
            checkGl("upload buffers");

            // The buffers were re-specified, offsets are relative to the start of the (new) vertex buffer
            if (this.currentVtxOffset != 0) {
                setVertexAttribPointers(0);
                this.currentVtxOffset = 0;
            }

            for (int cmdIdx = 0; cmdIdx < drawData.getCmdListCmdBufferSize(n); cmdIdx++) {
                drawData.getCmdListCmdBufferClipRect(props.clipRect, n, cmdIdx);

                float clipMinX = (props.clipRect.x - clipOffX) * clipScaleX;
                float clipMinY = (props.clipRect.y - clipOffY) * clipScaleY;
                float clipMaxX = (props.clipRect.z - clipOffX) * clipScaleX;
                float clipMaxY = (props.clipRect.w - clipOffY) * clipScaleY;

                // Clamp to the framebuffer, translation layers can choke on negative/out of range scissor rects
                clipMinX = Math.max(0, clipMinX);
                clipMinY = Math.max(0, clipMinY);
                clipMaxX = Math.min(fbWidth, clipMaxX);
                clipMaxY = Math.min(fbHeight, clipMaxY);

                if (clipMaxX <= clipMinX || clipMaxY <= clipMinY) {
                    continue;
                }

                // Y is inverted in OpenGL
                glScissor((int) clipMinX, (int) (fbHeight - clipMaxY), (int) (clipMaxX - clipMinX), (int) (clipMaxY - clipMinY));

                final long textureId = drawData.getCmdListCmdBufferTextureId(n, cmdIdx);
                final int elemCount = drawData.getCmdListCmdBufferElemCount(n, cmdIdx);
                final int idxOffset = drawData.getCmdListCmdBufferIdxOffset(n, cmdIdx);
                final int vtxOffset = drawData.getCmdListCmdBufferVtxOffset(n, cmdIdx);
                final long indices = idxOffset * (long) indexSize;

                glBindTexture(GL_TEXTURE_2D, (int) textureId);

                if (data.hasBaseVertex) {
                    glDrawElementsBaseVertex(GL_TRIANGLES, elemCount, indexType, indices, vtxOffset);
                } else {
                    // Emulate base vertex by moving the vertex attribute pointers
                    if (vtxOffset != this.currentVtxOffset) {
                        setVertexAttribPointers((long) vtxOffset * vertexSize);
                        this.currentVtxOffset = vtxOffset;
                    }
                    glDrawElements(GL_TRIANGLES, elemCount, indexType, indices);
                }
                checkGl("draw");
            }
        }

        // Restore modified GL state
        // glIsProgram() check: if the program is "pending deletion" it may have been deleted by now, see imgui #6220
        if (props.lastProgram[0] == 0 || glIsProgram(props.lastProgram[0])) {
            glUseProgram(props.lastProgram[0]);
        }
        glBindTexture(GL_TEXTURE_2D, props.lastTexture[0]);
        if (data.hasSamplers) {
            glBindSampler(0, props.lastSampler[0]);
        }
        glActiveTexture(props.lastActiveTexture[0]);
        if (data.hasVaos) {
            glBindVertexArray(props.lastVertexArrayObject[0]);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, props.lastFramebuffer[0]);
        glBindBuffer(GL_ARRAY_BUFFER, props.lastArrayBuffer[0]);
        glBlendEquationSeparate(props.lastBlendEquationRgb[0], props.lastBlendEquationAlpha[0]);
        glBlendFuncSeparate(props.lastBlendSrcRgb[0], props.lastBlendDstRgb[0], props.lastBlendSrcAlpha[0], props.lastBlendDstAlpha[0]);
        if (props.lastEnableBlend) glEnable(GL_BLEND);
        else glDisable(GL_BLEND);
        if (props.lastEnableCullFace) glEnable(GL_CULL_FACE);
        else glDisable(GL_CULL_FACE);
        if (props.lastEnableDepthTest) glEnable(GL_DEPTH_TEST);
        else glDisable(GL_DEPTH_TEST);
        if (props.lastEnableStencilTest) glEnable(GL_STENCIL_TEST);
        else glDisable(GL_STENCIL_TEST);
        if (props.lastEnableScissorTest) glEnable(GL_SCISSOR_TEST);
        else glDisable(GL_SCISSOR_TEST);
        if (data.hasPrimitiveRestart) {
            if (props.lastEnablePrimitiveRestart) glEnable(GL_PRIMITIVE_RESTART);
            else glDisable(GL_PRIMITIVE_RESTART);
        }
        if (data.hasPolygonMode) {
            if (data.glVersion <= 310 || data.glProfileIsCompat) {
                glPolygonMode(GL_FRONT, props.lastPolygonMode[0]);
                glPolygonMode(GL_BACK, props.lastPolygonMode[1]);
            } else {
                glPolygonMode(GL_FRONT_AND_BACK, props.lastPolygonMode[0]);
            }
        }
        glColorMask(props.lastColorMaskBytes.get(0) != 0, props.lastColorMaskBytes.get(1) != 0,
                props.lastColorMaskBytes.get(2) != 0, props.lastColorMaskBytes.get(3) != 0);
        glViewport(props.lastViewport[0], props.lastViewport[1], props.lastViewport[2], props.lastViewport[3]);
        glScissor(props.lastScissorBox[0], props.lastScissorBox[1], props.lastScissorBox[2], props.lastScissorBox[3]);
        checkGl("restore state");

        if (debugFrames == 0 || debugFrames == 30) {
            Flashback.LOGGER.info("ImGui frame {}: fb={}x{} lists={} prevFbo={} prevProgram={} prevVao={} prevSampler={} prevViewport={}",
                    debugFrames, fbWidth, fbHeight, drawData.getCmdListsCount(), props.lastFramebuffer[0], props.lastProgram[0],
                    props.lastVertexArrayObject[0], props.lastSampler[0], java.util.Arrays.toString(props.lastViewport));
        }
        debugFrames++;
    }

    /** Rebuilds the font texture. Call after changing fonts. */
    public void updateFontsTexture() {
        if (data.fontTexture != -1) glDeleteTextures(data.fontTexture);

        final ImFontAtlas fontAtlas = ReplayUI.getIO().getFonts();
        final ImInt width = new ImInt();
        final ImInt height = new ImInt();
        final ByteBuffer buffer = fontAtlas.getTexDataAsRGBA32(width, height);

        glGetIntegerv(GL_TEXTURE_BINDING_2D, props.lastTexture);

        data.fontTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, data.fontTexture);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        if (!data.isEs || data.glVersion >= 300) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        }
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width.get(), height.get(), 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

        fontAtlas.setTexID(data.fontTexture);

        glBindTexture(GL_TEXTURE_2D, props.lastTexture[0]);
    }

    public void destroyFontsTexture() {
        final ImGuiIO io = ReplayUI.getIO();
        if (data.fontTexture != -1) {
            glDeleteTextures(data.fontTexture);
            io.getFonts().setTexID(0);
            data.fontTexture = -1;
        }
    }

    protected boolean checkShader(final int handle, final String desc) {
        final int[] status = new int[1];
        final int[] logLength = new int[1];
        glGetShaderiv(handle, GL_COMPILE_STATUS, status);
        glGetShaderiv(handle, GL_INFO_LOG_LENGTH, logLength);
        if (status[0] == GL_FALSE) {
            Flashback.LOGGER.error("ImGui: failed to compile {} with GLSL: {}", desc, data.glslVersion);
        }
        if (logLength[0] > 1) {
            Flashback.LOGGER.error(glGetShaderInfoLog(handle));
        }
        return status[0] == GL_TRUE;
    }

    protected boolean checkProgram(final int handle, final String desc) {
        final int[] status = new int[1];
        final int[] logLength = new int[1];
        glGetProgramiv(handle, GL_LINK_STATUS, status);
        glGetProgramiv(handle, GL_INFO_LOG_LENGTH, logLength);
        if (status[0] == GL_FALSE) {
            Flashback.LOGGER.error("ImGui: failed to link {} with GLSL: {}", desc, data.glslVersion);
        }
        if (logLength[0] > 1) {
            Flashback.LOGGER.error(glGetProgramInfoLog(handle));
        }
        return status[0] == GL_TRUE;
    }

    protected int parseGlslVersionString(final String glslVersion) {
        final Matcher m = Pattern.compile("\\d+").matcher(glslVersion);
        if (m.find()) {
            return Integer.parseInt(m.group());
        }
        return 130;
    }

    /** GLSL versions to try, in order. The requested one always goes first. */
    private List<String> glslCandidates() {
        List<String> candidates = new ArrayList<>();
        candidates.add(data.glslVersion);
        String[] fallbacks = data.isEs
                ? new String[]{"#version 300 es", "#version 310 es", "#version 320 es"}
                : new String[]{"#version 150", "#version 330 core", "#version 130", "#version 300 es", "#version 120"};
        for (String fallback : fallbacks) {
            if (!candidates.contains(fallback)) candidates.add(fallback);
        }
        return candidates;
    }

    private static boolean isEsVersion(String glsl) {
        return glsl.contains("es");
    }

    private int buildProgram(final String glsl) {
        final int version = parseGlslVersionString(glsl);
        final boolean es = isEsVersion(glsl);

        final String vertexSource;
        final String fragmentSource;
        if (!es && version < 130) {
            vertexSource = glsl + "\n"
                    + "uniform mat4 ProjMtx;\n"
                    + "attribute vec2 Position;\n"
                    + "attribute vec2 UV;\n"
                    + "attribute vec4 Color;\n"
                    + "varying vec2 Frag_UV;\n"
                    + "varying vec4 Frag_Color;\n"
                    + "void main()\n"
                    + "{\n"
                    + "    Frag_UV = UV;\n"
                    + "    Frag_Color = Color;\n"
                    + "    gl_Position = ProjMtx * vec4(Position.xy,0,1);\n"
                    + "}\n";
            fragmentSource = glsl + "\n"
                    + "#ifdef GL_ES\n"
                    + "    precision mediump float;\n"
                    + "#endif\n"
                    + "uniform sampler2D Texture;\n"
                    + "varying vec2 Frag_UV;\n"
                    + "varying vec4 Frag_Color;\n"
                    + "void main()\n"
                    + "{\n"
                    + "    gl_FragColor = Frag_Color * texture2D(Texture, Frag_UV.st);\n"
                    + "}\n";
        } else {
            // Attribute locations are bound with glBindAttribLocation, no layout qualifiers needed (not allowed in GLSL 130/150)
            final String vertexPrecision = es ? "precision highp float;\n" : "";
            final String fragmentPrecision = es ? "precision mediump float;\n" : "";
            vertexSource = glsl + "\n"
                    + vertexPrecision
                    + "uniform mat4 ProjMtx;\n"
                    + "in vec2 Position;\n"
                    + "in vec2 UV;\n"
                    + "in vec4 Color;\n"
                    + "out vec2 Frag_UV;\n"
                    + "out vec4 Frag_Color;\n"
                    + "void main()\n"
                    + "{\n"
                    + "    Frag_UV = UV;\n"
                    + "    Frag_Color = Color;\n"
                    + "    gl_Position = ProjMtx * vec4(Position.xy,0,1);\n"
                    + "}\n";
            fragmentSource = glsl + "\n"
                    + fragmentPrecision
                    + "uniform sampler2D Texture;\n"
                    + "in vec2 Frag_UV;\n"
                    + "in vec4 Frag_Color;\n"
                    + "out vec4 Out_Color;\n"
                    + "void main()\n"
                    + "{\n"
                    + "    Out_Color = Frag_Color * texture(Texture, Frag_UV.st);\n"
                    + "}\n";
        }

        final String previous = data.glslVersion;
        data.glslVersion = glsl;

        final int vertHandle = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertHandle, vertexSource);
        glCompileShader(vertHandle);
        final boolean vertOk = checkShader(vertHandle, "vertex shader");

        final int fragHandle = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragHandle, fragmentSource);
        glCompileShader(fragHandle);
        final boolean fragOk = checkShader(fragHandle, "fragment shader");

        int program = 0;
        boolean linked = false;
        if (vertOk && fragOk) {
            program = glCreateProgram();
            glAttachShader(program, vertHandle);
            glAttachShader(program, fragHandle);
            glBindAttribLocation(program, LOC_POSITION, "Position");
            glBindAttribLocation(program, LOC_UV, "UV");
            glBindAttribLocation(program, LOC_COLOR, "Color");
            glLinkProgram(program);
            linked = checkProgram(program, "shader program");
            glDetachShader(program, vertHandle);
            glDetachShader(program, fragHandle);
        }

        glDeleteShader(vertHandle);
        glDeleteShader(fragHandle);

        if (!linked) {
            if (program != 0) glDeleteProgram(program);
            data.glslVersion = previous;
            return 0;
        }
        return program;
    }

    protected boolean createDeviceObjects() {
        // Backup GL state
        glGetIntegerv(GL_TEXTURE_BINDING_2D, props.lastTexture);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, props.lastArrayBuffer);
        if (data.hasVaos) {
            glGetIntegerv(GL_VERTEX_ARRAY_BINDING, props.lastVertexArrayObject);
        }

        int program = 0;
        for (String candidate : glslCandidates()) {
            program = buildProgram(candidate);
            if (program != 0) {
                data.glslVersion = candidate;
                break;
            }
        }
        if (program == 0) {
            Flashback.LOGGER.error("ImGui: unable to create a shader program with any GLSL version");
            data.shaderHandle = -1;
            return false;
        }

        data.shaderHandle = program;
        data.attribLocationTex = glGetUniformLocation(program, "Texture");
        data.attribLocationProjMtx = glGetUniformLocation(program, "ProjMtx");

        // We bound these ourselves, but trust the driver if it disagrees
        int pos = glGetAttribLocation(program, "Position");
        int uv = glGetAttribLocation(program, "UV");
        int color = glGetAttribLocation(program, "Color");
        data.attribLocationVtxPos = pos >= 0 ? pos : LOC_POSITION;
        data.attribLocationVtxUV = uv >= 0 ? uv : LOC_UV;
        data.attribLocationVtxColor = color >= 0 ? color : LOC_COLOR;

        data.vboHandle = glGenBuffers();
        data.elementsHandle = glGenBuffers();

        // Restore modified GL state
        glBindTexture(GL_TEXTURE_2D, props.lastTexture[0]);
        glBindBuffer(GL_ARRAY_BUFFER, props.lastArrayBuffer[0]);
        if (data.hasVaos) {
            glBindVertexArray(props.lastVertexArrayObject[0]);
        }

        return true;
    }

    public void destroyDeviceObjects() {
        if (data.vboHandle != 0) {
            glDeleteBuffers(data.vboHandle);
            data.vboHandle = 0;
        }
        if (data.elementsHandle != 0) {
            glDeleteBuffers(data.elementsHandle);
            data.elementsHandle = 0;
        }
        if (data.vaoHandle != 0) {
            glDeleteVertexArrays(data.vaoHandle);
            data.vaoHandle = 0;
        }
        if (data.shaderHandle != -1) {
            glDeleteProgram(data.shaderHandle);
            data.shaderHandle = -1;
        }
        destroyFontsTexture();
    }

    //--------------------------------------------------------------------------------------------------------
    // MULTI-VIEWPORT / PLATFORM INTERFACE SUPPORT (desktop only, never used on mobile)
    //--------------------------------------------------------------------------------------------------------

    private final class RendererRenderWindowFunction extends ImPlatformFuncViewport {
        @Override
        public void accept(final ImGuiViewport vp) {
            if (!vp.hasFlags(ImGuiViewportFlags.NoRendererClear)) {
                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT);
            }
            renderDrawData(vp.getDrawData());
        }
    }

    protected void initPlatformInterface() {
        ImGui.getPlatformIO().setRendererRenderWindow(new RendererRenderWindowFunction());
    }

    protected void shutdownPlatformInterface() {
        ImGui.destroyPlatformWindows();
    }
}
