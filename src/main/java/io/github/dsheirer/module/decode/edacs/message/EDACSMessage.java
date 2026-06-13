package io.github.dsheirer.module.decode.edacs.message;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.Message;
import io.github.dsheirer.module.decode.edacs.EDACSMessageType;
import io.github.dsheirer.protocol.Protocol;

import java.util.Collections;
import java.util.List;

/**
 * EDACS control channel message.
 */
public class EDACSMessage extends Message
{
    private EDACSMessageType mMessageType;
    private CorrectedBinaryMessage mData;
    private String mDetails;
    private int mGroup;
    private int mLCN;

    public EDACSMessage(EDACSMessageType messageType, CorrectedBinaryMessage data, long timestamp)
    {
        super(timestamp);
        mMessageType = messageType;
        mData = data;
    }

    public void setGroup(int group) { mGroup = group; }
    public int getGroup() { return mGroup; }
    public void setLCN(int lcn) { mLCN = lcn; }
    public int getLCN() { return mLCN; }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.EDACS;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("EDACS ").append(mMessageType);
        if(isValid())
        {
            sb.append(" BCH:PASS");
        }
        else
        {
            sb.append(" BCH:FAIL");
        }
        if(mDetails != null)
        {
            sb.append(" ").append(mDetails);
        }
        return sb.toString();
    }

    public void setDetails(String details)
    {
        mDetails = details;
    }

    public String getDetails()
    {
        return mDetails;
    }

    public EDACSMessageType getMessageType()
    {
        return mMessageType;
    }

    public CorrectedBinaryMessage getData()
    {
        return mData;
    }

    @Override
    public boolean isValid()
    {
        return mData != null && mData.getCorrectedBitCount() >= 0;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }
}
