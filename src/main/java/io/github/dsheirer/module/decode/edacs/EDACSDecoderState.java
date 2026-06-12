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
import io.github.dsheirer.source.tuner.channel.rotation.AddChannelRotationActiveStateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EDACSDecoderState extends DecoderState implements IMessageListener
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSDecoderState.class);
    private DecodeConfigEDACS mLcnFrequencies;
    private long mLastCcFrequency = 0;

    public EDACSDecoderState()
    {
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
                if(edacs.getMessageType() == EDACSMessageType.GROUP_CALL ||
                   edacs.getMessageType() == EDACSMessageType.INDIVIDUAL_CALL ||
                   edacs.getMessageType() == EDACSMessageType.ALL_CALL)
                {
                    broadcast(new DecoderStateEvent(this, Event.START, State.CALL));
                    if(hasInterModuleEventBus())
                        getInterModuleEventBus().post(new AddChannelRotationActiveStateRequest(State.CALL));
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
                    if(hasInterModuleEventBus())
                        getInterModuleEventBus().post(new AddChannelRotationActiveStateRequest(State.CONTROL));
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
