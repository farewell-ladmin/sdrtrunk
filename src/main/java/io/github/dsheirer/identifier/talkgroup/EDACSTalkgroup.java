package io.github.dsheirer.identifier.talkgroup;

import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.protocol.Protocol;

public class EDACSTalkgroup extends TalkgroupIdentifier implements Comparable<EDACSTalkgroup>
{
    public EDACSTalkgroup(Integer talkgroup, Role role)
    {
        super(talkgroup, role);
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.EDACS;
    }

    public static EDACSTalkgroup create(int talkgroup)
    {
        return new EDACSTalkgroup(talkgroup, Role.TO);
    }

    @Override
    public int compareTo(EDACSTalkgroup o)
    {
        return getValue().compareTo(o.getValue());
    }

    @Override
    public boolean equals(Object o)
    {
        if(!(o instanceof EDACSTalkgroup)) return false;
        return compareTo((EDACSTalkgroup)o) == 0;
    }
}
