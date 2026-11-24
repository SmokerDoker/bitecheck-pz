package io.github.bitecheckpz.launcher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Hand-drawn Swing pieces for BiteCheck's windows, styled by {@link Theme}. */
final class Ui {

    private Ui() {
    }

    enum Kind {
        /** The one main action of a window. */
        PRIMARY,
        /** Outlined, for other actions. */
        SECONDARY,
        /** Text only, for side actions. */
        QUIET
    }

    /** A button with the theme's look in every state: hover, pressed, focus and disabled. */
    static final class Button extends JButton {
        private static final long serialVersionUID = 1L;
        private final Kind kind;

        Button(String text, Kind kind) {
            super(text);
            this.kind = kind;
            Theme t = Theme.get();
            setFont(kind == Kind.PRIMARY ? t.bold(1f) : t.base);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            int v = kind == Kind.PRIMARY ? 10 : 8;
            int h = kind == Kind.QUIET ? 10 : kind == Kind.PRIMARY ? 20 : 14;
            setBorder(BorderFactory.createEmptyBorder(v, h, v, h));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.get();
            Graphics2D g2 = smooth(g);
            ButtonModel m = getModel();
            boolean on = isEnabled();
            int w = getWidth();
            int h = getHeight();
            int arc = 10;
            Color fill;
            Color stroke = null;
            Color fg;
            switch (kind) {
                case PRIMARY -> {
                    fill = !on ? t.raised : m.isPressed() ? t.primary : m.isRollover() ? t.primaryHover : t.primary;
                    fg = on ? t.primaryText : t.muted;
                }
                case SECONDARY -> {
                    fill = m.isPressed() && on ? t.raised : m.isRollover() && on ? t.selection : t.surface;
                    stroke = t.line;
                    fg = on ? t.text : t.muted;
                }
                default -> {
                    fill = m.isPressed() && on ? t.raised : m.isRollover() && on ? t.selection : null;
                    fg = on ? t.text : t.muted;
                }
            }
            if (fill != null) {
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            }
            if (stroke != null) {
                g2.setColor(stroke);
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            }
            if (isFocusOwner()) {
                g2.setColor(t.accent);
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(1, 1, w - 3, h - 3, arc, arc);
            }
            g2.setFont(getFont());
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics();
            int x = (w - fm.stringWidth(getText())) / 2;
            int y = (h - fm.getHeight()) / 2 + fm.getAscent();
            if (m.isPressed() && on) {
                y++;
            }
            g2.drawString(getText(), x, y);
            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            Insets in = getInsets();
            return new Dimension(fm.stringWidth(getText()) + in.left + in.right, fm.getHeight() + in.top + in.bottom);
        }
    }

    /** A filled, rounded panel (optionally outlined). */
    static JPanel card(LayoutManager layout, Color fill, Color stroke, int arc) {
        JPanel p = new JPanel(layout) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = smooth(g);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                if (stroke != null) {
                    g2.setColor(stroke);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setBackground(fill);
        return p;
    }

    /** Draws a small rounded tag: symbol and word on a tinted background. */
    static void paintPill(Graphics g, Rectangle area, String text, Color fg, Color tint) {
        Graphics2D g2 = smooth(g);
        FontMetrics fm = g2.getFontMetrics();
        int padX = 9;
        int w = fm.stringWidth(text) + 2 * padX;
        int h = fm.getHeight() + 6;
        int x = area.x;
        int y = area.y + (area.height - h) / 2;
        g2.setColor(tint);
        g2.fillRoundRect(x, y, w, h, h, h);
        g2.setColor(fg);
        g2.drawString(text, x + padX, y + 3 + fm.getAscent());
        g2.dispose();
    }

    /** A slim scroll bar in the theme's colors, without arrow buttons. */
    static void style(JScrollPane scroll, Color track) {
        Theme t = Theme.get();
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(track);
        scroll.setBackground(track);
        for (JScrollBar bar : new JScrollBar[] {scroll.getVerticalScrollBar(), scroll.getHorizontalScrollBar()}) {
            bar.setUI(new BasicScrollBarUI() {
                @Override
                protected void configureScrollBarColors() {
                    thumbColor = t.line;
                    trackColor = track;
                }

                @Override
                protected JButton createDecreaseButton(int orientation) {
                    return zeroButton();
                }

                @Override
                protected JButton createIncreaseButton(int orientation) {
                    return zeroButton();
                }

                @Override
                protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
                    g.setColor(track);
                    g.fillRect(r.x, r.y, r.width, r.height);
                }

                @Override
                protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                    if (r.isEmpty()) {
                        return;
                    }
                    Graphics2D g2 = smooth(g);
                    g2.setColor(isThumbRollover() ? t.muted : t.line);
                    int inset = 3;
                    g2.fillRoundRect(r.x + inset, r.y + inset, r.width - 2 * inset, r.height - 2 * inset, 8, 8);
                    g2.dispose();
                }
            });
            bar.setPreferredSize(new Dimension(12, 12));
            bar.setOpaque(false);
            bar.setUnitIncrement(16);
        }
    }

    private static JButton zeroButton() {
        JButton b = new JButton();
        b.setPreferredSize(new Dimension(0, 0));
        b.setMinimumSize(new Dimension(0, 0));
        b.setMaximumSize(new Dimension(0, 0));
        return b;
    }

    /** A 1px line under a component. */
    static AbstractBorder bottomLine(Color color, Insets padding) {
        return new AbstractBorder() {
            private static final long serialVersionUID = 1L;

            @Override
            public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
                g.setColor(color);
                g.fillRect(x, y + height - 1, width, 1);
            }

            @Override
            public Insets getBorderInsets(Component c, Insets insets) {
                insets.set(padding.top, padding.left, padding.bottom + 1, padding.right);
                return insets;
            }
        };
    }

    static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Object desktop = java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (desktop instanceof java.util.Map<?, ?> hints) {
            g2.addRenderingHints(hints); // the system's own text smoothing
        } else {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }
        return g2;
    }
}
