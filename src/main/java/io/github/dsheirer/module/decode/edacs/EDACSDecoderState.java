/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.edacs.channel.EDACSChannel;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.util.ThreadPool;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EDACSDecoderState extends DecoderState implements IMessageListener
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSDecoderState.class);
    private DecodeConfigEDACS mLcnFrequencies;
    private long mLastCcFrequency = 0;
    private long mLastMessageTime = 0;
    private int mCurrentFreqIndex = 0;
    private static final long ROTATION_DELAY_MS = 15000;
    private ScheduledFuture<?> mRotationTimer;

    public EDACSDecoderState()
    {
    }

    @Override
    public void start()
    {
        super.start();
        mLastMessageTime = System.currentTimeMillis();
        startRotationTimer();
    }

    @Override
    public void stop()
    {
        super.stop();
        stopRotationTimer();
    }

    private void startRotationTimer()
    {
        if(mRotationTimer == null)
        {
            mRotationTimer = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::checkRotation,
                ROTATION_DELAY_MS, ROTATION_DELAY_MS / 3, TimeUnit.MILLISECONDS);
        }
    }

    private void stopRotationTimer()
    {
        if(mRotationTimer != null)
        {
            mRotationTimer.cancel(true);
            mRotationTimer = null;
        }
    }

    private void checkRotation()
    {
        if(mLcnFrequencies != null && System.currentTimeMillis() - mLastMessageTime > ROTATION_DELAY_MS)
        {
            long[] freqs = mLcnFrequencies.getFrequencies();
            //Find next non-zero frequency
            for(int attempts = 0; attempts < freqs.length; attempts++)
            {
                mCurrentFreqIndex = (mCurrentFreqIndex + 1) % freqs.length;
                long nextFreq = freqs[mCurrentFreqIndex];
                if(nextFreq > 0 && nextFreq != getCurrentFrequency())
                {
                    mLog.info("EDACS rotating to frequency index " + mCurrentFreqIndex + " -> " + nextFreq + " Hz");
                    getIdentifierCollection().update(FrequencyConfigurationIdentifier.create(nextFreq));
                    mLastMessageTime = System.currentTimeMillis();
                    break;
                }
            }
        }
    }

    public void setLcnFrequencies(DecodeConfigEDACS config)
    {
        mLcnFrequencies = config;
    }

    private void updateControlChannel(int ccLcn)
    {
        if(mLcnFrequencies != null)
        {
            long newFreq = mLcnFrequencies.getFrequency(ccLcn);
            if(newFreq > 0 && newFreq != mLastCcFrequency)
            {
                mLastCcFrequency = newFreq;
                mLog.info("EDACS CC LCN " + ccLcn + " -> " + newFreq + " Hz - retuning");
                getIdentifierCollection().update(FrequencyConfigurationIdentifier.create(newFreq));
                setCurrentChannel(new EDACSChannel(ccLcn));
            }
        }
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.EDACS;
    }

    @Override
    public void receive(IMessage message)
    {
        if(message.isValid())
        {
            if(message instanceof EDACSMessage edacs)
            {
                mLastMessageTime = System.currentTimeMillis();

                if(edacs.getMessageType() == EDACSMessageType.GROUP_CALL ||
                   edacs.getMessageType() == EDACSMessageType.INDIVIDUAL_CALL ||
                   edacs.getMessageType() == EDACSMessageType.ALL_CALL)
                {
                    broadcast(new DecoderStateEvent(this, Event.START, State.CALL));
                }
                else if(edacs.getMessageType() == EDACSMessageType.SYSTEM_INFO && mLcnFrequencies != null)
                {
                    String details = edacs.getDetails();
                    if(details != null && details.contains("CC LCN:"))
                    {
                        try
                        {
                            int ccIdx = details.indexOf("CC LCN:") + 7;
                            int endIdx = details.indexOf(" ", ccIdx);
                            if(endIdx < 0) endIdx = details.length();
                            int ccLcn = Integer.parseInt(details.substring(ccIdx, endIdx));
                            updateControlChannel(ccLcn);
                        }
                        catch(Exception e) { /* parse error */ }
                    }
                }
                else
                {
                    broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
                }
            }
            else
            {
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.IDLE));
            }
        }
    }

    @Override
    public void init()
    {
    }

    @Override
    public String getActivitySummary()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Activity Summary\n\n");
        sb.append("Decoder:\tEDACS\n\n");
        return sb.toString();
    }

    @Override
    public void receiveDecoderStateEvent(DecoderStateEvent event)
    {
        switch(event.getEvent())
        {
            case REQUEST_RESET:
                resetState();
                break;
            default:
                break;
        }
    }

    @Override
    public void reset()
    {
        super.reset();
        resetState();
    }

    protected void resetState()
    {
        super.resetState();
    }
}
