package com.debugtools.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.ConnectException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

public final class DesktopFrame extends JFrame {
    private final DebugClient client = new DebugClient();
    /** Single-thread executor keeps command order without blocking EDT. */
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "debug-network");
        t.setDaemon(true);
        return t;
    });
    private final JTextField hostField = new JTextField("127.0.0.1");
    private final JTextField portField = new JTextField("4939");
    private final JTextArea outputArea = new JTextArea();
    private final JTextField methodField = new JTextField("GET");
    private final JTextField pathField = new JTextField("/api/profile");
    private final JTextField statusField = new JTextField("200");
    private final JTextArea bodyArea = new JTextArea("{\"name\":\"debug-user\"}");
    private final DefaultMutableTreeNode viewRoot = new DefaultMutableTreeNode("No view tree yet");
    private final DefaultTreeModel viewTreeModel = new DefaultTreeModel(viewRoot);
    private final JTree viewTree = new JTree(viewTreeModel);
    private final DefaultTableModel mockTableModel = new DefaultTableModel(new Object[]{"Method", "Path", "Status", "Body"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final DefaultTableModel watchTableModel = new DefaultTableModel(new Object[]{"Label", "Retained", "Class", "File:Line", "Retained(ms)", "Source", "Leak Trace"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable mockTable = new JTable(mockTableModel);
    private final JTable watchTable = new JTable(watchTableModel);
    private final JLabel retainedCountLabel = new JLabel("Retained objects: -");
    private final JTextArea leakHistoryArea = new JTextArea("No leak records yet");
    private final JCheckBox leakTrackingCheck = new JCheckBox("Track While Running (5s)");
    private final Timer leakPollingTimer = new Timer(5000, event -> requestLeakSnapshot());
    private final Map<String, String> leakSeenRecords = new HashMap<>();
    private final PreviewPanel previewPanel = new PreviewPanel();
    private final JLabel previewInfoLabel = new JLabel("No preview frame yet");
    private final JCheckBox enable3dCheck = new JCheckBox("3D Layer", true);
    private final JSlider yawSlider = new JSlider(-70, 70, 35);
    private final JSlider pitchSlider = new JSlider(-70, 70, 30);
    private final JSlider depthSlider = new JSlider(2, 40, 12);
    private final JSlider minDepthSlider = new JSlider(0, 0, 0);
    private final JSlider maxDepthSlider = new JSlider(0, 0, 0);
    private final JTextField selectedPathField = new JTextField();
    private final JTextField selectedClassField = new JTextField();
    private final JTextField selectedIdValueField = new JTextField();
    private final JTextField selectedIdField = new JTextField();
    private final JTextField selectedVisibilityField = new JTextField();
    private final JTextField selectedEnabledField = new JTextField();
    private final JTextField selectedClickableField = new JTextField();
    private final JTextField selectedFocusableField = new JTextField();
    private final JTextField selectedAlphaField = new JTextField();
    private final JTextField selectedLabelField = new JTextField();
    private final JTextField selectedContentDescriptionField = new JTextField();
    private final JTextField selectedHintField = new JTextField();
    private final JTextField selectedColorField = new JTextField();
    private final JTextField selectedTextColorField = new JTextField();
    private final JTextField selectedTextSizeSpField = new JTextField();
    private final JTextField selectedMarginLeftField = new JTextField();
    private final JTextField selectedMarginTopField = new JTextField();
    private final JTextField selectedMarginRightField = new JTextField();
    private final JTextField selectedMarginBottomField = new JTextField();
    private final JTextField selectedPaddingLeftField = new JTextField();
    private final JTextField selectedPaddingTopField = new JTextField();
    private final JTextField selectedPaddingRightField = new JTextField();
    private final JTextField selectedPaddingBottomField = new JTextField();
    private final JButton applyViewChangesButton = new JButton("Apply View Changes");
    private final List<ViewTreeItem> flatViewItems = new ArrayList<>();
    private final Map<ViewTreeItem, DefaultMutableTreeNode> viewTreeNodeMap = new HashMap<>();
    private int treeOriginLeft = 0;
    private int treeOriginTop = 0;
    private int nextViewDrawOrder = 0;
    private boolean suppressTreeSelectionCallback;
    private static final int DEFAULT_YAW_DEGREES = 30;
    private static final int DEFAULT_PITCH_DEGREES = 20;
    private static final int DEFAULT_DEPTH_SPACING = 10;
    private static final double DRAG_ROTATION_SENSITIVITY = 0.35;
    private static final DateTimeFormatter LEAK_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter ANALYSIS_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    public DesktopFrame() {
        super("Debug Tools Desktop");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(new Dimension(1080, 720));
        setLayout(new BorderLayout(12, 12));

        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);

        selectedPathField.setEditable(false);
        selectedClassField.setEditable(false);
        selectedIdValueField.setEditable(false);
        selectedIdField.setEditable(false);
        selectedVisibilityField.setEditable(false);
        selectedEnabledField.setEditable(false);
        selectedClickableField.setEditable(false);
        selectedFocusableField.setEditable(false);
        applyViewChangesButton.addActionListener(event -> applySelectedViewChanges());

        bindPreview3dControls();
        bindLeakTrackingControls();
        viewTree.addTreeSelectionListener(event -> onViewNodeSelected());
    }

    private JPanel buildTopPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));

        JPanel connectPanel = new JPanel(new GridLayout(1, 5, 8, 8));
        JButton connectButton = new JButton("Connect");
        connectButton.addActionListener(event -> connect());
        JButton viewButton = new JButton("Fetch View Tree");
        viewButton.addActionListener(event -> {
            send("get_view_tree", "");
            send("get_view_preview", "");
        });
        JButton previewButton = new JButton("Fetch Preview");
        previewButton.addActionListener(event -> send("get_view_preview", ""));

        connectPanel.add(new JLabel("Host"));
        connectPanel.add(hostField);
        connectPanel.add(new JLabel("Port"));
        connectPanel.add(portField);
        connectPanel.add(connectButton);

        JPanel actionPanel = new JPanel(new GridLayout(1, 2, 8, 8));
        actionPanel.add(viewButton);
        actionPanel.add(previewButton);

        panel.add(connectPanel);
        panel.add(actionPanel);
        return panel;
    }

    private JPanel buildCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        outputArea.setEditable(false);
        leakHistoryArea.setEditable(false);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Protocol Raw", new JScrollPane(outputArea));
        tabs.addTab("UI Inspector", buildInspectorPanel());
        tabs.addTab("Memory Leak", buildMemoryLeakTab());
        tabs.addTab("HTTP Mock", buildHttpMockPanel());

        panel.add(tabs, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildMemoryLeakTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JSplitPane leakContentSplit = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(watchTable),
            new JScrollPane(leakHistoryArea)
        );
        leakContentSplit.setResizeWeight(0.7);

        panel.add(retainedCountLabel, BorderLayout.NORTH);
        panel.add(leakContentSplit, BorderLayout.CENTER);
        panel.add(buildMemoryLeakToolPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildInspectorPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));

        JPanel previewHeader = new JPanel(new BorderLayout(8, 8));
        JPanel previewControlPanel = new JPanel(new GridLayout(2, 3, 8, 8));
        previewControlPanel.add(enable3dCheck);
        previewControlPanel.add(labeledComponent("Yaw", yawSlider));
        previewControlPanel.add(labeledComponent("Pitch", pitchSlider));
        previewControlPanel.add(labeledComponent("Depth", depthSlider));
        previewControlPanel.add(labeledComponent("Min Depth", minDepthSlider));
        previewControlPanel.add(labeledComponent("Max Depth", maxDepthSlider));
        previewHeader.add(previewInfoLabel, BorderLayout.NORTH);
        previewHeader.add(previewControlPanel, BorderLayout.CENTER);

        JSplitPane treePreviewSplit = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(viewTree),
            new JScrollPane(previewPanel)
        );
        treePreviewSplit.setResizeWeight(0.33);

        JScrollPane propertyScrollPane = new JScrollPane(buildPropertyPanel());
        propertyScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        propertyScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane inspectorSplit = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            treePreviewSplit,
            propertyScrollPane
        );
        inspectorSplit.setResizeWeight(0.75);

        panel.add(previewHeader, BorderLayout.NORTH);
        panel.add(inspectorSplit, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildPropertyPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel form = new JPanel(new GridLayout(23, 1, 8, 8));
        form.add(labeled("Path", selectedPathField));
        form.add(labeled("Class", selectedClassField));
        form.add(labeled("Id Value", selectedIdValueField));
        form.add(labeled("Id", selectedIdField));
        form.add(labeled("Visibility", selectedVisibilityField));
        form.add(labeled("Enabled", selectedEnabledField));
        form.add(labeled("Clickable", selectedClickableField));
        form.add(labeled("Focusable", selectedFocusableField));
        form.add(labeled("Alpha(0..1)", selectedAlphaField));
        form.add(labeled("Label", selectedLabelField));
        form.add(labeled("Content Description", selectedContentDescriptionField));
        form.add(labeled("Hint", selectedHintField));
        form.add(labeled("Color(#AARRGGBB)", selectedColorField));
        form.add(labeled("Text Color(#AARRGGBB)", selectedTextColorField));
        form.add(labeled("Text Size Sp", selectedTextSizeSpField));
        form.add(labeled("Margin Left", selectedMarginLeftField));
        form.add(labeled("Margin Top", selectedMarginTopField));
        form.add(labeled("Margin Right", selectedMarginRightField));
        form.add(labeled("Margin Bottom", selectedMarginBottomField));
        form.add(labeled("Padding Left", selectedPaddingLeftField));
        form.add(labeled("Padding Top", selectedPaddingTopField));
        form.add(labeled("Padding Right", selectedPaddingRightField));
        form.add(labeled("Padding Bottom", selectedPaddingBottomField));
        form.add(applyViewChangesButton);
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    private JPanel buildHttpMockPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel form = new JPanel(new GridLayout(7, 1, 8, 8));

        JButton applyMockButton = new JButton("Apply Mock");
        applyMockButton.addActionListener(event -> setMock());
        JButton refreshMockListButton = new JButton("Refresh Mock List");
        refreshMockListButton.addActionListener(event -> send("list_mocks", ""));
        JButton clearMockButton = new JButton("Clear Mock");
        clearMockButton.addActionListener(event -> clearMock());

        form.add(labeled("Method", methodField));
        form.add(labeled("Path", pathField));
        form.add(labeled("Status", statusField));
        form.add(new JLabel("Body"));
        form.add(new JScrollPane(bodyArea));
        form.add(applyMockButton);
        form.add(refreshMockListButton);
        form.add(new JScrollPane(mockTable));

        panel.add(form, BorderLayout.CENTER);
        panel.add(clearMockButton, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildMemoryLeakToolPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JButton refreshMemoryLeakButton = new JButton("Refresh Watches");
        refreshMemoryLeakButton.addActionListener(event -> requestLeakSnapshot());
        JButton clearLeakHistoryButton = new JButton("Clear Leak History");
        clearLeakHistoryButton.addActionListener(event -> clearLeakHistory());
        JPanel controls = new JPanel(new GridLayout(1, 3, 8, 8));
        controls.add(refreshMemoryLeakButton);
        controls.add(leakTrackingCheck);
        controls.add(clearLeakHistoryButton);
        panel.add(new JLabel("Track leak positions while app is running"), BorderLayout.NORTH);
        panel.add(controls, BorderLayout.CENTER);
        return panel;
    }

    private void bindLeakTrackingControls() {
        leakPollingTimer.setRepeats(true);
        leakTrackingCheck.addActionListener(event -> {
            if (leakTrackingCheck.isSelected()) {
                requestLeakSnapshot();
                leakPollingTimer.start();
                append("▶ Leak tracking started (5s polling)");
            } else {
                leakPollingTimer.stop();
                append("⏸ Leak tracking stopped");
            }
        });
    }

    private void requestLeakSnapshot() {
        send("list_watches", "");
    }

    private void clearLeakHistory() {
        leakSeenRecords.clear();
        leakHistoryArea.setText("No leak records yet");
    }

    private JPanel labeled(String label, JTextField field) {
        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 8));
        panel.add(new JLabel(label));
        panel.add(field);
        return panel;
    }

    private JPanel labeledComponent(String label, JComponent component) {
        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 8));
        panel.add(new JLabel(label));
        panel.add(component);
        return panel;
    }

    private void bindPreview3dControls() {
        yawSlider.setValue(DEFAULT_YAW_DEGREES);
        pitchSlider.setValue(DEFAULT_PITCH_DEGREES);
        depthSlider.setValue(DEFAULT_DEPTH_SPACING);
        yawSlider.setPaintTicks(true);
        pitchSlider.setPaintTicks(true);
        depthSlider.setPaintTicks(true);
        minDepthSlider.setPaintTicks(true);
        maxDepthSlider.setPaintTicks(true);
        yawSlider.setMajorTickSpacing(35);
        pitchSlider.setMajorTickSpacing(35);
        depthSlider.setMajorTickSpacing(10);
        minDepthSlider.setMajorTickSpacing(1);
        maxDepthSlider.setMajorTickSpacing(1);

        enable3dCheck.addActionListener(event -> applyPreview3dConfig());
        yawSlider.addChangeListener(event -> applyPreview3dConfig());
        pitchSlider.addChangeListener(event -> applyPreview3dConfig());
        depthSlider.addChangeListener(event -> applyPreview3dConfig());
        minDepthSlider.addChangeListener(event -> applyPreview3dConfig());
        maxDepthSlider.addChangeListener(event -> applyPreview3dConfig());
        bindPreviewDragRotation();

        updateDepthFilterRange(0);
        applyPreview3dConfig();
    }

    private void bindPreviewDragRotation() {
        MouseAdapter dragRotateHandler = new MouseAdapter() {
            private int lastX;
            private int lastY;
            private boolean dragging;

            @Override
            public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || !enable3dCheck.isSelected()) return;
                dragging = true;
                lastX = event.getX();
                lastY = event.getY();
                previewPanel.setInteracting(true);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (!dragging || !enable3dCheck.isSelected()) return;
                int deltaX = event.getX() - lastX;
                int deltaY = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();

                int nextYaw = clampSliderValue(yawSlider, yawSlider.getValue() + (int) Math.round(deltaX * DRAG_ROTATION_SENSITIVITY));
                int nextPitch = clampSliderValue(pitchSlider, pitchSlider.getValue() + (int) Math.round(deltaY * DRAG_ROTATION_SENSITIVITY));
                if (nextYaw != yawSlider.getValue()) yawSlider.setValue(nextYaw);
                if (nextPitch != pitchSlider.getValue()) pitchSlider.setValue(nextPitch);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragging = false;
                previewPanel.setInteracting(false);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                if (event.getClickCount() == 2) {
                    yawSlider.setValue(DEFAULT_YAW_DEGREES);
                    pitchSlider.setValue(DEFAULT_PITCH_DEGREES);
                    return;
                }
                ViewTreeItem picked = previewPanel.pickItemAt(event.getX(), event.getY());
                if (picked != null) {
                    selectViewItem(picked);
                }
            }
        };
        previewPanel.addMouseListener(dragRotateHandler);
        previewPanel.addMouseMotionListener(dragRotateHandler);
    }

    private int clampSliderValue(JSlider slider, int value) {
        return Math.max(slider.getMinimum(), Math.min(slider.getMaximum(), value));
    }

    private void applyPreview3dConfig() {
        int minDepth = Math.min(minDepthSlider.getValue(), maxDepthSlider.getValue());
        int maxDepth = Math.max(minDepthSlider.getValue(), maxDepthSlider.getValue());
        previewPanel.setLayer3dConfig(
            enable3dCheck.isSelected(),
            yawSlider.getValue(),
            pitchSlider.getValue(),
            depthSlider.getValue(),
            minDepth,
            maxDepth
        );
    }

    private void connect() {
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            showError(e);
            return;
        }
        networkExecutor.submit(() -> {
            try {
                client.connect(host, port);
                SwingUtilities.invokeLater(() -> {
                    append("✅ Connected to " + host + ":" + port);
                    if (leakTrackingCheck.isSelected() && !leakPollingTimer.isRunning()) {
                        leakPollingTimer.start();
                    }
                });
            } catch (Exception exception) {
                SwingUtilities.invokeLater(() -> showError(exception));
            }
        });
    }

    private void setMock() {
        String payload = ",\"method\":\"" + Jsons.escape(methodField.getText().trim()) +
            "\",\"path\":\"" + Jsons.escape(pathField.getText().trim()) +
            "\",\"statusCode\":" + Integer.parseInt(statusField.getText().trim()) +
            ",\"body\":\"" + Jsons.escape(bodyArea.getText()) + "\"";
        send("set_mock", payload);
    }

    private void clearMock() {
        String payload = ",\"method\":\"" + Jsons.escape(methodField.getText().trim()) +
            "\",\"path\":\"" + Jsons.escape(pathField.getText().trim()) + "\"";
        send("clear_mock", payload);
    }

    private void send(String type, String payload) {
        networkExecutor.submit(() -> {
            try {
                String response = client.send(type, payload);
                SwingUtilities.invokeLater(() -> {
                    append(response);
                    renderResponse(response);
                });
            } catch (IOException exception) {
                if (!retryAfterReconnect(type, payload, exception)) {
                    SwingUtilities.invokeLater(() -> showError(exception));
                }
            }
        });
    }

    private boolean retryAfterReconnect(String type, String payload, IOException firstError) {
        String message = firstError.getMessage();
        if (message == null || !message.contains("Server closed connection")) {
            return false;
        }
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ignored) {
            return false;
        }
        try {
            client.connect(host, port);
            String response = client.send(type, payload);
            SwingUtilities.invokeLater(() -> {
                append("↻ Reconnected and retried: " + type);
                append(response);
                renderResponse(response);
            });
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void renderResponse(String response) {
        JsonNode root = Jsons.parse(response);
        String type = Jsons.text(root, "type", "");
        JsonNode payload = Jsons.payload(root);
        switch (type) {
            case "view_tree" -> renderViewTree(payload);
            case "view_preview" -> renderViewPreview(payload);
            case "view_updated" -> append("✅ View updated");
            case "mock_list", "mock_saved" -> renderMocks(payload);
            case "watch_list" -> renderWatches(payload);
            case "leak_event" -> renderLeakEvent(payload);
            case "error" -> renderProtocolError(root);
            default -> {
            }
        }
    }

    private void renderProtocolError(JsonNode root) {
        JsonNode error = root == null ? null : root.get("error");
        String code = Jsons.text(error, "code", "unknown");
        String message = Jsons.text(error, "message", "Unknown protocol error");
        if ("no_activity".equals(code)) {
            append("⚠️ " + message + " (bring app to foreground, then retry Fetch View Tree)");
            return;
        }
        append("⚠️ " + code + ": " + message);
    }

    private void renderViewTree(JsonNode payload) {
        if (payload == null || payload.get("tree") == null) return;
        String previousPath = selectedPathField.getText().trim();
        String activity = Jsons.text(payload, "activity", "UnknownActivity");
        JsonNode rootNode = payload.get("tree");
        treeOriginLeft = Jsons.integer(rootNode, "left", 0);
        treeOriginTop = Jsons.integer(rootNode, "top", 0);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(activity);
        flatViewItems.clear();
        viewTreeNodeMap.clear();
        nextViewDrawOrder = 0;
        buildViewNodeTree(rootNode, root, 0, "0");
        updateDepthFilterRange(maxDepth(flatViewItems));
        previewPanel.setViewTreeItems(flatViewItems);
        viewTreeModel.setRoot(root);
        for (int index = 0; index < viewTree.getRowCount(); index++) {
            viewTree.expandRow(index);
        }
        if (!previousPath.isEmpty()) {
            ViewTreeItem previous = findItemByPath(previousPath);
            if (previous != null) {
                selectViewItem(previous);
            }
        }
    }

    private ViewTreeItem findItemByPath(String path) {
        for (ViewTreeItem item : flatViewItems) {
            if (item.path.equals(path)) {
                return item;
            }
        }
        return null;
    }

    private void buildViewNodeTree(JsonNode node, DefaultMutableTreeNode parent, int depth, String path) {
        String className = Jsons.text(node, "className", "View");
        String label = simpleClassName(className) +
            "#" + Jsons.text(node, "id", "no-id") +
            " [" + Jsons.integer(node, "width", 0) + "x" + Jsons.integer(node, "height", 0) + "]";
        int absoluteLeft = Jsons.integer(node, "left", 0);
        int absoluteTop = Jsons.integer(node, "top", 0);
        ViewTreeItem item = new ViewTreeItem(
            label,
            absoluteLeft - treeOriginLeft,
            absoluteTop - treeOriginTop,
            Jsons.integer(node, "width", 0),
            Jsons.integer(node, "height", 0),
            depth,
            nextViewDrawOrder++,
            path,
            className,
            Jsons.text(node, "id", "no-id"),
            Jsons.integer(node, "idValue", 0),
            Jsons.text(node, "visibility", "VISIBLE"),
            node.get("enabled") != null && node.get("enabled").asBoolean(true),
            node.get("clickable") != null && node.get("clickable").asBoolean(false),
            node.get("focusable") != null && node.get("focusable").asBoolean(false),
            node.get("alpha") != null ? node.get("alpha").asDouble(1.0) : 1.0,
            Jsons.text(node, "label", ""),
            Jsons.text(node, "contentDescription", ""),
            Jsons.text(node, "hint", ""),
            Jsons.text(node, "bgColor", ""),
            Jsons.text(node, "textColor", ""),
            node.get("textSizeSp") != null ? node.get("textSizeSp").asDouble(0.0) : 0.0,
            node.get("cornerRadiusPx") != null ? node.get("cornerRadiusPx").asDouble(0.0) : 0.0,
            Jsons.text(node, "iconHint", "view"),
            Jsons.integer(node, "marginLeft", 0),
            Jsons.integer(node, "marginTop", 0),
            Jsons.integer(node, "marginRight", 0),
            Jsons.integer(node, "marginBottom", 0),
            Jsons.integer(node, "paddingLeft", 0),
            Jsons.integer(node, "paddingTop", 0),
            Jsons.integer(node, "paddingRight", 0),
            Jsons.integer(node, "paddingBottom", 0)
        );
        DefaultMutableTreeNode current = new DefaultMutableTreeNode(item);
        parent.add(current);
        flatViewItems.add(item);
        viewTreeNodeMap.put(item, current);
        JsonNode children = node.get("children");
        if (children == null || !children.isArray()) return;
        int childIndex = 0;
        for (JsonNode child : children) {
            buildViewNodeTree(child, current, depth + 1, path + "." + childIndex);
            childIndex++;
        }
    }

    private String simpleClassName(String className) {
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }

    private void onViewNodeSelected() {
        if (suppressTreeSelectionCallback) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) viewTree.getLastSelectedPathComponent();
        if (node == null) return;
        Object userObject = node.getUserObject();
        if (!(userObject instanceof ViewTreeItem item)) return;
        selectViewItem(item);
    }

    private void selectViewItem(ViewTreeItem item) {
        previewPanel.setSelectedItem(item);
        previewPanel.setHighlight(new Rectangle(item.relativeLeft, item.relativeTop, item.width, item.height));
        previewInfoLabel.setText("Preview highlight: " + item.label);
        selectedPathField.setText(item.path);
        selectedClassField.setText(item.className);
        selectedIdValueField.setText(String.valueOf(item.viewIdValue));
        selectedIdField.setText(item.viewId);
        selectedVisibilityField.setText(item.visibility);
        selectedEnabledField.setText(String.valueOf(item.enabled));
        selectedClickableField.setText(String.valueOf(item.clickable));
        selectedFocusableField.setText(String.valueOf(item.focusable));
        selectedAlphaField.setText(String.format("%.2f", item.alpha));
        selectedLabelField.setText(item.editLabel);
        selectedContentDescriptionField.setText(item.contentDescription);
        selectedHintField.setText(item.hint);
        selectedColorField.setText(item.bgColor);
        selectedTextColorField.setText(item.textColor);
        selectedTextSizeSpField.setText(String.format("%.2f", item.textSizeSp));
        selectedMarginLeftField.setText(String.valueOf(item.marginLeft));
        selectedMarginTopField.setText(String.valueOf(item.marginTop));
        selectedMarginRightField.setText(String.valueOf(item.marginRight));
        selectedMarginBottomField.setText(String.valueOf(item.marginBottom));
        selectedPaddingLeftField.setText(String.valueOf(item.paddingLeft));
        selectedPaddingTopField.setText(String.valueOf(item.paddingTop));
        selectedPaddingRightField.setText(String.valueOf(item.paddingRight));
        selectedPaddingBottomField.setText(String.valueOf(item.paddingBottom));

        DefaultMutableTreeNode treeNode = viewTreeNodeMap.get(item);
        if (treeNode != null) {
            suppressTreeSelectionCallback = true;
            try {
                TreePath path = new TreePath(treeNode.getPath());
                viewTree.setSelectionPath(path);
                viewTree.scrollPathToVisible(path);
            } finally {
                suppressTreeSelectionCallback = false;
            }
        }
    }

    private void applySelectedViewChanges() {
        String selectedPath = selectedPathField.getText().trim();
        if (selectedPath.isEmpty()) {
            append("⚠️ Select a view node first.");
            return;
        }
        String label = selectedLabelField.getText();
        String color = selectedColorField.getText().trim();
        String contentDescription = selectedContentDescriptionField.getText();
        String hint = selectedHintField.getText();
        String textColor = selectedTextColorField.getText().trim();
        Integer marginLeft = parseOptionalInt(selectedMarginLeftField.getText().trim());
        Integer marginTop = parseOptionalInt(selectedMarginTopField.getText().trim());
        Integer marginRight = parseOptionalInt(selectedMarginRightField.getText().trim());
        Integer marginBottom = parseOptionalInt(selectedMarginBottomField.getText().trim());
        Integer paddingLeft = parseOptionalInt(selectedPaddingLeftField.getText().trim());
        Integer paddingTop = parseOptionalInt(selectedPaddingTopField.getText().trim());
        Integer paddingRight = parseOptionalInt(selectedPaddingRightField.getText().trim());
        Integer paddingBottom = parseOptionalInt(selectedPaddingBottomField.getText().trim());
        Double alpha = parseOptionalDouble(selectedAlphaField.getText().trim());
        Double textSizeSp = parseOptionalDouble(selectedTextSizeSpField.getText().trim());

        if (marginLeft == null || marginTop == null || marginRight == null || marginBottom == null ||
            paddingLeft == null || paddingTop == null || paddingRight == null || paddingBottom == null) {
            append("⚠️ Margin/Padding must be integer values.");
            return;
        }
        if (alpha == null || textSizeSp == null) {
            append("⚠️ Alpha/TextSize must be numeric values.");
            return;
        }

        String payload = ",\"path\":\"" + Jsons.escape(selectedPath) + "\"" +
            ",\"label\":\"" + Jsons.escape(label) + "\"" +
            ",\"contentDescription\":\"" + Jsons.escape(contentDescription) + "\"" +
            ",\"hint\":\"" + Jsons.escape(hint) + "\"" +
            ",\"color\":\"" + Jsons.escape(color) + "\"" +
            ",\"textColor\":\"" + Jsons.escape(textColor) + "\"" +
            ",\"alpha\":\"" + alpha + "\"" +
            ",\"textSizeSp\":\"" + textSizeSp + "\"" +
            ",\"marginLeft\":" + marginLeft +
            ",\"marginTop\":" + marginTop +
            ",\"marginRight\":" + marginRight +
            ",\"marginBottom\":" + marginBottom +
            ",\"paddingLeft\":" + paddingLeft +
            ",\"paddingTop\":" + paddingTop +
            ",\"paddingRight\":" + paddingRight +
            ",\"paddingBottom\":" + paddingBottom;
        send("update_view_props", payload);
        send("get_view_tree", "");
        send("get_view_preview", "");
    }

    private Integer parseOptionalInt(String value) {
        if (value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseOptionalDouble(String value) {
        if (value.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void renderViewPreview(JsonNode payload) {
        if (payload == null) return;
        String encoded = Jsons.text(payload, "imageBase64", "");
        if (encoded.isEmpty()) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return;
            previewPanel.setImage(image);
            String activity = Jsons.text(payload, "activity", "UnknownActivity");
            previewInfoLabel.setText(
                "Activity: " + activity + " | " +
                    image.getWidth() + "x" + image.getHeight() +
                    " | format=" + Jsons.text(payload, "format", "jpeg")
            );
        } catch (Exception ignored) {
            previewInfoLabel.setText("Preview decode failed");
        }
    }

    private void renderMocks(JsonNode payload) {
        mockTableModel.setRowCount(0);
        if (payload == null || payload.get("items") == null || !payload.get("items").isArray()) return;
        for (JsonNode item : payload.get("items")) {
            mockTableModel.addRow(new Object[]{
                Jsons.text(item, "method", ""),
                Jsons.text(item, "path", ""),
                Jsons.integer(item, "statusCode", 200),
                Jsons.text(item, "body", "")
            });
        }
    }

    private void renderWatches(JsonNode payload) {
        watchTableModel.setRowCount(0);
        if (payload == null) return;
        retainedCountLabel.setText("Retained objects: " + Jsons.integer(payload, "retainedObjectCount", 0));
        JsonNode items = payload.get("items");
        if (items == null || !items.isArray()) return;
        for (JsonNode item : items) {
            String leakClass = Jsons.text(item, "className", Jsons.text(item, "class", ""));
            String leakLocation = leakLocation(item);
            long retainedDurationMs = item.get("retainedDurationMs") != null ? item.get("retainedDurationMs").asLong(0L) : 0L;
            long analysisTimestampMs = item.get("analysisTimestampMs") != null ? item.get("analysisTimestampMs").asLong(0L) : 0L;
            boolean retained = item.get("retained") != null && item.get("retained").asBoolean(false);
            String label = Jsons.text(item, "label", "");
            String source = Jsons.text(item, "source", "watch");
            String traceSummary = Jsons.text(item, "traceSummary", "");
            watchTableModel.addRow(new Object[]{
                label,
                retained,
                leakClass,
                leakLocation,
                retainedDurationMs > 0 ? retainedDurationMs : "-",
                source,
                traceSummary.isEmpty() ? "-" : traceSummary
            });
            if (retained) {
                recordLeakObservation(label, leakClass, leakLocation, retainedDurationMs, source, traceSummary, analysisTimestampMs);
            }
        }
    }

    private void renderLeakEvent(JsonNode payload) {
        if (payload == null) return;
        JsonNode list = payload.get("watches");
        if (list != null && list.isObject()) {
            renderWatches(list);
        }
        JsonNode item = payload.get("item");
        if (item != null && item.isObject()) {
            String leakClass = Jsons.text(item, "className", Jsons.text(item, "class", ""));
            String leakLocation = leakLocation(item);
            long retainedDurationMs = item.get("retainedDurationMs") != null ? item.get("retainedDurationMs").asLong(0L) : 0L;
            long analysisTimestampMs = item.get("analysisTimestampMs") != null ? item.get("analysisTimestampMs").asLong(0L) : 0L;
            String source = Jsons.text(item, "source", "watch");
            String traceSummary = Jsons.text(item, "traceSummary", "");
            recordLeakObservation(Jsons.text(item, "label", ""), leakClass, leakLocation, retainedDurationMs, source, traceSummary, analysisTimestampMs);
        }
    }

    private String leakLocation(JsonNode item) {
        String explicit = Jsons.text(item, "location", "");
        if (!explicit.isEmpty()) return explicit;
        String file = Jsons.text(item, "file", "");
        int line = Jsons.integer(item, "line", -1);
        if (!file.isEmpty()) {
            return line >= 0 ? file + ":" + line : file;
        }
        return Jsons.text(item, "fileLine", "-");
    }

    private void recordLeakObservation(
        String label,
        String leakClass,
        String leakLocation,
        long retainedDurationMs,
        String source,
        String traceSummary,
        long analysisTimestampMs
    ) {
        String signature = label + "|" + leakClass + "|" + leakLocation + "|" + source + "|" + traceSummary;
        if (leakSeenRecords.containsKey(signature)) return;
        leakSeenRecords.put(signature, "seen");

        String durationText = retainedDurationMs > 0 ? retainedDurationMs + "ms" : "-";
        String classText = leakClass.isEmpty() ? "-" : leakClass;
        String locationText = leakLocation == null || leakLocation.isEmpty() ? "-" : leakLocation;
        String labelText = label == null || label.isEmpty() ? "unknown" : label;
        String sourceText = source == null || source.isEmpty() ? "watch" : source;
        String traceText = traceSummary == null || traceSummary.isEmpty() ? "-" : traceSummary;
        String analysisText = formatAnalysisTime(analysisTimestampMs);
        String timestamp = LocalDateTime.now().format(LEAK_TIME_FORMAT);
        String line = "[" + timestamp + "] " + labelText + " | " + classText + " | " + locationText +
            " | retained=" + durationText + " | source=" + sourceText + " | analysis=" + analysisText + " | trace=" + traceText;

        if ("No leak records yet".contentEquals(leakHistoryArea.getText().trim())) {
            leakHistoryArea.setText(line);
        } else {
            leakHistoryArea.append("\n" + line);
        }
    }

    private String formatAnalysisTime(long analysisTimestampMs) {
        if (analysisTimestampMs <= 0L) return "-";
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(analysisTimestampMs), ZoneId.systemDefault())
            .format(ANALYSIS_TIME_FORMAT);
    }

    private int maxDepth(List<ViewTreeItem> items) {
        int max = 0;
        for (ViewTreeItem item : items) {
            max = Math.max(max, item.depth);
        }
        return max;
    }

    private void updateDepthFilterRange(int maxDepth) {
        int safeMaxDepth = Math.max(0, maxDepth);
        minDepthSlider.setMaximum(safeMaxDepth);
        maxDepthSlider.setMaximum(safeMaxDepth);
        minDepthSlider.setMinorTickSpacing(1);
        maxDepthSlider.setMinorTickSpacing(1);
        minDepthSlider.setValue(Math.min(minDepthSlider.getValue(), safeMaxDepth));
        maxDepthSlider.setValue(safeMaxDepth);
    }

    private static final class ViewTreeItem {
        private final String label;
        private final int relativeLeft;
        private final int relativeTop;
        private final int width;
        private final int height;
        private final int depth;
        private final int drawOrder;
        private final String path;
        private final String className;
        private final String viewId;
        private final int viewIdValue;
        private final String visibility;
        private final boolean enabled;
        private final boolean clickable;
        private final boolean focusable;
        private final double alpha;
        private final String editLabel;
        private final String contentDescription;
        private final String hint;
        private final String bgColor;
        private final String textColor;
        private final double textSizeSp;
        private final double cornerRadiusPx;
        private final String iconHint;
        private final int marginLeft;
        private final int marginTop;
        private final int marginRight;
        private final int marginBottom;
        private final int paddingLeft;
        private final int paddingTop;
        private final int paddingRight;
        private final int paddingBottom;

        private ViewTreeItem(
            String label,
            int relativeLeft,
            int relativeTop,
            int width,
            int height,
            int depth,
            int drawOrder,
            String path,
            String className,
            String viewId,
            int viewIdValue,
            String visibility,
            boolean enabled,
            boolean clickable,
            boolean focusable,
            double alpha,
            String editLabel,
            String contentDescription,
            String hint,
            String bgColor,
            String textColor,
            double textSizeSp,
            double cornerRadiusPx,
            String iconHint,
            int marginLeft,
            int marginTop,
            int marginRight,
            int marginBottom,
            int paddingLeft,
            int paddingTop,
            int paddingRight,
            int paddingBottom
        ) {
            this.label = label;
            this.relativeLeft = relativeLeft;
            this.relativeTop = relativeTop;
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.depth = Math.max(0, depth);
            this.drawOrder = Math.max(0, drawOrder);
            this.path = path;
            this.className = className;
            this.viewId = viewId;
            this.viewIdValue = viewIdValue;
            this.visibility = visibility;
            this.enabled = enabled;
            this.clickable = clickable;
            this.focusable = focusable;
            this.alpha = alpha;
            this.editLabel = editLabel;
            this.contentDescription = contentDescription;
            this.hint = hint;
            this.bgColor = bgColor;
            this.textColor = textColor;
            this.textSizeSp = textSizeSp;
            this.cornerRadiusPx = cornerRadiusPx;
            this.iconHint = iconHint;
            this.marginLeft = marginLeft;
            this.marginTop = marginTop;
            this.marginRight = marginRight;
            this.marginBottom = marginBottom;
            this.paddingLeft = paddingLeft;
            this.paddingTop = paddingTop;
            this.paddingRight = paddingRight;
            this.paddingBottom = paddingBottom;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class PreviewPanel extends JPanel {
        private BufferedImage image;
        private Rectangle highlight;
        private ViewTreeItem selectedItem;
        private List<ViewTreeItem> viewItems = List.of();
        private boolean layer3dEnabled;
        private int yawDegrees;
        private int pitchDegrees;
        private int depthSpacing;
        private int minDepth;
        private int maxDepth;
        private boolean interacting;
        private List<ProjectedLayer> renderedLayers = List.of();
        private final Map<String, BufferedImage> semanticLayerCache = new HashMap<>();
        private static final double LAYER_GAP_MULTIPLIER = 0.65;
        private static final double PERSPECTIVE_MULTIPLIER = 2.4;
        private static final int MAX_RENDERED_LAYERS = 180;

        private PreviewPanel() {
            setBackground(Color.DARK_GRAY);
            setPreferredSize(new Dimension(360, 640));
        }

        private void setImage(BufferedImage image) {
            this.image = image;
            this.highlight = null;
            revalidate();
            repaint();
        }

        private void setViewTreeItems(List<ViewTreeItem> viewItems) {
            this.viewItems = List.copyOf(viewItems);
            this.semanticLayerCache.clear();
            repaint();
        }

        private void setSelectedItem(ViewTreeItem selectedItem) {
            this.selectedItem = selectedItem;
            repaint();
        }

        private void setHighlight(Rectangle highlight) {
            this.highlight = highlight;
            repaint();
        }

        private void setLayer3dConfig(boolean enabled, int yawDegrees, int pitchDegrees, int depthSpacing, int minDepth, int maxDepth) {
            this.layer3dEnabled = enabled;
            this.yawDegrees = yawDegrees;
            this.pitchDegrees = pitchDegrees;
            this.depthSpacing = depthSpacing;
            this.minDepth = Math.max(0, minDepth);
            this.maxDepth = Math.max(this.minDepth, maxDepth);
            repaint();
        }

        private void setInteracting(boolean interacting) {
            this.interacting = interacting;
            repaint();
        }

        private ViewTreeItem pickItemAt(int x, int y) {
            if (!layer3dEnabled || renderedLayers.isEmpty()) return null;
            for (int i = renderedLayers.size() - 1; i >= 0; i--) {
                ProjectedLayer layer = renderedLayers.get(i);
                Polygon polygon = new Polygon(layer.xPoints, layer.yPoints, 4);
                if (polygon.contains(x, y)) {
                    return layer.item;
                }
            }
            return null;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null) return;

            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                interacting ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                interacting ? RenderingHints.VALUE_ANTIALIAS_OFF : RenderingHints.VALUE_ANTIALIAS_ON
            );

            int availableWidth = getWidth();
            int availableHeight = getHeight();
            double scale = Math.min(
                availableWidth / (double) image.getWidth(),
                availableHeight / (double) image.getHeight()
            );
            int drawWidth = (int) Math.round(image.getWidth() * scale);
            int drawHeight = (int) Math.round(image.getHeight() * scale);
            int drawX = (availableWidth - drawWidth) / 2;
            int drawY = (availableHeight - drawHeight) / 2;

            if (!layer3dEnabled) {
                renderedLayers = List.of();
                g2.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
            }

            if (layer3dEnabled && !viewItems.isEmpty()) {
                drawLayerProjection(g2, drawX, drawY, scale);
            } else if (layer3dEnabled) {
                renderedLayers = List.of();
            }

            if (!layer3dEnabled && highlight != null && highlight.width > 0 && highlight.height > 0) {
                int x = drawX + (int) Math.round(highlight.x * scale);
                int y = drawY + (int) Math.round(highlight.y * scale);
                int w = (int) Math.round(highlight.width * scale);
                int h = (int) Math.round(highlight.height * scale);
                g2.setColor(new Color(255, 0, 0, 180));
                g2.drawRect(x, y, w, h);
            }
            g2.dispose();
        }

        private void drawLayerProjection(Graphics2D g2, int drawX, int drawY, double scale) {
            double yawRad = Math.toRadians(yawDegrees);
            double pitchRad = Math.toRadians(pitchDegrees);
            double sinYaw = Math.sin(yawRad);
            double cosYaw = Math.cos(yawRad);
            double sinPitch = Math.sin(pitchRad);
            double cosPitch = Math.cos(pitchRad);

            double centerX = drawX + (image.getWidth() * scale) / 2.0;
            double centerY = drawY + (image.getHeight() * scale) / 2.0;
            double layerGap = depthSpacing * scale * LAYER_GAP_MULTIPLIER;
            double focalLength = Math.max(getWidth(), getHeight()) * PERSPECTIVE_MULTIPLIER;

            List<ProjectedLayer> layers = new ArrayList<>();
            for (ViewTreeItem item : viewItems) {
                if (item.depth < minDepth || item.depth > maxDepth) continue;
                if (item.width <= 0 || item.height <= 0) continue;
                ProjectedLayer layer = projectLayer(
                    item,
                    centerX,
                    centerY,
                    scale,
                    layerGap,
                    sinYaw,
                    cosYaw,
                    sinPitch,
                    cosPitch,
                    focalLength
                );
                if (layer != null) {
                    layers.add(layer);
                }
            }

            layers.sort(Comparator.comparingDouble(layer -> layer.avgCameraZ));
            if (layers.size() > MAX_RENDERED_LAYERS) {
                int fromIndex = layers.size() - MAX_RENDERED_LAYERS;
                List<ProjectedLayer> reduced = new ArrayList<>(layers.subList(fromIndex, layers.size()));
                if (selectedItem != null && reduced.stream().noneMatch(layer -> layer.item == selectedItem)) {
                    for (int i = layers.size() - 1; i >= 0; i--) {
                        ProjectedLayer candidate = layers.get(i);
                        if (candidate.item == selectedItem) {
                            if (!reduced.isEmpty()) {
                                reduced.remove(0);
                            }
                            reduced.add(candidate);
                            break;
                        }
                    }
                }
                layers = reduced;
            }
            renderedLayers = List.copyOf(layers);
            int scaleBucket = Math.max(1, (int) Math.round(scale * 100));

            Stroke oldStroke = g2.getStroke();
            Composite oldComposite = g2.getComposite();
            for (ProjectedLayer layer : layers) {
                boolean selected = layer.item == selectedItem;
                Color borderColor = selected ? new Color(0, 255, 255, 230) : new Color(120, 220, 255, 130);
                Polygon quad = new Polygon(layer.xPoints, layer.yPoints, 4);

                if (interacting) {
                    Color quickFill = selected ? new Color(0, 245, 255, 120) : new Color(0, 150, 235, 70);
                    g2.setColor(quickFill);
                    g2.fillPolygon(quad);
                } else {
                    BufferedImage semantic = getSemanticLayerImage(layer.item, scaleBucket, scale);
                    double imgW = semantic.getWidth();
                    double imgH = semantic.getHeight();
                    double a = (layer.xPoints[1] - layer.xPoints[0]) / imgW;
                    double b = (layer.yPoints[1] - layer.yPoints[0]) / imgW;
                    double c = (layer.xPoints[3] - layer.xPoints[0]) / imgH;
                    double d = (layer.yPoints[3] - layer.yPoints[0]) / imgH;
                    AffineTransform at = new AffineTransform(a, b, c, d, layer.xPoints[0], layer.yPoints[0]);
                    Shape oldClip = g2.getClip();
                    g2.clip(quad);
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, selected ? 0.96f : 0.88f));
                    g2.drawImage(semantic, at, null);
                    g2.setComposite(oldComposite);
                    g2.setClip(oldClip);
                }

                g2.setColor(borderColor);
                g2.setStroke(selected ? new BasicStroke(2f) : new BasicStroke(1f));
                g2.drawPolygon(quad);
            }
            g2.setComposite(oldComposite);
            g2.setStroke(oldStroke);
        }

        private BufferedImage getSemanticLayerImage(ViewTreeItem item, int scaleBucket, double scale) {
            String cacheKey = item.path + "@" + scaleBucket;
            BufferedImage cached = semanticLayerCache.get(cacheKey);
            if (cached != null) return cached;
            BufferedImage created = createSemanticLayerImage(item, scale);
            semanticLayerCache.put(cacheKey, created);
            return created;
        }

        private BufferedImage createSemanticLayerImage(ViewTreeItem item, double scale) {
            int w = Math.max(24, Math.min(360, (int) Math.round(item.width * scale)));
            int h = Math.max(18, Math.min(240, (int) Math.round(item.height * scale)));
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color base = parseHexColor(item.bgColor, new Color(38, 90, 138));
            float alpha = (float) Math.max(0.18, Math.min(1.0, item.alpha));
            Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), (int) Math.round(alpha * 255));
            int radius = (int) Math.max(2, Math.min(Math.min(w, h) / 2.0, item.cornerRadiusPx * scale));
            g.setColor(fill);
            g.fillRoundRect(0, 0, w - 1, h - 1, radius * 2, radius * 2);

            Color stroke = new Color(170, 230, 255, 160);
            g.setColor(stroke);
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, w - 1, h - 1, radius * 2, radius * 2);

            int iconSize = Math.max(10, Math.min(18, h / 2));
            int iconX = 6;
            int iconY = Math.max(iconSize, h / 2);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, iconSize));
            g.setColor(new Color(255, 255, 255, 230));
            g.drawString(iconGlyph(item.iconHint), iconX, iconY);

            String text = !item.editLabel.isEmpty() ? item.editLabel
                : (!item.hint.isEmpty() ? item.hint : item.className.substring(item.className.lastIndexOf('.') + 1));
            int textSize = (int) Math.max(10, Math.min(20, item.textSizeSp > 0 ? item.textSizeSp * 0.9 : 12));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, textSize));
            FontMetrics fm = g.getFontMetrics();
            int textX = iconX + iconSize + 4;
            int textY = Math.max(fm.getAscent() + 3, h / 2 + fm.getAscent() / 2 - 2);
            Color textColor = parseHexColor(item.textColor, new Color(245, 245, 245));
            g.setColor(new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 230));
            g.drawString(ellipsize(text, fm, Math.max(20, w - textX - 6)), textX, Math.min(h - 4, textY));

            g.dispose();
            return image;
        }

        private Color parseHexColor(String value, Color fallback) {
            if (value == null || value.isEmpty()) return fallback;
            try {
                return Color.decode(value.startsWith("#") && value.length() > 7 ? "#" + value.substring(value.length() - 6) : value);
            } catch (Exception ignored) {
                return fallback;
            }
        }

        private String iconGlyph(String hint) {
            return switch (hint) {
                case "image" -> "▣";
                case "button" -> "◉";
                case "check" -> "✓";
                case "switch" -> "⇄";
                case "input" -> "⌨";
                case "text-icon" -> "✎";
                case "text" -> "T";
                default -> "□";
            };
        }

        private String ellipsize(String text, FontMetrics metrics, int maxWidth) {
            if (text == null || text.isEmpty()) return "";
            if (metrics.stringWidth(text) <= maxWidth) return text;
            String ellipsis = "...";
            int width = metrics.stringWidth(ellipsis);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                int charWidth = metrics.charWidth(text.charAt(i));
                if (width + charWidth > maxWidth) break;
                builder.append(text.charAt(i));
                width += charWidth;
            }
            return builder + ellipsis;
        }

        private ProjectedLayer projectLayer(
            ViewTreeItem item,
            double centerX,
            double centerY,
            double scale,
            double layerGap,
            double sinYaw,
            double cosYaw,
            double sinPitch,
            double cosPitch,
            double focalLength
        ) {
            double z = -item.drawOrder * layerGap;

            double left = item.relativeLeft * scale;
            double top = item.relativeTop * scale;
            double right = left + item.width * scale;
            double bottom = top + item.height * scale;

            double[][] corners = new double[][] {
                {left, top, z},
                {right, top, z},
                {right, bottom, z},
                {left, bottom, z}
            };

            int[] xPoints = new int[4];
            int[] yPoints = new int[4];
            double avgCameraZ = 0;

            for (int i = 0; i < 4; i++) {
                double px = corners[i][0] - (image.getWidth() * scale) / 2.0;
                double py = corners[i][1] - (image.getHeight() * scale) / 2.0;
                double pz = corners[i][2];

                double xz = px * cosYaw + pz * sinYaw;
                double zz = -px * sinYaw + pz * cosYaw;

                double yz = py * cosPitch - zz * sinPitch;
                double cameraZ = py * sinPitch + zz * cosPitch;
                avgCameraZ += cameraZ;

                double perspective = focalLength / Math.max(80.0, focalLength - cameraZ);
                double sx = centerX + xz * perspective;
                double sy = centerY + yz * perspective;

                xPoints[i] = (int) Math.round(sx);
                yPoints[i] = (int) Math.round(sy);
            }

            int minX = Math.min(Math.min(xPoints[0], xPoints[1]), Math.min(xPoints[2], xPoints[3]));
            int maxX = Math.max(Math.max(xPoints[0], xPoints[1]), Math.max(xPoints[2], xPoints[3]));
            int minY = Math.min(Math.min(yPoints[0], yPoints[1]), Math.min(yPoints[2], yPoints[3]));
            int maxY = Math.max(Math.max(yPoints[0], yPoints[1]), Math.max(yPoints[2], yPoints[3]));
            if (maxX < -32 || maxY < -32 || minX > getWidth() + 32 || minY > getHeight() + 32) {
                return null;
            }

            return new ProjectedLayer(item, xPoints, yPoints, avgCameraZ / 4.0);
        }

        private static final class ProjectedLayer {
            private final ViewTreeItem item;
            private final int[] xPoints;
            private final int[] yPoints;
            private final double avgCameraZ;

            private ProjectedLayer(ViewTreeItem item, int[] xPoints, int[] yPoints, double avgCameraZ) {
                this.item = item;
                this.xPoints = xPoints;
                this.yPoints = yPoints;
                this.avgCameraZ = avgCameraZ;
            }
        }
    }

    private void append(String message) {
        outputArea.append(message + "\n\n");
    }

    private void showError(Exception exception) {
        String message = exception.getMessage();
        if (exception instanceof ConnectException && "127.0.0.1".equals(hostField.getText().trim())) {
            message = "Connection refused: localhost:"
                + portField.getText().trim()
                + " is not mapped to device port.\n\n"
                + "Fix options:\n"
                + "1) USB: adb -s <device-id> forward tcp:"
                + portField.getText().trim()
                + " tcp:"
                + portField.getText().trim()
                + "\n"
                + "2) LAN: use app's host:port from DebugKit.describeState() in the Host field.\n\n"
                + "Original error: "
                + exception.getMessage();
        }
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
