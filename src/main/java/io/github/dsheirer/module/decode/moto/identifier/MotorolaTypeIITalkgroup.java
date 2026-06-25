package io.github.dsheirer.module.decode.moto.identifier;

import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;

public class MotorolaTypeIITalkgroup extends TalkgroupIdentifier
{
    public MotorolaTypeIITalkgroup(int value)
    {
        super(value, Role.TO);
    }

    public MotorolaTypeIITalkgroup(int value, Role role)
    {
        super(value, role);
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.MOTOROLA_TYPE_II;
    }
}
