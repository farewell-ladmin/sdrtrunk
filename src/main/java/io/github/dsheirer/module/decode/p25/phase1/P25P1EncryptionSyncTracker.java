/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

/**
 * Tracks whether the current P25 Phase 1 transmission is encrypted, from the algorithm ID of each valid HDU and LDU2
 * encryption sync.
 *
 * The first valid HDU or LDU2 of a transmission establishes the state.  After that, a single HDU or LDU2 that disagrees
 * is ignored, and the state only changes when two consecutive ones disagree.  A corrupt HDU or LDU2 can pass error
 * correction (ie the all-zero header is a valid Reed-Solomon codeword and decodes as algorithm 0x00) and report a
 * clear transmission as encrypted, so a single one is not trusted to change an established state.
 *
 * The tracker is reset at the end of each transmission.
 */
public class P25P1EncryptionSyncTracker
{
    private boolean mEstablished;
    private boolean mEncrypted;
    private boolean mChangePending;
    private boolean mConfirmed;

    /**
     * Processes the encrypted state from a valid LDU2 encryption sync.  An empty (all-zero) encryption sync is treated
     * as unencrypted, but a zero-filled LDU2 that was substituted for a lost one also contains it, so it is ignored
     * when the transmission is encrypted and can't change the state to unencrypted.
     * @param encrypted true if the LDU2 indicates encrypted audio
     * @param empty true if the encryption sync is all zeros
     * @return true if the transmission is encrypted, after processing the arguments.
     */
    public boolean process(boolean encrypted, boolean empty)
    {
        if(empty && mEstablished && mEncrypted)
        {
            return true;
        }

        return process(encrypted);
    }

    /**
     * Processes the encrypted state from a valid HDU or LDU2 encryption sync.
     * @param encrypted true if the HDU or LDU2 indicates encrypted audio
     * @return true if the transmission is encrypted, after processing the argument.
     */
    public boolean process(boolean encrypted)
    {
        if(!mEstablished)
        {
            mEstablished = true;
            mEncrypted = encrypted;
            mChangePending = false;
            mConfirmed = false;
        }
        else if(encrypted == mEncrypted)
        {
            mChangePending = false;
            mConfirmed = true;
        }
        else if(mChangePending)
        {
            //Second consecutive HDU or LDU2 that disagrees - change the state
            mEncrypted = encrypted;
            mChangePending = false;
            mConfirmed = true;
        }
        else
        {
            mChangePending = true;
        }

        return mEncrypted;
    }

    /**
     * Indicates if a valid HDU or LDU2 has established the encrypted state for the current transmission.
     */
    public boolean isEstablished()
    {
        return mEstablished;
    }

    /**
     * Indicates if the latest HDU or LDU2 disagreed with the established state and the next one decides whether the
     * state changes.
     */
    public boolean isChangePending()
    {
        return mChangePending;
    }

    /**
     * Indicates if the current transmission is encrypted.  Only meaningful when the state is established.
     */
    public boolean isEncrypted()
    {
        return mEstablished && mEncrypted;
    }

    /**
     * Indicates if the current transmission is encrypted and a second HDU or LDU2 has agreed with the one that
     * established it.  The channel state uses this, because the ENCRYPTED channel state can't change back to CALL.
     */
    public boolean isConfirmedEncrypted()
    {
        return mEstablished && mEncrypted && mConfirmed;
    }

    /**
     * Resets the tracker at the end of a transmission.
     */
    public void reset()
    {
        mConfirmed = false;
        mEstablished = false;
        mEncrypted = false;
        mChangePending = false;
    }
}
