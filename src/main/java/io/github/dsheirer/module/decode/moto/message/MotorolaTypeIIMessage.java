// Reference: OP25 tk_smartnet.py (boatbod/op25)
package io.github.dsheirer.module.decode.moto.message;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.Message;
import io.github.dsheirer.protocol.Protocol;

import java.util.Collections;
import java.util.List;

public class MotorolaTypeIIMessage extends Message
{
    private int mAddress;
    private boolean mIsGroup;
    private int mCommand;
    private int mChannelNumber;
    private CorrectedBinaryMessage mRawData;
    private boolean mValid;
    private MotorolaTypeIIMessageType mMessageType;

    public MotorolaTypeIIMessage(int address, boolean isGroup, int command, CorrectedBinaryMessage rawData, boolean valid)
    {
        this(address, isGroup, command, rawData, valid, MotorolaTypeIIMessageType.UNKNOWN);
    }

    public MotorolaTypeIIMessage(int address, boolean isGroup, int command, CorrectedBinaryMessage rawData, boolean valid,
                                 MotorolaTypeIIMessageType messageType)
    {
        this(address, isGroup, command, 0, rawData, valid, messageType);
    }

    public MotorolaTypeIIMessage(int address, boolean isGroup, int command, int channelNumber,
                                 CorrectedBinaryMessage rawData, boolean valid, MotorolaTypeIIMessageType messageType)
    {
        super();
        mAddress = address;
        mIsGroup = isGroup;
        mCommand = command;
        mChannelNumber = channelNumber;
        mRawData = rawData;
        mValid = valid;
        mMessageType = messageType;
    }

    public int getAddress()
    {
        return mAddress;
    }

    public boolean isGroup()
    {
        return mIsGroup;
    }

    public int getCommand()
    {
        return mCommand;
    }

    public int getChannelNumber()
    {
        return mChannelNumber;
    }

    public CorrectedBinaryMessage getRawData()
    {
        return mRawData;
    }

    public MotorolaTypeIIMessageType getMessageType()
    {
        return mMessageType;
    }

    public void setMessageType(MotorolaTypeIIMessageType messageType)
    {
        mMessageType = messageType;
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.MOTOROLA_TYPE_II;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("MOTO T2 ");
        sb.append(mMessageType.name());

        switch(mMessageType)
        {
            case ANALOG_GROUP_GRANT:
            case DIGITAL_GROUP_GRANT:
                sb.append(" TG:").append(String.format("0x%04X", mAddress));
                sb.append(" CH:").append(String.format("0x%03X", mChannelNumber));
                break;
            case ANALOG_PRIVATE_CALL:
            case DIGITAL_PRIVATE_CALL:
                sb.append(" RID:").append(String.format("0x%04X", mAddress));
                sb.append(" CH:").append(String.format("0x%03X", mChannelNumber));
                break;
            case GROUP_UPDATE:
                sb.append(" CH:").append(String.format("0x%03X", mChannelNumber));
                break;
            case SYSTEM_ID:
                sb.append(" SYS:").append(String.format("0x%04X", mAddress));
                break;
            case AMSS:
                int siteId = mCommand - 0x360 + 1;
                sb.append(" SITE:").append(siteId);
                break;
            case SYSTEM_ID_CC:
                sb.append(" SYS:").append(String.format("0x%04X", mAddress));
                sb.append(" CH:").append(String.format("0x%03X", mChannelNumber));
                break;
            case NETWORK_STATUS:
            case SYSTEM_STATUS:
                sb.append(" CMD:").append(String.format("0x%03X", mCommand));
                break;
            default:
                sb.append(" ");
                sb.append(mIsGroup ? "GRP" : "IND");
                sb.append(" ADDR:").append(String.format("%04X", mAddress));
                sb.append(" CMD:").append(String.format("%03X", mCommand));
                break;
        }

        if(!mValid)
        {
            sb.append(" CRC:FAIL");
        }
        return sb.toString();
    }

    @Override
    public boolean isValid()
    {
        return mValid;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }
}
