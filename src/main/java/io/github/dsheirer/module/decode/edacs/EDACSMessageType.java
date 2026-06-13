package io.github.dsheirer.module.decode.edacs;

/**
 * EDACS message types.
 *
 * EDACS Extended Addressing (EA) mode message types used by systems
 * like the MBTA (SID 3476).
 */
public enum EDACSMessageType
{
    /** Voice group call grant */
    GROUP_CALL("Group Call"),
    /** Voice individual call grant */
    INDIVIDUAL_CALL("Individual Call"),
    /** System information broadcast */
    SYSTEM_INFO("System Info"),
    /** Adjacent site information */
    ADJACENT_SITE("Adjacent Site"),
    /** Dynamic regroup */
    DYNAMIC_REGROUP("Dynamic Regroup"),
    /** Test call */
    TEST_CALL("Test Call"),
    /** All call */
    ALL_CALL("All Call"),
    /** Status/message broadcast */
    STATUS("Status"),
    /** Unknown/unrecognized message */
    UNKNOWN("Unknown"),
    /** Idle/empty slot */
    IDLE("Idle");

    private String mLabel;

    EDACSMessageType(String label)
    {
        mLabel = label;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
