package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.filter.Filter;
import io.github.dsheirer.filter.FilterElement;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSProVoiceMessage;

import java.util.function.Function;

/**
 * Message filter for EDACS control and ProVoice traffic messages.
 */
public class EDACSMessageFilter extends Filter<IMessage, String>
{
    private static final String PROVOICE_TRAFFIC = "ProVoice Traffic";
    private final KeyExtractor mKeyExtractor = new KeyExtractor();

    public EDACSMessageFilter()
    {
        super("EDACS Messages");

        for(EDACSMessageType type: EDACSMessageType.values())
        {
            add(new FilterElement<>(type.toString()));
        }

        add(new FilterElement<>(PROVOICE_TRAFFIC));
    }

    @Override
    public boolean canProcess(IMessage message)
    {
        return (message instanceof EDACSMessage || message instanceof EDACSProVoiceMessage) && super.canProcess(message);
    }

    @Override
    public Function<IMessage, String> getKeyExtractor()
    {
        return mKeyExtractor;
    }

    private class KeyExtractor implements Function<IMessage, String>
    {
        @Override
        public String apply(IMessage message)
        {
            if(message instanceof EDACSMessage edacs)
            {
                return edacs.getMessageType().toString();
            }
            else if(message instanceof EDACSProVoiceMessage)
            {
                return PROVOICE_TRAFFIC;
            }

            return null;
        }
    }
}
