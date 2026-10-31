package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Verifier;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
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

/** The BiteCheck window: shows every installed Java mod with its status and starts the game. */
final class LauncherWindow {

    private static final Color GREEN = new Color(0x1E7B34);
    private static final Color AMBER = new Color(0x9A6700);
    private static final Color RED = new Color(0xC62828);
    private static final Color DARK_RED = new Color(0x7F0000);

    private final Path dir;
    private final JFrame frame = new JFrame("BiteCheck");
    private final JLabel summary = new JLabel("Checking your Java mods…");
    private final ModTableModel model = new ModTableModel();
    private final JTable table = new JTable(model);
    private final JTextArea notes = new JTextArea(4, 60);
    private final JButton allowFlagged = new JButton("Allow anyway…");
    private final JButton refresh = new JButton("Check again");
    private final JButton applyOnly = new JButton("Apply only");
    private final JButton start = new JButton("Start Project Zomboid");
    private Session session;

    private LauncherWindow(Path dir) {
        this.dir = dir;
    }

    static void open(Path dir) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
                // keep the default look
            }
            new LauncherWindow(dir).show();
        });
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel header = new JPanel(new BorderLayout(4, 4));
        JLabel title = new JLabel("BiteCheck - verified Java mods for Project Zomboid");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4f));
        header.add(title, BorderLayout.NORTH);
        header.add(summary, BorderLayout.CENTER);
        JLabel legend = new JLabel("<html><body style='width: 620px'>Verified mods load without a question. Unverified mods are scanned locally; "
                + "ZombieBuddy warns you in game and asks before loading each one. Suspicious, flagged and blocked "
                + "mods are not loaded. The local scan is a quick check, not a review.</html>");
        legend.setForeground(Color.DARK_GRAY);
        header.add(legend, BorderLayout.SOUTH);
        content.add(header, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(table.getRowHeight() + 6);
        table.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(1).setPreferredWidth(170);
        table.getColumnModel().getColumn(2).setPreferredWidth(95);
        table.getColumnModel().getColumn(3).setPreferredWidth(250);
        table.getColumnModel().getColumn(4).setPreferredWidth(170);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(880, 300));

        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setFont(summary.getFont());
        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(tableScroll, BorderLayout.CENTER);
        center.add(new JScrollPane(notes), BorderLayout.SOUTH);
        content.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(allowFlagged);
        buttons.add(refresh);
        buttons.add(applyOnly);
        buttons.add(start);
        content.add(buttons, BorderLayout.SOUTH);
        frame.getRootPane().setDefaultButton(start);

        allowFlagged.addActionListener(e -> allowSelectedFlagged());
        refresh.addActionListener(e -> check());
        applyOnly.addActionListener(e -> apply(false));
        start.addActionListener(e -> apply(true));

        frame.setContentPane(content);
        frame.pack();
        frame.setSize(Math.min(frame.getWidth(), 960), frame.getHeight());
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        check();
    }

    private void setBusy(boolean busy) {
        refresh.setEnabled(!busy);
        applyOnly.setEnabled(!busy && session != null);
        start.setEnabled(!busy && session != null);
        allowFlagged.setEnabled(false);
        if (!busy) {
            updateButtons();
        }
    }

    private void updateButtons() {
        Planner.Row row = selectedRow();
        allowFlagged.setEnabled(row != null && row.needsPermission() && !row.allowedAnyway());
    }

    private Planner.Row selectedRow() {
        int i = table.getSelectedRow();
        return session == null || i < 0 ? null : session.rows.get(table.convertRowIndexToModel(i));
    }

    private void check() {
        setBusy(true);
        summary.setText("Checking your Java mods and the registry…");
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
                    summary.setText("Check failed.");
                    notes.setText(String.valueOf(e.getCause().getMessage()));
                }
                setBusy(false);
            }
        }.execute();
    }

    private void show(Session s) {
        session = s;
        model.setRows(s.rows);
        long verified = count(s.rows, Verifier.Status.VERIFIED);
        long suspicious = s.rows.stream().filter(Planner.Row::suspicious).count();
        long unverified = count(s.rows, Verifier.Status.UNVERIFIED) - suspicious;
        long flagged = count(s.rows, Verifier.Status.FLAGGED);
        long blocked = count(s.rows, Verifier.Status.BLOCKED);
        String indexText = s.index.index()
                .map(i -> "registry index from " + i.generatedAt().toString().replace('T', ' ').replace("Z", " UTC"))
                .orElse("registry index not available");
        summary.setText(s.mods.size() + " Java mod(s): " + verified + " verified, " + unverified + " unverified, "
                + suspicious + " suspicious, " + flagged + " flagged, " + blocked + " blocked - " + indexText);
        StringBuilder text = new StringBuilder();
        text.append("Game: ").append(s.platform.gameDir().map(Path::toString).orElse("not found")).append('\n');
        text.append("ZombieBuddy approvals: ").append(s.approvals.file()).append('\n');
        s.notes.forEach(n -> text.append("Note: ").append(n).append('\n'));
        if (s.mods.isEmpty()) {
            text.append("No Java mods found. Lua mods are loaded by the game itself and are not affected by BiteCheck.\n");
        }
        notes.setText(text.toString().strip());
        notes.setCaretPosition(0);
    }

    private static long count(List<Planner.Row> rows, Verifier.Status status) {
        return rows.stream().filter(r -> r.status() == status).count();
    }

    private void allowSelectedFlagged() {
        Planner.Row row = selectedRow();
        if (row == null || !row.needsPermission()) {
            return;
        }
        String why = row.status() == Verifier.Status.FLAGGED
                ? "has been <b>flagged</b> as possibly harmful by the BiteCheck registry:"
                : "is not reviewed and BiteCheck's <b>local scan</b> found risky code:";
        int first = JOptionPane.showConfirmDialog(frame,
                "<html><b>" + escape(row.mod().name()) + "</b> " + why + "<br><br>"
                        + escape(row.info()) + "<br><br>Java mods can access your whole computer.<br>"
                        + "Only allow it if you trust the author and know why the mod needs this.<br>"
                        + "Do you really want to allow this mod?</html>",
                "Risky mod", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (first != JOptionPane.YES_OPTION) {
            return;
        }
        String typed = JOptionPane.showInputDialog(frame,
                "<html>To confirm, type <b>ALLOW</b>. You can undo this by deleting<br>" + escape(
                        dir.resolve("state.toml").toString()) + "</html>",
                "Confirm", JOptionPane.WARNING_MESSAGE);
        if (!"ALLOW".equals(typed == null ? null : typed.strip())) {
            return;
        }
        session.state.allowedAnyway().put(row.jar().sha256(), row.mod().id() + " - allowed by the player");
        show(session.replan());
    }

    private void apply(boolean launch) {
        if (session == null) {
            return;
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

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class ModTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"Mod", "File", "Status", "Details", "On start"};
        private transient List<Planner.Row> rows = List.of();

        void setRows(List<Planner.Row> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
            Planner.Row r = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> r.mod().name() + (r.mod().workshopId() != null ? "  (" + r.mod().workshopId() + ")" : "");
                case 1 -> r.jar().path().getFileName().toString();
                case 2 -> r;
                case 3 -> r.allowedAnyway() ? "allowed by you: " + r.info() : r.info();
                default -> onStart(r);
            };
        }

        private static String onStart(Planner.Row r) {
            return switch (r.status()) {
                case VERIFIED -> "loads";
                case BLOCKED -> "blocked";
                case FLAGGED -> r.allowedAnyway() ? "loads (your choice)" : "blocked";
                default -> r.suspicious() ? (r.allowedAnyway() ? "loads (your choice)" : "blocked")
                        : Boolean.TRUE.equals(r.currentDecision()) && r.action() != Planner.Action.ASK_IN_GAME
                        ? "loads (approved before)"
                        : Boolean.FALSE.equals(r.currentDecision()) ? "blocked (your choice)" : "ZombieBuddy asks";
            };
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof Planner.Row r) {
                Verifier.Status status = r.status();
                setText(r.suspicious() ? "⚠ suspicious" : switch (status) {
                    case VERIFIED -> "✔ verified";
                    case UNVERIFIED -> "? unverified";
                    case FLAGGED -> "⚠ flagged";
                    case BLOCKED -> "✖ blocked";
                    case INVALID -> "✖ invalid";
                });
                if (!isSelected) {
                    c.setForeground(r.suspicious() ? RED : switch (status) {
                        case VERIFIED -> GREEN;
                        case UNVERIFIED -> AMBER;
                        case FLAGGED -> RED;
                        default -> DARK_RED;
                    });
                }
                setFont(getFont().deriveFont(Font.BOLD));
            }
            return c;
        }
    }
}
