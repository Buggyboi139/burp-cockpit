package com.buggyboi.burpcockpit.ui;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class TextContextMenu {
    private TextContextMenu() {}

    public static void install(JTextComponent component) {
        if (component == null) return;
        component.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { maybeShow(event); }
            @Override public void mouseReleased(MouseEvent event) { maybeShow(event); }

            private void maybeShow(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                JPopupMenu menu = new JPopupMenu();
                JMenuItem cut = new JMenuItem("Cut");
                cut.addActionListener(e -> component.cut());
                cut.setEnabled(component.isEditable() && component.isEnabled());
                JMenuItem copy = new JMenuItem("Copy");
                copy.addActionListener(e -> component.copy());
                copy.setEnabled(component.isEnabled());
                JMenuItem paste = new JMenuItem("Paste");
                paste.addActionListener(e -> component.paste());
                paste.setEnabled(component.isEditable() && component.isEnabled());
                JMenuItem selectAll = new JMenuItem("Select all");
                selectAll.addActionListener(e -> component.selectAll());
                menu.add(cut);
                menu.add(copy);
                menu.add(paste);
                menu.addSeparator();
                menu.add(selectAll);
                menu.show(event.getComponent(), event.getX(), event.getY());
            }
        });
    }

    public static JTextArea area(int rows, int cols, boolean editable) {
        JTextArea area;
        if (isPromptInput(rows, cols, editable)) {
            area = new PromptArea(rows, cols);
        } else if (isRequestEditor(rows, cols, editable)) {
            area = new RequestEditorArea(rows, cols);
        } else {
            area = new JTextArea(rows, cols);
        }
        area.setEditable(editable);
        boolean visualWrap = isRequestEditor(rows, cols, editable) || isResponseViewer(rows, cols, editable);
        area.setLineWrap(visualWrap);
        area.setWrapStyleWord(visualWrap);
        install(area);
        return area;
    }

    public static ChatTranscriptPane transcript(int rows, int cols) {
        ChatTranscriptPane pane = new ChatTranscriptPane(rows, cols);
        install(pane);
        return pane;
    }

    public static JTextField field(String text) {
        JTextField field = new JTextField(text == null ? "" : text);
        install(field);
        return field;
    }

    public static void later(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) runnable.run();
        else SwingUtilities.invokeLater(runnable);
    }

    private static boolean isPromptInput(int rows, int cols, boolean editable) {
        return editable && rows == 4 && cols == 70;
    }

    private static boolean isRequestEditor(int rows, int cols, boolean editable) {
        return editable && rows == 24 && cols == 90;
    }

    private static boolean isResponseViewer(int rows, int cols, boolean editable) {
        return !editable && rows == 12 && cols == 90;
    }

    public static final class ChatTranscriptPane extends JEditorPane {
        private final List<ChatCard> cards = new ArrayList<>();
        private int activeAssistantIndex = -1;
        private boolean rendering;

        private ChatTranscriptPane(int rows, int cols) {
            setContentType("text/html");
            setEditable(false);
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            setPreferredScrollableViewportSize(new Dimension(cols * 8, rows * 18));
            render();
        }

        public void startTurn(String userRole, String userPrompt, boolean analysis) {
            Instant now = Instant.now();
            activeAssistantIndex = -1;
            cards.add(new ChatCard(userRole, Objects.toString(userPrompt, "").trim(), analysis, now));
            cards.add(new ChatCard(analysis ? "Analyze" : "Assistant", "", analysis, now));
            activeAssistantIndex = cards.size() - 1;
            render();
        }

        public void appendAssistantText(String text) {
            if (activeAssistantIndex < 0 || activeAssistantIndex >= cards.size()) {
                cards.add(new ChatCard("Assistant", "", false, Instant.now()));
                activeAssistantIndex = cards.size() - 1;
            }
            ChatCard card = cards.get(activeAssistantIndex);
            cards.set(activeAssistantIndex, new ChatCard(card.role(), card.content() + Objects.toString(text, ""), card.analysis(), card.timestamp()));
            render();
        }

        public void replaceActiveAssistantText(String text) {
            if (activeAssistantIndex < 0 || activeAssistantIndex >= cards.size()) {
                cards.add(new ChatCard("Assistant", Objects.toString(text, ""), false, Instant.now()));
                activeAssistantIndex = cards.size() - 1;
            } else {
                ChatCard card = cards.get(activeAssistantIndex);
                cards.set(activeAssistantIndex, new ChatCard(card.role(), Objects.toString(text, ""), card.analysis(), card.timestamp()));
            }
            render();
        }

        public void clearTranscript() {
            cards.clear();
            activeAssistantIndex = -1;
            render();
        }

        @Override public void setText(String text) {
            if (!rendering && (text == null || text.isBlank())) {
                clearTranscript();
                return;
            }
            super.setText(text);
        }

        @Override public String getText() {
            if (cards.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            for (ChatCard card : cards) {
                if (!out.isEmpty()) out.append("\n\n");
                out.append(card.role()).append("\n").append(card.content());
            }
            return out.toString();
        }

        private void render() {
            StringBuilder html = new StringBuilder(4096);
            html.append("<html><head><style>")
                    .append("body{font-family:sans-serif;font-size:12px;background:#1f1f1f;color:#d7d7d7;margin:6px;}")
                    .append(".card{border:1px solid #444;margin:0 0 10px 0;padding:10px;background:#2b2b2b;}")
                    .append(".assistant{background:#303336;}")
                    .append(".analyze{border-color:#806000;background:#343024;}")
                    .append(".role{color:#afb1b3;font-weight:bold;margin-bottom:6px;}")
                    .append("pre{background:#1b1b1b;border:1px solid #3d3d3d;padding:6px;white-space:pre-wrap;}")
                    .append("code{font-family:monospaced;}")
                    .append("</style></head><body>");
            for (ChatCard card : cards) {
                String kind = card.analysis() ? "card analyze" : "Assistant".equals(card.role()) ? "card assistant" : "card";
                html.append("<div class='").append(kind).append("'>");
                html.append("<div class='role'>").append(escape(card.role())).append(" | ").append(escape(card.timestamp().toString())).append("</div>");
                html.append(markdownToHtml(card.content()));
                html.append("</div>");
            }
            html.append("</body></html>");
            rendering = true;
            try {
                super.setText(html.toString());
                setCaretPosition(getDocument().getLength());
            } finally {
                rendering = false;
            }
        }

        private static String markdownToHtml(String content) {
            String text = Objects.toString(content, "").replace("\r\n", "\n").replace('\r', '\n');
            if (text.isBlank()) return "<div>&nbsp;</div>";
            StringBuilder out = new StringBuilder(text.length() + 128);
            boolean inFence = false;
            for (String raw : text.split("\n", -1)) {
                String line = raw.trim();
                if (line.startsWith("```") || line.startsWith("~~~")) {
                    if (inFence) out.append("</code></pre>");
                    else out.append("<pre><code>");
                    inFence = !inFence;
                    continue;
                }
                if (inFence) {
                    out.append(escape(raw)).append("\n");
                } else if (line.isBlank()) {
                    out.append("<br>");
                } else if (line.startsWith("#")) {
                    out.append("<b>").append(escape(line.replaceFirst("^#{1,6}\\s*", ""))).append("</b><br>");
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    out.append("&#8226; ").append(inlineMarkdown(line.substring(2))).append("<br>");
                } else {
                    out.append(inlineMarkdown(raw)).append("<br>");
                }
            }
            if (inFence) out.append("</code></pre>");
            return out.toString();
        }

        private static String inlineMarkdown(String value) {
            String escaped = escape(value);
            escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
            escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
            escaped = escaped.replaceAll("__([^_]+)__", "<b>$1</b>");
            escaped = escaped.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<i>$1</i>");
            return escaped;
        }

        private static String escape(String value) {
            return Objects.toString(value, "")
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }
    }

    private record ChatCard(String role, String content, boolean analysis, Instant timestamp) {}

    private static final class RequestEditorArea extends JTextArea {
        private static final String REFRESH_COOKIES_BUTTON_NAME = "burp-cockpit-refresh-cookies";
        private boolean refreshButtonInstalled;

        private RequestEditorArea(int rows, int cols) {
            super(rows, cols);
        }

        @Override public void addNotify() {
            super.addNotify();
            SwingUtilities.invokeLater(this::installRefreshCookiesButton);
        }

        private void installRefreshCookiesButton() {
            if (refreshButtonInstalled) return;
            Container root = rootContainer(this);
            if (root == null) return;
            JButton send = findButtonRecursive(root, "Send");
            if (send == null || !(send.getParent() instanceof JToolBar toolbar)) return;
            for (Component component : toolbar.getComponents()) {
                if (REFRESH_COOKIES_BUTTON_NAME.equals(component.getName())) {
                    refreshButtonInstalled = true;
                    return;
                }
            }
            JButton refresh = new JButton("Refresh Cookies");
            refresh.setName(REFRESH_COOKIES_BUTTON_NAME);
            refresh.addActionListener(e -> refreshCookiesFromBurp());
            toolbar.add(refresh, Math.max(0, toolbar.getComponentIndex(send) + 1));
            toolbar.revalidate();
            toolbar.repaint();
            refreshButtonInstalled = true;
        }

        private void refreshCookiesFromBurp() {
            try {
                RequestTarget target = RequestTarget.from(getText());
                if (target.host().isBlank()) {
                    setCockpitStatus("Refresh Cookies failed: request has no Host header.");
                    return;
                }
                Object api = fieldValue(cockpitPanel(this), "api");
                List<Object> cookies = readBurpCookies(api, target.url());
                Map<String, String> matched = new LinkedHashMap<>();
                for (Object cookie : cookies) {
                    CookieView view = CookieView.from(cookie);
                    if (view.matches(target)) matched.put(view.name(), view.value());
                }
                if (matched.isEmpty()) {
                    setCockpitStatus("Refresh Cookies found no matching Burp cookie jar cookies for " + target.host() + target.path() + ".");
                    return;
                }
                StringBuilder header = new StringBuilder();
                for (Map.Entry<String, String> entry : matched.entrySet()) {
                    if (!header.isEmpty()) header.append("; ");
                    header.append(entry.getKey()).append('=').append(entry.getValue());
                }
                setText(withCookieHeader(getText(), header.toString()));
                setCaretPosition(0);
                setCockpitStatus("Refreshed " + matched.size() + " cookie(s) from Burp cookie jar for " + target.host() + ".");
            } catch (Throwable throwable) {
                setCockpitStatus("Refresh Cookies failed: " + throwable.getClass().getSimpleName() + ": " + Objects.toString(throwable.getMessage(), "no detail"));
            }
        }

        private void setCockpitStatus(String message) {
            Object label = fieldValue(cockpitPanel(this), "statusLabel");
            if (label instanceof JLabel jLabel) jLabel.setText(message);
        }

        private static List<Object> readBurpCookies(Object api, String url) throws Exception {
            Object http = invokeNoArg(api, "http");
            Object jar = invokeNoArg(http, "cookieJar");
            Object result = null;
            for (String method : List.of("cookies", "getCookies")) {
                result = tryInvokeNoArg(jar, method);
                if (result != null) break;
                result = tryInvokeOneString(jar, method, url);
                if (result != null) break;
            }
            if (result == null) throw new IllegalStateException("Montoya cookie jar did not expose cookies()/getCookies().");
            List<Object> out = new ArrayList<>();
            if (result instanceof Iterable<?> iterable) {
                for (Object item : iterable) out.add(item);
                return out;
            }
            if (result.getClass().isArray()) {
                for (int i = 0; i < Array.getLength(result); i++) out.add(Array.get(result, i));
                return out;
            }
            throw new IllegalStateException("Montoya cookie jar returned unsupported type: " + result.getClass().getName());
        }

        private static String withCookieHeader(String rawRequest, String cookieHeader) {
            String raw = Objects.toString(rawRequest, "");
            String newline = raw.contains("\r\n") ? "\r\n" : "\n";
            String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
            int split = normalized.indexOf("\n\n");
            String headers = split >= 0 ? normalized.substring(0, split) : normalized;
            String body = split >= 0 ? normalized.substring(split + 2) : "";
            String[] lines = headers.split("\n", -1);
            List<String> out = new ArrayList<>();
            boolean replaced = false;
            boolean inserted = false;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.toLowerCase(Locale.ROOT).startsWith("cookie:")) {
                    if (!replaced) {
                        out.add("Cookie: " + cookieHeader);
                        replaced = true;
                    }
                    continue;
                }
                out.add(line);
                if (!replaced && !inserted && i > 0 && line.toLowerCase(Locale.ROOT).startsWith("host:")) {
                    out.add("Cookie: " + cookieHeader);
                    inserted = true;
                }
            }
            if (!replaced && !inserted) out.add(Math.min(1, out.size()), "Cookie: " + cookieHeader);
            return String.join(newline, out) + newline + newline + body.replace("\n", newline);
        }
    }

    private record RequestTarget(String scheme, String host, String path) {
        String url() { return scheme + "://" + host + path; }

        static RequestTarget from(String rawRequest) {
            String text = Objects.toString(rawRequest, "").replace("\r\n", "\n").replace('\r', '\n');
            String[] lines = text.split("\n");
            String first = lines.length > 0 ? lines[0] : "";
            String host = "";
            String path = "/";
            String scheme = "https";
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("host:")) host = line.substring(line.indexOf(':') + 1).trim();
                else if (lower.startsWith("origin: http://") || lower.startsWith("referer: http://")) scheme = "http";
            }
            String[] parts = first.split("\\s+");
            if (parts.length > 1) {
                path = parts[1].trim();
                if (path.startsWith("http://") || path.startsWith("https://")) {
                    try {
                        URI uri = URI.create(path);
                        scheme = uri.getScheme() == null ? scheme : uri.getScheme();
                        host = uri.getHost() == null ? host : uri.getHost();
                        if (uri.getPort() > 0) host = host + ":" + uri.getPort();
                        path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
                        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                    } catch (Throwable ignored) {
                        path = "/";
                    }
                }
            }
            return new RequestTarget(scheme, host, path.isBlank() ? "/" : path);
        }
    }

    private record CookieView(String name, String value, String domain, String path, boolean secure) {
        boolean matches(RequestTarget target) {
            if (name.isBlank()) return false;
            String targetHost = stripPort(target.host()).toLowerCase(Locale.ROOT);
            String cookieDomain = domain.toLowerCase(Locale.ROOT);
            if (cookieDomain.startsWith(".")) cookieDomain = cookieDomain.substring(1);
            boolean domainOk = cookieDomain.isBlank() || targetHost.equals(cookieDomain) || targetHost.endsWith("." + cookieDomain);
            boolean pathOk = path.isBlank() || target.path().startsWith(path);
            boolean secureOk = !secure || "https".equalsIgnoreCase(target.scheme());
            return domainOk && pathOk && secureOk;
        }

        static CookieView from(Object cookie) {
            return new CookieView(
                    str(cookie, "name", "getName"),
                    str(cookie, "value", "getValue"),
                    str(cookie, "domain", "getDomain"),
                    str(cookie, "path", "getPath"),
                    bool(cookie, "secure", "isSecure", "getSecure")
            );
        }

        private static String stripPort(String host) {
            String value = Objects.toString(host, "").trim();
            int colon = value.lastIndexOf(':');
            return colon > 0 && value.indexOf(']') < colon ? value.substring(0, colon) : value;
        }

        private static String str(Object target, String... names) {
            for (String name : names) {
                try { return Objects.toString(invokeNoArg(target, name), ""); }
                catch (Throwable ignored) { }
            }
            return "";
        }

        private static boolean bool(Object target, String... names) {
            for (String name : names) {
                try {
                    Object value = invokeNoArg(target, name);
                    return value instanceof Boolean b ? b : Boolean.parseBoolean(Objects.toString(value, "false"));
                } catch (Throwable ignored) { }
            }
            return false;
        }
    }

    private static final class PromptArea extends JTextArea {
        private String submittedPrompt = "";
        private boolean submittedPromptAvailable;
        private boolean autoClearing;
        private boolean submitHooksInstalled;

        private PromptArea(int rows, int cols) {
            super(rows, cols);
            installEnterBindings();
        }

        @Override public void addNotify() {
            super.addNotify();
            SwingUtilities.invokeLater(this::installSubmitHooks);
        }

        @Override public String getText() {
            String current = super.getText();
            if (SwingUtilities.isEventDispatchThread()) {
                if (current.isBlank()) {
                    submittedPrompt = "";
                    submittedPromptAvailable = false;
                    return current;
                }
                submittedPrompt = current;
                submittedPromptAvailable = true;
                return current;
            }
            return submittedPromptAvailable ? submittedPrompt : current;
        }

        @Override public void setText(String text) {
            if (!autoClearing) {
                submittedPrompt = "";
                submittedPromptAvailable = false;
            }
            super.setText(text);
        }

        private void installEnterBindings() {
            getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "burp-cockpit-send-chat");
            getActionMap().put("burp-cockpit-send-chat", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) {
                    captureSubmittedPrompt();
                    JButton sendChat = findButton(PromptArea.this, "Send Chat");
                    if (sendChat != null && sendChat.isEnabled()) sendChat.doClick();
                }
            });
            getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "burp-cockpit-insert-break");
            getActionMap().put("burp-cockpit-insert-break", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { replaceSelection("\n"); }
            });
        }

        private void installSubmitHooks() {
            if (submitHooksInstalled) return;
            JButton sendChat = findButton(this, "Send Chat");
            JButton analyze = findButton(this, "Analyze");
            if (sendChat == null && analyze == null) return;
            if (sendChat != null) sendChat.addActionListener(e -> captureSubmittedPrompt());
            if (analyze != null) analyze.addActionListener(e -> captureSubmittedPrompt());
            submitHooksInstalled = true;
        }

        private void captureSubmittedPrompt() {
            String submitted = super.getText();
            if (submitted.isBlank()) {
                submittedPrompt = "";
                submittedPromptAvailable = false;
                return;
            }
            submittedPrompt = submitted;
            submittedPromptAvailable = true;
            SwingUtilities.invokeLater(() -> {
                if (!super.getText().equals(submitted)) return;
                autoClearing = true;
                try { super.setText(""); }
                finally { autoClearing = false; }
            });
        }
    }

    private static Object cockpitPanel(Component start) {
        Component current = start;
        while (current != null) {
            if ("com.buggyboi.burpcockpit.ui.CockpitPanel".equals(current.getClass().getName())) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find CockpitPanel root.");
    }

    private static Object fieldValue(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable throwable) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object tryInvokeNoArg(Object target, String name) {
        try { return invokeNoArg(target, name); }
        catch (Throwable ignored) { return null; }
    }

    private static Object tryInvokeOneString(Object target, String name, String arg) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1 || !method.getParameterTypes()[0].equals(String.class)) continue;
            try {
                method.setAccessible(true);
                return method.invoke(target, arg);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Container rootContainer(Component start) {
        Container root = start.getParent();
        while (root != null && root.getParent() != null) root = root.getParent();
        return root;
    }

    private static JButton findButton(Component start, String text) {
        Container root = rootContainer(start);
        return root == null ? null : findButtonRecursive(root, text);
    }

    private static JButton findButtonRecursive(Component component, String text) {
        if (component instanceof JButton button && text.equals(button.getText())) return button;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findButtonRecursive(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
