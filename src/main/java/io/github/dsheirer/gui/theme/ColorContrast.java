/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.gui.theme;

import java.awt.Color;

/**
 * Utility methods for keeping foreground colors readable against the current theme's backgrounds.
 *
 * Contrast is measured with the WCAG relative luminance contrast ratio (1.0 to 21.0).
 */
public final class ColorContrast
{
    /**
     * Minimum contrast ratio for user-colored text (WCAG AA for normal text).
     */
    public static final double MINIMUM_TEXT_CONTRAST = 4.5;

    private static final double BLEND_STEP = 0.05;

    private ColorContrast()
    {
    }

    /**
     * Relative luminance of the color per WCAG 2.x.
     * @param color to measure
     * @return luminance in range 0.0 (black) to 1.0 (white)
     */
    public static double luminance(Color color)
    {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(int channel)
    {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /**
     * Contrast ratio between two colors.
     * @return ratio in range 1.0 (identical luminance) to 21.0 (black on white)
     */
    public static double contrast(Color a, Color b)
    {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * Returns the foreground color unchanged when it already has sufficient contrast against the background,
     * otherwise blends it toward white (on dark backgrounds) or black (on light backgrounds) just far enough to
     * reach the minimum text contrast.  This keeps the hue of user-chosen colors (e.g. alias colors) while making
     * them legible on both light and dark themes.
     *
     * @param foreground color requested for the text
     * @param background color the text is drawn on
     * @return readable foreground color
     */
    public static Color readable(Color foreground, Color background)
    {
        if(foreground == null || background == null || contrast(foreground, background) >= MINIMUM_TEXT_CONTRAST)
        {
            return foreground;
        }

        Color target = luminance(background) < 0.5 ? Color.WHITE : Color.BLACK;

        for(double fraction = BLEND_STEP; fraction < 1.0; fraction += BLEND_STEP)
        {
            Color blended = blend(foreground, target, fraction);

            if(contrast(blended, background) >= MINIMUM_TEXT_CONTRAST)
            {
                return blended;
            }
        }

        return target;
    }

    /**
     * Black or white, whichever has more contrast against the background.  Use for text drawn on
     * fixed status colors (e.g. green/yellow/red cells) where the theme's foreground may be unreadable.
     */
    public static Color textOn(Color background)
    {
        return contrast(Color.BLACK, background) >= contrast(Color.WHITE, background) ? Color.BLACK : Color.WHITE;
    }

    private static Color blend(Color from, Color to, double fraction)
    {
        return new Color(
            (int)Math.round(from.getRed() + (to.getRed() - from.getRed()) * fraction),
            (int)Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * fraction),
            (int)Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * fraction));
    }
}
