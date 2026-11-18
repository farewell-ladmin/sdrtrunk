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
    void emptySyncDoesNotChangeAnEncryptedState()
    {
        P25P1EncryptionSyncTracker tracker = new P25P1EncryptionSyncTracker();
        tracker.process(true, false);
        assertTrue(tracker.process(false, true));
        assertTrue(tracker.process(false, true));
        assertTrue(tracker.process(false, true), "any number of empty encryption syncs are ignored");
        assertFalse(tracker.isChangePending());

    }

    @Test
    void singleEmptySyncDoesNotEstablishClear()
    {
        P25P1EncryptionSyncTracker tracker = new P25P1EncryptionSyncTracker();
        assertFalse(tracker.isEmptySyncOnly());
        assertFalse(tracker.process(false, true));
        assertFalse(tracker.isEstablished(), "one empty encryption sync doesn't establish the state");
        assertTrue(tracker.isEmptySyncOnly());
        assertTrue(tracker.process(true, false), "the encrypted sync that follows establishes it");
        assertTrue(tracker.isEncrypted());
        assertFalse(tracker.isEmptySyncOnly());

        //Two consecutive empty syncs establish clear
        tracker.reset();
        tracker.process(false, true);
        assertFalse(tracker.process(false, true));
        assertTrue(tracker.isEstablished());
        assertFalse(tracker.isEncrypted());

        //An empty sync agrees with an established clear state
        assertFalse(tracker.process(false, true));
        assertFalse(tracker.isChangePending());
    }

    @Test
    void singleEmptySyncDoesNotSettlePendingChange()
    {
        P25P1EncryptionSyncTracker tracker = new P25P1EncryptionSyncTracker();
        tracker.process(false);
        tracker.process(true, false);
        assertTrue(tracker.isChangePending());
        assertFalse(tracker.process(false, true));
        assertTrue(tracker.isChangePending(), "one empty sync leaves the change pending");
        assertTrue(tracker.process(true, false), "the second encrypted sync changes the state");

        //Two consecutive empty syncs settle the pending change as clear
        tracker.reset();
        tracker.process(false);
        tracker.process(true, false);
        tracker.process(false, true);
        assertFalse(tracker.process(false, true));
        assertFalse(tracker.isChangePending());
        assertFalse(tracker.isEncrypted());
    }

    @Test
    void emptyHeaderIsNotEvidenceOfEncryption()
    {
        P25P1EncryptionSyncTracker tracker = new P25P1EncryptionSyncTracker();
        tracker.processEmptyHeader();
        assertFalse(tracker.isEstablished(), "an empty header doesn't establish the state");
        assertFalse(tracker.isEncrypted());
        assertFalse(tracker.process(false), "the clear sync that follows establishes it");
        assertTrue(tracker.isEstablished());
        assertFalse(tracker.isChangePending());

        //It doesn't confirm or change an encrypted state
        tracker.reset();
        tracker.process(true);
        tracker.processEmptyHeader();
        assertTrue(tracker.isEncrypted());
        assertFalse(tracker.isConfirmedEncrypted());
        assertFalse(tracker.isChangePending());

        //It holds an established clear state as pending until the next sync decides, and can't change it
        tracker.reset();
        tracker.process(false);
        tracker.processEmptyHeader();
        tracker.processEmptyHeader();
        assertTrue(tracker.isChangePending());
        assertFalse(tracker.isEncrypted());
        assertFalse(tracker.process(false));
        assertFalse(tracker.isChangePending());

        //A transmission with an empty header isn't an empty-sync-only transmission
        tracker.reset();
        tracker.processEmptyHeader();
        tracker.process(false, true);
        assertFalse(tracker.isEmptySyncOnly());
        tracker.reset();
        tracker.process(false, true);
        assertTrue(tracker.isEmptySyncOnly());
    }

    @Test
    void encryptedIsConfirmedBySecondAgreement()
    {
        P25P1EncryptionSyncTracker tracker = new P25P1EncryptionSyncTracker();
        tracker.process(true);
        assertTrue(tracker.isEncrypted());
        assertFalse(tracker.isConfirmedEncrypted(), "one encrypted value is not confirmed");
        tracker.process(true);
        assertTrue(tracker.isConfirmedEncrypted());

        tracker.reset();
        tracker.process(false);
        tracker.process(true);
        assertFalse(tracker.isConfirmedEncrypted());
        tracker.process(true);
        assertTrue(tracker.isConfirmedEncrypted(), "a change to encrypted by two values is confirmed");
        tracker.reset();
        assertFalse(tracker.isConfirmedEncrypted());
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
