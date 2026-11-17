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

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class P25P1EncryptionSyncTrackerTest
{
    @Test
    void firstValueEstablishesTheState()
    {
        P25P1EncryptionSyncTracker tracker = new P25P1EncryptionSyncTracker();
        assertFalse(tracker.isEstablished());
        assertFalse(tracker.isEncrypted());
        assertTrue(tracker.process(true));
        assertTrue(tracker.isEstablished());
        assertTrue(tracker.isEncrypted());

        tracker.reset();
        assertFalse(tracker.isEstablished());
        assertFalse(tracker.process(false));
        assertTrue(tracker.isEstablished());
        assertFalse(tracker.isEncrypted());
    }

    @Test
    void singleDisagreementIsIgnored()
    {
        P25P1EncryptionSyncTracker tracker = new P25P1EncryptionSyncTracker();
        tracker.process(false);
        assertFalse(tracker.process(true), "one encrypted value doesn't change a clear state");
        assertTrue(tracker.isChangePending());
        assertFalse(tracker.process(false));
        assertFalse(tracker.isChangePending());
        assertFalse(tracker.process(true), "a later single encrypted value is ignored again");

        tracker.reset();
        tracker.process(true);
        assertTrue(tracker.process(false), "one clear value doesn't change an encrypted state");
        assertTrue(tracker.process(true));
    }

    @Test
    void twoConsecutiveDisagreementsChangeTheState()
    {
        P25P1EncryptionSyncTracker tracker = new P25P1EncryptionSyncTracker();
        tracker.process(false);
        assertFalse(tracker.process(true));
        assertTrue(tracker.process(true));
        assertFalse(tracker.isChangePending());
        assertTrue(tracker.process(false));
        assertFalse(tracker.process(false));
    }
}
