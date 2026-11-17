package dev.kmads.consignado.gateway.functions;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.TimerTrigger;
import dev.kmads.consignado.gateway.App;
import java.util.logging.Level;

/** The timer, every minute: advance sessions, end windows, stop the idle cluster, refresh the snapshot. */
public class ReaperFunction {

    @FunctionName("reaper")
    public void reaper(@TimerTrigger(name = "timer", schedule = "0 * * * * *") String timer,
                       ExecutionContext context) {
        try {
            App.handlers().reaper();
        } catch (RuntimeException e) {
            context.getLogger().log(Level.SEVERE, "reaper failed: {0}",
                    e instanceof IllegalStateException ? e.getMessage() : e.getClass().getName());
        }
    }
}
