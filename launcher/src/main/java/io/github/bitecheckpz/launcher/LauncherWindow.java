package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Verifier;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;

/**
 * The BiteCheck window: answers "can I start?" at the top, lists every installed Java mod with its
 * status, and starts the game. Plain Swing, styled by {@link Theme} and {@link Ui}.
 */
final class LauncherWindow {

    private static final DateTimeFormatter CONFIRMED =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final Theme t = Theme.get();
    private final Path dir;
    private final JFrame frame = new JFrame("BiteCheck");
    private final JPanel verdict = Ui.card(new BorderLayout(14, 0), t.raised, null, 14);
    private final JLabel glyph = new JLabel("…");
    private final JLabel summary = new JLabel("Checking your Java mods…");
    private final JLabel detail = new JLabel(" ");
    private final JLabel registry = new JLabel(" ");
    private final ModTableModel model = new ModTableModel();
    private final JTable table = new JTable(model) {
        private static final long serialVersionUID = 1L;

        @Override
        public String getToolTipText(java.awt.event.MouseEvent e) {
            int row = rowAtPoint(e.getPoint());
            if (row < 0) {
                return null;
            }
            ModLine line = model.line(convertRowIndexToModel(row));
            if (columnAtPoint(e.getPoint()) == 2) {
                // the full details, which the column usually cuts off
                return "<html><body style='width: 380px'>" + escape(String.valueOf(model.getValueAt(
                        convertRowIndexToModel(row), 2))) + "</body></html>";
            }
            return line.tooltip();
        }
    };
    private final JTextArea notes = new JTextArea();
    private final Ui.Button allowFlagged = new Ui.Button("Allow…", Ui.Kind.SECONDARY);
    private final Ui.Button refresh = new Ui.Button("Check again", Ui.Kind.QUIET);
    private final Ui.Button applyOnly = new Ui.Button("Apply only", Ui.Kind.SECONDARY);
    private final Ui.Button start = new Ui.Button("Start Project Zomboid", Ui.Kind.PRIMARY);
    private final Ui.Button steamSetup = new Ui.Button("Steam setup…", Ui.Kind.QUIET);
    private final Ui.Button update = new Ui.Button("Update BiteCheck…", Ui.Kind.SECONDARY);
    private Session session;
    private SelfUpdate.Offer offer;
    private boolean autoUpdateTried;
    /** The offered install repairs a changed installation (same version again), see {@link SelfCheck}. */
    private boolean repair;
    private boolean updating;

    private LauncherWindow(Path dir) {
        this.dir = dir;
    }

    static void open(Path dir) {
        SwingUtilities.invokeLater(() -> {
            setUpLook();
            new LauncherWindow(dir).show();
        });
    }

