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
        int tw = Math.max(W_WIDGET, width - X_WIDGET - 10);
        int bx = X_LABEL + 4;
        int fieldW = width - 20;
        int fieldStep = 36;
        int fieldY = y + 140;

        enabledBtn = toggle(x, y, tw, cfg.enabled);
        compressionBtn = toggle(x, y + 28, tw, cfg.compression);
        autoReconnBtn = toggle(x, y + 56, tw, cfg.autoReconnect);
        showLatBtn = toggle(x, y + 84, tw, cfg.showLatency);
        showStatusBtn = toggle(x, y + 112, tw, cfg.showStatus);

        hostBox = box(bx, fieldY, fieldW, String.valueOf(cfg.host), false);
        portBox = box(bx, fieldY + fieldStep, fieldW, String.valueOf(cfg.port), true);
        tokenBox = box(bx, fieldY + fieldStep * 2, fieldW, cfg.token, false);
        timeoutBox = box(bx, fieldY + fieldStep * 3, fieldW, String.valueOf(cfg.timeoutMs), true);
        maxTasksBox = box(bx, fieldY + fieldStep * 4, fieldW, String.valueOf(cfg.maxConcurrentTasks), true);

        int buttonsY = fieldY + fieldStep * 5 + 6;
        testBtn = Button.builder(Component.literal("Test connection"),
                        b -> testConnection())
                .bounds(width / 2 - 120, buttonsY, 118, 20).build();
        saveBtn = Button.builder(Component.literal("Save & Close"),
                        b -> save())
                .bounds(width / 2 + 2, buttonsY, 118, 20).build();

        addRenderableWidget(enabledBtn);
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
        label(g, "Compression", compressionBtn.getY());
        label(g, "Auto Reconnect", autoReconnBtn.getY());
        label(g, "Host", hostBox.getY() - 14);
        label(g, "Port", portBox.getY() - 14);
        label(g, "Token", tokenBox.getY() - 14);
        label(g, "Timeout ms", timeoutBox.getY() - 14);
        label(g, "Max tasks", maxTasksBox.getY() - 14);
        label(g, "Show latency", showLatBtn.getY());
        label(g, "Show status", showStatusBtn.getY());

        if (!status.isEmpty()) {
            int buttonsY = hostBox.getY() + 36 * 5 + 6;
            g.text(this.font, status, width / 2 - font.width(status) / 2, buttonsY + 26,
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

    private Button toggle(int x, int y, int w, boolean initial) {
        Button b = Button.builder(Component.literal(initial ? "ON" : "OFF"),
                        btn -> {
                            boolean next = btn.getMessage().getString().endsWith("ON");
                            btn.setMessage(Component.literal(next ? "OFF" : "ON"));
                        })
                .bounds(x, y, w, 20).build();
        return b;
    }
}