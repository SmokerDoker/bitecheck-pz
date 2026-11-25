package io.github.bitecheckpz.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;

/** The "Steam setup…" window: the launch options line with a copy button and a short explanation. */
final class SteamSetupDialog {

    private static final int TEXT_WIDTH = 540;

    private SteamSetupDialog() {
    }

    static void show(JFrame owner, Platform platform) {
        Platform.Os os = platform.os();
        Optional<String> current = SteamSetup.currentLaunchOptions(platform).stream().findFirst();
        boolean setUp = SteamSetup.isSetUp(current);
        boolean jumpstart = current.map(SteamSetup::hasJumpstart).orElse(false);
        boolean jumpstartAgent = SteamSetup.jumpstartAgent(platform, current);
        String loader = jumpstartAgent ? "Jumpstart" : "ZombieBuddy";
        String line = SteamSetup.launchOptions(os, SteamSetup.script(os), current, jumpstartAgent);

        Theme th = Theme.get();
        JDialog dialog = new JDialog(owner, "Steam setup", true);
        JPanel body = new JPanel();
        body.setBackground(th.window);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(16, 20, 8, 20));

        JLabel title = new JLabel("Check your mods every time you start the game");
        title.setFont(th.bold(1.3f));
        title.setForeground(th.text);
        add(body, title, 6);

        JLabel status = html(setUp
                ? "✔ Already set up. BiteCheck runs whenever you start Project Zomboid from Steam."
                : "Not set up yet. Right now BiteCheck only checks when you open it yourself.");
        status.setForeground(setUp ? th.ok : th.warn);
        status.setFont(th.bold(1f));
        add(body, status, 14);

        if (setUp) {
            add(body, html("Your launch options in Steam:"), 6);
        } else {
            add(body, html("<b>1.</b>&nbsp; In Steam, right-click <b>Project Zomboid</b> and choose <b>Properties…</b>"), 6);
            add(body, html("<b>2.</b>&nbsp; Under <b>General → Launch Options</b>, replace the text with this line:"), 6);
        }

        JTextArea command = new JTextArea(line);
        command.setEditable(false);
        command.setLineWrap(true);
        command.setFont(new Font(Font.MONOSPACED, Font.PLAIN, th.base.getSize()));
        command.setBackground(th.surface);
        command.setForeground(th.text);
        command.setSelectionColor(th.selection);
        command.setSelectedTextColor(th.text);
        command.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(th.line),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        // monospaced: the number of lines follows from the columns, so the area is never cut off
        int columns = Math.max(20, TEXT_WIDTH / command.getFontMetrics(command.getFont()).charWidth('m'));
        command.setColumns(columns);
        command.setRows((line.length() + columns - 1) / columns);
        JButton copy = new Ui.Button("Copy", Ui.Kind.SECONDARY);
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(line), null);
            copy.setText("Copied ✔");
            Timer reset = new Timer(2000, t -> copy.setText("Copy"));
            reset.setRepeats(false);
            reset.start();
        });
        JPanel copyColumn = new JPanel(new BorderLayout());
        copyColumn.setOpaque(false);
        copyColumn.add(copy, BorderLayout.NORTH);
        JPanel commandRow = new JPanel(new BorderLayout(8, 0));
        commandRow.setOpaque(false);
        commandRow.add(command, BorderLayout.CENTER);
        commandRow.add(copyColumn, BorderLayout.EAST);
        commandRow.setBorder(BorderFactory.createEmptyBorder(0, setUp ? 0 : 18, 0, 0));
        commandRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, commandRow.getPreferredSize().height));
        add(body, commandRow, 6);

        if (!setUp) {
            add(body, html("<b>3.</b>&nbsp; Close the window and start the game from Steam as usual."), 14);
        } else {
            body.add(Box.createVerticalStrut(8));
        }

        JPanel separator = new JPanel();
        separator.setBackground(th.line);
        separator.setPreferredSize(new Dimension(TEXT_WIDTH, 1));
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        add(body, separator, 10);

        add(body, note("<b>How it works:</b> Steam starts BiteCheck first. It checks your Java mods, tells "
                + loader + " which ones may load, then starts the game. If BiteCheck itself fails, the game "
                + "still starts and " + loader + " asks about new mods as before."), 8);
        if (jumpstart) {
            add(body, note("<b>Jumpstart</b>&nbsp;is in your launch options too. It runs first and only updates "
                    + "itself; BiteCheck checks your mods right after that, before the game loads any of them."), 8);
        }
        if (os == Platform.Os.WINDOWS) {
            add(body, note("While you play, a small console window stays open. That is BiteCheck waiting "
                    + "for the game to close; you can ignore it."), 8);
        }
        if (current.isPresent() && !setUp) {
            add(body, note("Your current launch options are kept in the line above."), 8);
        }
        add(body, note(jumpstartAgent
                ? "<b>To undo:</b> remove BiteCheck's part, everything up to and including <code>"
                        + SteamSetup.WRAP + "</code>. Keep <code>%command%</code> and what follows."
                : "<b>To undo:</b> put back your old launch options. For ZombieBuddy alone that is "
                        + "<code>" + SteamSetup.zombieBuddyOption(os) + "</code>"), 4);

        JButton close = new Ui.Button("Close", setUp ? Ui.Kind.PRIMARY : Ui.Kind.SECONDARY);
        close.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBackground(th.window);
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 20, 16, 20));
        buttons.add(close);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(th.window);
        content.add(body, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(setUp ? close : copy);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static JLabel html(String text) {
        JLabel label = new JLabel();
        label.setForeground(Theme.get().text);
        Font f = Theme.get().base;
        // the label's own font for the HTML too, or bold words are measured with another font and overlap
        label.setText("<html><body style='width: " + TEXT_WIDTH + "px; font-family: \"" + f.getFamily()
                + "\"; font-size: " + f.getSize() + "pt'>" + text + "</body></html>");
        return label;
    }

    private static JLabel note(String text) {
        JLabel label = html(text);
        label.setForeground(Theme.get().muted);
        return label;
    }

    private static void add(JPanel body, Component c, int gapBelow) {
        if (c instanceof javax.swing.JComponent j) {
            j.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        body.add(c);
        body.add(Box.createVerticalStrut(gapBelow));
    }
}