    /** The system look with the theme's colors for dialogs; also used for the questions before a Steam start. */
    static void setUpLook() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
            // keep the default look
        }
        themeDialogs(Theme.get());
    }

    /** Message boxes and tooltips in the theme's colors, so a dark window does not open light dialogs. */
    private static void themeDialogs(Theme t) {
        UIManager.put("OptionPane.background", t.window);
        UIManager.put("Panel.background", t.window);
        UIManager.put("OptionPane.messageForeground", t.text);
        UIManager.put("Label.foreground", t.text);
        UIManager.put("TextField.background", t.surface);
        UIManager.put("TextField.foreground", t.text);
        UIManager.put("TextField.caretForeground", t.text);
        UIManager.put("ToolTip.background", t.raised);
        UIManager.put("ToolTip.foreground", t.text);
        UIManager.put("ToolTip.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(t.line), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
    }

    /** The BiteCheck logo in several sizes, for the title bar and the taskbar. */
    private static List<Image> icons() {
        List<Image> icons = new ArrayList<>();
        for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
            URL url = LauncherWindow.class.getResource("icon-" + size + ".png");
            if (url != null) {
                icons.add(new ImageIcon(url).getImage());
            }
        }
        return icons;
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImages(icons());

        JPanel content = new JPanel(new BorderLayout(0, 16));
        content.setBackground(t.window);
        content.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));
        content.add(top(), BorderLayout.NORTH);
        content.add(modList(), BorderLayout.CENTER);
        content.add(bottom(), BorderLayout.SOUTH);

        allowFlagged.addActionListener(e -> allowSelectedFlagged());
        refresh.addActionListener(e -> check());
        applyOnly.addActionListener(e -> apply(false));
        start.addActionListener(e -> apply(true));
        steamSetup.addActionListener(e -> showSteamSetup());
        update.addActionListener(e -> {
            if (offer != null) {
                runUpdate(offer, false);
            }
        });

        frame.setContentPane(content);
        frame.getRootPane().setDefaultButton(start);
        frame.setMinimumSize(new Dimension(760, 520));
        frame.setSize(1000, 680);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        start.requestFocusInWindow();
        check();
    }

    /** Brand row and the verdict: the answer to "can I start?". */
    private JPanel top() {
        JPanel brand = new JPanel(new BorderLayout(10, 0));
        brand.setOpaque(false);
        URL logo = LauncherWindow.class.getResource("icon-32.png");
        if (logo != null) {
            brand.add(new JLabel(new ImageIcon(logo)), BorderLayout.WEST);
        }
        JLabel name = new JLabel("<html><span style='font-weight:bold'>BiteCheck</span>"
                + "<span style='color:" + hex(t.muted) + "'>&nbsp;&nbsp;Java mod check for Project Zomboid</span></html>");
        name.setFont(t.size(1.2f));
        name.setForeground(t.text);
        brand.add(name, BorderLayout.CENTER);
        registry.setFont(t.size(0.92f));
        registry.setForeground(t.muted);
        brand.add(registry, BorderLayout.EAST);

        glyph.setFont(t.bold(1.7f));
        glyph.setVerticalAlignment(JLabel.TOP);
        glyph.setPreferredSize(new Dimension(glyph.getFontMetrics(glyph.getFont()).getHeight(),
                glyph.getFontMetrics(glyph.getFont()).getHeight()));
        glyph.setHorizontalAlignment(JLabel.CENTER);
        summary.setFont(t.bold(1.3f));
        summary.setForeground(t.text);
        detail.setFont(t.base);
        detail.setForeground(t.muted);
        JPanel words = new JPanel();
        words.setOpaque(false);
        words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
        words.add(summary);
        words.add(Box.createVerticalStrut(4));
        words.add(detail);
        verdict.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        verdict.add(glyph, BorderLayout.WEST);
        verdict.add(words, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout(0, 16));
        top.setOpaque(false);
        top.add(brand, BorderLayout.NORTH);
        top.add(verdict, BorderLayout.CENTER);
        return top;
    }

    /** The mod table in a quiet outlined panel. */
    private JPanel modList() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFont(t.base);
        table.setBackground(t.surface);
        table.setForeground(t.text);
        table.setSelectionBackground(t.selection);
        table.setSelectionForeground(t.text);
        table.setFillsViewportHeight(true);
        table.setFocusable(false);
        table.setRowHeight(table.getFontMetrics(t.base).getHeight() * 2 + 20);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.getColumnModel().getColumn(0).setCellRenderer(new ModRenderer(t));
        table.getColumnModel().getColumn(1).setCellRenderer(new StatusRenderer(t));
        table.getColumnModel().getColumn(2).setCellRenderer(new TextRenderer(t, true));
        table.getColumnModel().getColumn(3).setCellRenderer(new TextRenderer(t, false));
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(130);
        table.getColumnModel().getColumn(2).setPreferredWidth(340);
        table.getColumnModel().getColumn(3).setPreferredWidth(170);
        JTableHeader header = table.getTableHeader();
        header.setUI(new javax.swing.plaf.basic.BasicTableHeaderUI()); // the system one ignores our padding
        header.setReorderingAllowed(false);
        header.setBackground(t.surface);
        header.setBorder(BorderFactory.createEmptyBorder());
        header.setDefaultRenderer(new HeaderRenderer(t));

        JScrollPane scroll = new JScrollPane(table);
        Ui.style(scroll, t.surface);
        scroll.setColumnHeaderView(header);
        scroll.getColumnHeader().setBackground(t.surface);
        scroll.setCorner(JScrollPane.UPPER_RIGHT_CORNER, filler(t.surface));

        JPanel card = Ui.card(new BorderLayout(), t.surface, t.line, 14);
        card.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    /** Notes (paths, problems) and the buttons. */
    private JPanel bottom() {
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setOpaque(false);
        notes.setBorder(BorderFactory.createEmptyBorder());
        notes.setFont(t.size(0.92f));
        notes.setForeground(t.muted);
        notes.setSelectionColor(t.selection);
        notes.setSelectedTextColor(t.text);

        JLabel legend = new JLabel("<html>Verified mods load right away. For every other mod BiteCheck asks you once before the "
                + "start; suspicious and flagged ones need a second confirmation, blocked ones never load. "
                + "The local scan is a quick check, not a review.</html>");
        legend.setFont(t.size(0.92f));
        legend.setForeground(t.muted);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        left.add(steamSetup);
        left.add(refresh);
        left.add(Box.createHorizontalStrut(6));
        left.add(allowFlagged);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        update.setVisible(false);
        right.add(update);
        right.add(applyOnly);
        right.add(start);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[] {legend, notes, buttons}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        bottom.add(legend);
        bottom.add(Box.createVerticalStrut(6));
        bottom.add(notes);
        bottom.add(Box.createVerticalStrut(14));
        bottom.add(buttons);
        return bottom;
    }

    private static JComponent filler(Color color) {
        JPanel p = new JPanel();
        p.setBackground(color);
        return p;
    }

    private enum Tone {
        CALM, NOTE, ATTENTION, PROBLEM
    }

    private void setVerdict(Tone tone, String symbol, String headline, String text) {
        Color fill = switch (tone) {
            case CALM -> t.okTint;
            case ATTENTION -> t.warnTint;
            case PROBLEM -> t.badTint;
            default -> t.raised;
        };
        Color mark = switch (tone) {
            case CALM -> t.ok;
            case ATTENTION -> t.warn;
            case PROBLEM -> t.bad;
            default -> t.muted;
        };
        verdict.setBackground(fill);
        glyph.setForeground(mark);
        glyph.setText(symbol);
        summary.setText(headline);
        detail.setText(text == null || text.isBlank() ? " " : "<html>" + escape(text) + "</html>");
        verdict.repaint();
    }

    private void setBusy(boolean requested) {
        boolean busy = requested || updating; // an update in progress keeps the buttons off
        refresh.setEnabled(!busy);
        applyOnly.setEnabled(!busy && session != null);
        start.setEnabled(!busy && session != null);
        allowFlagged.setEnabled(false);
        if (!busy) {
            updateButtons();
        }
    }

    private void updateButtons() {
        ModLine line = selectedLine();
        allowFlagged.setEnabled(line != null && !line.toAllow().isEmpty());
    }

    private ModLine selectedLine() {
        int i = table.getSelectedRow();
        return session == null || i < 0 ? null : model.line(table.convertRowIndexToModel(i));
    }

    private void check() {
        setBusy(true);
        setVerdict(Tone.NOTE, "…", "Checking your Java mods…", "Reading your mod folders and the BiteCheck registry.");
        new SwingWorker<Session, Void>() {
            @Override
            protected Session doInBackground() throws Exception {
                return Session.run(dir);
            }

            @Override
            protected void done() {
                try {
                    show(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    session = null;
                    setVerdict(Tone.PROBLEM, "✖", "The check failed",
                            String.valueOf(e.getCause().getMessage()) + " Your earlier decisions for ZombieBuddy stay as they are.");
                }
                setBusy(false);
            }
        }.execute();
    }

    private void show(Session s) {
        session = s;
        List<ModLine> lines = ModLine.group(s.rows);
        model.setLines(lines);
        showVerdict(lines);
        registry.setText(s.index.index()
                .map(i -> "Registry confirmed " + CONFIRMED.format(i.confirmedAt()))
                .orElse("⚠ Registry not available"));
        registry.setForeground(s.index.index().isPresent() ? t.muted : t.warn);
        StringBuilder text = new StringBuilder();
        text.append("Game: ").append(s.platform.gameDir().map(Path::toString).orElse("not found")).append('\n');
        text.append("ZombieBuddy approvals: ").append(s.approvals.file()).append('\n');
        s.notes.forEach(n -> text.append("Note: ").append(n).append('\n'));
        notes.setText(text.toString().strip());
        notes.setCaretPosition(0);
        java.util.Optional<Session.UpdateTarget> target = s.updateTarget();
        offer = target.flatMap(u -> SelfUpdate.offer(u.version(), s.config)).orElse(null);
        repair = target.map(Session.UpdateTarget::repair).orElse(false);
        update.setVisible(offer != null && !updating);
        if (offer != null) {
            update.setText(repair ? "Repair BiteCheck" : "Update to " + offer.version());
            if (!autoUpdateTried && SelfUpdate.autoOffer(offer.version(), s.config, dir.resolve("updates")).isPresent()) {
                autoUpdateTried = true;
                runUpdate(offer, true);
            }
        }
    }

    /** The headline: whether the game can start and what will not load, in proportion to the risk. */
    private void showVerdict(List<ModLine> lines) {
        if (lines.isEmpty()) {
            setVerdict(Tone.CALM, "✔", "No Java mods installed",
                    "Lua mods are loaded by the game itself; BiteCheck has nothing to check.");
            return;
        }
        Optional<ModLine> blockedLoader = lines.stream()
                .filter(l -> l.mod().isLoader() && onStart(l.shown()).equals(NO_START)).findFirst();
        if (blockedLoader.isPresent()) {
            String loader = blockedLoader.get().mod().name();
            setVerdict(Tone.PROBLEM, "✖", loader + " is blocked. The game does not start with it.",
                    "Select " + loader + " to see why, and install it again from its official source.");
            return;
        }
        long blocked = lines.stream().filter(l -> onStart(l.shown()).startsWith("blocked")
                || onStart(l.shown()).startsWith("off")).count();
        long asks = lines.stream().filter(l -> onStart(l.shown()).equals(ASKS)).count();
        long verified = count(lines, Verifier.Status.VERIFIED);
        long suspicious = lines.stream().filter(l -> l.shown().suspicious()).count();
        long unverified = count(lines, Verifier.Status.UNVERIFIED) - suspicious;
        long flagged = count(lines, Verifier.Status.FLAGGED);
        long hardBlocked = count(lines, Verifier.Status.BLOCKED) + count(lines, Verifier.Status.INVALID);
        List<String> parts = new ArrayList<>();
        parts.add(lines.size() + (lines.size() == 1 ? " Java mod" : " Java mods"));
        addCount(parts, verified, "verified");
        addCount(parts, unverified, "unverified");
        addCount(parts, suspicious, "suspicious");
        addCount(parts, flagged, "flagged");
        addCount(parts, hardBlocked, "blocked");
        String counts = String.join(" · ", parts);
        if (blocked > 0) {
            setVerdict(Tone.ATTENTION, "⚠", "Ready to start. " + blocked + (blocked == 1 ? " mod stays" : " mods stay")
                    + " off" + (asks > 0 ? ", " + asks + " to decide." : "."), counts + ". Select a mod to see why.");
        } else if (asks > 0) {
            setVerdict(Tone.NOTE, "?", "Ready to start. BiteCheck asks you about " + asks
                    + (asks == 1 ? " mod" : " mods") + " first.", counts + ".");
        } else {
            setVerdict(Tone.CALM, "✔", lines.size() == 1 ? "All clear. Your Java mod loads."
                    : "All clear. All " + lines.size() + " Java mods load.", counts + ".");
        }
    }

    private static void addCount(List<String> parts, long n, String word) {
        if (n > 0) {
            parts.add(n + " " + word);
        }
    }

    /**
     * Downloads the newer release, checks its signature and installs it (see {@link SelfUpdate}).
     * Automatic updates run without any question; problems only end up in the notes. The button
     * is the fallback when an automatic update failed.
     */
    private void runUpdate(SelfUpdate.Offer o, boolean auto) {
        updating = true;
        update.setVisible(false);
        setBusy(true);
        boolean fix = repair;
        setVerdict(Tone.NOTE, "↻", fix ? "Repairing BiteCheck…" : "Updating BiteCheck to " + o.version() + "…",
                fix ? "BiteCheck's program file differs from its signed release; the release is installed again."
                        : "The new version is checked against its release signature before it is installed.");
        Config config = session != null ? session.config : Config.empty();
        new SwingWorker<SelfUpdate.Result, Void>() {
            @Override
            protected SelfUpdate.Result doInBackground() throws Exception {
                return SelfUpdate.auto(o, dir.resolve("updates"), config, true, fix);
            }

            @Override
            protected void done() {
                String problem = null;
                try {
                    SelfUpdate.Result result = get();
                    if (result.exitNow()) {
                        frame.dispose();
                        System.exit(0);
                    }
                    problem = result.message();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    problem = "The update to " + o.version() + " failed: " + e.getCause().getMessage()
                            + " You can download it yourself: " + UpdateCheck.RELEASES_URL;
                }
                updating = false;
                setBusy(false);
                if (session != null) {
                    show(session);
                }
                if (problem != null) {
                    if (auto) {
                        notes.setText(problem + "\n\n" + notes.getText());
                        notes.setCaretPosition(0);
                    } else {
                        JTextArea text = new JTextArea(problem, 8, 70);
                        text.setEditable(false);
                        text.setLineWrap(true);
                        JOptionPane.showMessageDialog(frame, new JScrollPane(text), "Update BiteCheck",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                }
            }
        }.execute();
    }

    private static long count(List<ModLine> lines, Verifier.Status status) {
        return lines.stream().filter(l -> l.shown().status() == status).count();
    }

    /** Shows the Steam launch options that run BiteCheck automatically, with a copy button. */
    private void showSteamSetup() {
        SteamSetupDialog.show(frame, session != null ? session.platform : new Platform(Config.empty()));
    }

    /** The same question as before the start, for the selected mod (also to change an earlier "no"). */
    private void allowSelectedFlagged() {
        ModLine line = selectedLine();
        if (line == null || line.toAllow().isEmpty()) {
            return;
        }
        if (!StartGate.ask(frame, new StartGate.Question(line.mod(), line.toAllow()), dir.resolve("state.toml"))) {
            return;
        }
        for (Planner.Row r : line.toAllow()) {
            session.state.refused().remove(r.jar().sha256());
            session.state.allowedAnyway().put(r.jar().sha256(), r.mod().id() + " - allowed by the player");
        }
        show(session.replan());
    }

    private void apply(boolean launch) {
        if (session == null) {
            return;
        }
        if (launch) {
            boolean mayStart = StartGate.askAll(frame, session, dir);
            session = session.replan();
            show(session);
            if (!mayStart) {
                try {
                    session.apply(); // keep the answers given so far
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(frame, e.getMessage(), "BiteCheck", JOptionPane.ERROR_MESSAGE);
                }
                return;
            }
        }
        setBusy(true);
        Session s = session;
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                int changes = s.apply();
                if (launch) {
                    GameStarter.start(s.platform.os());
                }
                return changes;
            }

            @Override
            protected void done() {
                try {
                    int changes = get();
                    if (launch) {
                        frame.dispose();
                        System.exit(0);
                    }
                    JOptionPane.showMessageDialog(frame, changes == 0 ? "Everything is up to date."
                            : changes + " decision(s) written for ZombieBuddy.");
                    show(s.replan());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    JOptionPane.showMessageDialog(frame, e.getCause().getMessage(), "BiteCheck",
                            JOptionPane.ERROR_MESSAGE);
                }
                setBusy(false);
            }
        }.execute();
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(Color c) {
        return String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }

    /**
     * One table line per installed mod. A mod can ship several jars (one per game version, for
     * example); the line shows the most serious of them, the tooltip lists them all.
     */
    record ModLine(JavaMods.JavaMod mod, List<Planner.Row> rows, Planner.Row shown) {

        static List<ModLine> group(List<Planner.Row> rows) {
            Map<Path, List<Planner.Row>> byMod = new LinkedHashMap<>();
            for (Planner.Row r : rows) {
                byMod.computeIfAbsent(r.mod().folder(), k -> new ArrayList<>()).add(r);
            }
            List<ModLine> lines = new ArrayList<>();
            for (List<Planner.Row> group : byMod.values()) {
                Planner.Row shown = group.get(0);
                for (Planner.Row r : group) {
                    if (rank(r) > rank(shown)) {
                        shown = r;
                    }
                }
                lines.add(new ModLine(shown.mod(), List.copyOf(group), shown));
            }
            return lines;
        }

        /** Higher is more serious. */
        private static int rank(Planner.Row r) {
            return switch (r.status()) {
                case BLOCKED, INVALID -> 5;
                case FLAGGED -> r.allowedAnyway() ? 3 : 4;
                case VERIFIED -> 0;
                default -> r.suspicious() ? (r.allowedAnyway() ? 2 : 3) : 1;
            };
        }

        /** Jars of this mod the player can still allow: not verified, not blocked, not allowed yet. */
        List<Planner.Row> toAllow() {
            return rows.stream().filter(r -> (r.status() == Verifier.Status.UNVERIFIED
                    || r.status() == Verifier.Status.FLAGGED) && !r.allowedAnyway()).toList();
        }

        String files() {
            List<String> names = rows.stream().map(r -> r.jar().path().getFileName().toString()).distinct().toList();
            return rows.size() == 1 ? names.get(0) : rows.size() + " jars (" + String.join(", ", names) + ")";
        }

        /** Each jar relative to the mod folder with its status; no local paths. */
        String tooltip() {
            if (rows.size() == 1) {
                return null;
            }
            StringBuilder t = new StringBuilder("<html>");
            for (Planner.Row r : rows) {
                Path rel = mod.folder().relativize(r.jar().path());
                t.append(escape(rel.toString().replace('\\', '/'))).append(": ").append(statusText(r))
                        .append("<br>");
            }
            return t.append("</html>").toString();
        }
    }

    private static String statusText(Planner.Row r) {
        return r.suspicious() ? "⚠ suspicious" : switch (r.status()) {
            case VERIFIED -> "✔ verified";
            case UNVERIFIED -> "? unverified";
            case FLAGGED -> "⚠ flagged";
            case BLOCKED -> "✖ blocked";
            case INVALID -> "✖ invalid";
        };
    }

    static final String ASKS = "asks you first";
    static final String NO_START = "game does not start";

    static String onStart(Planner.Row r) {
        if (r.status() == Verifier.Status.BLOCKED || r.status() == Verifier.Status.INVALID) {
            return r.mod().isLoader() ? NO_START : "blocked";
        }
        if (r.status() == Verifier.Status.VERIFIED) {
            return "loads";
        }
        if (r.allowedAnyway()) {
            return "loads (your choice)";
        }
        if (r.refused()) {
            return "off (your choice)";
        }
        return ASKS;
    }

    private static final class ModTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"Mod", "Status", "Details", "On start"};
        private transient List<ModLine> lines = List.of();

        void setLines(List<ModLine> lines) {
            this.lines = lines;
            fireTableDataChanged();
        }

        ModLine line(int index) {
            return lines.get(index);
        }

        @Override
        public int getRowCount() {
            return lines.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ModLine l = lines.get(rowIndex);
            Planner.Row r = l.shown();
            return switch (columnIndex) {
                case 0 -> l;
                case 1 -> r;
                case 2 -> (r.change() != null ? r.change().text() + " " : "")
                        + (r.allowedAnyway() ? "Allowed by you. " : r.refused() ? "Not allowed by you. " : "") + r.info();
                default -> onStart(r);
            };
        }
    }

    private static final Insets CELL = new Insets(0, 12, 0, 12);

    /** Background and row separator shared by all cells. */
    private static void cell(JComponent c, Theme t, boolean selected) {
        c.setOpaque(true);
        c.setBackground(selected ? t.selection : t.surface);
        c.setBorder(Ui.bottomLine(t.line, CELL));
    }

    /** Mod name, and below it the jar file(s) and Workshop id. */
    private static final class ModRenderer extends JPanel implements TableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final transient Theme t;
        private final JLabel name = new JLabel();
        private final JLabel sub = new JLabel();

        ModRenderer(Theme t) {
            super(new BorderLayout(0, 2));
            this.t = t;
            name.setFont(t.base.deriveFont(Font.BOLD));
            name.setForeground(t.text);
            sub.setFont(t.size(0.88f));
            sub.setForeground(t.muted);
            JPanel stack = new JPanel(new BorderLayout(0, 2));
            stack.setOpaque(false);
            stack.add(name, BorderLayout.NORTH);
            stack.add(sub, BorderLayout.SOUTH);
            add(stack, BorderLayout.CENTER);
            stack.setBorder(BorderFactory.createEmptyBorder(9, 0, 9, 0));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            cell(this, t, isSelected);
            if (value instanceof ModLine l) {
                name.setText(l.mod().name());
                String files = l.rows().size() == 1 ? l.files() : l.rows().size() + " jars";
                sub.setText(l.mod().isLoader() ? files + "  ·  mod loader, in the game folder"
                        : l.mod().workshopId() != null ? files + "  ·  Workshop " + l.mod().workshopId() : files);
            }
            return this;
        }
    }

    /** The status as a small tinted tag with symbol and word. */
    private static final class StatusRenderer extends JComponent implements TableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final transient Theme t;
        private String text = "";
        private Color fg;
        private Color tint;

        StatusRenderer(Theme t) {
            this.t = t;
            setFont(t.bold(0.92f));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            cell(this, t, isSelected);
            if (value instanceof Planner.Row r) {
                text = statusText(r);
                boolean allowed = r.allowedAnyway();
                if (r.status() == Verifier.Status.VERIFIED) {
                    fg = t.ok;
                    tint = t.okTint;
                } else if (r.status() == Verifier.Status.UNVERIFIED && !r.suspicious() || allowed) {
                    fg = t.warn;
                    tint = t.warnTint;
                } else {
                    fg = t.bad;
                    tint = t.badTint;
                }
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            Insets in = getInsets();
            g.setFont(getFont());
            Ui.paintPill(g, new Rectangle(in.left, 0, getWidth() - in.left - in.right, getHeight() - 1), text, fg, tint);
        }
    }

    /** Plain text cells: details in muted color, "On start" colored by outcome. */
    private static final class TextRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final transient Theme t;
        private final boolean details;

        TextRenderer(Theme t, boolean details) {
            this.t = t;
            this.details = details;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            cell(this, t, isSelected);
            String s = String.valueOf(value);
            setFont(details ? t.size(0.92f) : t.base);
            if (details) {
                setForeground(t.muted);
                setToolTipText(null);
            } else {
                setForeground(s.equals("blocked") || s.equals(NO_START) ? t.bad : s.equals(ASKS) ? t.warn : t.text);
            }
            setText(details ? s : capitalize(s));
            return this;
        }

        private static String capitalize(String s) {
            return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    /** Column titles: small, muted, with a line below. */
    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final transient Theme t;

        HeaderRenderer(Theme t) {
            this.t = t;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setOpaque(true);
            setBackground(t.surface);
            setForeground(t.muted);
            setFont(t.size(0.88f).deriveFont(Font.BOLD));
            setBorder(Ui.bottomLine(t.line, new Insets(8, 12, 8, 12)));
            return this;
        }
    }
}
