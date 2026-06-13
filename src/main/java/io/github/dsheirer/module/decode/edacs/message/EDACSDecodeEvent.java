package io.github.dsheirer.module.decode.edacs.message;

import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;

public class EDACSDecodeEvent extends DecodeEvent
{
    public EDACSDecodeEvent(DecodeEventType decodeEventType, long start)
    {
        super(decodeEventType, start);
    }
}
