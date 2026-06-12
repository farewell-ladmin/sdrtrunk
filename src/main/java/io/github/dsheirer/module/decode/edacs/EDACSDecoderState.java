package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EDACSDecoderState extends DecoderState implements IMessageListener
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSDecoderState.class);

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
    public void init() { }

    @Override
    public String getActivitySummary()
    {
        return "Decoder:\tEDACS\n\n";
    }

    @Override
    public void receiveDecoderStateEvent(DecoderStateEvent event)
    {
        if(event.getEvent() == Event.REQUEST_RESET)
            resetState();
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
