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
package io.github.dsheirer.module.decode.p25.audio;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.codec.mbe.ImbeAudioModule;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.dsp.gain.NonClippingGain;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase1.P25P1EncryptionSyncTracker;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDULCMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDUMessage;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;

public class P25P1AudioModule extends ImbeAudioModule
{
    private final P25P1EncryptionSyncTracker mEncryptionTracker = new P25P1EncryptionSyncTracker();

    private SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private List<LDUMessage> mCachedLDUMessages = new ArrayList<>();

    public P25P1AudioModule(UserPreferences userPreferences, AliasList aliasList)
    {
        super(userPreferences, aliasList);
    }

    @Override
    protected int getTimeslot()
    {
        return 0;
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchStateListener;
    }

    @Override
    public void reset()
    {
        getIdentifierCollection().clear();
    }

    @Override
    public void start()
    {
    }

    /**
     * Processes call header (HDU) and voice frame (LDU1/LDU2) messages to decode audio and to determine the
     * encrypted audio status of a call event. Only the HDU and LDU2 messages convey encrypted call status. If an
     * LDU1 message is received without a preceding HDU message, then the LDU1 message is cached until the first
     * LDU2 message is received and the encryption state can be determined. Both the LDU1 and the LDU2 message are
     * then processed for audio if the call is unencrypted.
     *
     * The encryption state follows every valid HDU and LDU2 through the encryption sync tracker, so that a single
     * corrupt HDU or LDU2 that reports a clear transmission as encrypted doesn't mute the rest of it.  While one HDU
     * or LDU2 disagrees with the established state, voice frames are held until the next LDU2 decides, so that no
     * audio is lost when the state stands and no encrypted audio is played when it changes.  The state is reset by
     * the terminator (TDU/TDULC) at the end of each transmission.
     */
    public void receive(IMessage message)
    {
        if(hasAudioCodec())
        {
            if(message instanceof TDUMessage || message instanceof TDULCMessage)
            {
                mEncryptionTracker.reset();
                mCachedLDUMessages.clear();
            }
            else if(message instanceof HDUMessage hdu)
            {
                if(hdu.isValid())
                {
                    //The HDU starts a new transmission.  Voice frames that are still held belong to the previous one.
                    mCachedLDUMessages.clear();
                    mEncryptionTracker.process(hdu.getHeaderData().isEncryptedAudio());
                }
            }
            else if(message instanceof LDU1Message ldu1)
            {
                if(isEncryptionStateDecided())
                {
                    processAudio(ldu1);
                }
                else
                {
                    //When we receive an LDU1 message without first receiving the HDU message, cache the LDU1 Message
                    //until we can determine the encrypted call state from the next LDU2 message
                    mCachedLDUMessages.add(ldu1);
                }
            }
            else if(message instanceof LDU2Message ldu2)
            {
                if(ldu2.getEncryptionSyncParameters().isValid())
                {
                    mEncryptionTracker.process(ldu2.getEncryptionSyncParameters().isEncryptedAudio(),
                            ldu2.getEncryptionSyncParameters().isEmpty());
                }

                if(isEncryptionStateDecided())
                {
                    processCachedAudio();
                    processAudio(ldu2);
                }
                else
                {
                    mCachedLDUMessages.add(ldu2);
                }
            }
        }
    }

    /**
     * Indicates if the encryption state of the transmission is established and isn't waiting on the next HDU or LDU2
     * to decide a change.
     */
    private boolean isEncryptionStateDecided()
    {
        return mEncryptionTracker.isEstablished() && !mEncryptionTracker.isChangePending();
    }

    /**
     * Processes and clears the cached voice frames.
     */
    private void processCachedAudio()
    {
        for(LDUMessage cachedLdu : mCachedLDUMessages)
        {
            processAudio(cachedLdu);
        }

        mCachedLDUMessages.clear();
    }

    /**
     * Processes an audio packet by decoding the IMBE audio frames and rebroadcasting them as PCM audio packets.
     */
    private void processAudio(LDUMessage ldu)
    {
        if(!mEncryptionTracker.isEncrypted())
        {
            for(byte[] frame : ldu.getIMBEFrames())
            {
                addAudio(getAudioCodec().getAudio(frame));
            }
        }
        else
        {
            //Encrypted audio processing not implemented
        }
    }

    /**
     * Wrapper for squelch state to process end of call actions.  At call end the encrypted call state established
     * flag is reset so that the encrypted audio state for the next call can be properly detected and we send an
     * END audio packet so that downstream processors like the audio recorder can properly close out a call sequence.
     */
    public class SquelchStateListener implements Listener<SquelchStateEvent>
    {
        @Override
        public void receive(SquelchStateEvent event)
        {
            if(event.getSquelchState() == SquelchState.SQUELCH)
            {
                //A transmission that ended with a single empty (all-zero) encryption sync as its only one is played
                //as unencrypted, as it was before the encryption sync tracker.  Systems that send an all-zero
                //encryption sync with unencrypted audio would otherwise lose short late-entry transmissions.
                if(mEncryptionTracker.isEmptySyncOnly())
                {
                    processCachedAudio();
                }

                closeAudioSegment();
                mEncryptionTracker.reset();
                mCachedLDUMessages.clear();
            }
        }
    }
}
