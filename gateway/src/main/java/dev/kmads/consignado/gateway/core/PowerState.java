package dev.kmads.consignado.gateway.core;

/** What ARM says about the cluster: power code (Running, Stopped) and provisioning state. */
public record PowerState(String code, String provisioning) {
    public static final PowerState UNKNOWN = new PowerState("Unknown", "Unknown");

    public boolean known() { return !"Unknown".equals(code); }
    public boolean starting() { return "Starting".equalsIgnoreCase(provisioning); }
    public boolean stopping() { return "Stopping".equalsIgnoreCase(provisioning); }
    /** ARM works on the cluster now: a start or a stop call is refused. */
    public boolean transitioning() {
        return known() && !"Succeeded".equalsIgnoreCase(provisioning) && !"Failed".equalsIgnoreCase(provisioning)
                && !"Canceled".equalsIgnoreCase(provisioning);
    }
    public boolean running() { return "Running".equalsIgnoreCase(code) && !transitioning(); }
    public boolean stopped() { return "Stopped".equalsIgnoreCase(code) && !transitioning(); }
}
