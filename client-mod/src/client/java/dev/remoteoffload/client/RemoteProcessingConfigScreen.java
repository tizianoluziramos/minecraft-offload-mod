package dev.remoteoffload.client;

import dev.remoteoffload.config.RemoteProcessingConfig;
import dev.remoteoffload.config.RemoteProcessingStore;
import dev.remoteoffload.runtime.RemoteProcessingEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Pantalla "Remote Processing" dentro de Opciones.
 * La prueba de conexión es asíncrona (no bloquea el render/tick).
 */
public class RemoteProcessingConfigScreen extends Screen {

    private final Screen parent;

    private EditBox hostBox;
    private EditBox portBox;
    private EditBox tokenBox;
    private EditBox timeoutBox;
    private EditBox maxTasksBox;

    private Button enabledBtn;
    private Button modeBtn;
    private Button compressionBtn;
    private Button autoReconnBtn;
    private Button showLatBtn;
    private Button showStatusBtn;
    private Button testBtn;
    private Button saveBtn;

    private volatile String status = "";

    public RemoteProcessingConfigScreen(Screen parent) {
        super(Component.literal("Remote Processing"));
        this.parent = parent;
    }

    private static final int X_LABEL = 10;
    private static final int X_WIDGET = 128;
    private static final int W_WIDGET = 170;

    @Override
    protected void init() {
        RemoteProcessingConfig cfg = baseConfig();

        int x = X_WIDGET;
        int y = 28;
        enabledBtn = toggle(x, y, "Enabled", cfg.enabled, v -> {});
        modeBtn = modeToggle(x + 66, y, cfg.mode);
        compressionBtn = toggle(x, y + 28, "Compression", cfg.compression, v -> {});
        autoReconnBtn = toggle(x + 66, y + 28, "AutoReconnect", cfg.autoReconnect, v -> {});

        hostBox = box(X_LABEL + 4, y + 28 * 2, width - 20, String.valueOf(cfg.host), false);
        portBox = box(X_LABEL + 4, y + 28 * 3, width - 20, String.valueOf(cfg.port), true);
        tokenBox = box(X_LABEL + 4, y + 28 * 4, width - 20, cfg.token, false);
        timeoutBox = box(X_LABEL + 4, y + 28 * 5, width - 20, String.valueOf(cfg.timeoutMs), true);
        maxTasksBox = box(X_LABEL + 4, y + 28 * 6, width - 20, String.valueOf(cfg.maxConcurrentTasks), true);

        showLatBtn = toggle(x, y + 28 * 7, "Show latency", cfg.showLatency, v -> {});
        showStatusBtn = toggle(x + 66, y + 28 * 7, "Show status", cfg.showStatus, v -> {});

        testBtn = Button.builder(Component.literal("Test connection"),
                        b -> testConnection())
                .bounds(width / 2 - 120, y + 28 * 8, 118, 20).build();
        saveBtn = Button.builder(Component.literal("Save & Close"),
                        b -> save())
                .bounds(width / 2 + 2, y + 28 * 8, 118, 20).build();

        addRenderableWidget(enabledBtn);
        addRenderableWidget(modeBtn);
        addRenderableWidget(compressionBtn);
        addRenderableWidget(autoReconnBtn);
        addRenderableWidget(hostBox);
        addRenderableWidget(portBox);
        addRenderableWidget(tokenBox);
        addRenderableWidget(timeoutBox);
        addRenderableWidget(maxTasksBox);
        addRenderableWidget(showLatBtn);
        addRenderableWidget(showStatusBtn);
        addRenderableWidget(testBtn);
        addRenderableWidget(saveBtn);

        setInitialFocus(hostBox);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        super.extractRenderState(g, mx, my, partialTick);
        {
            g.text(this.font, "Remote Processing", width / 2 - font.width("Remote Processing") / 2, 8, 0xFFFFFFFF, false);
        }
        int y = 28;
        label(g, "Enabled", enabledBtn.getY());
        label(g, "Mode", modeBtn.getY() + 8);
        label(g, "Compression", compressionBtn.getY());
        label(g, "AutoReconnect", autoReconnBtn.getY() + 8);
        label(g, "Host", hostBox.getY() - 14);
        label(g, "Port", portBox.getY() - 14);
        label(g, "Token", tokenBox.getY() - 14);
        label(g, "Timeout ms", timeoutBox.getY() - 14);
        label(g, "Max tasks", maxTasksBox.getY() - 14);
        label(g, "Show latency", showLatBtn.getY() + 8);
        label(g, "Show status", showStatusBtn.getY() + 8);

        if (!status.isEmpty()) {
            g.text(this.font, status, width / 2 - font.width(status) / 2, y + 28 * 8 + 26,
                    0xFF55FF55, false);
        }
    }

