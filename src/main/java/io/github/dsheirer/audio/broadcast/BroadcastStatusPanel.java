/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.gui.theme.ColorContrast;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import java.awt.Color;
import java.awt.Component;
import net.miginfocom.swing.MigLayout;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Table of broadcast streams and statuses.
 */
public class BroadcastStatusPanel extends JPanel
{
    private JTable mTable;
    private JTableColumnWidthMonitor mColumnWidthMonitor;
    private JScrollPane mScrollPane;
    private BroadcastModel mBroadcastModel;
    private UserPreferences mUserPreferences;
    private String mPreferenceKey;

    /**
     * Constructs an instance
     * @param broadcastModel to access the streams
     * @param userPreferences for configuring the panel
     * @param preferenceKey to store column preferences for this panel.
     */
    public BroadcastStatusPanel(BroadcastModel broadcastModel, UserPreferences userPreferences, String preferenceKey)
    {
        mBroadcastModel = broadcastModel;
        mUserPreferences = userPreferences;
        mPreferenceKey = preferenceKey;

        init();
    }

    public JTable getTable()
    {
        return mTable;
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0 ", "[grow,fill]", "[grow,fill]"));

        mTable = new JTable(mBroadcastModel);

        DefaultTableCellRenderer renderer = (DefaultTableCellRenderer)mTable.getDefaultRenderer(String.class);
        renderer.setHorizontalAlignment(SwingConstants.CENTER);

        mTable.getColumnModel().getColumn(BroadcastModel.COLUMN_BROADCASTER_STATUS).setCellRenderer(new StatusCellRenderer());
        mTable.getColumnModel().getColumn(BroadcastModel.COLUMN_BROADCAST_SERVER_TYPE).setCellRenderer(new ServerTypeRenderer());
        mColumnWidthMonitor = new JTableColumnWidthMonitor(mUserPreferences, mTable, mPreferenceKey);

        mScrollPane = new JScrollPane(mTable);

        add(mScrollPane);
    }

    public class ServerTypeRenderer extends DefaultTableCellRenderer
    {
        public ServerTypeRenderer()
        {
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
        {
            JLabel component = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof BroadcastServerType broadcastServerType)
            {
                component.setText(broadcastServerType.toString());
                Icon icon = new Icon("empty", broadcastServerType.getIconPath());
                ImageIcon imageIcon = icon.getIcon();
                ImageIcon scaledIcon = IconModel.getScaledIcon(imageIcon, 13);
                component.setIcon(scaledIcon);
            }
            else
            {
                component.setText(null);
                component.setIcon(null);
            }

            return component;
        }
    }

    /**
     * Custom cell renderer for the broadcast state column.
     */
    public class StatusCellRenderer extends DefaultTableCellRenderer
    {
        private static final Color STATUS_GREEN = Color.GREEN;
        private static final Color STATUS_GREEN_DARK = new Color(0x2E7D32);
        private static final Color STATUS_YELLOW = Color.YELLOW;
        private static final Color STATUS_YELLOW_DARK = new Color(0x9E7C00);
        private static final Color STATUS_RED = Color.RED;
        private static final Color STATUS_RED_DARK = new Color(0xB71C1C);

        public StatusCellRenderer()
        {
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel component = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(isSelected)
            {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            }
            else
            {
                if(value instanceof BroadcastState)
                {
                    BroadcastState state = (BroadcastState)value;

                    if(state == BroadcastState.CONNECTED)
                    {
                        setStatusColors(STATUS_GREEN, STATUS_GREEN_DARK);
                    }
                    else if(state == BroadcastState.DISABLED)
                    {
                        setBackground(table.getBackground());
                        //Use the LAF's disabled-foreground so the cell reads correctly on any
                        //theme - LIGHT_GRAY is invisible on a light background.
                        Color disabled = javax.swing.UIManager.getColor("Label.disabledForeground");
                        setForeground(disabled != null ? disabled : table.getForeground());
                    }
                    else if(state == BroadcastState.INVALID_SETTINGS ||
                            state == BroadcastState.NETWORK_UNAVAILABLE)
                    {
                        setStatusColors(STATUS_YELLOW, STATUS_YELLOW_DARK);
                    }
                    else if(state.isErrorState())
                    {
                        setStatusColors(STATUS_RED, STATUS_RED_DARK);
                    }
                    else
                    {
                        setBackground(table.getBackground());
                        setForeground(table.getForeground());
                    }
                }
                else
                {
                    setForeground(table.getForeground());
                    setBackground(table.getBackground());
                }
            }

            return this;
        }

        /**
         * Applies a status background, using the muted variant on dark themes, with black or white text
         * (whichever is more readable) since the theme foreground may not contrast with the status color.
         */
        private void setStatusColors(Color light, Color dark)
        {
            Color background = ThemeManager.getInstance().isDarkMode() ? dark : light;
            setBackground(background);
            setForeground(ColorContrast.textOn(background));
        }
    }
}
