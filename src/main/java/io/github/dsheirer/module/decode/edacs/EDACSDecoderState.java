package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.module.decode.DecoderType;

public class EDACSDecoderState extends DecoderState implements IMessageListener
{
    private long mLastControlTime;

    public EDACSDecoderState()
    {
        mLastControlTime = System.currentTimeMillis();
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.EDACS;
    }

    @Override
    public void receive(IMessage message)
    {
        if(message != null && message.isValid())
        {
            mLastControlTime = System.currentTimeMillis();
            broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
        }
    }

    public void signalDetected()
    {
        mLastControlTime = System.currentTimeMillis();
        broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
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
        mLastControlTime = System.currentTimeMillis();
        super.resetState();
    }
}
