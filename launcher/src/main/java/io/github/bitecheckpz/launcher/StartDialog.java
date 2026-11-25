package io.github.bitecheckpz.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * The window before the game starts: every mod that is not verified and not decided yet, each with a
 * checkbox (none ticked), and one button to start the game. Ticked mods load; the others stay off for
 * this start only and are shown again next time. Flagged or risky mods still need the second
 * confirmation after this window (see {@link StartGate}).
 *
 * <p>Always on top: started from Steam, BiteCheck's window must not hide behind Steam or the desktop,
 * or the player only sees that the game does not start.
 */
final class StartDialog {

    private StartDialog() {
    }

    /**
     * Shows the window and returns the questions the player ticked, or empty if the window was
     * closed without starting.
     */
    static Optional<List<StartGate.Question>> show(Component parent, List<StartGate.Question> questions) {
        Theme th = Theme.get();
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        if (owner == null && parent instanceof Window w) {
            owner = w;
        }
        JDialog dialog = new JDialog(owner, "BiteCheck: new Java mods", JDialog.ModalityType.APPLICATION_MODAL);
        StartGate.onTop(dialog);

        JPanel body = new JPanel();
        body.setBackground(th.window);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(18, 22, 10, 22));

        JLabel title = new JLabel(questions.size() == 1 ? "1 Java mod is not verified yet"
                : questions.size() + " Java mods are not verified yet");
        title.setFont(th.bold(1.3f));
        title.setForeground(th.text);
        add(body, title, 6);
        add(body, html(th, "Java mods can access your whole computer. Tick the ones you trust. The others stay "
                + "off for this start, and BiteCheck asks again next time.", th.muted), 14);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(th.surface);
        list.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        Map<StartGate.Question, JCheckBox> boxes = new LinkedHashMap<>();
        for (StartGate.Question q : questions) {
            JCheckBox box = new JCheckBox(q.mod().name() + (q.mod().isLoader() ? "  (needed to start the game)" : ""));
            box.setFont(th.bold(1f));
            box.setForeground(th.text);
            box.setOpaque(false);
            box.setFocusPainted(false);
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            boxes.put(q, box);
            list.add(box);
            JLabel detail = html(th, detail(q), q.risky() || warnsChange(q) ? th.warn : th.muted, 470);
            detail.setBorder(BorderFactory.createEmptyBorder(0, 26, 8, 0));
            list.add(detail);
        }
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(th.line));
        scroll.getViewport().setBackground(th.surface);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        int height = Math.min(420, list.getPreferredSize().height + 4);
        scroll.setPreferredSize(new Dimension(560, height));
        Ui.style(scroll, th.surface);
        add(body, scroll, 12);

        JLabel hint = html(th, "", th.warn);
        add(body, hint, 4);

        List<StartGate.Question> chosen = new ArrayList<>();
        boolean[] started = {false};
        Ui.Button start = new Ui.Button("Start game", Ui.Kind.PRIMARY);
        Ui.Button all = new Ui.Button("Tick all", Ui.Kind.QUIET);
        all.addActionListener(e -> boxes.values().forEach(b -> b.setSelected(true)));
        start.addActionListener(e -> {
            for (Map.Entry<StartGate.Question, JCheckBox> en : boxes.entrySet()) {
                if (en.getKey().mod().isLoader() && !en.getValue().isSelected()) {
                    hint.setText("<html>" + LauncherWindow.escape(en.getKey().mod().name()) + " loads every Java mod. "
                            + "Without it the game does not start: tick it, or close this window.</html>");
                    dialog.pack();
                    return;
                }
            }
            boxes.forEach((q, b) -> {
                if (b.isSelected()) {
                    chosen.add(q);
                }
            });
            started[0] = true;
            dialog.dispose();
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                dialog.toFront();
                start.requestFocusInWindow();
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(all);
        buttons.add(start);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(buttons);

        dialog.getContentPane().setBackground(th.window);
        dialog.getContentPane().add(body, BorderLayout.CENTER);
        dialog.getRootPane().setDefaultButton(start);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true); // modal: returns when the player started or closed it
        return started[0] ? Optional.of(chosen) : Optional.empty();
    }

    /** What BiteCheck knows about the mod: why it asks, and the update hint if a trusted jar changed. */
    private static String detail(StartGate.Question q) {
        Planner.Row row = q.rows().stream().filter(Planner.Row::needsPermission).findFirst().orElse(q.first());
        String info = row.info() == null ? "" : row.info();
        String found = after(info, "local scan found: ");
        String notes = after(info, "local scan, check: ");
        StringBuilder s = new StringBuilder();
        if (row.status() == io.github.bitecheckpz.core.Verifier.Status.FLAGGED) {
            s.append("Flagged as possibly harmful by the BiteCheck registry: ").append(LauncherWindow.escape(info));
        } else if (q.risky()) {
            s.append("Not reviewed. The scan found risky code").append(found == null ? "" : ": " + LauncherWindow.escape(found));
        } else {
            s.append("Not reviewed. ").append(notes == null ? "The scan found nothing risky."
                    : "The scan notes: " + LauncherWindow.escape(notes));
        }
        if (q.risky()) {
            s.append("<br>Needs a second confirmation.");
        }
        for (Planner.Row r : q.rows()) {
            if (r.change() != null) {
                String text = LauncherWindow.escape(r.change().text());
                s.append("<br>").append(r.change().kind() == Planner.ChangeKind.WITHOUT_UPDATE ? "⚠ " + text : text);
                break;
            }
        }
        return s.toString();
    }

    /** The text after {@code marker} up to the next ";", or null. */
    private static String after(String info, String marker) {
        int i = info.indexOf(marker);
        if (i < 0) {
            return null;
        }
        String rest = info.substring(i + marker.length());
        int end = rest.indexOf(';');
        return (end < 0 ? rest : rest.substring(0, end)).strip();
    }

    private static boolean warnsChange(StartGate.Question q) {
        return q.rows().stream().anyMatch(r -> r.change() != null && r.change().kind() == Planner.ChangeKind.WITHOUT_UPDATE);
    }

    private static JLabel html(Theme th, String text, java.awt.Color color) {
        return html(th, text, color, 540);
    }

    private static JLabel html(Theme th, String text, java.awt.Color color, int width) {
        JLabel l = new JLabel("<html><div style='width:" + width + "px'>" + text + "</div></html>");
        l.setFont(th.base);
        l.setForeground(color);
        return l;
    }

    private static void add(JPanel body, JComponent c, int gap) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(c);
        body.add(Box.createVerticalStrut(gap));
    }
}
