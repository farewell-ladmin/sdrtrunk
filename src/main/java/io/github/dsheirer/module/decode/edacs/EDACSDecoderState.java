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
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;

public class EDACSDecoderState extends DecoderState implements IMessageListener
{
    public EDACSDecoderState()
    {
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
