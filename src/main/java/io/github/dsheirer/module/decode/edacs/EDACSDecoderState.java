package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.talkgroup.EDACSTalkgroup;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;

public class EDACSDecoderState extends DecoderState implements IMessageListener
{
    private EDACSTalkgroup mCurrentTalkgroup;
    private DecodeEvent mCurrentCallEvent;

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
        if(message == null || !message.isValid())
            return;

        if(message instanceof EDACSMessage edacs)
        {
            if(edacs.getMessageType() == EDACSMessageType.GROUP_CALL)
            {
                int group = edacs.getGroup();
                if(group <= 0 || group > 65535)
                {
                    broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
                    return;
                }

                EDACSTalkgroup tg = EDACSTalkgroup.create(group);

                if(mCurrentTalkgroup == null || !mCurrentTalkgroup.equals(tg))
                {
                    if(mCurrentCallEvent != null)
                        mCurrentCallEvent.end(message.getTimestamp());

                    mCurrentTalkgroup = tg;
                    getIdentifierCollection().remove(IdentifierClass.USER);
                    getIdentifierCollection().update(tg);

                    mCurrentCallEvent = DecodeEvent.builder(DecodeEventType.CALL, message.getTimestamp())
                        .identifiers(getIdentifierCollection().copyOf())
                        .details(edacs.getDetails())
                        .build();
                }
                else
                {
                    mCurrentCallEvent.update(message.getTimestamp());
                }

                broadcast(mCurrentCallEvent);
                broadcast(new DecoderStateEvent(this, Event.START, State.CALL));
            }
            else
            {
                if(mCurrentCallEvent != null)
                {
                    mCurrentCallEvent.end(message.getTimestamp());
                    mCurrentCallEvent = null;
                    mCurrentTalkgroup = null;
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));
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
        mCurrentTalkgroup = null;
        mCurrentCallEvent = null;
        super.resetState();
    }
}