    private void label(GuiGraphicsExtractor g, String text, int y) {
        g.text(this.font, text, X_LABEL, y + 5, 0xFFAAAAAA, false);
    }

    // ------------------------------------------------------------- acciones

    private void testConnection() {
        RemoteProcessingConfig t = buildFromFields();
        String warn = t.validateHostWarnings();
        if (warn != null) {
            status = "Config inválida: " + warn;
            return;
        }
        status = "Probando...";
        testBtn.active = false;
        RemoteProcessingEngine engine = RemoteProcessingEngine.instance();
        if (engine == null) {
            status = "Motor no inicializado";
            testBtn.active = true;
            return;
        }
        engine.testConnection(t).whenComplete((r, e) -> Minecraft.getInstance().execute(() -> {
            status = r;
            testBtn.active = true;
        }));
    }

    private void save() {
        RemoteProcessingConfig t = buildFromFields();
        String warn = t.validateHostWarnings();
        if (warn != null) {
            status = "Config inválida: " + warn;
            return;
        }
        String saveErr = RemoteProcessingStore.save(t);
        if (saveErr != null) {
            status = saveErr;
            return;
        }
        RemoteProcessingEngine engine = RemoteProcessingEngine.instance();
        if (engine != null) {
            engine.applyConfig(t);
        }
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    // ------------------------------------------------------------- helpers

    private RemoteProcessingConfig baseConfig() {
        RemoteProcessingEngine e = RemoteProcessingEngine.instance();
        return e != null ? e.config().copy() : new RemoteProcessingConfig();
    }

    private RemoteProcessingConfig buildFromFields() {
        RemoteProcessingEngine e = RemoteProcessingEngine.instance();
        RemoteProcessingConfig c = e != null ? e.config().copy() : new RemoteProcessingConfig();
        c.enabled = value(enabledBtn, false);
        c.mode = modeFromBtn();
        c.host = hostBox.getValue();
        c.port = intOf(portBox.getValue(), c.port);
        c.token = tokenBox.getValue();
        c.timeoutMs = intOf(timeoutBox.getValue(), c.timeoutMs);
        c.maxConcurrentTasks = Math.max(1, Math.min(64, intOf(maxTasksBox.getValue(), c.maxConcurrentTasks)));
        c.compression = value(compressionBtn, true);
        c.autoReconnect = value(autoReconnBtn, true);
        c.showLatency = value(showLatBtn, false);
        c.showStatus = value(showStatusBtn, false);
        return c;
    }

    private boolean value(Button b, boolean def) {
        return b != null ? b.getMessage().getString().toLowerCase().contains("on") : def;
    }

    private String modeFromBtn() {
        String s = modeBtn.getMessage().getString();
        return s.toUpperCase().contains("INTERNET") ? "INTERNET" : "LAN";
    }

    private static int intOf(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private EditBox box(int x, int y, int w, String v, boolean digitsOnly) {
        EditBox b = new EditBox(this.font, x, y, w, 20, Component.literal(v));
        b.setValue(v);
        b.setMaxLength(256);
        if (digitsOnly) {
            b.setResponder(s -> {
                String cleaned = s.replaceAll("[^0-9]", "");
                if (!s.equals(cleaned)) {
                    b.setValue(cleaned);
                }
            });
        }
        return b;
    }

    private Button toggle(int x, int y, String name, boolean initial, Consumer<Boolean> on) {
        Button b = Button.builder(Component.literal(name + ": " + (initial ? "ON" : "OFF")),
                        btn -> {
                            boolean next = btn.getMessage().getString().endsWith("ON");
                            btn.setMessage(Component.literal(name + ": " + (next ? "OFF" : "ON")));
                            on.accept(!next);
                        })
                .bounds(x, y, 62, 20).build();
        return b;
    }

    private Button modeToggle(int x, int y, String mode) {
        Button b = Button.builder(Component.literal("Mode:" + mode.toUpperCase()),
                        btn -> btn.setMessage(Component.literal(
                                "Mode:" + (btn.getMessage().getString().contains("LAN") ? "INTERNET" : "LAN"))))
                .bounds(x, y, 92, 20).build();
        return b;
    }
}