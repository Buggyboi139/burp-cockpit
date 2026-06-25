package com.buggyboi.burpcockpit.ui;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
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
            @Override
            public void mousePressed(MouseEvent event) {
                maybeShow(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShow(event);
            }

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
            area = new AutoClearingPromptArea(rows, cols);
        } else if (isChatTranscript(rows, cols, editable)) {
            area = new ChatTranscriptArea(rows, cols);
        } else if (isRequestEditor(rows, cols, editable)) {
            area = new RequestEditorArea(rows, cols);
        } else {
            area = new JTextArea(rows, cols);
        }
        area.setEditable(editable);
        if (isChatTranscript(rows, cols, editable) || isRequestEditor(rows, cols, editable) || isResponseViewer(rows, cols, editable)) {
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
        } else {
            area.setLineWrap(false);
            area.setWrapStyleWord(false);
        }
        install(area);
        return area;
    }

    public static JTextField field(String text) {
        JTextField field = new JTextField(text == null ? "" : text);
        install(field);
        return field;
    }

    public static void later(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    private static boolean isPromptInput(int rows, int cols, boolean editable) {
        return editable && rows == 4 && cols == 70;
    }

    private static boolean isChatTranscript(int rows, int cols, boolean editable) {
        return !editable && rows == 24 && cols == 70;
    }

    private static boolean isRequestEditor(int rows, int cols, boolean editable) {
        return editable && rows == 24 && cols == 90;
    }

    private static boolean isResponseViewer(int rows, int cols, boolean editable) {
        return !editable && rows == 12 && cols == 90;
    }

    private static final class RequestEditorArea extends JTextArea {
        private static final String REFRESH_COOKIES_BUTTON_NAME = "burp-cockpit-refresh-cookies";
        private boolean refreshButtonInstalled;

        private RequestEditorArea(int rows, int cols) {
            super(rows, cols);
        }

        @Override
        public void addNotify() {
            super.addNotify();
            SwingUtilities.invokeLater(this::installRefreshCookiesButton);
        }

        private void installRefreshCookiesButton() {
            if (refreshButtonInstalled) {
                return;
            }
            Container root = rootContainer(this);
            if (root == null) {
                return;
            }
            JButton send = findButtonRecursive(root, "Send");
            if (send == null || !(send.getParent() instanceof JToolBar toolbar)) {
                return;
            }
            for (Component component : toolbar.getComponents()) {
                if (REFRESH_COOKIES_BUTTON_NAME.equals(component.getName())) {
                    refreshButtonInstalled = true;
                    return;
                }
            }
            JButton refreshCookies = new JButton("Refresh Cookies");
            refreshCookies.setName(REFRESH_COOKIES_BUTTON_NAME);
            refreshCookies.addActionListener(e -> refreshCookiesFromBurp());
            int index = toolbar.getComponentIndex(send);
            toolbar.add(refreshCookies, Math.max(0, index + 1));
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
                Object cockpit = cockpitPanel(this);
                Object api = fieldValue(cockpit, "api");
                List<Object> cookies = readBurpCookies(api, target.url());
                Map<String, String> matched = new LinkedHashMap<>();
                for (Object cookie : cookies) {
                    CookieView view = CookieView.from(cookie);
                    if (view.matches(target)) {
                        matched.put(view.name(), view.value());
                    }
                }
                if (matched.isEmpty()) {
                    setCockpitStatus("Refresh Cookies found no matching Burp cookie jar cookies for " + target.host() + target.path() + ".");
                    return;
                }
                StringBuilder cookieHeader = new StringBuilder();
                for (Map.Entry<String, String> entry : matched.entrySet()) {
                    if (!cookieHeader.isEmpty()) {
                        cookieHeader.append("; ");
                    }
                    cookieHeader.append(entry.getKey()).append('=').append(entry.getValue());
                }
                setText(withCookieHeader(getText(), cookieHeader.toString()));
                setCaretPosition(0);
                setCockpitStatus("Refreshed " + matched.size() + " cookie(s) from Burp cookie jar for " + target.host() + ".");
            } catch (Throwable throwable) {
                setCockpitStatus("Refresh Cookies failed: " + throwable.getClass().getSimpleName() + ": " + Objects.toString(throwable.getMessage(), "no detail"));
            }
        }

        private void setCockpitStatus(String message) {
            Object cockpit = cockpitPanel(this);
            Object label = fieldValue(cockpit, "statusLabel");
            if (label instanceof JLabel jLabel) {
                jLabel.setText(message);
            }
        }

        private static List<Object> readBurpCookies(Object api, String url) throws Exception {
            Object http = invokeNoArg(api, "http");
            Object jar = invokeNoArg(http, "cookieJar");
            Object result = null;
            for (String method : List.of("cookies", "getCookies")) {
                result = tryInvokeNoArg(jar, method);
                if (result != null) {
                    break;
                }
                result = tryInvokeOneString(jar, method, url);
                if (result != null) {
                    break;
                }
            }
            if (result == null) {
                throw new IllegalStateException("Montoya cookie jar did not expose cookies()/getCookies().");
            }
            if (result instanceof Iterable<?> iterable) {
                List<Object> out = new ArrayList<>();
                for (Object item : iterable) {
                    out.add(item);
                }
                return out;
            }
            if (result.getClass().isArray()) {
                List<Object> out = new ArrayList<>();
                int len = java.lang.reflect.Array.getLength(result);
                for (int i = 0; i < len; i++) {
                    out.add(java.lang.reflect.Array.get(result, i));
                }
                return out;
            }
            throw new IllegalStateException("Montoya cookie jar returned unsupported type: " + result.getClass().getName());
        }

        private static Object cockpitPanel(Component start) {
            Component current = start;
            while (current != null) {
                if ("com.buggyboi.burpcockpit.ui.CockpitPanel".equals(current.getClass().getName())) {
                    return current;
                }
                current = current.getParent();
            }
            throw new IllegalStateException("Could not find CockpitPanel root.");
        }

        private static Object fieldValue(Object target, String fieldName) {
            if (target == null) {
                return null;
            }
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
            try {
                return invokeNoArg(target, name);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Object tryInvokeOneString(Object target, String name, String arg) {
            for (Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1 || !method.getParameterTypes()[0].equals(String.class)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(target, arg);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return null;
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
            if (!replaced && !inserted) {
                int insertAt = Math.min(1, out.size());
                out.add(insertAt, "Cookie: " + cookieHeader);
            }
            return String.join(newline, out) + newline + newline + body.replace("\n", newline);
        }

        private record RequestTarget(String scheme, String host, String path) {
            String url() {
                return scheme + "://" + host + path;
            }

            static RequestTarget from(String rawRequest) {
                String text = Objects.toString(rawRequest, "").replace("\r\n", "\n").replace('\r', '\n');
                String[] lines = text.split("\n");
                String first = lines.length > 0 ? lines[0] : "";
                String host = "";
                String path = "/";
                String scheme = "https";
                for (String line : lines) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("host:")) {
                        host = line.substring(line.indexOf(':') + 1).trim();
                    } else if (lower.startsWith("origin: http://")) {
                        scheme = "http";
                    } else if (lower.startsWith("referer: http://")) {
                        scheme = "http";
                    }
                }
                String[] parts = first.split("\\s+");
                if (parts.length > 1) {
                    path = parts[1].trim();
                    if (path.startsWith("http://") || path.startsWith("https://")) {
                        try {
                            URI uri = URI.create(path);
                            scheme = uri.getScheme() == null ? scheme : uri.getScheme();
                            host = uri.getHost() == null ? host : uri.getHost();
                            if (uri.getPort() > 0) {
                                host = host + ":" + uri.getPort();
                            }
                            path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
                            if (uri.getRawQuery() != null) {
                                path += "?" + uri.getRawQuery();
                            }
                        } catch (Throwable ignored) {
                            path = "/";
                        }
                    }
                }
                if (path.isBlank()) {
                    path = "/";
                }
                return new RequestTarget(scheme, host, path);
            }
        }

        private record CookieView(String name, String value, String domain, String path, boolean secure) {
            boolean matches(RequestTarget target) {
                if (name.isBlank()) {
                    return false;
                }
                String targetHost = stripPort(target.host()).toLowerCase(Locale.ROOT);
                String cookieDomain = domain.toLowerCase(Locale.ROOT);
                if (cookieDomain.startsWith(".")) {
                    cookieDomain = cookieDomain.substring(1);
                }
                boolean domainOk = cookieDomain.isBlank()
                        || targetHost.equals(cookieDomain)
                        || targetHost.endsWith("." + cookieDomain);
                boolean pathOk = path.isBlank() || target.path().startsWith(path);
                boolean secureOk = !secure || "https".equalsIgnoreCase(target.scheme());
                return domainOk && pathOk && secureOk;
            }

            static CookieView from(Object cookie) {
                String name = str(cookie, "name", "getName");
                String value = str(cookie, "value", "getValue");
                String domain = str(cookie, "domain", "getDomain");
                String path = str(cookie, "path", "getPath");
                boolean secure = bool(cookie, "secure", "isSecure", "getSecure");
                return new CookieView(name, value, domain, path, secure);
            }

            private static String stripPort(String host) {
                String value = Objects.toString(host, "").trim();
                int colon = value.lastIndexOf(':');
                if (colon > 0 && value.indexOf(']') < colon) {
                    return value.substring(0, colon);
                }
                return value;
            }

            private static String str(Object target, String... names) {
                for (String name : names) {
                    try {
                        Object value = invokeNoArg(target, name);
                        return Objects.toString(value, "");
                    } catch (Throwable ignored) {
                        // Try next name. Ceremony, because Java.
                    }
                }
                return "";
            }

            private static boolean bool(Object target, String... names) {
                for (String name : names) {
                    try {
                        Object value = invokeNoArg(target, name);
                        if (value instanceof Boolean b) {
                            return b;
                        }
                        return Boolean.parseBoolean(Objects.toString(value, "false"));
                    } catch (Throwable ignored) {
                        // Try next name.
                    }
                }
                return false;
            }
        }
    }

    private static final class ChatTranscriptArea extends JTextArea {
        private static final String CLEAR_CHAT_BUTTON_NAME = "burp-cockpit-clear-chat";
        private boolean clearButtonInstalled;

        private ChatTranscriptArea(int rows, int cols) {
            super(rows, cols);
            setLineWrap(true);
            setWrapStyleWord(true);
        }

        @Override
        public void addNotify() {
            super.addNotify();
            SwingUtilities.invokeLater(this::installClearChatButton);
        }

        @Override
        public void replaceRange(String str, int start, int end) {
            super.replaceRange(cleanAssistantText(str), start, end);
        }

        private void installClearChatButton() {
            if (clearButtonInstalled) {
                return;
            }
            Container viewport = getParent();
            if (viewport == null) {
                return;
            }
            Container scroll = viewport.getParent();
            if (scroll == null) {
                return;
            }
            Container center = scroll.getParent();
            if (!(center instanceof JPanel centerPanel) || !(centerPanel.getLayout() instanceof BorderLayout centerLayout)) {
                return;
            }
            Container analysis = centerPanel.getParent();
            if (!(analysis instanceof JPanel analysisPanel) || !(analysisPanel.getLayout() instanceof BorderLayout analysisLayout)) {
                return;
            }
            Component top = analysisLayout.getLayoutComponent(BorderLayout.NORTH);
            if (!(top instanceof JPanel topPanel) || !(topPanel.getLayout() instanceof BorderLayout topLayout)) {
                return;
            }
            Component buttonsComponent = topLayout.getLayoutComponent(BorderLayout.SOUTH);
            if (!(buttonsComponent instanceof JPanel buttons)) {
                return;
            }
            for (Component component : buttons.getComponents()) {
                if (CLEAR_CHAT_BUTTON_NAME.equals(component.getName())) {
                    clearButtonInstalled = true;
                    return;
                }
            }
            JButton clearChat = new JButton("Clear Chat");
            clearChat.setName(CLEAR_CHAT_BUTTON_NAME);
            clearChat.addActionListener(e -> {
                setText("");
                Component statusComponent = centerLayout.getLayoutComponent(BorderLayout.SOUTH);
                if (statusComponent instanceof JLabel label) {
                    label.setText("Chat cleared.");
                }
            });
            buttons.add(clearChat);
            buttons.revalidate();
            buttons.repaint();
            clearButtonInstalled = true;
        }

        private static String cleanAssistantText(String value) {
            String text = Objects.toString(value, "").replace("\r\n", "\n").replace('\r', '\n');
            if (text.isBlank()) {
                return text;
            }
            StringBuilder out = new StringBuilder(text.length() + 128);
            boolean inFence = false;
            String[] lines = text.split("\n", -1);
            for (String originalLine : lines) {
                String line = originalLine;
                String trimmed = line.trim();
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    inFence = !inFence;
                    out.append(line).append('\n');
                    continue;
                }
                if (!inFence) {
                    line = cleanMarkdownLine(line);
                    line = shapePlainLine(line);
                    if (isPlainHeading(line)) {
                        appendBlankLineIfNeeded(out);
                    }
                }
                out.append(line);
                if (!line.endsWith("\n")) {
                    out.append('\n');
                }
            }
            String cleaned = out.toString()
                    .replaceAll("\\n{4,}", "\n\n\n")
                    .replaceAll("(?m)^\\s*[-*]\\s*$\\n", "")
                    .stripTrailing();
            return cleaned.isEmpty() ? "" : cleaned;
        }

        private static String cleanMarkdownLine(String line) {
            String leading = leadingWhitespace(line);
            String body = line.substring(leading.length());
            body = body.replaceFirst("^#{1,6}\\s+", "");
            body = body.replaceFirst("^[-*+]\\s+", "- ");
            body = body.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "$1");
            body = body.replaceAll("___(.+?)___", "$1");
            body = body.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
            body = body.replaceAll("__(.+?)__", "$1");
            body = body.replaceAll("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)", "$1");
            body = body.replaceAll("(?<!_)_([^_\\n]+)_(?!_)", "$1");
            body = body.replaceAll("`([^`\\n]+)`", "$1");
            return leading + body;
        }

        private static String shapePlainLine(String line) {
            String leading = leadingWhitespace(line);
            String body = line.substring(leading.length()).trim();
            if (body.isBlank()) {
                return "";
            }
            if (looksRaw(body)) {
                return leading + body;
            }
            if (body.length() < 90 && !body.contains("; ") && !body.contains(". ")) {
                return leading + body;
            }

            body = body.replaceAll("\\s+(?=(What matters|Highest-value|Highest Value|Best next|Exact fields|Expected response|Low-value|Skip|Notes?|Findings?|Signal|Signals|Next steps?|Recommendation|Summary|Risk|Target|Request|Response|Parameters?|Primary|Secondary|Test|Tests|Why|Impact|Evidence):)", "\n\n");
            body = body.replaceAll("\\s+(?=(Target path|Primary concern|Secondary concern|High-value|Likely signal|Main signal|Test this|Do not waste|What to send|What to watch))", "\n");
            body = body.replaceAll("\\s+(?=\\d+[.)]\\s+)", "\n");
            body = body.replaceAll("\\s+(?=-\\s+)", "\n");
            body = body.replaceAll("(?<=[.!?])\\s+(?=[A-Z0-9])", "\n");
            body = body.replaceAll(";\\s+(?=[A-Z0-9])", ";\n");
            body = body.replaceAll(",\\s+(?=(cookies?|headers?|parameters?|body|status|response|request|tokens?|IDs?|auth|origin|referer|method|path)\\b)", ",\n");

            StringBuilder shaped = new StringBuilder(body.length() + 64);
            String[] chunks = body.split("\\n", -1);
            for (String chunk : chunks) {
                String clean = chunk.trim();
                if (clean.isEmpty()) {
                    appendBlankLineIfNeeded(shaped);
                    continue;
                }
                if (isSectionLabel(clean)) {
                    appendBlankLineIfNeeded(shaped);
                    shaped.append(clean).append("\n\n");
                } else if (clean.startsWith("- ")) {
                    shaped.append("  ").append(clean).append('\n');
                } else if (clean.matches("^\\d+[.)]\\s+.*")) {
                    shaped.append(clean).append("\n\n");
                } else {
                    shaped.append(wrapChunk(clean, 96)).append("\n\n");
                }
            }
            return leading + shaped.toString().stripTrailing();
        }

        private static String wrapChunk(String value, int width) {
            String text = Objects.toString(value, "").trim();
            if (text.length() <= width) {
                return text;
            }
            StringBuilder out = new StringBuilder(text.length() + 32);
            int lineLen = 0;
            for (String word : text.split("\\s+")) {
                if (lineLen > 0 && lineLen + 1 + word.length() > width) {
                    out.append('\n');
                    lineLen = 0;
                } else if (lineLen > 0) {
                    out.append(' ');
                    lineLen++;
                }
                out.append(word);
                lineLen += word.length();
            }
            return out.toString();
        }

        private static boolean looksRaw(String body) {
            String text = body.trim();
            if (text.startsWith("GET ")
                    || text.startsWith("POST ")
                    || text.startsWith("PUT ")
                    || text.startsWith("PATCH ")
                    || text.startsWith("DELETE ")
                    || text.startsWith("HTTP/")
                    || text.contains("\r\n")
                    || text.startsWith("{")
                    || text.startsWith("[")) {
                return true;
            }
            if (text.matches("^[A-Za-z0-9_-]+: .*") && !text.contains(". ") && !text.contains("; ")) {
                String key = text.substring(0, text.indexOf(':')).toLowerCase();
                return key.equals("host")
                        || key.equals("cookie")
                        || key.equals("content-type")
                        || key.equals("content-length")
                        || key.equals("origin")
                        || key.equals("referer")
                        || key.equals("accept")
                        || key.equals("authorization")
                        || key.startsWith("x-")
                        || key.startsWith("sec-");
            }
            return false;
        }

        private static boolean isSectionLabel(String value) {
            String trimmed = value.trim();
            return trimmed.endsWith(":") && trimmed.length() <= 80;
        }

        private static boolean isPlainHeading(String line) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("-") || trimmed.matches("^\\d+[.)].*")) {
                return false;
            }
            return trimmed.endsWith(":") || trimmed.length() <= 64 && !trimmed.contains(".") && !trimmed.contains(";");
        }

        private static void appendBlankLineIfNeeded(StringBuilder out) {
            int len = out.length();
            if (len == 0) {
                return;
            }
            if (len >= 2 && out.charAt(len - 1) == '\n' && out.charAt(len - 2) == '\n') {
                return;
            }
            if (out.charAt(len - 1) != '\n') {
                out.append('\n');
            }
            out.append('\n');
        }

        private static String leadingWhitespace(String value) {
            int i = 0;
            while (i < value.length() && Character.isWhitespace(value.charAt(i))) {
                i++;
            }
            return value.substring(0, i);
        }
    }

    private static final class AutoClearingPromptArea extends JTextArea {
        private String submittedPrompt = "";
        private boolean submittedPromptAvailable;
        private boolean autoClearing;
        private boolean submitHooksInstalled;

        private AutoClearingPromptArea(int rows, int cols) {
            super(rows, cols);
            installEnterBindings();
        }

        @Override
        public void addNotify() {
            super.addNotify();
            SwingUtilities.invokeLater(this::installSubmitHooks);
        }

        @Override
        public String getText() {
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
            if (submittedPromptAvailable) {
                return submittedPrompt;
            }
            return current;
        }

        @Override
        public void setText(String text) {
            if (!autoClearing) {
                submittedPrompt = "";
                submittedPromptAvailable = false;
            }
            super.setText(text);
        }

        private void installEnterBindings() {
            getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "burp-cockpit-send-chat");
            getActionMap().put("burp-cockpit-send-chat", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    captureSubmittedPrompt();
                    JButton sendChat = findButton(AutoClearingPromptArea.this, "Send Chat");
                    if (sendChat != null && sendChat.isEnabled()) {
                        sendChat.doClick();
                    }
                }
            });
            getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "burp-cockpit-insert-break");
            getActionMap().put("burp-cockpit-insert-break", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    replaceSelection("\n");
                }
            });
        }

        private void installSubmitHooks() {
            if (submitHooksInstalled) {
                return;
            }
            JButton sendChat = findButton(this, "Send Chat");
            JButton analyze = findButton(this, "Analyze");
            if (sendChat == null && analyze == null) {
                return;
            }
            if (sendChat != null) {
                sendChat.addActionListener(e -> captureSubmittedPrompt());
            }
            if (analyze != null) {
                analyze.addActionListener(e -> captureSubmittedPrompt());
            }
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
            scheduleClearIfUnchanged(submitted);
        }

        private void scheduleClearIfUnchanged(String submitted) {
            SwingUtilities.invokeLater(() -> {
                if (!super.getText().equals(submitted)) {
                    return;
                }
                autoClearing = true;
                try {
                    super.setText("");
                } finally {
                    autoClearing = false;
                }
            });
        }
    }

    private static Container rootContainer(Component start) {
        Container root = start.getParent();
        while (root != null && root.getParent() != null) {
            root = root.getParent();
        }
        return root;
    }

    private static JButton findButton(Component start, String text) {
        Container root = rootContainer(start);
        if (root == null) {
            return null;
        }
        return findButtonRecursive(root, text);
    }

    private static JButton findButtonRecursive(Component component, String text) {
        if (component instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findButtonRecursive(child, text);
                if (found != null) {
                    return button;
                }
            }
        }
        return null;
    }
}
