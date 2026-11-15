package io.github.dsheirer.module.decode.edacs.message;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.Message;
import io.github.dsheirer.protocol.Protocol;

import java.util.Collections;
import java.util.List;

/**
 * End of a ProVoice transmission: the site sends dotting (alternating bits) on the working channel when it drops the
 * channel.  This is the EDACS counterpart of a P25 network commanded call termination.
 */
public class EDACSProVoiceEndMessage extends Message
{
    public EDACSProVoiceEndMessage(long timestamp)
    {
        super(timestamp);
    }

    @Override
    public String toString()
    {
        return "PROVOICE END OF TRANSMISSION";
    }

    @Override
    public boolean isValid()
    {
        return true;
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.EDACS;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }
}
